package br.com.coreplugins.spawners.listener;

import br.com.coreplugins.spawners.SpawnersProduct;
import br.com.coreplugins.spawners.manager.BoosterManager;
import br.com.coreplugins.spawners.manager.SpawnerManager;
import br.com.coreplugins.spawners.model.BoosterType;
import br.com.coreplugins.spawners.model.Spawner;
import br.com.coreplugins.spawners.model.SpawnerType;
import br.com.coreplugins.spawners.util.Amounts;
import br.com.coreplugins.spawners.util.Eggs;
import br.com.coreplugins.spawners.util.Items;
import br.com.coreplugins.spawners.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Colocar, quebrar, ovo, boosters, menu do botão direito e proteção do bloco do gerador. */
public final class BlockListener implements Listener {

    private final SpawnersProduct plugin;

    public BlockListener(SpawnersProduct plugin) {
        this.plugin = plugin;
    }

    @SuppressWarnings("deprecation")
    private ItemStack hand(Player p) {
        return p.getInventory().getItemInHand();
    }

    @SuppressWarnings("deprecation")
    private void setHand(Player p, ItemStack it) {
        p.getInventory().setItemInHand(it);
    }

    private boolean offHand(PlayerInteractEvent e) {
        try {
            return e.getHand() == org.bukkit.inventory.EquipmentSlot.OFF_HAND;
        } catch (Throwable t) {
            return false; // servidor antigo: só existe uma mão
        }
    }

    // ------------------------------------------------------------------ colocar

    /** O que a validação decidiu e a confirmação final vai executar. */
    private static final class Plan {
        final BlockPlaceEvent event;
        final SpawnerType type;
        final int stack;
        final int units;
        final boolean egg;
        final Spawner target;

        Plan(BlockPlaceEvent event, SpawnerType type, int stack, int units, boolean egg, Spawner target) {
            this.event = event;
            this.type = type;
            this.stack = stack;
            this.units = units;
            this.egg = egg;
            this.target = target;
        }
    }

    private final Map<UUID, Plan> plans = new HashMap<UUID, Plan>();

    private boolean protectionOn() {
        return plugin.getConfig().getBoolean("protection.enabled", true);
    }

