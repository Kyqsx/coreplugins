package br.com.coreplugins.spawners.manager;

import br.com.coreplugins.spawners.SpawnersProduct;
import br.com.coreplugins.spawners.economy.EconomyManager;
import br.com.coreplugins.spawners.model.DropType;
import br.com.coreplugins.spawners.model.Price;
import br.com.coreplugins.spawners.model.SpawnerType;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/** Lê os tipos de gerador da pasta spawners/. */
public final class TypeManager {

    private final SpawnersProduct plugin;
    private final Map<String, SpawnerType> types = new LinkedHashMap<String, SpawnerType>();

    public TypeManager(SpawnersProduct plugin) {
        this.plugin = plugin;
    }

    public void load() {
        types.clear();
        File dir = new File(plugin.getDataFolder(), "spawners");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            plugin.getLogger().warning("Não foi possível criar a pasta spawners/.");
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        java.util.Arrays.sort(files);
        for (File f : files) {
            String n = f.getName();
            if (!n.toLowerCase(Locale.ROOT).endsWith(".yml")) {
                continue;
            }
            String id = n.substring(0, n.length() - 4);
            try {
                SpawnerType t = SpawnerType.load(id, YamlConfiguration.loadConfiguration(f), plugin.getLogger());
                if (t != null) {
                    types.put(t.id, t);
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Falha ao ler spawners/" + n, e);
            }
        }
    }

    public SpawnerType get(String id) {
        return id == null ? null : types.get(id.toLowerCase(Locale.ROOT));
    }

    public Collection<SpawnerType> all() {
        return types.values();
    }

    public List<String> ids() {
        return new ArrayList<String>(types.keySet());
    }

    /** Avisa sobre nomes de economia que não existem (chamado depois que o coreEconomias já iniciou). */
    public void validateEconomies(EconomyManager eco) {
        for (SpawnerType t : types.values()) {
            check(eco, t.id, "upgrades.drops", t.dropsUpgrade.currency, t.dropsUpgrade.enabled());
            check(eco, t.id, "upgrades.speed", t.speedUpgrade.currency, t.speedUpgrade.enabled());
            for (DropType d : t.drops().values()) {
                for (Price p : d.prices) {
                    check(eco, t.id, "drops.types." + d.key, p.currency, true);
                }
            }
        }
    }

    private void check(EconomyManager eco, String type, String where, String currency, boolean used) {
        if (used && eco.get(currency) == null) {
            plugin.getLogger().warning("spawners/" + type + ".yml (" + where + "): economia '" + currency
                    + "' não encontrada. Veja /spawners economies.");
        }
    }
}
