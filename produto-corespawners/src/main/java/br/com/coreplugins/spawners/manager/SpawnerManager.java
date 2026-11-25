package br.com.coreplugins.spawners.manager;

import br.com.coreplugins.spawners.SpawnersProduct;
import br.com.coreplugins.spawners.model.BoosterType;
import br.com.coreplugins.spawners.model.DropType;
import br.com.coreplugins.spawners.model.Spawner;
import br.com.coreplugins.spawners.model.SpawnerType;
import br.com.coreplugins.spawners.util.Amounts;
import br.com.coreplugins.spawners.util.Compat;
import br.com.coreplugins.spawners.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Zombie;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** Índice dos geradores, geração de mobs por tempo, stack de mobs e processamento de mortes. */
public final class SpawnerManager {

    private static final int[] SIDE_DX = {0, -1, 0, 1};
    private static final int[] SIDE_DZ = {1, 0, -1, 0};

    /** Lado (0-3) para onde o jogador olha: 0 = +z, 1 = -x, 2 = -z, 3 = +x. */
    public static int sideOf(float yaw) {
        return Math.round(yaw / 90F) & 3;
    }

    private final SpawnersProduct plugin;
    private final Map<Long, Spawner> byId = new HashMap<Long, Spawner>();
    private final Map<String, Spawner> byLoc = new HashMap<String, Spawner>();
    private final Map<String, Set<Spawner>> byChunk = new HashMap<String, Set<Spawner>>();
    private final Map<UUID, Spawner> byEntity = new HashMap<UUID, Spawner>();
    private final Set<Long> deleted = new HashSet<Long>();
    private final Random random = new Random();
    private long nextId = 1L;

    public SpawnerManager(SpawnersProduct plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ índice

    public void load(List<Spawner> all) {
        for (Spawner s : all) {
            index(s);
            nextId = Math.max(nextId, s.id + 1L);
        }
    }

    private void index(Spawner s) {
        byId.put(s.id, s);
        byLoc.put(Spawner.locKey(s.world, s.x, s.y, s.z), s);
        String ck = Spawner.chunkKey(s.world, s.chunkX(), s.chunkZ());
        Set<Spawner> set = byChunk.get(ck);
        if (set == null) {
            set = new HashSet<Spawner>();
            byChunk.put(ck, set);
        }
        set.add(s);
    }

    public Spawner get(long id) {
        return byId.get(id);
    }

    public Spawner at(Location l) {
        if (l == null || l.getWorld() == null) {
            return null;
        }
        return byLoc.get(Spawner.locKey(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ()));
    }

    public Spawner ofEntity(Entity e) {
        return e == null ? null : byEntity.get(e.getUniqueId());
    }

    public int count() {
        return byId.size();
    }

    /** Todos os geradores (somente leitura; só na thread principal). */
    public java.util.Collection<Spawner> all() {
        return java.util.Collections.unmodifiableCollection(byId.values());
    }

    /** {soma dos stacks, quantidade de blocos} do dono. */
    public long[] totalsOf(UUID owner) {
        long stack = 0L;
        long blocks = 0L;
        for (Spawner s : byId.values()) {
            if (s.owner.equals(owner)) {
                stack += s.stack;
                blocks++;
            }
        }
        return new long[]{stack, blocks};
    }

    /** Soma dos stacks e dos mobs de todos os geradores (para o /spawners debug). */
    public long[] globalTotals() {
        long stack = 0L;
        long mobs = 0L;
        for (Spawner s : byId.values()) {
            stack += s.stack;
            mobs += s.mobs;
        }
        return new long[]{stack, mobs};
    }

    /** Geradores alterados que ainda não foram gravados no banco. */
    public int dirtyCount() {
        int n = 0;
        for (Spawner s : byId.values()) {
            if (s.dirty) {
                n++;
            }
        }
        return n;
    }

    /** Remoções ainda não gravadas no banco. */
    public int deletedCount() {
        return deleted.size();
    }

    public Spawner create(SpawnerType t, Player owner, Location loc, int stack, boolean egg) {
        Spawner s = new Spawner(nextId++, t.id, owner.getUniqueId(), owner.getName(), loc.getWorld().getName(),
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), stack);
        s.isPublic = plugin.getConfig().getBoolean("general.public-default", false);
        s.hologram = plugin.getConfig().getBoolean("general.hologram-default", true);
        s.autosell = plugin.getConfig().getBoolean("general.autosell-default", false);
        s.egg = egg || !plugin.eggRequired();
        s.dirty = true;
        index(s);
        return s;
    }