    /**
     * 1ª fase (prioridade alta): só valida. As permissões valem para qualquer jogador, inclusive o dono.
     * Com {@code protection.enabled} ligado nada é criado aqui: a criação espera a 2ª fase, depois que os plugins
     * de terreno/ilha (WorldGuard, PlotSquared, GriefPrevention, Towny, SuperiorSkyblock, BentoBox...) já deram o veredito.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Plan plan = prepare(e);
        if (plan == null) {
            return;
        }
        if (protectionOn()) {
            plans.put(e.getPlayer().getUniqueId(), plan);
        } else {
            commit(plan);
        }
    }

    /** 2ª fase: o evento chega aqui só se ninguém o cancelou; confere se o jogador pode construir ali e então cria. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlaceFinal(BlockPlaceEvent e) {
        Plan plan = plans.remove(e.getPlayer().getUniqueId());
        if (plan == null || plan.event != e) {
            return;
        }
        if (!e.canBuild()) {
            plugin.messages().send(e.getPlayer(), "block.protected");
            return; // o servidor cancela a colocação: não há bloco nem gerador
        }
        commit(plan);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plans.remove(e.getPlayer().getUniqueId());
    }

    private Plan prepare(BlockPlaceEvent e) {
        String[] mark = Items.readMark(plugin.getLoader(), e.getItemInHand());
        if (mark == null) {
            return null;
        }
        Player p = e.getPlayer();
        SpawnerType t = plugin.types().get(mark[0]);
        if (t == null) {
            e.setCancelled(true);
            p.sendMessage(Text.color("&cEste gerador é de um tipo que não existe mais (" + mark[0] + ")."));
            return null;
        }
        int stack;
        try {
            stack = Math.max(1, Integer.parseInt(mark[1]));
        } catch (NumberFormatException ex) {
            stack = 1;
        }
        // Shift: todo o pack da mão vira um gerador só (soma o stack de cada item). Sem shift: 1 item por vez.
        final int units = (p.isSneaking() && p.hasPermission("corespawners.pack")
                && plugin.getConfig().getBoolean("general.shift-place-all", true))
                ? Math.max(1, e.getItemInHand().getAmount()) : 1;
        final long total = (long) stack * units;
        stack = (int) Math.min(Integer.MAX_VALUE, total);
        final boolean egg = "1".equals(mark[2]) || !plugin.eggRequired();
        Location loc = e.getBlockPlaced().getLocation();
        if (plugin.getConfig().getStringList("general.world-blacklist").contains(loc.getWorld().getName())) {
            e.setCancelled(true);
            plugin.messages().send(p, "block.world-blocked");
            return null;
        }
        if (plugin.getConfig().getBoolean("general.survival-only", true) && p.getGameMode() != GameMode.SURVIVAL) {
            e.setCancelled(true);
            plugin.messages().send(p, "general.survival-only");
            return null;
        }
        if (!plugin.perm(p, "place")) {
            e.setCancelled(true);
            return null;
        }
        if (!t.permission.isEmpty() && !p.hasPermission(t.permission)) {
            e.setCancelled(true);
            plugin.messages().send(p, "block.type-permission", Text.vars("permission", t.permission));
            return null;
        }
        Spawner target = plugin.spawners().findMergeTarget(p.getUniqueId(), t.id, loc,
                plugin.getConfig().getInt("general.merge-radius", 5));
        if (target != null && target.egg != egg) {
            target = null; // um com ovo e outro sem: não soma
        }
        if (target != null) {
            if (t.stackMax > 0 && (long) target.stack + stack > t.stackMax) {
                e.setCancelled(true);
                plugin.messages().send(p, "block.stack-max", Text.vars("max", String.valueOf(t.stackMax)));
                return null;
            }
        } else if (t.stackMax > 0 && stack > t.stackMax) {
            e.setCancelled(true);
            plugin.messages().send(p, "block.stack-max", Text.vars("max", String.valueOf(t.stackMax)));
            return null;
        }
        long limit = plugin.limits().limitOf(p);
        if (limit > 0L) {
            long used = plugin.limits().used(p.getUniqueId());
            long adding = plugin.limits().countStacks() ? stack : (target != null ? 0L : 1L);
            if (used + adding > limit) {
                e.setCancelled(true);
                plugin.messages().send(p, "block.limit", Text.vars("used", Amounts.number(used),
                        "limit", Amounts.number(limit), "left", Amounts.number(Math.max(0L, limit - used)),
                        "amount", Amounts.number(adding)));
                return null;
            }
        }
        return new Plan(e, t, stack, units, egg, target);
    }

    /** Cria o gerador (ou soma ao stack do que já existe). */
    private void commit(Plan plan) {
        final BlockPlaceEvent e = plan.event;
        final Player p = e.getPlayer();
        final SpawnerType t = plan.type;
        final int stack = plan.stack;
        final int units = plan.units;
        final Location loc = e.getBlockPlaced().getLocation();
        Spawner target = plan.target;
        if (target != null && plugin.spawners().get(target.id) != target) {
            target = null; // foi removido entre as duas fases
        }
        if (target != null) {
            e.setCancelled(true);
            target.stack += stack;
            target.dirty = true;
            consume(p, units);
            plugin.holograms().update(target);
            plugin.messages().send(p, "block.merged", Text.vars("amount", String.valueOf(stack),
                    "stack", Amounts.number(target.stack)));
            return;
        }
        final Spawner s = plugin.spawners().create(t, p, loc, stack, plan.egg);
        s.facing = (SpawnerManager.sideOf(p.getLocation().getYaw()) + 2) & 3; // lado oposto ao que o jogador olha = onde ele estava
        if (units > 1) {
            // o servidor já tira 1 item deste evento; tiramos o resto do pack 1 tick depois
            final int rest = units - 1;
            Bukkit.getScheduler().runTask(plugin.getLoader(), new Runnable() {
                @Override
                public void run() {
                    consume(p, rest);
                }
            });
        }
        // 1 tick depois: o servidor ainda aplica os dados do item ao bloco logo após este evento
        Bukkit.getScheduler().runTask(plugin.getLoader(), new Runnable() {
            @Override
            public void run() {
                plugin.spawners().applyVisual(s);
                plugin.holograms().update(s);
            }
        });
        plugin.messages().send(p, "block.placed", Text.vars("amount", String.valueOf(stack), "type", Text.color(t.display)));
        if (!s.egg) {
            plugin.messages().send(p, "egg.needed", Text.vars("entity", Text.color(t.entityName)));
        }
    }

