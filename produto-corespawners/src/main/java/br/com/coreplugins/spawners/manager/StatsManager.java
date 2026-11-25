package br.com.coreplugins.spawners.manager;

import br.com.coreplugins.spawners.SpawnersProduct;
import br.com.coreplugins.spawners.economy.EconomyProvider;
import br.com.coreplugins.spawners.model.PlayerStats;
import br.com.coreplugins.spawners.model.Spawner;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Contadores por jogador (mobs mortos, vendas) e o ranking dos geradores.
 * O ranking é um instantâneo refeito a cada poucos segundos na thread principal e lido sem trava
 * (scoreboard, holograma e tab chamam os placeholders o tempo todo, às vezes fora da thread principal).
 */
public final class StatsManager {

    public static final String STACK = "stack";
    public static final String KILLS = "kills";
    public static final String SALES = "sales";

    /** Uma linha do ranking. */
    public static final class Entry {
        public final UUID id;
        public final String name;
        public final double value;

        Entry(UUID id, String name, double value) {
            this.id = id;
            this.name = name;
            this.value = value;
        }
    }

    /** Totais de geradores de um dono. */
    public static final class Owned {
        public long stack;
        public long blocks;
        public long drops;
    }

    /** Estado imutável do ranking em um instante. */
    public static final class Snapshot {
        final Map<UUID, Owned> owners;
        final Map<String, List<Entry>> tops;
        final Map<String, Map<UUID, Integer>> positions;
        final Map<UUID, Long> limits;
        public final String salesCurrency;

        Snapshot(Map<UUID, Owned> owners, Map<String, List<Entry>> tops, Map<String, Map<UUID, Integer>> positions,
                 Map<UUID, Long> limits, String salesCurrency) {
            this.owners = owners;
            this.tops = tops;
            this.positions = positions;
            this.limits = limits;
            this.salesCurrency = salesCurrency;
        }
    }

    private final SpawnersProduct plugin;
    private final Map<UUID, PlayerStats> stats = new ConcurrentHashMap<UUID, PlayerStats>();
    private volatile Snapshot snapshot = new Snapshot(Collections.<UUID, Owned>emptyMap(),
            Collections.<String, List<Entry>>emptyMap(), Collections.<String, Map<UUID, Integer>>emptyMap(), Collections.<UUID, Long>emptyMap(), "");

