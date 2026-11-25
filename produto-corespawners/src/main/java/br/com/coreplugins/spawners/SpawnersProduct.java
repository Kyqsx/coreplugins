package br.com.coreplugins.spawners;

import br.com.coreplugins.api.ProductPlugin;
import br.com.coreplugins.spawners.command.SpawnersCommand;
import br.com.coreplugins.spawners.economy.EconomyManager;
import br.com.coreplugins.spawners.listener.BlockListener;
import br.com.coreplugins.spawners.listener.MobListener;
import br.com.coreplugins.spawners.listener.WorldListener;
import br.com.coreplugins.spawners.manager.BonusManager;
import br.com.coreplugins.spawners.manager.BoosterManager;
import br.com.coreplugins.spawners.manager.HologramManager;
import br.com.coreplugins.spawners.manager.LimitManager;
import br.com.coreplugins.spawners.manager.StatsManager;
import br.com.coreplugins.spawners.manager.SpawnerManager;
import br.com.coreplugins.spawners.manager.TypeManager;
import br.com.coreplugins.spawners.menu.MenuManager;
import br.com.coreplugins.spawners.model.PlayerStats;
import br.com.coreplugins.spawners.model.Spawner;
import br.com.coreplugins.spawners.model.SpawnerType;
import br.com.coreplugins.spawners.service.DropService;
import br.com.coreplugins.spawners.service.UpgradeService;
import br.com.coreplugins.spawners.storage.Database;
import br.com.coreplugins.spawners.util.Amounts;
import br.com.coreplugins.spawners.util.BuildInfo;
import br.com.coreplugins.spawners.util.RecentErrors;
import br.com.coreplugins.spawners.util.Items;
import br.com.coreplugins.spawners.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/** Produto coreSpawners: geradores de mobs com stack, drops, venda e upgrades. */
public final class SpawnersProduct extends ProductPlugin {

    private final List<BukkitTask> tasks = new ArrayList<BukkitTask>();
    private Messages messages;
    private TypeManager types;
    private EconomyManager economy;
    private BonusManager bonus;
    private BoosterManager boosters;
    private HologramManager holograms;
    private SpawnerManager spawners;
    private DropService drops;
    private UpgradeService upgrades;
    private MenuManager menus;
    private Database database;
    private StatsManager stats;
    private LimitManager limits;
    private ThreadPoolExecutor dbExecutor;
    private volatile boolean disabled;
    private boolean started;
    private boolean papiRegistered;

    // ---- diagnóstico (/spawners debug)
    private final RecentErrors recentErrors = new RecentErrors(15);
    private BuildInfo buildInfo;
    private long startedAt;
    private volatile long lastFlushAt;
    private volatile long lastFlushMs;
    private volatile int lastFlushItems;
    private volatile String lastFlushError;

    @Override
    public void onEnable() {
        if (!isLoadedByLoader()) {
            getLogger().severe("Este plugin precisa do corePlugins.");
            return;
        }
        getLogger().addHandler(recentErrors);
        buildInfo = BuildInfo.read(getClass(), getResource("build.properties"));
        getLogger().info("Build " + buildInfo.version + " (compilado em " + buildInfo.built + ") - jar "
                + buildInfo.jarName + " [" + buildInfo.jarSha + "]");
        saveDefaultConfig();
        // Inicialização 1 tick depois: Vault, PlayerPoints e os outros produtos já terminaram de carregar.
        tasks.add(Bukkit.getScheduler().runTaskLater(getLoader(), new Runnable() {
            @Override
            public void run() {
                start();
            }
        }, 1L));
    }

