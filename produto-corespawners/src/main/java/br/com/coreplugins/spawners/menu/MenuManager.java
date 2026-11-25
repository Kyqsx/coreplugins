package br.com.coreplugins.spawners.menu;

import br.com.coreplugins.spawners.Defaults;
import br.com.coreplugins.spawners.SpawnersProduct;
import br.com.coreplugins.spawners.manager.BoosterManager;
import br.com.coreplugins.spawners.model.BoosterType;
import br.com.coreplugins.spawners.economy.EconomyProvider;
import br.com.coreplugins.spawners.model.DropType;
import br.com.coreplugins.spawners.model.Spawner;
import br.com.coreplugins.spawners.model.SpawnerType;
import br.com.coreplugins.spawners.model.UpgradeConfig;
import br.com.coreplugins.spawners.service.UpgradeService;
import br.com.coreplugins.spawners.util.Amounts;
import br.com.coreplugins.spawners.util.Items;
import br.com.coreplugins.spawners.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Menus do gerador (principal, drops, upgrades, amigos), montados a partir do menus.yml. */
public final class MenuManager implements Listener {

    private interface Click {
        void run(Player p, ClickType type);
    }

    private static final class Holder implements InventoryHolder {
        final String kind;
        final long spawnerId;
        final int page;
        final Map<Integer, Click> actions = new HashMap<Integer, Click>();
        Inventory inv;

        Holder(String kind, long spawnerId, int page) {
            this.kind = kind;
            this.spawnerId = spawnerId;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final SpawnersProduct plugin;
    private YamlConfiguration cfg;
    private YamlConfiguration defaults;
    private final Map<UUID, Long> prompts = new ConcurrentHashMap<UUID, Long>();

    public MenuManager(SpawnersProduct plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        cfg = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "menus.yml"));
        defaults = Defaults.load(plugin, "menus.yml");
    }

    // ------------------------------------------------------------------ utilidades

    /** Seção do menus.yml do servidor; se faltar (arquivo de uma versão antiga), usa a padrão do jar. */
    private ConfigurationSection sec(String path) {
        ConfigurationSection s = cfg.getConfigurationSection(path);
        if ((s == null || s.getKeys(false).isEmpty()) && defaults != null) {
            return defaults.getConfigurationSection(path);
        }
        return s;
    }

    private ConfigurationSection child(ConfigurationSection parent, String key) {
        if (parent == null) {
            return null;
        }
        ConfigurationSection c = parent.getConfigurationSection(key);
        if ((c == null || c.getKeys(false).isEmpty()) && defaults != null) {
            return defaults.getConfigurationSection(parent.getCurrentPath() + "." + key);
        }
        return c;
    }

    private Holder create(String kind, Spawner s, int page, String menuPath, Map<String, String> vars) {
        ConfigurationSection m = sec(menuPath);
        int size = m == null ? 27 : m.getInt("size", 27);
        size = Math.max(9, Math.min(54, (size / 9) * 9));
        String title = Text.render(m == null ? "&8Gerador" : m.getString("title", "&8Gerador"), vars);
        if (title.length() > 32) {
            title = title.substring(0, 32);
        }
        Holder h = new Holder(kind, s.id, page);
        h.inv = Bukkit.createInventory(h, size, title);
        return h;
    }

    private void put(Holder h, ConfigurationSection parent, String key, Map<String, String> vars, Click action) {
        ConfigurationSection item = child(parent, key);
        if (item == null) {
            return;
        }
        int slot = item.getInt("slot", -1);
        place(h, slot, Items.build(item, vars), action);
    }

    private void place(Holder h, int slot, ItemStack item, Click action) {
        if (slot < 0 || slot >= h.inv.getSize()) {
            return;
        }
        h.inv.setItem(slot, item);
        if (action != null) {
            h.actions.put(slot, action);
        }
    }

    /** Item com o slot em uma chave separada (menus de lista: back-slot, add-slot...). */
    private void putAt(Holder h, ConfigurationSection menu, String slotKey, String itemKey, Map<String, String> vars, Click action) {
        if (menu == null) {
            return;
        }
        place(h, menu.getInt(slotKey, defaultSlot(menu, slotKey)), Items.build(child(menu, itemKey), vars), action);
    }