    public StatsManager(SpawnersProduct plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ contadores

    public void load(List<PlayerStats> all) {
        for (PlayerStats s : all) {
            stats.put(s.id, s);
        }
    }

    public PlayerStats of(UUID id) {
        return stats.get(id);
    }

    private PlayerStats getOrCreate(Player p) {
        PlayerStats s = stats.get(p.getUniqueId());
        if (s == null) {
            s = new PlayerStats(p.getUniqueId(), p.getName());
            s.dirty = true;
            stats.put(p.getUniqueId(), s);
        }
        s.name(p.getName());
        return s;
    }

    public void addKills(Player p, long n) {
        if (p != null && n > 0) {
            getOrCreate(p).addKills(n);
        }
    }

    /** Registra uma venda: {@code items} drops vendidos e o que foi pago em cada moeda. */
    public void addSale(Player p, long items, Map<EconomyProvider, Double> paid) {
        if (p == null) {
            return;
        }
        PlayerStats s = getOrCreate(p);
        boolean first = true;
        for (Map.Entry<EconomyProvider, Double> e : paid.entrySet()) {
            s.addSold(first ? items : 0L, e.getKey().id(), e.getValue());
            first = false;
        }
        if (paid.isEmpty()) {
            s.addSold(items, null, 0D);
        }
    }

    /** Cópias dos que mudaram (e zera a marca). Chamar na thread principal. */
    public List<PlayerStats> drainDirty() {
        List<PlayerStats> out = new ArrayList<PlayerStats>();
        for (PlayerStats s : stats.values()) {
            if (s.dirty) {
                s.dirty = false;
                out.add(s.snapshot());
            }
        }
        return out;
    }

    public void requeue(List<PlayerStats> snaps) {
        for (PlayerStats snap : snaps) {
            PlayerStats s = stats.get(snap.id);
            if (s != null) {
                s.dirty = true;
            }
        }
    }

    public int dirtyCount() {
        int n = 0;
        for (PlayerStats s : stats.values()) {
            if (s.dirty) {
                n++;
            }
        }
        return n;
    }

    public int players() {
        return stats.size();
    }

    // ------------------------------------------------------------------ ranking

    /** Id da moeda usada no ranking de vendas ("" se ainda não há economia detectada). */
    public String salesCurrency() {
        String c = plugin.getConfig().getString("ranking.sales-currency", "auto").trim();
        EconomyProvider ep = null;
        if (plugin.economy() != null) {
            ep = c.isEmpty() || c.equalsIgnoreCase("auto") ? plugin.economy().defaultProvider() : plugin.economy().get(c);
        }
        if (ep != null) {
            return ep.id();
        }
        return c.equalsIgnoreCase("auto") ? "" : c.toLowerCase(Locale.ROOT);
    }

    public int size() {
        return Math.max(1, Math.min(100, plugin.getConfig().getInt("ranking.size", 10)));
    }

    /** Refaz o ranking. Thread principal. */
    public void refresh() {
        Map<UUID, Owned> owners = new HashMap<UUID, Owned>();
        Map<UUID, String> names = new HashMap<UUID, String>();
        for (Spawner s : plugin.spawners().all()) {
            Owned o = owners.get(s.owner);
            if (o == null) {
                o = new Owned();
                owners.put(s.owner, o);
            }
            o.stack += s.stack;
            o.blocks++;
            o.drops += s.totalDrops();
            if (s.ownerName != null) {
                names.put(s.owner, s.ownerName);
            }
        }
        String currency = salesCurrency();
        List<Entry> stackAll = new ArrayList<Entry>();
        for (Map.Entry<UUID, Owned> e : owners.entrySet()) {
            if (e.getValue().stack > 0) {
                String n = names.get(e.getKey());
                stackAll.add(new Entry(e.getKey(), n == null ? "?" : n, e.getValue().stack));
            }
        }
        List<Entry> killsAll = new ArrayList<Entry>();
        List<Entry> salesAll = new ArrayList<Entry>();
        for (PlayerStats s : stats.values()) {
            if (s.kills() > 0) {
                killsAll.add(new Entry(s.id, s.name(), s.kills()));
            }
            double v = currency.isEmpty() ? 0D : s.sales(currency);
            if (v > 0D) {
                salesAll.add(new Entry(s.id, s.name(), v));
            }
        }
        Map<String, List<Entry>> tops = new HashMap<String, List<Entry>>();
        Map<String, Map<UUID, Integer>> positions = new HashMap<String, Map<UUID, Integer>>();
        int size = size();
        rank(STACK, stackAll, size, tops, positions);
        rank(KILLS, killsAll, size, tops, positions);
        rank(SALES, salesAll, size, tops, positions);
        // Limite de cada jogador online, guardado aqui para os placeholders não consultarem permissões fora da thread principal.
        Map<UUID, Long> limits = new HashMap<UUID, Long>();
        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            limits.put(p.getUniqueId(), plugin.limits().limitOf(p));
        }
        snapshot = new Snapshot(owners, tops, positions, limits, currency);
    }

    private static void rank(String key, List<Entry> all, int size, Map<String, List<Entry>> tops,
                             Map<String, Map<UUID, Integer>> positions) {
        Collections.sort(all, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                int c = Double.compare(b.value, a.value);
                return c != 0 ? c : a.name.compareToIgnoreCase(b.name);
            }
        });
        Map<UUID, Integer> pos = new HashMap<UUID, Integer>();
        for (int i = 0; i < all.size(); i++) {
            pos.put(all.get(i).id, i + 1);
        }
        tops.put(key, Collections.unmodifiableList(new ArrayList<Entry>(all.subList(0, Math.min(size, all.size())))));
        positions.put(key, pos);
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    /** Totais de geradores do dono segundo o último instantâneo (nunca nulo). */
    public Owned owned(UUID id) {
        Owned o = snapshot.owners.get(id);
        return o == null ? new Owned() : o;
    }

    /** Top da categoria (já limitado a {@code ranking.size}). */
    public List<Entry> top(String category) {
        List<Entry> l = snapshot.tops.get(category);
        return l == null ? Collections.<Entry>emptyList() : l;
    }

    /** Posição do jogador (1 = primeiro); 0 = fora do ranking. */
    public int position(String category, UUID id) {
        Map<UUID, Integer> m = snapshot.positions.get(category);
        Integer p = m == null ? null : m.get(id);
        return p == null ? 0 : p;
    }

    public double valueOf(String category, UUID id) {
        if (STACK.equals(category)) {
            return owned(id).stack;
        }
        PlayerStats s = stats.get(id);
        if (s == null) {
            return 0D;
        }
        return KILLS.equals(category) ? s.kills() : (snapshot.salesCurrency.isEmpty() ? 0D : s.sales(snapshot.salesCurrency));
    }

    /** Limite do jogador no último instantâneo (0 = ilimitado); -1 se ele não estava online. */
    public long limitOf(UUID id) {
        Long l = snapshot.limits.get(id);
        return l == null ? -1L : l;
    }

    public static boolean isCategory(String c) {
        return STACK.equals(c) || KILLS.equals(c) || SALES.equals(c);
    }
}