    private void start() {
        if (disabled) {
            return;
        }
        try {
            messages = new Messages(this);
            types = new TypeManager(this);
            types.load();
            economy = new EconomyManager(this);
            economy.reload(YamlConfiguration.loadConfiguration(new File(getDataFolder(), "economies.yml")));
            economy.rescan();
            bonus = new BonusManager(this);
            bonus.load();
            boosters = new BoosterManager(this);
            boosters.load();
            holograms = new HologramManager(this);
            spawners = new SpawnerManager(this);
            drops = new DropService(this);
            upgrades = new UpgradeService(this);

            ConfigurationSection db = getConfig().getConfigurationSection("database");
            if (db == null) {
                throw new IllegalStateException("Seção 'database' ausente no config.yml");
            }
            database = new Database(getDataFolder(), db);
            database.init();
            spawners.load(database.loadAll());
            stats = new StatsManager(this);
            stats.load(database.loadStats());
            limits = new LimitManager(this);
            dbExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(),
                    new ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "coreSpawners-db");
                            t.setDaemon(true);
                            return t;
                        }
                    });

            menus = new MenuManager(this);
            registerEvents(menus);
            registerEvents(new BlockListener(this));
            registerEvents(new MobListener(this));
            registerEvents(new WorldListener(this));
            if (getCommand("spawners") != null) {
                SpawnersCommand cmd = new SpawnersCommand(this);
                getCommand("spawners").setExecutor(cmd);
                getCommand("spawners").setTabCompleter(cmd);
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Falha ao iniciar o coreSpawners.", e);
            return;
        }
        started = true;
        startedAt = System.currentTimeMillis();
        stats.refresh();
        tryPapi();

        tasks.add(Bukkit.getScheduler().runTaskTimer(getLoader(), new Runnable() {
            @Override
            public void run() {
                spawners.tick();
            }
        }, 20L, 20L));
        long saveTicks = Math.max(5, getConfig().getInt("save-interval-seconds", 30)) * 20L;
        tasks.add(Bukkit.getScheduler().runTaskTimer(getLoader(), new Runnable() {
            @Override
            public void run() {
                flush(false);
            }
        }, saveTicks, saveTicks));
        // O coreEconomias pode iniciar depois deste plugin ou ganhar moedas novas: refaz a detecção a cada 5 s.
        tasks.add(Bukkit.getScheduler().runTaskTimer(getLoader(), new Runnable() {
            @Override
            public void run() {
                economy.rescan();
                tryPapi();
            }
        }, 40L, 100L));
        long rankTicks = Math.max(1, getConfig().getInt("ranking.refresh-seconds", 10)) * 20L;
        tasks.add(Bukkit.getScheduler().runTaskTimer(getLoader(), new Runnable() {
            @Override
            public void run() {
                stats.refresh();
            }
        }, rankTicks, rankTicks));
        tasks.add(Bukkit.getScheduler().runTaskLater(getLoader(), new Runnable() {
            @Override
            public void run() {
                types.validateEconomies(economy);
            }
        }, 200L));
        tasks.add(Bukkit.getScheduler().runTaskLater(getLoader(), new Runnable() {
            @Override
            public void run() {
                spawners.refreshAllLoaded();
            }
        }, 5L));

        getLogger().info(types.all().size() + " tipo(s) de gerador e " + spawners.count() + " gerador(es) carregado(s).");
    }

    /** Registra os placeholders no PlaceholderAPI (se ele estiver ativo). Tenta de novo a cada 5 s até conseguir. */
    private void tryPapi() {
        if (papiRegistered || !Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return;
        }
        try {
            if (br.com.coreplugins.spawners.placeholder.PapiHook.register(this)) {
                papiRegistered = true;
                getLogger().info("Placeholders do PlaceholderAPI registrados (%corespawners_...%).");
            }
        } catch (Throwable t) {
            papiRegistered = true; // não insiste: o erro fica no /spawners debug
            getLogger().log(Level.WARNING, "Não foi possível registrar os placeholders do PlaceholderAPI.", t);
        }
    }

    /** Grava no banco o que mudou. {@code sync} = na própria thread (desligamento). */
    private void flush(boolean sync) {
        if (!started || spawners == null || database == null) {
            return;
        }
        final List<Spawner> snaps = spawners.drainDirty();
        final List<Long> del = spawners.drainDeleted();
        final List<PlayerStats> statSnaps = stats.drainDirty();
        if (snaps.isEmpty() && del.isEmpty() && statSnaps.isEmpty()) {
            return;
        }
        Runnable job = new Runnable() {
            @Override
            public void run() {
                long t0 = System.currentTimeMillis();
                try {
                    database.write(snaps, del, statSnaps);
                    lastFlushError = null;
                    lastFlushMs = System.currentTimeMillis() - t0;
                    lastFlushItems = snaps.size() + del.size() + statSnaps.size();
                    lastFlushAt = System.currentTimeMillis();
                } catch (Exception e) {
                    lastFlushError = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
                    lastFlushAt = System.currentTimeMillis();
                    getLogger().log(Level.SEVERE, "Falha ao gravar os geradores; tentarei de novo.", e);
                    try {
                        Bukkit.getScheduler().runTask(getLoader(), new Runnable() {
                            @Override
                            public void run() {
                                spawners.requeue(snaps, del);
                                stats.requeue(statSnaps);
                            }
                        });
                    } catch (RuntimeException ignored) {
                        // desligando: o que ficou pendente está no banco da última gravação
                    }
                }
            }
        };
        if (sync || dbExecutor == null) {
            job.run();
        } else {
            dbExecutor.execute(job);
        }
    }

    /** /spawners reload — não troca o banco de dados (exige reiniciar). */
    public void reloadAll() {
        if (!started) {
            return;
        }
        reloadConfig();
        messages.reload();
        types.load();
        menus.reload();
        bonus.load();
        boosters.load();
        economy.reload(YamlConfiguration.loadConfiguration(new File(getDataFolder(), "economies.yml")));
        economy.rescan();
        types.validateEconomies(economy);
        spawners.refreshAllLoaded();
        stats.refresh();
    }

    @Override
    public void onDisable() {
        disabled = true;
        if (papiRegistered) {
            try {
                br.com.coreplugins.spawners.placeholder.PapiHook.unregister();
            } catch (Throwable ignored) {
                // PlaceholderAPI já saiu
            }
            papiRegistered = false;
        }
        for (BukkitTask t : tasks) {
            t.cancel();
        }
        tasks.clear();
        if (spawners != null) {
            spawners.shutdown();
        }
        if (dbExecutor != null) {
            dbExecutor.shutdown();
            try {
                dbExecutor.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        flush(true);
        if (database != null) {
            database.close();
        }
        getLogger().removeHandler(recentErrors);
    }

    // ------------------------------------------------------------------ regras de acesso

    public boolean canAccess(Player p, Spawner s) {
        return s.owner.equals(p.getUniqueId()) || s.isFriend(p.getUniqueId()) || s.isPublic
                || p.hasPermission("corespawners.admin");
    }

    /** Tem a permissão {@code corespawners.<acao>}? Se não, avisa o jogador e devolve false. */
    public boolean perm(Player p, String action) {
        if (p.hasPermission("corespawners." + action)) {
            return true;
        }
        messages().send(p, "general.no-permission", Text.vars("permission", "corespawners." + action));
        return false;
    }

    public boolean canManage(Player p, Spawner s) {
        return s.owner.equals(p.getUniqueId()) || p.hasPermission("corespawners.admin");
    }

    /** O servidor exige colocar o ovo de spawn no gerador? (general.require-egg, padrão false = já vem com o ovo) */
    public boolean eggRequired() {
        return getConfig().getBoolean("general.require-egg", false);
    }

    /** Sistema de drops ligado para este tipo? (general.drops-enabled e drops.enabled do tipo) */
    public boolean dropsEnabled(SpawnerType t) {
        return getConfig().getBoolean("general.drops-enabled", true) && t.dropsEnabled;
    }

    /**
     * Item de gerador do tipo, com os dados gravados no próprio item.
     * {@code stack} = geradores que o item vale; {@code amount} = quantos itens; {@code egg} = já vem com o ovo
     * (sempre verdadeiro quando o servidor não exige ovo).
     */
    public ItemStack createItem(SpawnerType t, int stack, int amount, boolean egg) {
        boolean hasEgg = egg || !eggRequired();
        ItemStack it = Items.parse(t.itemMaterial, Math.max(1, amount));
        if (hasEgg) {
            Items.spawnerType(it, t.entity);
        }
        Items.decorate(it, t.itemName, t.itemLore, Text.vars("stack", Amounts.number(stack),
                "type", Text.color(t.display), "entity", Text.color(t.entityName)), false);
        return Items.withMark(getLoader(), it, t.id, stack, hasEgg);
    }

    // ------------------------------------------------------------------ acesso aos módulos

    public Messages messages() { return messages; }
    public TypeManager types() { return types; }
    public EconomyManager economy() { return economy; }
    public BonusManager bonus() { return bonus; }
    public BoosterManager boosters() { return boosters; }
    public HologramManager holograms() { return holograms; }
    public SpawnerManager spawners() { return spawners; }
    public DropService drops() { return drops; }
    public UpgradeService upgrades() { return upgrades; }
    public MenuManager menus() { return menus; }
    public StatsManager stats() { return stats; }
    public LimitManager limits() { return limits; }

    // ------------------------------------------------------------------ diagnóstico

    public BuildInfo buildInfo() { return buildInfo; }
    public RecentErrors recentErrors() { return recentErrors; }
    public Database database() { return database; }
    public long startedAt() { return startedAt; }
    public boolean papiRegistered() { return papiRegistered; }
    public long lastFlushAt() { return lastFlushAt; }
    public long lastFlushMs() { return lastFlushMs; }
    public int lastFlushItems() { return lastFlushItems; }
    public String lastFlushError() { return lastFlushError; }

    /** Gravações esperando na fila do banco. */
    public int dbQueued() {
        return dbExecutor == null ? 0 : dbExecutor.getQueue().size();
    }

    /** 1 se há uma gravação em andamento agora. */
    public int dbActive() {
        return dbExecutor == null ? 0 : dbExecutor.getActiveCount();
    }
}