    /** Slot padrão do jar quando o menus.yml do servidor não tem a chave (arquivo de versão antiga). */
    private int defaultSlot(ConfigurationSection menu, String slotKey) {
        if (defaults == null || menu == null) {
            return -1;
        }
        return defaults.getInt(menu.getCurrentPath() + "." + slotKey, -1);
    }

    private Click backToMain(final Spawner s) {
        return new Click() {
            @Override
            public void run(Player p, ClickType type) {
                openMain(p, s);
            }
        };
    }

    /** Reabre o menu em que o jogador está (depois de uma ação), atualizando os valores. */
    public void refresh(Player p) {
        Inventory top = p.getOpenInventory().getTopInventory();
        if (top == null || !(top.getHolder() instanceof Holder)) {
            return;
        }
        Holder h = (Holder) top.getHolder();
        Spawner s = plugin.spawners().get(h.spawnerId);
        if (s == null) {
            p.closeInventory();
            return;
        }
        if (h.kind.equals("drops")) {
            openDrops(p, s);
        } else if (h.kind.equals("upgrades")) {
            openUpgrades(p, s);
        } else if (h.kind.equals("friends")) {
            openFriends(p, s, h.page);
        } else if (h.kind.equals("boosters")) {
            openBoosters(p, s);
        } else {
            openMain(p, s);
        }
    }

