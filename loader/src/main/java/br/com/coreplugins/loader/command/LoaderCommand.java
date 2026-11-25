package br.com.coreplugins.loader.command;

import br.com.coreplugins.loader.LoaderPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;

/** /coreplugins status | reload */
public final class LoaderCommand implements CommandExecutor {

    private final LoaderPlugin plugin;

    public LoaderCommand(LoaderPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("coreplugins.admin")) {
            sender.sendMessage("§cSem permissão.");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();
        switch (sub) {
            case "status":
                sender.sendMessage("§e[corePlugins] §fEndereço autenticado: §a" + plugin.getLastAddress());
                sender.sendMessage("§e[corePlugins] §fStatus: §a" + plugin.getLastStatus());
                long t = plugin.getLastCheckMillis();
                sender.sendMessage("§e[corePlugins] §fÚltima verificação: §7"
                        + (t == 0 ? "nunca" : new SimpleDateFormat("dd/MM HH:mm:ss").format(new Date(t))));
                Collection<String> list = plugin.getProducts().describe();
                sender.sendMessage("§e[corePlugins] §fProdutos (" + list.size() + "):");
                for (String s : list) {
                    sender.sendMessage("  §7- §f" + s);
                }
                return true;
            case "reload":
                plugin.refreshAsync(false, true);
                sender.sendMessage("§e[corePlugins] §fVerificando a licença e aplicando as versões escolhidas no site. Veja o console.");
                return true;
            default:
                sender.sendMessage("§e/" + label + " §7[status|reload]");
                return true;
        }
    }
}