    private void consume(Player p, int n) {
        if (p.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        ItemStack h = hand(p);
        if (h == null) {
            return;
        }
        if (h.getAmount() <= n) {
            setHand(p, null);
        } else {
            h.setAmount(h.getAmount() - n);
            setHand(p, h);
        }
    }

    private void consumeOne(Player p) {
        if (p.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        ItemStack h = hand(p);
        if (h == null) {
            return;
        }
        if (h.getAmount() <= 1) {
            setHand(p, null);
        } else {
            h.setAmount(h.getAmount() - 1);
            setHand(p, h);
        }
    }

    // ------------------------------------------------------------------ quebrar

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Spawner s = plugin.spawners().at(e.getBlock().getLocation());
        if (s == null) {
            return;
        }
        // O plugin cuida de tudo (item, bloco e dados): o drop padrão do Bukkit nunca acontece.
        e.setCancelled(true);
        Player p = e.getPlayer();
        SpawnerType t = plugin.types().get(s.typeId);
        if (t == null) {
            return;
        }
        if (!plugin.canManage(p, s)) {
            plugin.messages().send(p, "block.not-owner");
            return;
        }
        if (!plugin.perm(p, "break")) {
            return;
        }
        if (t.silkTouch) {
            ItemStack tool = hand(p);
            if (tool == null || tool.getEnchantmentLevel(Enchantment.SILK_TOUCH) < 1) {
                plugin.messages().send(p, "block.silk-touch");
                return;
            }
        }
        boolean all = p.isSneaking() || s.stack <= 1;
        int amount = all ? s.stack : 1;
        if (all && s.totalDrops() > 0) {
            plugin.messages().send(p, "block.storage-not-empty");
            return;
        }
        ItemStack item = plugin.createItem(t, amount, 1, s.egg);
        for (ItemStack left : p.getInventory().addItem(item).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), left);
        }
        if (all) {
            if (plugin.getConfig().getBoolean("general.close-menu-on-break", true)) {
                plugin.menus().closeFor(s.id);
            }
            plugin.spawners().remove(s);
            e.getBlock().setType(Material.AIR);
        } else {
            s.stack -= 1;
            s.dirty = true;
            plugin.holograms().update(s);
        }
        plugin.messages().send(p, "block.broken", Text.vars("amount", String.valueOf(amount), "type", Text.color(t.display)));
    }

    // ------------------------------------------------------------------ botão direito (menu, ovo, booster)

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() == Action.RIGHT_CLICK_AIR) {
            compactPack(e);
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) {
            return;
        }
        Spawner s = plugin.spawners().at(e.getClickedBlock().getLocation());
        if (s == null) {
            return;
        }
        if (offHand(e)) {
            e.setCancelled(true); // o mesmo clique chega duas vezes (mão principal e secundária)
            return;
        }
        if (e.useInteractedBlock() == Event.Result.DENY) {
            return;
        }
        Player p = e.getPlayer();
        ItemStack h = hand(p);
        boolean holdingSomething = h != null && h.getType() != Material.AIR;
        BoosterType booster = holdingSomething ? plugin.boosters().fromItem(h) : null;
        boolean egg = holdingSomething && Eggs.isEgg(h);

        if (!booster_or_egg(booster, egg) && p.isSneaking() && holdingSomething) {
            return; // agachado com um bloco na mão: deixa colocar
        }
        e.setCancelled(true);
        if (!plugin.canAccess(p, s)) {
            plugin.messages().send(p, "access.denied");
            return;
        }
        if (booster != null) {
            if (plugin.perm(p, "booster")) {
                useBooster(p, s, booster);
            }
        } else if (egg) {
            if (plugin.perm(p, "egg")) {
                useEgg(p, s, h);
            }
        } else if (plugin.perm(p, "use")) {
            plugin.menus().openMain(p, s);
        }
    }

    /**
     * Shift + botão direito no ar com um gerador na mão: junta TODOS os geradores iguais (mesmo tipo e mesmo estado
     * de ovo) do inventário em 1 item só, com o stack somado.
     */
    private void compactPack(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!p.isSneaking() || offHand(e) || !plugin.getConfig().getBoolean("general.shift-place-all", true)) {
            return;
        }
        if (!p.hasPermission("corespawners.pack")) {
            return;
        }
        ItemStack h = hand(p);
        if (h == null || h.getType() == Material.AIR) {
            return;
        }
        String[] mark = Items.readMark(plugin.getLoader(), h);
        if (mark == null) {
            return;
        }
        SpawnerType t = plugin.types().get(mark[0]);
        if (t == null) {
            return;
        }
        e.setCancelled(true);
        boolean hasEgg = "1".equals(mark[2]) || !plugin.eggRequired();
        org.bukkit.inventory.PlayerInventory inv = p.getInventory();
        int held = inv.getHeldItemSlot();
        int size = Math.min(inv.getSize(), 36); // só mochila e barra; armadura e mão secundária ficam de fora

        long total = 0L;
        int items = 0;
        java.util.List<Integer> slots = new java.util.ArrayList<Integer>();
        for (int i = 0; i < size; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() == Material.AIR) {
                continue;
            }
            String[] m = Items.readMark(plugin.getLoader(), it);
            if (m == null || !m[0].equals(t.id) || hasEgg != ("1".equals(m[2]) || !plugin.eggRequired())) {
                continue;
            }
            int each;
            try {
                each = Math.max(1, Integer.parseInt(m[1]));
            } catch (NumberFormatException ex) {
                each = 1;
            }
            total += (long) each * it.getAmount();
            items += it.getAmount();
            slots.add(i);
        }
        if (items < 2) {
            return; // nada para juntar
        }
        for (int i : slots) {
            inv.setItem(i, null);
        }
        long made = t.stackMax > 0 ? Math.min(total, t.stackMax) : total;
        made = Math.min(made, Integer.MAX_VALUE);
        inv.setItem(held, plugin.createItem(t, (int) made, 1, hasEgg));
        long rest = total - made;
        while (rest > 0) {
            long chunk = t.stackMax > 0 ? Math.min(rest, t.stackMax) : rest;
            chunk = Math.min(chunk, Integer.MAX_VALUE);
            for (ItemStack left : inv.addItem(plugin.createItem(t, (int) chunk, 1, hasEgg)).values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), left);
            }
            rest -= chunk;
        }
        p.updateInventory();
        plugin.messages().send(p, "block.packed", Text.vars("stack", Amounts.number(made)));
    }

    private static boolean booster_or_egg(BoosterType booster, boolean egg) {
        return booster != null || egg;
    }

    private void useBooster(Player p, Spawner s, BoosterType b) {
        if (plugin.boosters().apply(s, b) == BoosterManager.Result.ALREADY_ACTIVE) {
            plugin.messages().send(p, "booster.already", Text.vars("kind", plugin.messages().text("booster.kinds." + b.kind.name())));
            return;
        }
        consumeOne(p);
        plugin.holograms().update(s);
        plugin.messages().send(p, "booster.applied", Text.vars("booster", Text.color(b.display),
                "multiplier", BoosterManager.trim(b.multiplier), "time", Text.duration(b.durationSeconds)));
    }

    private void useEgg(Player p, Spawner s, ItemStack hand) {
        SpawnerType t = plugin.types().get(s.typeId);
        if (t == null) {
            return;
        }
        if (!plugin.eggRequired() || s.egg) {
            plugin.messages().send(p, "egg.not-needed");
            return;
        }
        if (!Eggs.matches(hand, t.entity)) {
            plugin.messages().send(p, "egg.wrong", Text.vars("entity", Text.color(t.entityName)));
            return;
        }
        consumeOne(p);
        s.egg = true;
        s.dirty = true;
        plugin.spawners().applyVisual(s);
        plugin.holograms().update(s);
        plugin.messages().send(p, "egg.applied", Text.vars("entity", Text.color(t.entityName)));
    }

    // ------------------------------------------------------------------ proteção

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        List<Block> blocks = e.blockList();
        Iterator<Block> it = blocks.iterator();
        while (it.hasNext()) {
            if (plugin.spawners().at(it.next().getLocation()) != null) {
                it.remove();
            }
        }
    }
}
