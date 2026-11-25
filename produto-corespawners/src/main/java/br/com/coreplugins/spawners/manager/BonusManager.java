package br.com.coreplugins.spawners.manager;

import br.com.coreplugins.spawners.SpawnersProduct;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Bônus de venda por permissão (bonus.yml) e bônus de drops por quantidade de geradores no chunk. */
public final class BonusManager {

    private static final class Group {
        final String permission;
        final double percent;

        Group(String permission, double percent) {
            this.permission = permission;
            this.percent = percent;
        }
    }

    private final SpawnersProduct plugin;
    private final List<Group> groups = new ArrayList<Group>();
    private final NavigableMap<Integer, Double> chunkTable = new TreeMap<Integer, Double>();
    private boolean chunkEnabled;
    private boolean accumulate;

    public BonusManager(SpawnersProduct plugin) {
        this.plugin = plugin;
    }

    public void load() {
        groups.clear();
        chunkTable.clear();
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "bonus.yml"));
        ConfigurationSection s = cfg.getConfigurationSection("bonus");
        if (s != null) {
            for (String key : s.getKeys(false)) {
                ConfigurationSection g = s.getConfigurationSection(key);
                if (g != null && !g.getString("permission", "").isEmpty()) {
                    groups.add(new Group(g.getString("permission"), g.getDouble("percent", 0D)));
                }
            }
        }
        accumulate = plugin.getConfig().getBoolean("general.accumulate-bonus", true);
        chunkEnabled = plugin.getConfig().getBoolean("chunk-bonus.enabled", true);
        ConfigurationSection t = plugin.getConfig().getConfigurationSection("chunk-bonus.table");
        if (t != null) {
            for (String k : t.getKeys(false)) {
                try {
                    chunkTable.put(Integer.parseInt(k.trim()), t.getDouble(k));
                } catch (NumberFormatException ignored) {
                    plugin.getLogger().warning("chunk-bonus.table: chave inválida '" + k + "'.");
                }
            }
        }
    }

    /** Bônus de venda do jogador, em %. */
    public double sellBonus(Player p) {
        double sum = 0D;
        double best = 0D;
        for (Group g : groups) {
            if (p.hasPermission(g.permission)) {
                sum += g.percent;
                best = Math.max(best, g.percent);
            }
        }
        return accumulate ? sum : best;
    }

    /** Multiplicador de drops pelo total de geradores do mesmo tipo no chunk (1.0 = sem bônus). */
    public double chunkMultiplier(int unitsInChunk) {
        if (!chunkEnabled || chunkTable.isEmpty()) {
            return 1D;
        }
        java.util.Map.Entry<Integer, Double> e = chunkTable.floorEntry(unitsInChunk);
        return e == null ? 1D : Math.max(1D, e.getValue());
    }
}
