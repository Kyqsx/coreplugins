package br.com.coreplugins.spawners.model;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/** Um tipo de gerador, lido de spawners/&lt;id&gt;.yml. */
public final class SpawnerType {

    public final String id;
    public final String display;
    public final EntityType entity;
    public final String entityName;

    public final String itemMaterial;
    public final String itemName;
    public final List<String> itemLore;

    public final String permission;
    public final String killPermission;
    public final double health;
    public final int intervalSeconds;
    public final boolean silkTouch;
    public final boolean above;
    public final int distance;
    public final boolean generateOnline;
    public final int exp;
    public final boolean baby;
    public final int slimeSize;
    public final int stackMax;
    public final long mobsMax;
    public final String mobName;
    public final boolean killAllWithoutShift;

    public final UpgradeConfig dropsUpgrade;
    public final UpgradeConfig speedUpgrade;

    public final boolean hologramEnabled;
    public final double hologramOffset;
    public final List<String> hologramLines;

    public final boolean dropsEnabled;
    public final long storageMax;
    public final boolean sellEnabled;
    public final boolean autoSellAllowed;
    public final String sellPermission;
    public final String collectPermission;
    private final Map<String, DropType> drops;

    private SpawnerType(String id, YamlConfiguration c, EntityType entity, Map<String, DropType> drops) {
        this.id = id;
        this.entity = entity;
        this.drops = drops;
        this.display = c.getString("display", "&f" + id);
        this.entityName = c.getString("entity-name", entity.name());
        this.itemMaterial = c.getString("item.material", "SPAWNER");
        this.itemName = c.getString("item.name", display);
        this.itemLore = c.getStringList("item.lore");
        this.permission = c.getString("permission", "");
        this.killPermission = c.getString("kill-permission", "");
        this.health = c.getDouble("health", 0D);
        this.intervalSeconds = Math.max(1, c.getInt("interval-seconds", 10));
        this.silkTouch = c.getBoolean("silk-touch", false);
        this.above = "ABOVE".equalsIgnoreCase(c.getString("mob-position", "SIDE"));
        this.distance = Math.max(1, c.getInt("distance", 2));
        this.generateOnline = c.getBoolean("generate-online", false);
        this.exp = Math.max(0, c.getInt("exp", 0));
        this.baby = c.getBoolean("entity-options.baby", false);
        this.slimeSize = Math.max(1, c.getInt("entity-options.slime-size", 1));
        this.stackMax = Math.max(0, c.getInt("stack.max", 0));
        this.mobsMax = Math.max(0L, c.getLong("mobs.max", 0L));
        this.mobName = c.getString("mobs.name", "&7{amount}x &f{entity}");
        this.killAllWithoutShift = c.getBoolean("mobs.kill-all-without-shift", true);
        this.dropsUpgrade = new UpgradeConfig(c.getConfigurationSection("upgrades.drops"), "add-per-level");
        this.speedUpgrade = new UpgradeConfig(c.getConfigurationSection("upgrades.speed"), "reduce-seconds");
        this.hologramEnabled = c.getBoolean("hologram.enabled", true);
        this.hologramOffset = c.getDouble("hologram.offset", 2.0D);
        this.hologramLines = c.getStringList("hologram.lines");
        this.dropsEnabled = c.getBoolean("drops.enabled", true);
        this.storageMax = Math.max(0L, c.getLong("drops.storage-max", 0L));
        this.sellEnabled = c.getBoolean("drops.sell", true);
        this.autoSellAllowed = c.getBoolean("drops.auto-sell", true);
        this.sellPermission = c.getString("drops.sell-permission", "");
        this.collectPermission = c.getString("drops.collect-permission", "");
    }

    /** {@code null} (com aviso no log) se o arquivo for inválido. */
    public static SpawnerType load(String id, YamlConfiguration c, Logger log) {
        String entityName = c.getString("entity", "");
        EntityType entity;
        try {
            entity = EntityType.valueOf(entityName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warning("spawners/" + id + ".yml: entidade '" + entityName + "' não existe nesta versão. Tipo ignorado.");
            return null;
        }
        if (!entity.isAlive() || !entity.isSpawnable()) {
            log.warning("spawners/" + id + ".yml: '" + entityName + "' não é um mob que possa ser gerado. Tipo ignorado.");
            return null;
        }
        Map<String, DropType> drops = new LinkedHashMap<String, DropType>();
        ConfigurationSection ds = c.getConfigurationSection("drops.types");
        if (ds != null) {
            for (String key : ds.getKeys(false)) {
                ConfigurationSection d = ds.getConfigurationSection(key);
                if (d == null) {
                    continue;
                }
                drops.put(key.toLowerCase(Locale.ROOT), new DropType(
                        key.toLowerCase(Locale.ROOT),
                        d.getString("name", key),
                        Math.max(0D, Math.min(100D, d.getDouble("chance", 100D))),
                        d.getBoolean("collect", false),
                        d.getString("item.material", "PAPER"),
                        d.getString("item.name"),
                        d.isList("item.lore") ? d.getStringList("item.lore") : new ArrayList<String>(),
                        Price.readAll(d)));
            }
        }
        if (drops.isEmpty()) {
            log.warning("spawners/" + id + ".yml: nenhum drop em drops.types; o gerador funcionará sem drops.");
        }
        return new SpawnerType(id.toLowerCase(Locale.ROOT), c, entity, drops);
    }

    public Map<String, DropType> drops() {
        return Collections.unmodifiableMap(drops);
    }

    public DropType drop(String key) {
        return key == null ? null : drops.get(key.toLowerCase(Locale.ROOT));
    }
}