    public void remove(Spawner s) {
        byId.remove(s.id);
        byLoc.remove(Spawner.locKey(s.world, s.x, s.y, s.z));
        Set<Spawner> set = byChunk.get(Spawner.chunkKey(s.world, s.chunkX(), s.chunkZ()));
        if (set != null) {
            set.remove(s);
        }
        removeEntity(s);
        plugin.holograms().remove(s);
        deleted.add(s.id);
    }

    /** Gerador do mesmo dono e tipo perto do local (para somar o stack). */
    public Spawner findMergeTarget(UUID owner, String typeId, Location loc, int radius) {
        if (radius <= 0) {
            return null;
        }
        String w = loc.getWorld().getName();
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        int span = (radius >> 4) + 1;
        Spawner best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -span; dx <= span; dx++) {
            for (int dz = -span; dz <= span; dz++) {
                Set<Spawner> set = byChunk.get(Spawner.chunkKey(w, cx + dx, cz + dz));
                if (set == null) {
                    continue;
                }
                for (Spawner s : set) {
                    if (!s.owner.equals(owner) || !s.typeId.equals(typeId)) {
                        continue;
                    }
                    double d = Math.max(Math.abs(s.x - loc.getBlockX()), Math.max(Math.abs(s.y - loc.getBlockY()),
                            Math.abs(s.z - loc.getBlockZ())));
                    if (d <= radius && d < bestDist) {
                        best = s;
                        bestDist = d;
                    }
                }
            }
        }
        return best;
    }

    /** Há gerador do plugin perto deste ponto? (usado para impedir o spawn vanilla do bloco) */
    public boolean nearManaged(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        String w = loc.getWorld().getName();
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Set<Spawner> set = byChunk.get(Spawner.chunkKey(w, cx + dx, cz + dz));
                if (set == null) {
                    continue;
                }
                for (Spawner s : set) {
                    if (Math.abs(s.x + 0.5D - loc.getX()) <= 6D && Math.abs(s.y - loc.getY()) <= 5D
                            && Math.abs(s.z + 0.5D - loc.getZ()) <= 6D) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public int unitsInChunk(Spawner of) {
        Set<Spawner> set = byChunk.get(Spawner.chunkKey(of.world, of.chunkX(), of.chunkZ()));
        int total = 0;
        if (set != null) {
            for (Spawner s : set) {
                if (s.typeId.equals(of.typeId)) {
                    total += s.stack;
                }
            }
        }
        return total;
    }

    // ------------------------------------------------------------------ gravação

    /** Snapshots dos que mudaram (e zera a marca). Chamar na thread principal. */
    public List<Spawner> drainDirty() {
        List<Spawner> out = new ArrayList<Spawner>();
        for (Spawner s : byId.values()) {
            if (s.dirty) {
                s.dirty = false;
                out.add(s.snapshot());
            }
        }
        return out;
    }

    public List<Long> drainDeleted() {
        List<Long> out = new ArrayList<Long>(deleted);
        deleted.clear();
        return out;
    }

    /** Falha ao gravar: volta a marcar para tentar de novo. */
    public void requeue(List<Spawner> snapshots, List<Long> ids) {
        for (Spawner snap : snapshots) {
            Spawner s = byId.get(snap.id);
            if (s != null) {
                s.dirty = true;
            }
        }
        deleted.addAll(ids);
    }

    // ------------------------------------------------------------------ valores derivados

    public int interval(Spawner s, SpawnerType t) {
        double v = Math.max(t.speedUpgrade.minSeconds, t.intervalSeconds - s.speedLevel * t.speedUpgrade.perLevel);
        v = v / plugin.boosters().multiplier(s, BoosterType.Kind.SPEED);
        return (int) Math.max(1D, Math.ceil(v));
    }

    public long dropsPerMob(Spawner s, SpawnerType t) {
        return Math.max(1L, (long) (1D + s.dropsLevel * t.dropsUpgrade.perLevel));
    }

    public Map<String, String> vars(Spawner s) {
        SpawnerType t = plugin.types().get(s.typeId);
        Map<String, String> v = new LinkedHashMap<String, String>();
        v.put("id", String.valueOf(s.id));
        v.put("type", t == null ? s.typeId : Text.color(t.display));
        v.put("entity", t == null ? "?" : Text.color(t.entityName));
        v.put("owner", s.ownerName == null ? "?" : s.ownerName);
        v.put("stack", Amounts.number(s.stack));
        v.put("mobs", Amounts.number(s.mobs));
        v.put("amount", Amounts.number(s.mobs));
        v.put("status", plugin.messages().on(s.enabled));
        v.put("public", plugin.messages().on(s.isPublic));
        v.put("hologram", plugin.messages().on(s.hologram));
        v.put("autosell", plugin.messages().on(s.autosell));
        v.put("drops_total", Amounts.letter(s.totalDrops()));
        v.put("drops", Amounts.letter(s.totalDrops()));
        v.put("drops_per_mob", t == null ? "1" : String.valueOf(dropsPerMob(s, t)));
        v.put("interval", t == null ? "?" : String.valueOf(interval(s, t)));
        v.put("friends", String.valueOf(s.friends.size()));
        v.put("egg", plugin.messages().text(s.egg || !plugin.eggRequired() ? "words.yes" : "words.egg-missing"));
        return v;
    }

    // ------------------------------------------------------------------ geração

    /** Roda a cada segundo. */
    public void tick() {
        for (Spawner s : byId.values()) {
            SpawnerType t = plugin.types().get(s.typeId);
            World w = s.world();
            if (t == null || w == null || !s.enabled || !w.isChunkLoaded(s.chunkX(), s.chunkZ())) {
                continue;
            }
            if (!s.egg && plugin.eggRequired()) {
                continue; // sem ovo o gerador não produz
            }
            if (t.generateOnline && Bukkit.getPlayer(s.owner) == null) {
                continue;
            }
            s.countdown++;
            if (s.countdown >= interval(s, t)) {
                s.countdown = 0;
                wave(s, t);
            }
        }
    }

    private void wave(Spawner s, SpawnerType t) {
        long add = s.stack;
        if (t.mobsMax > 0) {
            add = Math.min(add, t.mobsMax - s.mobs);
        }
        if (add <= 0) {
            return;
        }
        s.mobs += add;
        s.dirty = true;
        ensureEntity(s);
        plugin.holograms().update(s);
    }

    // ------------------------------------------------------------------ entidade (mob empilhado)

    private Location spawnLocation(Spawner s, SpawnerType t, World w) {
        Location above = new Location(w, s.x + 0.5D, s.y + 1D, s.z + 0.5D);
        if (t.above) {
            return above;
        }
        // Lado escolhido ao colocar; se estiver bloqueado, tenta os outros (começando pelo escolhido).
        for (int i = 0; i < 4; i++) {
            int side = (s.facing + i) & 3;
            int bx = s.x + SIDE_DX[side] * t.distance;
            int bz = s.z + SIDE_DZ[side] * t.distance;
            if (w.getBlockAt(bx, s.y, bz).getType() == Material.AIR && w.getBlockAt(bx, s.y + 1, bz).getType() == Material.AIR) {
                return new Location(w, bx + 0.5D, s.y, bz + 0.5D);
            }
        }
        return above;
    }

    /** Garante que exista (e esteja atualizado) o mob empilhado do gerador, se houver mobs e o chunk estiver carregado. */
    public void ensureEntity(Spawner s) {
        SpawnerType t = plugin.types().get(s.typeId);
        World w = s.world();
        if (t == null || w == null || s.mobs <= 0 || !w.isChunkLoaded(s.chunkX(), s.chunkZ())) {
            if (s.mobs <= 0) {
                removeEntity(s);
            }
            return;
        }
        if (s.entity != null && s.entity.isValid()) {
            refreshEntity(s, t);
            return;
        }
        removeEntity(s);
        try {
            Entity spawned = w.spawnEntity(spawnLocation(s, t, w), t.entity);
            if (!(spawned instanceof LivingEntity)) {
                spawned.remove();
                return;
            }
            LivingEntity e = (LivingEntity) spawned;
            Compat.freeze(e);
            Compat.tag(e);
            Compat.setMaxHealth(e, t.health);
            if (e instanceof Slime) {
                ((Slime) e).setSize(t.slimeSize);
            }
            if (t.baby) {
                if (e instanceof Ageable) {
                    ((Ageable) e).setBaby();
                } else if (e instanceof Zombie) {
                    ((Zombie) e).setBaby(true);
                }
            }
            e.setCustomNameVisible(true);
            s.entity = e;
            byEntity.put(e.getUniqueId(), s);
            refreshEntity(s, t);
        } catch (Throwable ex) {
            plugin.getLogger().warning("Não foi possível gerar o mob do gerador #" + s.id + ": " + ex);
        }
    }

    private void refreshEntity(Spawner s, SpawnerType t) {
        LivingEntity e = s.entity;
        if (e == null || !e.isValid()) {
            return;
        }
        e.setCustomName(Text.render(t.mobName, vars(s)));
        e.setHealth(Math.min(Compat.maxHealth(e), t.health > 0D ? t.health : Compat.maxHealth(e)));
    }

    public void removeEntity(Spawner s) {
        if (s.entity != null) {
            byEntity.remove(s.entity.getUniqueId());
            s.entity.remove();
            s.entity = null;
        }
    }

    /** O mob morreu por fora do plugin (ex.: /kill): some do índice; ele é recriado no próximo ensureEntity. */
    public void entityGone(Entity e) {
        Spawner s = byEntity.remove(e.getUniqueId());
        if (s != null && s.entity != null && s.entity.getUniqueId().equals(e.getUniqueId())) {
            s.entity = null;
        }
    }

    /** Mostra o mob dentro da gaiola do bloco (só depois que o gerador tem o ovo). */
    public void applyVisual(Spawner s) {
        SpawnerType t = plugin.types().get(s.typeId);
        World w = s.world();
        if (t == null || w == null || !s.egg || !w.isChunkLoaded(s.chunkX(), s.chunkZ())) {
            return;
        }
        try {
            org.bukkit.block.BlockState st = w.getBlockAt(s.x, s.y, s.z).getState();
            if (st instanceof org.bukkit.block.CreatureSpawner) {
                ((org.bukkit.block.CreatureSpawner) st).setSpawnedType(t.entity);
                st.update(true, false);
            }
        } catch (Throwable ignored) {
            // só o visual da gaiola; o gerador funciona do mesmo jeito
        }
    }

    // ------------------------------------------------------------------ chunks

    public void onChunkLoad(Chunk c) {
        String key = Spawner.chunkKey(c.getWorld().getName(), c.getX(), c.getZ());
        boolean has = byChunk.get(key) != null && !byChunk.get(key).isEmpty();
        cleanStray(c);
        if (has) {
            for (Spawner s : new ArrayList<Spawner>(byChunk.get(key))) {
                applyVisual(s);
                ensureEntity(s);
                plugin.holograms().update(s);
            }
        }
    }

    /** Remove entidades marcadas que sobraram de uma queda do servidor (só em versões com marca). */
    private void cleanStray(Chunk c) {
        for (Entity e : c.getEntities()) {
            if (Compat.tagged(e) && !byEntity.containsKey(e.getUniqueId()) && !(e instanceof Player)) {
                e.remove();
            }
        }
    }

    public void onChunkUnload(Chunk c) {
        Set<Spawner> set = byChunk.get(Spawner.chunkKey(c.getWorld().getName(), c.getX(), c.getZ()));
        if (set != null) {
            for (Spawner s : set) {
                removeEntity(s);
                plugin.holograms().remove(s);
            }
        }
        for (Entity e : c.getEntities()) {
            if (Compat.tagged(e) && !(e instanceof Player)) {
                e.remove();
            }
        }
    }

    public void refreshAllLoaded() {
        for (World w : Bukkit.getWorlds()) {
            for (Chunk c : w.getLoadedChunks()) {
                onChunkLoad(c);
            }
        }
    }

    public void shutdown() {
        for (Spawner s : new ArrayList<Spawner>(byId.values())) {
            removeEntity(s);
        }
        byEntity.clear();
        plugin.holograms().removeAll();
    }

    // ------------------------------------------------------------------ mortes

    /**
     * Um jogador matou mobs do gerador. {@code sneaking} mata só 1; sem shift mata todos
     * (se kill-all-without-shift estiver ligado no tipo).
     */
    public void kill(Player p, Spawner s, boolean sneaking) {
        SpawnerType t = plugin.types().get(s.typeId);
        if (t == null || s.mobs <= 0) {
            return;
        }
        long units = (sneaking || !t.killAllWithoutShift) ? 1L : s.mobs;
        plugin.stats().addKills(p, units);
        s.mobs -= units;
        s.dirty = true;
        if (s.mobs <= 0) {
            s.mobs = 0;
            removeEntity(s);
        } else {
            refreshEntity(s, t);
        }
        Map<DropType, Long> gained = new LinkedHashMap<DropType, Long>();
        double mult = plugin.bonus().chunkMultiplier(unitsInChunk(s))
                * plugin.boosters().multiplier(s, BoosterType.Kind.DROPS);
        long perMob = dropsPerMob(s, t);
        for (DropType d : plugin.dropsEnabled(t) ? t.drops().values() : java.util.Collections.<DropType>emptyList()) {
            double expected = units * (double) perMob * (d.chance / 100D) * mult;
            long amount = (long) Math.floor(expected);
            if (random.nextDouble() < expected - amount) {
                amount++;
            }
            if (amount > 0) {
                gained.put(d, amount);
            }
        }
        if (!gained.isEmpty()) {
            plugin.drops().handleKill(p, s, t, gained);
        }
        long xp = t.exp * units;
        if (xp > 0) {
            p.giveExp((int) Math.min(xp, Integer.MAX_VALUE));
        }
        plugin.holograms().update(s);
    }

    /** Soma drops ao armazém respeitando o limite do tipo. */
    public void store(Spawner s, SpawnerType t, DropType d, long amount) {
        long cur = s.dropAmount(d.key);
        long next = cur + amount;
        if (t.storageMax > 0) {
            next = Math.min(next, t.storageMax);
        }
        s.drops.put(d.key, next);
        s.dirty = true;
    }
}
