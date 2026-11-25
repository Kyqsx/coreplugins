package br.com.coreplugins.spawners.listener;

import br.com.coreplugins.spawners.SpawnersProduct;
import br.com.coreplugins.spawners.model.Spawner;
import br.com.coreplugins.spawners.model.SpawnerType;
import br.com.coreplugins.spawners.util.ActionBar;
import br.com.coreplugins.spawners.util.Amounts;
import br.com.coreplugins.spawners.util.Compat;
import br.com.coreplugins.spawners.util.Text;
import org.bukkit.GameMode;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;

/** Dano e morte dos mobs empilhados, e bloqueio do spawn vanilla dos blocos de gerador. */
public final class MobListener implements Listener {

    private final SpawnersProduct plugin;

    public MobListener(SpawnersProduct plugin) {
        this.plugin = plugin;
    }

    private static Player attacker(EntityDamageByEntityEvent e) {
        Entity d = e.getDamager();
        if (d instanceof Player) {
            return (Player) d;
        }
        if (d instanceof Projectile && ((Projectile) d).getShooter() instanceof Player) {
            return (Player) ((Projectile) d).getShooter();
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) {
        final Spawner s = plugin.spawners().ofEntity(e.getEntity());
        if (s == null) {
            return;
        }
        Player p = e instanceof EntityDamageByEntityEvent ? attacker((EntityDamageByEntityEvent) e) : null;
        if (p == null) {
            if (!plugin.getConfig().getBoolean("general.mob-natural-damage", false)) {
                e.setCancelled(true);
            }
            return;
        }
        SpawnerType t = plugin.types().get(s.typeId);
        if (t == null) {
            e.setCancelled(true);
            return;
        }
        if (p.getGameMode() != GameMode.SURVIVAL) {
            e.setCancelled(true);
            plugin.messages().send(p, "general.survival-only");
            return;
        }
        if (!plugin.canAccess(p, s)) {
            e.setCancelled(true);
            plugin.messages().send(p, "access.denied");
            return;
        }
        if (!p.hasPermission("corespawners.kill")) {
            e.setCancelled(true);
            plugin.messages().send(p, "access.kill-denied", Text.vars("permission", "corespawners.kill"));
            return;
        }
        if (!t.killPermission.isEmpty() && !p.hasPermission(t.killPermission)) {
            e.setCancelled(true);
            plugin.messages().send(p, "access.kill-denied", Text.vars("permission", t.killPermission));
            return;
        }
        LivingEntity mob = (LivingEntity) e.getEntity();
        double damage = e.getFinalDamage();
        double before = mob.getHealth();
        boolean hud = plugin.getConfig().getBoolean("general.actionbar-hit", true);
        if (damage >= before) {
            // golpe fatal: em vez de matar de verdade, processa as mortes do stack
            e.setCancelled(true);
            long mobsBefore = s.mobs;
            plugin.spawners().kill(p, s, p.isSneaking());
            if (hud) {
                ActionBar.send(p, plugin.messages().text("actionbar.kill", Text.vars(
                        "killed", Amounts.number(mobsBefore - s.mobs), "damage", Amounts.number(damage),
                        "mobs", Amounts.number(s.mobs))));
            }
        } else if (hud) {
            ActionBar.send(p, plugin.messages().text("actionbar.hit", Text.vars(
                    "health", Amounts.number(before - damage), "max", Amounts.number(Compat.maxHealth(mob)),
                    "damage", Amounts.number(damage), "mobs", Amounts.number(s.mobs))));
        }
    }

    /** Se mesmo assim o mob morrer (ex.: /kill), não dropa nada e é recriado. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(EntityDeathEvent e) {
        final Spawner s = plugin.spawners().ofEntity(e.getEntity());
        if (s == null) {
            return;
        }
        e.getDrops().clear();
        e.setDroppedExp(0);
        plugin.spawners().entityGone(e.getEntity());
        Bukkit.getScheduler().runTask(plugin.getLoader(), new Runnable() {
            @Override
            public void run() {
                plugin.spawners().ensureEntity(s);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCombust(EntityCombustEvent e) {
        if (plugin.spawners().ofEntity(e.getEntity()) != null
                && !plugin.getConfig().getBoolean("general.mob-natural-damage", false)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (plugin.spawners().ofEntity(e.getRightClicked()) != null) {
            e.setCancelled(true);
        }
    }

    /** O bloco gerador não pode spawnar mobs vanilla: quem gera é o plugin. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER
                && plugin.spawners().nearManaged(e.getLocation())) {
            e.setCancelled(true);
        }
    }
}
