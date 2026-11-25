package br.com.coreplugins.spawners.command;

import br.com.coreplugins.spawners.SpawnersProduct;
import br.com.coreplugins.spawners.economy.EconomyProvider;
import br.com.coreplugins.spawners.manager.StatsManager;
import br.com.coreplugins.spawners.model.BoosterType;
import br.com.coreplugins.spawners.model.SpawnerType;
import br.com.coreplugins.spawners.util.Amounts;
import br.com.coreplugins.spawners.util.RecentErrors;
import br.com.coreplugins.spawners.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /spawners give | booster | list | economies | reload | top | debug | help */
public final class SpawnersCommand implements CommandExecutor, TabCompleter {

    private final SpawnersProduct plugin;

    public SpawnersCommand(SpawnersProduct plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        // Ranking e debug têm permissão própria; o resto é só da administração.
        if (sub.equals("top")) {
            if (!sender.hasPermission("corespawners.top")) {
                plugin.messages().send(sender, "general.no-permission", Text.vars("permission", "corespawners.top"));
                return true;
            }
            top(sender, args);
            return true;
        }
        if (sub.equals("debug")) {
            if (!sender.hasPermission("corespawners.debug")) {
                plugin.messages().send(sender, "general.no-permission", Text.vars("permission", "corespawners.debug"));
                return true;
            }
            debug(sender);
            return true;
        }
        if (!sender.hasPermission("corespawners.admin")) {
            if (sub.equals("help") && sender.hasPermission("corespawners.top")) {
                plugin.messages().send(sender, "general.help-player");
            } else {
                plugin.messages().send(sender, "general.no-permission", Text.vars("permission", "corespawners.admin"));
            }
            return true;
        }
        switch (sub) {
            case "give":
                give(sender, args);
                break;
            case "booster":
                booster(sender, args);
                break;
            case "list":
                plugin.messages().send(sender, "give.list", Text.vars("list", String.join(", ", plugin.types().ids())));
                break;
            case "economies":
            case "economias":
                economies(sender);
                break;
            case "reload":
                plugin.reloadAll();
                plugin.messages().send(sender, "general.reloaded", Text.vars(
                        "types", String.valueOf(plugin.types().all().size()),
                        "economies", String.valueOf(plugin.economy().all().size())));
                break;
            default:
                plugin.messages().send(sender, "general.help");
                break;
        }
        return true;
    }

    // ------------------------------------------------------------------ top

    private void top(CommandSender sender, String[] args) {
        String cat = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : StatsManager.STACK;
        if (cat.equals("vendas")) {
            cat = StatsManager.SALES;
        } else if (cat.equals("mortos") || cat.equals("mobs")) {
            cat = StatsManager.KILLS;
        }
        if (!StatsManager.isCategory(cat)) {
            plugin.messages().send(sender, "top.usage");
            return;
        }
        StatsManager st = plugin.stats();
        plugin.messages().send(sender, "top.header", Text.vars("category", plugin.messages().text("top.categories." + cat)));
        java.util.List<StatsManager.Entry> list = st.top(cat);
        if (list.isEmpty()) {
            plugin.messages().send(sender, "top.empty");
        }
        int pos = 1;
        for (StatsManager.Entry e : list) {
            plugin.messages().send(sender, "top.line", Text.vars("pos", String.valueOf(pos++), "name", e.name,
                    "value", Amounts.letter(e.value)));
        }
        if (sender instanceof Player) {
            Player p = (Player) sender;
            int mine = st.position(cat, p.getUniqueId());
            if (mine > 0) {
                plugin.messages().send(sender, "top.you", Text.vars("pos", String.valueOf(mine),
                        "value", Amounts.letter(st.valueOf(cat, p.getUniqueId()))));
            }
        }
    }

    // ------------------------------------------------------------------ debug

    private void debug(CommandSender sender) {
        java.text.SimpleDateFormat day = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
        java.text.SimpleDateFormat hour = new java.text.SimpleDateFormat("HH:mm:ss");
        br.com.coreplugins.spawners.util.BuildInfo b = plugin.buildInfo();
        long now = System.currentTimeMillis();
        plugin.messages().send(sender, "debug.header");
        plugin.messages().send(sender, "debug.build", Text.vars("version", b.version, "built", b.built));
        plugin.messages().send(sender, "debug.jar", Text.vars("name", b.jarName, "sha", b.jarSha,
                "size", b.jarSize < 0 ? "?" : Amounts.number(b.jarSize / 1024D) + " KB",
                "modified", b.jarModified < 0 ? "?" : day.format(new java.util.Date(b.jarModified))));
        plugin.messages().send(sender, "debug.path", Text.vars("path", b.jarPath));
        plugin.messages().send(sender, "debug.server", Text.vars("server", Bukkit.getVersion(),
                "java", System.getProperty("java.version", "?")));
        plugin.messages().send(sender, "debug.uptime", Text.vars("time", Text.duration((now - plugin.startedAt()) / 1000L)));
        long[] g = plugin.spawners().globalTotals();
        plugin.messages().send(sender, "debug.spawners", Text.vars("blocks", Amounts.number(plugin.spawners().count()),
                "stack", Amounts.number(g[0]), "mobs", Amounts.number(g[1]),
                "types", String.valueOf(plugin.types().all().size())));
        plugin.messages().send(sender, "debug.database", Text.vars("type", plugin.database().describe(),
                "dirty", String.valueOf(plugin.spawners().dirtyCount()),
                "deleted", String.valueOf(plugin.spawners().deletedCount()),
                "stats", String.valueOf(plugin.stats().dirtyCount()),
                "queued", String.valueOf(plugin.dbQueued()), "active", String.valueOf(plugin.dbActive())));
        if (plugin.lastFlushAt() == 0L) {
            plugin.messages().send(sender, "debug.last-write-none");
        } else {
            String err = plugin.lastFlushError();
            plugin.messages().send(sender, err == null ? "debug.last-write" : "debug.last-write-error", Text.vars(
                    "ago", Text.duration((now - plugin.lastFlushAt()) / 1000L), "ms", String.valueOf(plugin.lastFlushMs()),
                    "items", String.valueOf(plugin.lastFlushItems()), "error", err == null ? "" : err));
        }
        EconomyProvider d = plugin.economy().defaultProvider();
        plugin.messages().send(sender, "debug.economy", Text.vars("count", String.valueOf(plugin.economy().all().size()),
                "default", d == null ? "-" : d.id()));
        plugin.messages().send(sender, "debug.hooks", Text.vars(
                "papi", plugin.messages().on(plugin.papiRegistered()),
                "protection", plugin.messages().on(plugin.getConfig().getBoolean("protection.enabled", true))));
        java.util.List<RecentErrors.Item> errors = plugin.recentErrors().recent();
        if (errors.isEmpty()) {
            plugin.messages().send(sender, "debug.no-errors");
            return;
        }
        plugin.messages().send(sender, "debug.errors-header", Text.vars("count", String.valueOf(errors.size())));
        int shown = 0;
        for (RecentErrors.Item it : errors) {
            if (shown++ >= 8) {
                break;
            }
            plugin.messages().send(sender, "debug.error-line", Text.vars(
                    "time", hour.format(new java.util.Date(it.time)), "level", it.level.getName(), "text", it.text));
        }
    }

