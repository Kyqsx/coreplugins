package br.com.coreplugins.economias;

import br.com.coreplugins.api.ProductPlugin;
import br.com.coreplugins.economias.command.MainCommand;
import br.com.coreplugins.economias.economy.CurrencyManager;
import br.com.coreplugins.economias.economy.EconomyService;
import br.com.coreplugins.economias.economy.TopManager;
import br.com.coreplugins.economias.hook.PapiHook;
import br.com.coreplugins.economias.hook.VaultHook;
import br.com.coreplugins.economias.listener.PlayerListener;
import br.com.coreplugins.economias.menu.MenuManager;
import br.com.coreplugins.economias.storage.Storage;
import br.com.coreplugins.economias.sync.RedisSync;
import br.com.coreplugins.economias.util.Amounts;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public final class EconomiasProduct extends ProductPlugin {

    private final List<BukkitTask> tasks = new ArrayList<BukkitTask>();
    private Messages messages;
    private Storage storage;
    private CurrencyManager currencies;
    private EconomyService service;
    private TopManager top;
    private MenuManager menus;
    private RedisSync redis;
    private Object vaultProvider;
    private Object papiExpansion;
    private volatile boolean disabled;

    @Override
    public void onEnable() {
        if (!isLoadedByLoader()) {
            getLogger().severe("Este plugin precisa do corePlugins.");
            return;
        }
        saveDefaultConfig();
        // Inicialização 1 tick depois: Vault, PlaceholderAPI e afins já terminaram de carregar.
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
            Amounts.setSuffixes(getConfig().getStringList("suffixes"));
            messages = new Messages(this);

            ConfigurationSection db = getConfig().getConfigurationSection("database");
            if (db == null) {
                throw new IllegalStateException("Seção 'database' ausente no config.yml");
            }
            storage = new Storage(getLogger(), getDataFolder(), db);
            storage.init();

            currencies = new CurrencyManager(this);
            currencies.load();
            service = new EconomyService(getLogger(), storage, currencies, getDataFolder());
            top = new TopManager(storage, currencies, service);
            configureSafety(db);
            startRedis(db);

            registerEvents(new PlayerListener(this));
            menus = new MenuManager(this);
            registerEvents(menus);
            if (getCommand("economies") != null) {
                MainCommand main = new MainCommand(this);
                getCommand("economies").setExecutor(main);
                getCommand("economies").setTabCompleter(main);
            }
            currencies.registerAll();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Falha ao iniciar o coreEconomias.", e);
            return;
        }

        long saveTicks = Math.max(5, getConfig().getInt("save-interval-seconds", 30)) * 20L;
        long resyncTicks = Math.max(2, getConfig().getInt("resync-seconds", 30)) * 20L;
        long topTicks = Math.max(1, getConfig().getInt("top-refresh-minutes", 10)) * 60L * 20L;
        tasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(getLoader(), new Runnable() {
            @Override
            public void run() {
                service.maintenance();
            }
        }, saveTicks, saveTicks));
        tasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(getLoader(), new Runnable() {
            @Override
            public void run() {
                service.resyncOnline();
            }
        }, resyncTicks, resyncTicks));
        tasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(getLoader(), new Runnable() {
            @Override
            public void run() {
                top.refresh();
            }
        }, 20L, topTicks));

        // Jogadores que já estavam online (ex.: /coreplugins reload) não passaram pelo pré-login.
        for (final Player p : Bukkit.getOnlinePlayers()) {
            Bukkit.getScheduler().runTaskAsynchronously(getLoader(), new Runnable() {
                @Override
                public void run() {
                    try {
                        service.load(p.getUniqueId(), p.getName());
                    } catch (Exception e) {
                        getLogger().log(Level.SEVERE, "Falha ao carregar " + p.getName(), e);
                    }
                }
            });
        }

        hookVault();
        hookPlaceholderApi();
        getLogger().info(currencies.all().size() + " economia(s) carregada(s).");
    }

    /** Débito confirmado no banco (anti gasto duplo): ligado por padrão com MySQL, irrelevante com SQLITE. */
    private void configureSafety(ConfigurationSection db) {
        boolean mysql = "MYSQL".equalsIgnoreCase(db.getString("type", "SQLITE"));
        boolean wanted = getConfig().getBoolean("safety.strict-debit", true);
        long timeout = getConfig().getLong("safety.debit-timeout-ms", 3000L);
        service.setStrict(mysql && wanted, timeout);
        if (mysql && !wanted) {
            getLogger().warning("safety.strict-debit desligado: com vários servidores no mesmo MySQL, o saldo pode ficar negativo em gasto simultâneo.");
        }
    }

    /** Redis é opcional e só avisa os outros servidores; o saldo de verdade fica sempre no banco. */
    private void startRedis(ConfigurationSection db) {
        ConfigurationSection rc = getConfig().getConfigurationSection("redis");
        if (rc == null || !rc.getBoolean("enabled", false)) {
            return;
        }
        if (!"MYSQL".equalsIgnoreCase(db.getString("type", "SQLITE"))) {
            getLogger().warning("Redis ligado com SQLITE: só faz sentido com um MySQL compartilhado entre servidores.");
        }
        final EconomyService svc = service;
        redis = new RedisSync(getLogger(), rc, new RedisSync.Listener() {
            @Override
            public void onRemoteChange(UUID player) {
                svc.onRemoteChange(player);
            }

            @Override
            public void onReconnected() {
                svc.resyncOnline();
            }
        });
        service.setSync(redis);
        redis.start();
    }

    private void hookVault() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            return;
        }
        try {
            vaultProvider = VaultHook.register(this, getConfig().getString("vault-currency", ""));
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Não foi possível integrar com o Vault.", t);
        }
    }

    private void hookPlaceholderApi() {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return;
        }
        try {
            papiExpansion = PapiHook.register(this);
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Não foi possível integrar com o PlaceholderAPI.", t);
        }
    }

    /** /economies reload — não troca o banco de dados (exige reiniciar). */
    public void reloadAll() {
        reloadConfig();
        Amounts.setSuffixes(getConfig().getStringList("suffixes"));
        messages.reload();
        currencies.reload();
        Bukkit.getScheduler().runTaskAsynchronously(getLoader(), new Runnable() {
            @Override
            public void run() {
                top.refresh();
            }
        });
    }

    @Override
    public void onDisable() {
        disabled = true;
        for (BukkitTask task : tasks) {
            task.cancel();
        }
        tasks.clear();
        if (vaultProvider != null) {
            getServer().getServicesManager().unregister(vaultProvider);
            vaultProvider = null;
        }
        if (papiExpansion != null) {
            try {
                PapiHook.unregister(papiExpansion);
            } catch (Throwable ignored) {
                // PlaceholderAPI já pode ter sido desligado
            }
            papiExpansion = null;
        }
        if (currencies != null) {
            currencies.unregisterAll();
        }
        if (service != null) {
            service.shutdown();
        }
        if (redis != null) {
            redis.close();
            redis = null;
        }
        if (storage != null) {
            storage.close();
        }
    }

    public Messages getMessages() { return messages; }
    public CurrencyManager getCurrencies() { return currencies; }
    public EconomyService getService() { return service; }
    public TopManager getTop() { return top; }
    public MenuManager getMenus() { return menus; }
}
