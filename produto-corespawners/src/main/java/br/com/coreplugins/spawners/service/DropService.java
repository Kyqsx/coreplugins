package br.com.coreplugins.spawners.service;

import br.com.coreplugins.spawners.SpawnersProduct;
import br.com.coreplugins.spawners.economy.EconomyProvider;
import br.com.coreplugins.spawners.model.BoosterType;
import br.com.coreplugins.spawners.model.DropType;
import br.com.coreplugins.spawners.model.Price;
import br.com.coreplugins.spawners.model.Spawner;
import br.com.coreplugins.spawners.model.SpawnerType;
import br.com.coreplugins.spawners.util.Amounts;
import br.com.coreplugins.spawners.util.Items;
import br.com.coreplugins.spawners.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Armazenamento, venda (manual e automática) e coleta dos drops. */
public final class DropService {

    /** Resultado de uma venda. */
    public static final class Sale {
        public final Map<DropType, Long> sold = new LinkedHashMap<DropType, Long>();
        public final Map<EconomyProvider, Double> totals = new LinkedHashMap<EconomyProvider, Double>();
        public double bonus;

        public String value() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<EconomyProvider, Double> e : totals.entrySet()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(e.getKey().format(e.getValue()));
            }
            return sb.toString();
        }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<DropType, Long> e : sold.entrySet()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(Amounts.letter(e.getValue())).append("x ").append(Text.color(e.getKey().name));
            }
            return sb.toString();
        }
    }

    private final SpawnersProduct plugin;

    public DropService(SpawnersProduct plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ morte de mobs

    public boolean autosellActive(Player p, Spawner s, SpawnerType t) {
        if (!s.autosell || !t.autoSellAllowed || !p.hasPermission("corespawners.autosell")) {
            return false;
        }
        String perm = plugin.getConfig().getString("general.autosell-permission", "");
        return perm.isEmpty() || p.hasPermission(perm);
    }

    public void handleKill(Player p, Spawner s, SpawnerType t, Map<DropType, Long> gained) {
        Map<DropType, Long> toStore = new LinkedHashMap<DropType, Long>(gained);
        if (autosellActive(p, s, t)) {
            Sale sale = sell(p, s, gained);
            for (DropType d : sale.sold.keySet()) {
                toStore.remove(d);
            }
            if (!sale.sold.isEmpty()) {
                plugin.messages().send(p, "drops.autosell", Text.vars("value", sale.value(), "summary", sale.summary()));
            }
        }
        for (Map.Entry<DropType, Long> e : toStore.entrySet()) {
            plugin.spawners().store(s, t, e.getKey(), e.getValue());
        }
    }

    // ------------------------------------------------------------------ venda

    /** Paga ao jogador pelos drops informados. Drops cuja economia não está disponível não são vendidos. */
    public Sale sell(Player p, Spawner s, Map<DropType, Long> amounts) {
        Sale sale = new Sale();
        double factor = (1D + plugin.bonus().sellBonus(p) / 100D) * plugin.boosters().multiplier(s, BoosterType.Kind.SELL);
        sale.bonus = (factor - 1D) * 100D;
        Map<EconomyProvider, Double> totals = new LinkedHashMap<EconomyProvider, Double>();
        for (Map.Entry<DropType, Long> e : amounts.entrySet()) {
            DropType d = e.getKey();
            long amount = e.getValue();
            if (amount <= 0 || d.prices.isEmpty()) {
                continue;
            }
            Map<EconomyProvider, Double> part = new LinkedHashMap<EconomyProvider, Double>();
            boolean ok = true;
            for (Price pr : d.prices) {
                EconomyProvider ep = plugin.economy().get(pr.currency);
                if (ep == null || !ep.isAvailable()) {
                    ok = false;
                    break;
                }
                Double cur = part.get(ep);
                part.put(ep, (cur == null ? 0D : cur) + pr.amount * amount * factor);
            }
            if (!ok) {
                continue;
            }
            for (Map.Entry<EconomyProvider, Double> pe : part.entrySet()) {
                Double cur = totals.get(pe.getKey());
                totals.put(pe.getKey(), (cur == null ? 0D : cur) + pe.getValue());
            }
            sale.sold.put(d, amount);
        }
        if (sale.sold.isEmpty()) {
            return sale;
        }
        boolean anyPaid = false;
        Map<EconomyProvider, Double> paid = new LinkedHashMap<EconomyProvider, Double>();
        for (Map.Entry<EconomyProvider, Double> e : totals.entrySet()) {
            if (e.getValue() > 0D && plugin.economy().pay(p, e.getKey(), e.getValue())) {
                anyPaid = true;
                paid.put(e.getKey(), e.getValue());
            } else if (e.getValue() > 0D) {
                plugin.getLogger().warning("Não foi possível pagar " + e.getValue() + " em " + e.getKey().id() + " para " + p.getName());
            }
        }
        if (!anyPaid) {
            sale.sold.clear();
            return sale;
        }
        sale.totals.putAll(paid);
        long items = 0L;
        for (Long n : sale.sold.values()) {
            items += n;
        }
        plugin.stats().addSale(p, items, paid);
        return sale;
    }

    /** Venda pelo menu. {@code only == null} vende todos os tipos de drop armazenados. */
    public void sellFromStorage(Player p, Spawner s, DropType only) {
        SpawnerType t = plugin.types().get(s.typeId);
        if (t == null) {
            return;
        }
        if (!plugin.dropsEnabled(t)) {
            plugin.messages().send(p, "drops.disabled");
            return;
        }
        if (!t.sellEnabled) {
            plugin.messages().send(p, "drops.sell-disabled");
            return;
        }
        if (!plugin.perm(p, "sell")) {
            return;
        }
        if (!t.sellPermission.isEmpty() && !p.hasPermission(t.sellPermission)) {
            plugin.messages().send(p, "drops.no-permission");
            return;
        }
        Map<DropType, Long> amounts = new LinkedHashMap<DropType, Long>();
        for (DropType d : t.drops().values()) {
            if (only != null && only != d) {
                continue;
            }
            long a = s.dropAmount(d.key);
            if (a > 0) {
                amounts.put(d, a);
            }
        }
        if (amounts.isEmpty()) {
            plugin.messages().send(p, "drops.nothing");
            return;
        }
        Sale sale = sell(p, s, amounts);
        if (sale.sold.isEmpty()) {
            plugin.messages().send(p, "economy.error");
            return;
        }
        for (Map.Entry<DropType, Long> e : sale.sold.entrySet()) {
            s.drops.put(e.getKey().key, Math.max(0L, s.dropAmount(e.getKey().key) - e.getValue()));
        }
        s.dirty = true;
        String bonus = Amounts.number(sale.bonus);
        if (only != null && sale.sold.size() == 1) {
            plugin.messages().send(p, "drops.sold", Text.vars("amount", Amounts.number(sale.sold.get(only)),
                    "drop", Text.color(only.name), "value", sale.value(), "bonus", bonus));
        } else {
            plugin.messages().send(p, "drops.sold-all", Text.vars("value", sale.value(), "bonus", bonus));
        }
        plugin.holograms().update(s);
    }

    /** Valor de uma unidade do drop (sem bônus), para os menus. */
    public String unitValue(DropType d, double amount) {
        Map<EconomyProvider, Double> totals = new LinkedHashMap<EconomyProvider, Double>();
        for (Price pr : d.prices) {
            EconomyProvider ep = plugin.economy().get(pr.currency);
            if (ep == null) {
                return "?";
            }
            Double cur = totals.get(ep);
            totals.put(ep, (cur == null ? 0D : cur) + pr.amount * amount);
        }
        if (totals.isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<EconomyProvider, Double> e : totals.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e.getKey().format(e.getValue()));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ coleta

    public void collect(Player p, Spawner s, DropType d) {
        SpawnerType t = plugin.types().get(s.typeId);
        if (t == null) {
            return;
        }
        if (!plugin.dropsEnabled(t)) {
            plugin.messages().send(p, "drops.disabled");
            return;
        }
        if (!d.collect) {
            plugin.messages().send(p, "drops.collect-disabled");
            return;
        }
        if (!plugin.perm(p, "collect")) {
            return;
        }
        if (!t.collectPermission.isEmpty() && !p.hasPermission(t.collectPermission)) {
            plugin.messages().send(p, "drops.no-permission");
            return;
        }
        long stored = s.dropAmount(d.key);
        if (stored <= 0) {
            plugin.messages().send(p, "drops.nothing");
            return;
        }
        ItemStack proto = Items.parse(d.materialSpec, 1);
        if (d.itemName != null || (d.itemLore != null && !d.itemLore.isEmpty())) {
            Items.decorate(proto, d.itemName, d.itemLore, null, false);
        }
        int maxStack = Math.max(1, proto.getMaxStackSize());
        long capacity = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack cur = p.getInventory().getItem(i);
            if (cur == null || cur.getType().name().equals("AIR")) {
                capacity += maxStack;
            } else if (cur.isSimilar(proto)) {
                capacity += Math.max(0, maxStack - cur.getAmount());
            }
        }
        long give = Math.min(stored, capacity);
        if (give <= 0) {
            plugin.messages().send(p, "drops.inventory-full");
            return;
        }
        long left = give;
        while (left > 0) {
            int n = (int) Math.min(left, maxStack);
            ItemStack c = proto.clone();
            c.setAmount(n);
            List<ItemStack> overflow = new ArrayList<ItemStack>(p.getInventory().addItem(c).values());
            for (ItemStack o : overflow) {
                p.getWorld().dropItemNaturally(p.getLocation(), o);
            }
            left -= n;
        }
        s.drops.put(d.key, stored - give);
        s.dirty = true;
        plugin.messages().send(p, "drops.collected", Text.vars("amount", Amounts.number(give), "drop", Text.color(d.name)));
        plugin.holograms().update(s);
    }
}