    private void give(CommandSender sender, String[] args) {
        if (args.length < 3) {
            plugin.messages().send(sender, "general.help");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            plugin.messages().send(sender, "general.player-not-found", Text.vars("player", args[1]));
            return;
        }
        SpawnerType t = plugin.types().get(args[2]);
        if (t == null) {
            plugin.messages().send(sender, "give.type-not-found", Text.vars("list", String.join(", ", plugin.types().ids())));
            return;
        }
        int amount = parse(sender, args, 3, 1);
        int stack = parse(sender, args, 4, 1);
        if (amount < 1 || stack < 1) {
            return;
        }
        ItemStack item = plugin.createItem(t, stack, amount, false);
        for (ItemStack left : target.getInventory().addItem(item).values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), left);
        }
        plugin.messages().send(sender, "give.given", Text.vars("amount", String.valueOf(amount),
                "type", Text.color(t.display), "stack", String.valueOf(stack), "player", target.getName()));
        plugin.messages().send(target, "give.received", Text.vars("amount", String.valueOf(amount),
                "type", Text.color(t.display)));
    }

    private void booster(CommandSender sender, String[] args) {
        if (args.length < 3) {
            plugin.messages().send(sender, "general.help");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            plugin.messages().send(sender, "general.player-not-found", Text.vars("player", args[1]));
            return;
        }
        BoosterType b = plugin.boosters().get(args[2]);
        if (b == null) {
            plugin.messages().send(sender, "booster.not-found", Text.vars("list", String.join(", ", plugin.boosters().ids())));
            return;
        }
        int amount = parse(sender, args, 3, 1);
        if (amount < 1) {
            return;
        }
        for (ItemStack left : target.getInventory().addItem(plugin.boosters().createItem(b, amount)).values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), left);
        }
        plugin.messages().send(sender, "booster.given", Text.vars("amount", String.valueOf(amount),
                "booster", Text.color(b.display), "player", target.getName()));
        plugin.messages().send(target, "booster.received", Text.vars("amount", String.valueOf(amount),
                "booster", Text.color(b.display)));
    }

    private int parse(CommandSender sender, String[] args, int index, int def) {
        if (args.length <= index) {
            return def;
        }
        try {
            int v = Integer.parseInt(args[index]);
            if (v < 1 || v > 100000) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            plugin.messages().send(sender, "general.invalid-number", Text.vars("value", args[index]));
            return -1;
        }
    }

    private void economies(CommandSender sender) {
        plugin.economy().rescan();
        List<EconomyProvider> all = plugin.economy().all();
        plugin.messages().send(sender, "economies.header");
        if (all.isEmpty()) {
            plugin.messages().send(sender, "economies.none");
            return;
        }
        EconomyProvider d = plugin.economy().defaultProvider();
        if (d != null) {
            plugin.messages().send(sender, "economies.default", Text.vars("name", d.id()));
        }
        for (EconomyProvider p : all) {
            plugin.messages().send(sender, "economies.line", Text.vars("name", p.id(), "source", p.source(),
                    "state", plugin.messages().text(p.isAvailable() ? "economies.available" : "economies.unavailable")));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<String>();
        boolean admin = sender.hasPermission("corespawners.admin");
        if (args.length == 1) {
            for (String s : new String[]{"give", "booster", "list", "economies", "reload", "top", "debug", "help"}) {
                boolean allowed = s.equals("top") ? sender.hasPermission("corespawners.top")
                        : s.equals("debug") ? sender.hasPermission("corespawners.debug") : admin;
                if (allowed && s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("top") && sender.hasPermission("corespawners.top")) {
            for (String c : new String[]{"stack", "kills", "sales"}) {
                if (c.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(c);
                }
            }
            return out;
        }
        if (!admin) {
            return out;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("booster"))) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("booster")) {
            for (String id : plugin.boosters().ids()) {
                if (id.startsWith(args[2].toLowerCase(Locale.ROOT))) {
                    out.add(id);
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            for (String id : plugin.types().ids()) {
                if (id.startsWith(args[2].toLowerCase(Locale.ROOT))) {
                    out.add(id);
                }
            }
        }
        return out;
    }
}
