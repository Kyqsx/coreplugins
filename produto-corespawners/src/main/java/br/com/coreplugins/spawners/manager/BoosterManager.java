package br.com.coreplugins.spawners.manager;

import br.com.coreplugins.spawners.SpawnersProduct;
import br.com.coreplugins.spawners.model.BoosterType;
import br.com.coreplugins.spawners.model.BoosterType.Kind;
import br.com.coreplugins.spawners.model.Spawner;
import br.com.coreplugins.spawners.util.Items;
import br.com.coreplugins.spawners.util.Text;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Boosters (boosters.yml): itens que multiplicam drops, velocidade ou venda de um gerador por um tempo. */
public final class BoosterManager {

    public enum Result { OK, ALREADY_ACTIVE }

    private final SpawnersProduct plugin;
    private final Map<String, BoosterType> boosters = new LinkedHashMap<String, BoosterType>();

    public BoosterManager(SpawnersProduct plugin) {
        this.plugin = plugin;
    }

    public void load() {
        boosters.clear();
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "boosters.yml"));
        ConfigurationSection root = cfg.getConfigurationSection("boosters");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection b = root.getConfigurationSection(key);
            if (b == null) {
                continue;
            }
            Kind kind;
            try {
                kind = Kind.valueOf(b.getString("kind", "").trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("boosters.yml: '" + key + "' tem 'kind' inválido (use DROPS, SPEED ou SELL). Ignorado.");
                continue;
            }
            double mult = b.getDouble("multiplier", 1D);
            long duration = b.getLong("duration", 3600L);
            if (mult <= 1D || duration <= 0) {
                plugin.getLogger().warning("boosters.yml: '" + key + "' precisa de multiplier > 1 e duration > 0. Ignorado.");
                continue;
            }
            String id = key.toLowerCase(Locale.ROOT);
            boosters.put(id, new BoosterType(id, kind, mult, duration, b.getString("display", "&f" + key),
                    b.getString("item.material", "EXPERIENCE_BOTTLE"), b.getString("item.name", b.getString("display", key)),
                    b.getStringList("item.lore")));
        }
    }

    public BoosterType get(String id) {
        return id == null ? null : boosters.get(id.toLowerCase(Locale.ROOT));
    }

    public Collection<BoosterType> all() {
        return boosters.values();
    }

    public List<String> ids() {
        return new java.util.ArrayList<String>(boosters.keySet());
    }

    private static Map<String, String> vars(BoosterType b) {
        return Text.vars("booster", Text.color(b.display), "multiplier", trim(b.multiplier),
                "time", Text.duration(b.durationSeconds), "kind", b.kind.name());
    }

    public static String trim(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    public ItemStack createItem(BoosterType b, int amount) {
        ItemStack it = Items.parse(b.material, Math.max(1, amount));
        Items.decorate(it, b.itemName, b.itemLore, vars(b), true);
        return Items.withTag(plugin.getLoader(), it, "booster", b.id);
    }

    /** Booster do item, ou {@code null} se o item não for um booster (ou o tipo não existir mais). */
    public BoosterType fromItem(ItemStack it) {
        return get(Items.readTag(plugin.getLoader(), it, "booster"));
    }

    // ------------------------------------------------------------------ efeitos

    /** Booster ativo deste tipo no gerador (limpa o vencido). */
    public BoosterType active(Spawner s, Kind k) {
        Spawner.Boost b = s.boosts.get(k.name());
        if (b == null) {
            return null;
        }
        if (b.expiresAt <= System.currentTimeMillis()) {
            s.boosts.remove(k.name());
            s.dirty = true;
            return null;
        }
        return get(b.boosterId);
    }

    public double multiplier(Spawner s, Kind k) {
        BoosterType b = active(s, k);
        return b == null ? 1D : b.multiplier;
    }

    public long remainingSeconds(Spawner s, Kind k) {
        Spawner.Boost b = s.boosts.get(k.name());
        return b == null ? 0L : Math.max(0L, (b.expiresAt - System.currentTimeMillis() + 999L) / 1000L);
    }

    public Result apply(Spawner s, BoosterType b) {
        if (active(s, b.kind) != null) {
            return Result.ALREADY_ACTIVE;
        }
        s.boosts.put(b.kind.name(), new Spawner.Boost(b.id, System.currentTimeMillis() + b.durationSeconds * 1000L));
        s.dirty = true;
        return Result.OK;
    }

    public Map<String, String> describe(BoosterType b) {
        return vars(b);
    }
}
