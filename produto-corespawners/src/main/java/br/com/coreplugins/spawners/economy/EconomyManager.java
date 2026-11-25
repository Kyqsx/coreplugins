package br.com.coreplugins.spawners.economy;

import br.com.coreplugins.spawners.SpawnersProduct;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Detecta as economias do servidor e resolve o nome usado nos arquivos ({@code currency: coins}) para uma
 * {@link EconomyProvider}. Ordem de preferência para o mesmo nome: moedas do coreEconomias, depois
 * Vault/PlayerPoints, depois as definidas em economies.yml.
 *
 * <p>A detecção se repete sozinha: o coreEconomias pode terminar de iniciar depois deste plugin, ou
 * ganhar moedas novas pelo menu dele; basta chamar {@link #rescan()} (o plugin faz isso a cada 5 s).</p>
 */
public final class EconomyManager {

    private final SpawnersProduct plugin;
    private final CoreEconomiasLink core;

    private final Map<String, EconomyProvider> names = new LinkedHashMap<String, EconomyProvider>();
    private final List<EconomyProvider> list = new ArrayList<EconomyProvider>();
    private final Map<String, String> aliasConfig = new HashMap<String, String>();
    private final List<CommandProvider> customs = new ArrayList<CommandProvider>();
    private boolean autoDetect = true;
    private String defaultName = "auto";
    private VaultProvider vault;
    private PlayerPointsProvider points;
    private String lastSummary = "";

    public EconomyManager(SpawnersProduct plugin) {
        this.plugin = plugin;
        this.core = new CoreEconomiasLink(plugin.getLogger(), plugin.getConfig().getBoolean("debug-mode", false));
    }

    /** Lê economies.yml (apelidos, padrão e integrações por comando). Não mexe na detecção: chame {@link #rescan()}. */
    public void reload(YamlConfiguration cfg) {
        autoDetect = cfg.getBoolean("auto-detect", true);
        defaultName = cfg.getString("default", "auto").trim().toLowerCase(Locale.ROOT);
        aliasConfig.clear();
        ConfigurationSection al = cfg.getConfigurationSection("aliases");
        if (al != null) {
            for (String key : al.getKeys(false)) {
                String target = al.getString(key);
                if (target != null && !target.trim().isEmpty()) {
                    aliasConfig.put(key.toLowerCase(Locale.ROOT), target.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        customs.clear();
        ConfigurationSection cs = cfg.getConfigurationSection("custom");
        if (cs != null) {
            for (String key : cs.getKeys(false)) {
                ConfigurationSection c = cs.getConfigurationSection(key);
                if (c == null) {
                    continue;
                }
                customs.add(new CommandProvider(key, c.getString("display", key),
                        c.getString("balance-placeholder", ""), c.getString("give-command", ""),
                        c.getString("take-command", "")));
            }
        }
        lastSummary = "";
    }

    /** Refaz a lista de economias detectadas. Barato: pode ser chamado a cada poucos segundos. */
    public void rescan() {
        Map<String, EconomyProvider> found = new LinkedHashMap<String, EconomyProvider>();
        List<EconomyProvider> canonical = new ArrayList<EconomyProvider>();

        if (autoDetect) {
            for (String id : core.currencyIds()) {
                EconomyProvider existing = names.get(id);
                EconomyProvider p = existing instanceof CoreCurrencyProvider ? existing : new CoreCurrencyProvider(core, id);
                found.put(id, p);
                canonical.add(p);
            }
            if (Bukkit.getPluginManager().isPluginEnabled("Vault")) {
                try {
                    if (vault == null) {
                        vault = new VaultProvider();
                    }
                    if (vault.isAvailable()) {
                        add(found, canonical, vault, "vault", "money");
                    }
                } catch (Throwable t) {
                    plugin.getLogger().warning("[economia] Vault presente, mas não foi possível usá-lo: " + t);
                }
            }
            if (Bukkit.getPluginManager().isPluginEnabled("PlayerPoints")) {
                if (points == null) {
                    points = new PlayerPointsProvider();
                }
                if (points.isAvailable()) {
                    add(found, canonical, points, "playerpoints", "points");
                }
            }
        }
        for (CommandProvider cp : customs) {
            add(found, canonical, cp, cp.id());
        }

        names.clear();
        names.putAll(found);
        list.clear();
        list.addAll(canonical);

        String summary = summary();
        if (!summary.equals(lastSummary)) {
            lastSummary = summary;
            if (list.isEmpty()) {
                plugin.getLogger().warning("[economia] Nenhuma economia detectada ainda (coreEconomias, Vault ou PlayerPoints).");
            } else {
                plugin.getLogger().info("[economia] Detectadas: " + summary);
            }
        }
    }

    private static void add(Map<String, EconomyProvider> found, List<EconomyProvider> canonical,
                            EconomyProvider p, String... keys) {
        boolean used = false;
        for (String k : keys) {
            if (!found.containsKey(k)) {
                found.put(k, p);
                used = true;
            }
        }
        if (used && !canonical.contains(p)) {
            canonical.add(p);
        }
    }

    private String summary() {
        StringBuilder sb = new StringBuilder();
        for (EconomyProvider p : list) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(p.id()).append(" (").append(p.source()).append(')');
        }
        EconomyProvider d = defaultProvider();
        return sb + (d == null ? "" : " | padrão: " + d.id());
    }

    // ------------------------------------------------------------------ consulta

    /** Economia pelo nome usado nos arquivos. 'auto' (ou vazio) = economia padrão. {@code null} se não existir. */
    public EconomyProvider get(String name) {
        if (name == null) {
            return defaultProvider();
        }
        String k = name.trim().toLowerCase(Locale.ROOT);
        if (k.isEmpty() || k.equals("auto") || k.equals("default")) {
            return defaultProvider();
        }
        return lookup(k, 0);
    }

    private EconomyProvider lookup(String k, int depth) {
        if (depth > 4) {
            return null;
        }
        EconomyProvider p = names.get(k);
        if (p != null) {
            return p;
        }
        String target = aliasConfig.get(k);
        if (target != null && !target.equals(k)) {
            return lookup(target, depth + 1);
        }
        // formatos de outros plugins: core:coins, coreeconomias:coins, economias-coins
        String[] prefixes = {"core:", "coreeconomias:", "economias-", "coreeconomias-"};
        for (String prefix : prefixes) {
            if (k.startsWith(prefix) && k.length() > prefix.length()) {
                return lookup(k.substring(prefix.length()), depth + 1);
            }
        }
        // moeda criada no coreEconomias depois da última varredura
        if (autoDetect && core.ready() && core.currency(k) != null) {
            EconomyProvider created = new CoreCurrencyProvider(core, k);
            names.put(k, created);
            list.add(created);
            return created;
        }
        return null;
    }

    /** A economia usada por {@code currency: auto}. */
    public EconomyProvider defaultProvider() {
        if (!defaultName.equals("auto")) {
            EconomyProvider p = lookup(defaultName, 0);
            if (p != null) {
                return p;
            }
        }
        EconomyProvider coins = names.get("coins");
        if (coins instanceof CoreCurrencyProvider) {
            return coins;
        }
        for (EconomyProvider p : list) {
            if (p instanceof CoreCurrencyProvider) {
                return p;
            }
        }
        for (EconomyProvider p : list) {
            if (p instanceof VaultProvider) {
                return p;
            }
        }
        for (EconomyProvider p : list) {
            if (p instanceof PlayerPointsProvider) {
                return p;
            }
        }
        return list.isEmpty() ? null : list.get(0);
    }

    public List<EconomyProvider> all() {
        return Collections.unmodifiableList(new ArrayList<EconomyProvider>(list));
    }

    // ------------------------------------------------------------------ cobrança

    /**
     * Cobra de um jogador. Economias thread-safe (coreEconomias) são cobradas fora da thread principal,
     * porque o débito confirmado no banco pode levar alguns milissegundos; o resultado volta na thread principal.
     */
    public void charge(final Player player, final EconomyProvider provider, final double amount,
                       final Consumer<EconomyProvider.Result> done) {
        if (!provider.threadSafe()) {
            done.accept(safeWithdraw(provider, player, amount));
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin.getLoader(), new Runnable() {
            @Override
            public void run() {
                final EconomyProvider.Result r = safeWithdraw(provider, player, amount);
                try {
                    Bukkit.getScheduler().runTask(plugin.getLoader(), new Runnable() {
                        @Override
                        public void run() {
                            done.accept(r);
                        }
                    });
                } catch (RuntimeException e) {
                    if (r == EconomyProvider.Result.OK) {
                        plugin.getLogger().severe("Débito de " + amount + " (" + provider.id() + ") de "
                                + player.getName() + " feito, mas o plugin foi desligado antes de entregar o resultado.");
                    }
                }
            }
        });
    }

    private EconomyProvider.Result safeWithdraw(EconomyProvider provider, OfflinePlayer player, double amount) {
        try {
            if (!provider.isAvailable()) {
                return EconomyProvider.Result.ERROR;
            }
            return provider.withdraw(player, amount);
        } catch (Throwable t) {
            plugin.getLogger().warning("[economia] erro ao cobrar em " + provider.id() + ": " + t);
            return EconomyProvider.Result.ERROR;
        }
    }

    /** Pagamento (sempre na thread principal). */
    public boolean pay(OfflinePlayer player, EconomyProvider provider, double amount) {
        try {
            return provider.isAvailable() && provider.deposit(player, amount);
        } catch (Throwable t) {
            plugin.getLogger().warning("[economia] erro ao pagar em " + provider.id() + ": " + t);
            return false;
        }
    }
}
