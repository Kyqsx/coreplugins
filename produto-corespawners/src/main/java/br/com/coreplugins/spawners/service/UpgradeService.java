package br.com.coreplugins.spawners.service;

import br.com.coreplugins.spawners.SpawnersProduct;
import br.com.coreplugins.spawners.economy.EconomyProvider;
import br.com.coreplugins.spawners.model.Spawner;
import br.com.coreplugins.spawners.model.SpawnerType;
import br.com.coreplugins.spawners.model.UpgradeConfig;
import br.com.coreplugins.spawners.util.Text;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;

/** Compra dos upgrades de drops e de velocidade. */
public final class UpgradeService {

    public enum Kind { DROPS, SPEED }

    private final SpawnersProduct plugin;
    private final Set<String> pending = new HashSet<String>();

    public UpgradeService(SpawnersProduct plugin) {
        this.plugin = plugin;
    }

    public UpgradeConfig config(SpawnerType t, Kind k) {
        return k == Kind.DROPS ? t.dropsUpgrade : t.speedUpgrade;
    }

    public int level(Spawner s, Kind k) {
        return k == Kind.DROPS ? s.dropsLevel : s.speedLevel;
    }

    public void buy(final Player p, final Spawner s, final Kind kind, final Runnable after) {
        final SpawnerType t = plugin.types().get(s.typeId);
        if (t == null) {
            return;
        }
        if (!plugin.perm(p, "upgrade")) {
            return;
        }
        final UpgradeConfig cfg = config(t, kind);
        if (!cfg.enabled()) {
            plugin.messages().send(p, "upgrade.disabled");
            return;
        }
        final int level = level(s, kind);
        if (level >= cfg.maxLevel) {
            plugin.messages().send(p, "upgrade.max");
            return;
        }
        final EconomyProvider ep = plugin.economy().get(cfg.currency);
        if (ep == null || !ep.isAvailable()) {
            plugin.messages().send(p, "economy.unavailable", Text.vars("currency", cfg.currency));
            return;
        }
        final String key = s.id + ":" + kind;
        if (!pending.add(key)) {
            return; // cobrança em andamento (clique duplo)
        }
        final double price = cfg.priceFor(level);
        if (price <= 0D) {
            pending.remove(key);
            apply(p, s, kind, ep, 0D, after);
            return;
        }
        plugin.economy().charge(p, ep, price, new java.util.function.Consumer<EconomyProvider.Result>() {
            @Override
            public void accept(EconomyProvider.Result r) {
                pending.remove(key);
                if (r == EconomyProvider.Result.OK) {
                    if (plugin.spawners().get(s.id) != s || level(s, kind) != level) {
                        plugin.economy().pay(p, ep, price); // o gerador sumiu durante a cobrança: devolve
                        return;
                    }
                    apply(p, s, kind, ep, price, after);
                } else if (r == EconomyProvider.Result.INSUFFICIENT) {
                    plugin.messages().send(p, "economy.no-balance", Text.vars("price", ep.format(price)));
                } else {
                    plugin.messages().send(p, "economy.error");
                }
            }
        });
    }

    private void apply(Player p, Spawner s, Kind kind, EconomyProvider ep, double price, Runnable after) {
        if (kind == Kind.DROPS) {
            s.dropsLevel++;
        } else {
            s.speedLevel++;
        }
        s.dirty = true;
        plugin.messages().send(p, "upgrade.done", Text.vars(
                "upgrade", plugin.messages().text("upgrade.names." + (kind == Kind.DROPS ? "drops" : "speed")),
                "level", String.valueOf(level(s, kind)), "price", ep.format(price)));
        plugin.holograms().update(s);
        if (after != null) {
            after.run();
        }
    }
}
