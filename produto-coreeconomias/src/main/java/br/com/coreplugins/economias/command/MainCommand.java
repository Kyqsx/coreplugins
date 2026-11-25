package br.com.coreplugins.economias.command;

import br.com.coreplugins.economias.EconomiasProduct;
import br.com.coreplugins.economias.economy.Currency;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /economies (menu) | list | reload | help. As permissões são checadas aqui (funciona com qualquer plugin de permissões). */
public final class MainCommand implements CommandExecutor, TabCompleter {

    private final EconomiasProduct plugin;

    public MainCommand(EconomiasProduct plugin) {
        this.plugin = plugin;
    }

    private static boolean isAdmin(CommandSender s) {
        return s.hasPermission("coreeconomias.admin");
    }

    private static boolean canMenu(CommandSender s) {
        return s.hasPermission("coreeconomias.menu") || isAdmin(s);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        boolean player = sender instanceof Player;
        if (sub.equals("menu") || (sub.isEmpty() && player && canMenu(sender))) {
            if (!canMenu(sender)) {
                plugin.getMessages().send(sender, "permission");
            } else if (!player) {
                plugin.getMessages().send(sender, "only-player");
            } else {
                plugin.getMenus().openMain((Player) sender, 0);
            }
            return true;
        }
        if (!isAdmin(sender)) {
            plugin.getMessages().send(sender, "permission");
            return true;
        }
        if (sub.equals("reload")) {
            plugin.reloadAll();
            plugin.getMessages().send(sender, "reloaded");
        } else if (sub.equals("list")) {
            plugin.getMessages().send(sender, "list-header");
            for (Currency c : plugin.getCurrencies().all()) {
                plugin.getMessages().send(sender, "list-line",
                        "id", c.getId(), "command", c.getCommandName(), "format", c.getFormat().name());
            }
        } else {
            plugin.getMessages().send(sender, "help");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<String>();
        if (args.length != 1) {
            return out;
        }
        String last = args[0].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<String>();
        if (canMenu(sender)) {
            options.add("menu");
        }
        if (isAdmin(sender)) {
            options.add("list");
            options.add("reload");
            options.add("help");
        }
        for (String o : options) {
            if (o.startsWith(last)) {
                out.add(o);
            }
        }
        return out;
    }
}