    /** Fecha o menu de todos que estão com este gerador aberto (gerador removido). */
    public void closeFor(long spawnerId) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Inventory top = p.getOpenInventory().getTopInventory();
            if (top != null && top.getHolder() instanceof Holder && ((Holder) top.getHolder()).spawnerId == spawnerId) {
                p.closeInventory();
            }
        }
    }

    // ------------------------------------------------------------------ menu principal

    public void openMain(final Player p, final Spawner s) {
        final SpawnerType t = plugin.types().get(s.typeId);
        if (t == null) {
            return;
        }
        Map<String, String> vars = plugin.spawners().vars(s);
        ConfigurationSection m = sec("main");
        Holder h = create("main", s, 0, "main", vars);
        put(h, m, "info", vars, null);
        if (plugin.dropsEnabled(t)) {
            put(h, m, "drops", vars, new Click() {
                @Override
                public void run(Player pl, ClickType type) {
                    openDrops(pl, s);
                }
            });
        }
        put(h, m, "boosters", vars, new Click() {
            @Override
            public void run(Player pl, ClickType type) {
                openBoosters(pl, s);
            }
        });
        if (plugin.canManage(p, s)) {
            put(h, m, "upgrades", vars, new Click() {
                @Override
                public void run(Player pl, ClickType type) {
                    openUpgrades(pl, s);
                }
            });
            put(h, m, "friends", vars, new Click() {
                @Override
                public void run(Player pl, ClickType type) {
                    openFriends(pl, s, 0);
                }
            });
            put(h, m, "status", vars, new Click() {
                @Override
                public void run(Player pl, ClickType type) {
                    if (!plugin.perm(pl, "toggle")) {
                        return;
                    }
                    s.enabled = !s.enabled;
                    s.dirty = true;
                    plugin.messages().send(pl, "toggle.status", Text.vars("state", plugin.messages().on(s.enabled)));
                    refresh(pl);
                }
            });
            put(h, m, "public", vars, new Click() {
                @Override
                public void run(Player pl, ClickType type) {
                    if (!plugin.perm(pl, "toggle")) {
                        return;
                    }
                    s.isPublic = !s.isPublic;
                    s.dirty = true;
                    plugin.messages().send(pl, "toggle.public", Text.vars("state", plugin.messages().on(s.isPublic)));
                    refresh(pl);
                }
            });
            put(h, m, "hologram", vars, new Click() {
                @Override
                public void run(Player pl, ClickType type) {
                    if (!plugin.perm(pl, "toggle")) {
                        return;
                    }
                    s.hologram = !s.hologram;
                    s.dirty = true;
                    plugin.holograms().update(s);
                    plugin.messages().send(pl, "toggle.hologram", Text.vars("state", plugin.messages().on(s.hologram)));
                    refresh(pl);
                }
            });
            if (t.autoSellAllowed) {
                put(h, m, "autosell", vars, new Click() {
                    @Override
                    public void run(Player pl, ClickType type) {
                        String perm = plugin.getConfig().getString("general.autosell-permission", "");
                        if (!pl.hasPermission("corespawners.autosell") || (!perm.isEmpty() && !pl.hasPermission(perm))) {
                            plugin.messages().send(pl, "toggle.autosell-no-permission");
                            return;
                        }
                        s.autosell = !s.autosell;
                        s.dirty = true;
                        plugin.messages().send(pl, "toggle.autosell", Text.vars("state", plugin.messages().on(s.autosell)));
                        refresh(pl);
                    }
                });
            }
        }
        p.openInventory(h.inv);
    }

    // ------------------------------------------------------------------ drops

    public void openDrops(final Player p, final Spawner s) {
        final SpawnerType t = plugin.types().get(s.typeId);
        if (t == null) {
            return;
        }
        if (!plugin.dropsEnabled(t)) {
            plugin.messages().send(p, "drops.disabled");
            return;
        }
        Map<String, String> vars = plugin.spawners().vars(s);
        ConfigurationSection m = sec("drops");
        Holder h = create("drops", s, 0, "drops", vars);
        List<Integer> slots = m == null ? new ArrayList<Integer>() : m.getIntegerList("slots");
        int i = 0;
        for (final DropType d : t.drops().values()) {
            if (i >= slots.size()) {
                break;
            }
            long stored = s.dropAmount(d.key);
            Map<String, String> dv = new HashMap<String, String>(vars);
            dv.put("drop", Text.color(d.name));
            dv.put("stored", Amounts.number(stored));
            dv.put("unit_value", plugin.drops().unitValue(d, 1D));
            dv.put("total_value", plugin.drops().unitValue(d, stored));
            ItemStack icon = Items.parse(d.materialSpec, 1);
            ConfigurationSection tpl = child(m, "item");
            Items.decorate(icon, tpl == null ? "{drop}" : tpl.getString("name", "{drop}"),
                    tpl == null ? null : tpl.getStringList("lore"), dv, false);
            place(h, slots.get(i), icon, new Click() {
                @Override
                public void run(Player pl, ClickType type) {
                    if (type.isRightClick()) {
                        plugin.drops().collect(pl, s, d);
                    } else {
                        plugin.drops().sellFromStorage(pl, s, d);
                    }
                    refresh(pl);
                }
            });
            i++;
        }
        putAt(h, m, "sell-all-slot", "sell-all", vars, new Click() {
            @Override
            public void run(Player pl, ClickType type) {
                plugin.drops().sellFromStorage(pl, s, null);
                refresh(pl);
            }
        });
        putAt(h, m, "back-slot", "back", vars, backToMain(s));
        p.openInventory(h.inv);
    }

    // ------------------------------------------------------------------ upgrades

    public void openUpgrades(final Player p, final Spawner s) {
        final SpawnerType t = plugin.types().get(s.typeId);
        if (t == null) {
            return;
        }
        if (!plugin.canManage(p, s)) {
            plugin.messages().send(p, "access.only-owner");
            return;
        }
        Map<String, String> vars = plugin.spawners().vars(s);
        ConfigurationSection m = sec("upgrades");
        Holder h = create("upgrades", s, 0, "upgrades", vars);
        upgradeItem(h, m, "drops", "drops-slot", UpgradeService.Kind.DROPS, s, t, vars);
        upgradeItem(h, m, "speed", "speed-slot", UpgradeService.Kind.SPEED, s, t, vars);
        putAt(h, m, "back-slot", "back", vars, backToMain(s));
        p.openInventory(h.inv);
    }

    private void upgradeItem(Holder h, ConfigurationSection m, String key, String slotKey,
                             final UpgradeService.Kind kind, final Spawner s, SpawnerType t, Map<String, String> vars) {
        if (m == null) {
            return;
        }
        UpgradeConfig uc = plugin.upgrades().config(t, kind);
        if (!uc.enabled()) {
            return;
        }
        int level = plugin.upgrades().level(s, kind);
        boolean max = level >= uc.maxLevel;
        Map<String, String> v = new HashMap<String, String>(vars);
        v.put("level", String.valueOf(level));
        v.put("max", String.valueOf(uc.maxLevel));
        if (kind == UpgradeService.Kind.DROPS) {
            v.put("value", String.valueOf(plugin.spawners().dropsPerMob(s, t)));
            v.put("next", String.valueOf(Math.max(1L, (long) (1D + (level + 1) * uc.perLevel))));
        } else {
            v.put("value", String.valueOf(plugin.spawners().interval(s, t)));
            v.put("next", String.valueOf((int) Math.max(1D, Math.max(uc.minSeconds, t.intervalSeconds - (level + 1) * uc.perLevel))));
        }
        EconomyProvider ep = plugin.economy().get(uc.currency);
        v.put("price", ep == null ? "?" : ep.format(uc.priceFor(level)));
        ConfigurationSection item = child(m, max ? key + "-max" : key);
        if (item == null) {
            return;
        }
        place(h, m.getInt(slotKey, defaultSlot(m, slotKey)), Items.build(item, v), max ? null : new Click() {
            @Override
            public void run(Player pl, ClickType type) {
                plugin.upgrades().buy(pl, s, kind, new Runnable() {
                    @Override
                    public void run() {
                        refresh(pl);
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ boosters

    public void openBoosters(final Player p, final Spawner s) {
        Map<String, String> vars = plugin.spawners().vars(s);
        ConfigurationSection m = sec("boosters");
        Holder h = create("boosters", s, 0, "boosters", vars);
        for (BoosterType.Kind k : BoosterType.Kind.values()) {
            BoosterType b = plugin.boosters().active(s, k);
            Map<String, String> v = new HashMap<String, String>(vars);
            v.put("state", plugin.messages().text(b != null ? "booster.state-active" : "booster.state-inactive"));
            v.put("booster", b == null ? "-" : Text.color(b.display));
            v.put("multiplier", b == null ? "-" : BoosterManager.trim(b.multiplier));
            v.put("time", b == null ? "-" : Text.duration(plugin.boosters().remainingSeconds(s, k)));
            ConfigurationSection item = child(m, k.name().toLowerCase(java.util.Locale.ROOT));
            if (item != null) {
                place(h, item.getInt("slot", -1), Items.build(item, v), null);
            }
        }
        putAt(h, m, "back-slot", "back", vars, backToMain(s));
        p.openInventory(h.inv);
    }

    // ------------------------------------------------------------------ amigos

    public void openFriends(final Player p, final Spawner s, int page) {
        if (!plugin.canManage(p, s)) {
            plugin.messages().send(p, "access.only-owner");
            return;
        }
        if (!plugin.perm(p, "friends")) {
            return;
        }
        Map<String, String> vars = plugin.spawners().vars(s);
        ConfigurationSection m = sec("friends");
        List<Integer> slots = m == null ? new ArrayList<Integer>() : m.getIntegerList("slots");
        int per = Math.max(1, slots.size());
        List<Map.Entry<UUID, String>> all = new ArrayList<Map.Entry<UUID, String>>(s.friends.entrySet());
        int pages = Math.max(1, (all.size() + per - 1) / per);
        final int cur = Math.max(0, Math.min(page, pages - 1));
        Holder h = create("friends", s, cur, "friends", vars);
        for (int i = 0; i < per && cur * per + i < all.size(); i++) {
            final Map.Entry<UUID, String> f = all.get(cur * per + i);
            Map<String, String> fv = new HashMap<String, String>(vars);
            fv.put("friend", f.getValue());
            ConfigurationSection fs = child(m, "friend");
            place(h, slots.get(i), Items.build(fs, fv), new Click() {
                @Override
                public void run(Player pl, ClickType type) {
                    if (type.isRightClick() && s.friends.remove(f.getKey()) != null) {
                        s.dirty = true;
                        plugin.messages().send(pl, "friends.removed", Text.vars("player", f.getValue()));
                        openFriends(pl, s, cur);
                    }
                }
            });
        }
        putAt(h, m, "add-slot", "add", vars, new Click() {
            @Override
            public void run(Player pl, ClickType type) {
                prompts.put(pl.getUniqueId(), s.id);
                pl.closeInventory();
                plugin.messages().send(pl, "friends.prompt");
            }
        });
        if (cur > 0) {
            putAt(h, m, "previous-slot", "previous", vars, new Click() {
                @Override
                public void run(Player pl, ClickType type) {
                    openFriends(pl, s, cur - 1);
                }
            });
        }
        if (cur < pages - 1) {
            putAt(h, m, "next-slot", "next", vars, new Click() {
                @Override
                public void run(Player pl, ClickType type) {
                    openFriends(pl, s, cur + 1);
                }
            });
        }
        putAt(h, m, "back-slot", "back", vars, backToMain(s));
        p.openInventory(h.inv);
    }

    // ------------------------------------------------------------------ eventos

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top == null || !(top.getHolder() instanceof Holder)) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player) || e.getRawSlot() < 0 || e.getRawSlot() >= top.getSize()) {
            return;
        }
        Holder h = (Holder) top.getHolder();
        Player p = (Player) e.getWhoClicked();
        Click c = h.actions.get(e.getRawSlot());
        if (c == null) {
            return;
        }
        Spawner s = plugin.spawners().get(h.spawnerId);
        if (s == null || !plugin.canAccess(p, s)) {
            p.closeInventory();
            return;
        }
        // A permissão vale também para o dono: se ela foi retirada com o menu aberto, o clique não faz nada.
        if (!plugin.perm(p, "use")) {
            p.closeInventory();
            return;
        }
        c.run(p, e.getClick());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top != null && top.getHolder() instanceof Holder) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        final Long id = prompts.remove(e.getPlayer().getUniqueId());
        if (id == null) {
            return;
        }
        e.setCancelled(true);
        final Player p = e.getPlayer();
        final String text = e.getMessage().trim();
        if (text.equalsIgnoreCase("cancelar")) {
            Bukkit.getScheduler().runTask(plugin.getLoader(), new Runnable() {
                @Override
                public void run() {
                    plugin.messages().send(p, "general.cancelled");
                    Spawner s = plugin.spawners().get(id);
                    if (s != null) {
                        openFriends(p, s, 0);
                    }
                }
            });
            return;
        }
        // Bukkit.getOfflinePlayer(nome) pode consultar a Mojang e demorar: resolve fora da thread principal.
        final OfflinePlayer[] target = new OfflinePlayer[1];
        target[0] = Bukkit.getOfflinePlayer(text);
        Bukkit.getScheduler().runTask(plugin.getLoader(), new Runnable() {
            @Override
            public void run() {
                addFriend(p, id, text, target[0]);
            }
        });
    }

    private void addFriend(Player p, long id, String typed, OfflinePlayer target) {
        Spawner s = plugin.spawners().get(id);
        if (s == null || !p.isOnline() || !plugin.canManage(p, s)) {
            return;
        }
        if (target == null || (!target.isOnline() && !target.hasPlayedBefore())) {
            plugin.messages().send(p, "friends.not-found");
        } else if (target.getUniqueId().equals(s.owner)) {
            plugin.messages().send(p, "friends.is-owner");
        } else if (s.friends.containsKey(target.getUniqueId())) {
            plugin.messages().send(p, "friends.exists");
        } else {
            int max = plugin.getConfig().getInt("general.friend-max", 0);
            if (max > 0 && s.friends.size() >= max) {
                plugin.messages().send(p, "friends.max", Text.vars("max", String.valueOf(max)));
            } else {
                String name = target.getName() == null ? typed : target.getName();
                s.friends.put(target.getUniqueId(), name);
                s.dirty = true;
                plugin.messages().send(p, "friends.added", Text.vars("player", name));
            }
        }
        openFriends(p, s, 0);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        prompts.remove(e.getPlayer().getUniqueId());
    }
}
