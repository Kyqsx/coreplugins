package br.com.coreplugins.spawners.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Um gerador colocado no mundo (estado persistido + estado de execução). */
public final class Spawner {

    public final long id;
    public final String typeId;
    public final UUID owner;
    public String ownerName;
    public final String world;
    public final int x;
    public final int y;
    public final int z;

    /** Lado do mob: 0 = +z (sul), 1 = -x (oeste), 2 = -z (norte), 3 = +x (leste). */
    public int facing;
    public int stack;
    public long mobs;
    public boolean enabled = true;
    public boolean isPublic;
    public boolean hologram = true;
    public boolean autosell;
    /** Já recebeu o ovo (ou o servidor não exige ovo). Sem ovo o gerador não produz. */
    public boolean egg = true;
    public int dropsLevel;
    public int speedLevel;
    public final Map<UUID, String> friends = new LinkedHashMap<UUID, String>();
    public final Map<String, Long> drops = new LinkedHashMap<String, Long>();
    /** Boosters ativos, um por tipo (chave = nome do BoosterKind). */
    public final Map<String, Boost> boosts = new LinkedHashMap<String, Boost>();

    /** Um booster ativo neste gerador. */
    public static final class Boost {
        public final String boosterId;
        public final long expiresAt;

        public Boost(String boosterId, long expiresAt) {
            this.boosterId = boosterId;
            this.expiresAt = expiresAt;
        }
    }

    // ---- execução (não vai para o banco)
    public transient int countdown;
    public transient LivingEntity entity;
    public transient boolean dirty;

    public Spawner(long id, String typeId, UUID owner, String ownerName, String world, int x, int y, int z, int stack) {
        this.id = id;
        this.typeId = typeId;
        this.owner = owner;
        this.ownerName = ownerName;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.stack = stack;
    }

    public World world() {
        return Bukkit.getWorld(world);
    }

    public Location location() {
        World w = world();
        return w == null ? null : new Location(w, x, y, z);
    }

    public long totalDrops() {
        long t = 0;
        for (Long v : drops.values()) {
            t += v;
        }
        return t;
    }

    public long dropAmount(String key) {
        Long v = drops.get(key);
        return v == null ? 0L : v;
    }

    public boolean isFriend(UUID id) {
        return friends.containsKey(id);
    }

    public int chunkX() {
        return x >> 4;
    }

    public int chunkZ() {
        return z >> 4;
    }

    /** Cópia dos dados persistidos, segura para gravar em outra thread. */
    public Spawner snapshot() {
        Spawner c = new Spawner(id, typeId, owner, ownerName, world, x, y, z, stack);
        c.mobs = mobs;
        c.facing = facing;
        c.enabled = enabled;
        c.isPublic = isPublic;
        c.hologram = hologram;
        c.autosell = autosell;
        c.egg = egg;
        c.dropsLevel = dropsLevel;
        c.speedLevel = speedLevel;
        c.friends.putAll(friends);
        c.drops.putAll(drops);
        c.boosts.putAll(boosts);
        return c;
    }

    public static String locKey(String world, int x, int y, int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }

    public static String chunkKey(String world, int cx, int cz) {
        return world + ":" + cx + ":" + cz;
    }
}
