package br.com.coreplugins.economias.command;

import br.com.coreplugins.economias.EconomiasProduct;
import br.com.coreplugins.economias.economy.Currency;
import br.com.coreplugins.economias.economy.EconomyService;
import br.com.coreplugins.economias.storage.Storage;
import br.com.coreplugins.economias.util.Amounts;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Comando de uma economia (ex.: /coins). Registrado direto no CommandMap pelo CurrencyManager. */
public final class CurrencyCommand extends Command {

    private interface TargetAction {
        void run(UUID id, String name);
    }

    private static final List<String> TARGET_ACTIONS = Arrays.asList("pay", "add", "remove", "set");

    private final EconomiasProduct plugin;
    private final Currency currency;
    private final Map<UUID, Long> lastUse = new ConcurrentHashMap<UUID, Long>();

    public CurrencyCommand(EconomiasProduct plugin, Currency currency) {
        super(currency.getCommandName(), "Economia " + currency.getId(),
                "/" + currency.getCommandName(), new ArrayList<String>(currency.getAliases()));
        this.plugin = plugin;
        this.currency = currency;
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        try {
            handle(sender, args);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Erro no comando /" + getName(), e);
            sender.sendMessage(ChatColor.RED + "Erro interno. Avise a administração.");
        }
        return true;
    }

    private void handle(CommandSender sender, String[] args) {
        if (!allowed(sender, "use")) {
            send(sender, "permission");
            return;
        }
        if (sender instanceof Player && onCooldown((Player) sender)) {
            return;
        }
        if (args.length == 0) {
            if (sender instanceof Player) {
                Player p = (Player) sender;
                double bal = plugin.getService().get(p.getUniqueId(), currency);
                send(sender, "balance", "amount", currency.format(bal), "unit", currency.unit(bal));
            } else {
                sendHelp(sender);
            }
            return;
        }
        String action = currency.actionOf(args[0]);
        if (action == null) {
            lookup(sender, args[0]);
            return;
        }
        switch (action) {
            case "pay":
                pay(sender, args);
                break;
            case "add":
            case "remove":
            case "set":
                admin(sender, action, args);
                break;
            case "top":
                top(sender);
                break;
            case "forceupdate":
                forceUpdate(sender);
                break;
            default:
                sendHelp(sender);
        }
    }

    // ---------- ações ----------

    private void lookup(CommandSender sender, String targetName) {
        if (!allowed(sender, "look")) {
            send(sender, "permission");
            return;
        }
        final EconomyService service = plugin.getService();
        withTarget(sender, targetName, true, (id, name) -> {
            double bal = service.get(id, currency);
            send(sender, "balance-target", "player", name,
                    "amount", currency.format(bal), "unit", currency.unit(bal));
        });
    }

    private void pay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            send(sender, "only-player");
            return;
        }
        if (!currency.isPayEnabled() || !allowed(sender, "pay")) {
            send(sender, "permission");
            return;
        }
        if (args.length < 3) {
            send(sender, "usage-pay");
            return;
        }
        final Player p = (Player) sender;
        final double amount = Amounts.parse(args[2]);
        if (Double.isNaN(amount) || amount <= 0D) {
            send(sender, "number");
            return;
        }
        if (amount < currency.getMinPay()) {
            send(sender, "min-pay", "amount", currency.format(currency.getMinPay()),
                    "unit", currency.unit(currency.getMinPay()));
            return;
        }
        if (args[1].equalsIgnoreCase(p.getName())) {
            send(sender, "yourself");
            return;
        }
        final EconomyService service = plugin.getService();
        withTarget(sender, args[1], !currency.isBlockOfflinePay(), (id, name) -> {
            if (id.equals(p.getUniqueId())) {
                send(p, "yourself");
                return;
            }
            EconomyService.Result r = service.tryTransfer(p.getUniqueId(), id, currency, amount);
            if (r != EconomyService.Result.OK) {
                send(p, r == EconomyService.Result.INSUFFICIENT ? "has" : "error");
                return;
            }
            send(p, "send", "player", name, "amount", currency.format(amount), "unit", currency.unit(amount));
            Player target = Bukkit.getPlayer(id);
            if (target != null) {
                send(target, "received", "player", p.getName(),
                        "amount", currency.format(amount), "unit", currency.unit(amount));
            }
        });
    }

    private void admin(CommandSender sender, String action, String[] args) {
        if (!allowed(sender, action)) {
            send(sender, "permission");
            return;
        }
        if (args.length < 3) {
            send(sender, "usage-" + action);
            return;
        }
        final double amount = Amounts.parse(args[2]);
        boolean valid = !Double.isNaN(amount) && (action.equals("set") ? amount >= 0D : amount > 0D);
        if (!valid) {
            send(sender, "number");
            return;
        }
        final EconomyService service = plugin.getService();
        withTarget(sender, args[1], true, (id, name) -> {
            EconomyService.Result r;
            if (action.equals("set")) {
                r = service.set(id, currency, amount) ? EconomyService.Result.OK : EconomyService.Result.ERROR;
            } else if (action.equals("add")) {
                r = service.add(id, currency, amount) ? EconomyService.Result.OK : EconomyService.Result.ERROR;
            } else {
                r = service.tryRemove(id, currency, amount);
            }
            if (r != EconomyService.Result.OK) {
                send(sender, r == EconomyService.Result.INSUFFICIENT ? "target-has" : "error");
                return;
            }
            send(sender, action, "player", name, "amount", currency.format(amount), "unit", currency.unit(amount));
        });
    }

    private void top(CommandSender sender) {
        if (!allowed(sender, "top")) {
            send(sender, "permission");
            return;
        }
        List<Storage.TopEntry> list = plugin.getTop().get(currency.getId());
        if (list.isEmpty()) {
            send(sender, "top-empty");
            return;
        }
        send(sender, "top-header");
        int pos = 1;
        for (Storage.TopEntry e : list) {
            send(sender, "top-line", "pos", String.valueOf(pos++), "player", e.name,
                    "amount", currency.format(e.balance), "unit", currency.unit(e.balance));
        }
    }

    private void forceUpdate(CommandSender sender) {
        if (!allowed(sender, "forceupdate")) {
            send(sender, "permission");
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin.getLoader(), () -> {
            plugin.getTop().refresh();
            Bukkit.getScheduler().runTask(plugin.getLoader(), () -> send(sender, "force-update"));
        });
    }

    private void sendHelp(CommandSender sender) {
        send(sender, allowed(sender, "add") ? "help-admin" : "help");
    }

    // ---------- utilitários ----------

    /** Jogador online responde na hora; offline é procurado no banco fora da thread principal. */
    private void withTarget(final CommandSender sender, final String name, boolean allowOffline,
                            final TargetAction action) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            action.run(online.getUniqueId(), online.getName());
            return;
        }
        if (!allowOffline) {
            send(sender, "offline-pay");
            return;
        }
        final EconomyService service = plugin.getService();
        Bukkit.getScheduler().runTaskAsynchronously(plugin.getLoader(), () -> {
            final Storage.PlayerRef ref = service.findPlayer(name);
            final boolean ok = ref != null && service.preload(ref.uuid);
            Bukkit.getScheduler().runTask(plugin.getLoader(), () -> {
                if (!ok) {
                    send(sender, "target");
                    return;
                }
                action.run(ref.uuid, ref.name);
            });
        });
    }

    private boolean onCooldown(Player p) {
        int delay = currency.getCommandDelay();
        if (delay <= 0 || allowed(p, "add")) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long last = lastUse.get(p.getUniqueId());
        long window = delay * 1000L;
        if (last != null && now - last < window) {
            long remain = (window - (now - last) + 999L) / 1000L;
            send(p, "delay", "time", String.valueOf(remain));
            return true;
        }
        lastUse.put(p.getUniqueId(), now);
        return false;
    }

    /**
     * Permissão específica OU coreeconomias.admin. Se a específica foi definida explicitamente (inclusive negada,
     * ex.: "-coreeconomias.coins.add" no LuckPerms), ela vale e o admin não a sobrepõe.
     */
    private boolean allowed(CommandSender sender, String action) {
        String node = "coreeconomias." + currency.getId() + "." + action;
        if (sender.isPermissionSet(node)) {
            return sender.hasPermission(node);
        }
        return sender.hasPermission(node) || sender.hasPermission("coreeconomias.admin");
    }

    public String currencyId() {
        return currency.getId();
    }

    private void send(CommandSender to, String key, String... placeholders) {
        String text = currency.message(key).replace("{command}", currency.getCommandName());
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            text = text.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
        }
        if (text.isEmpty()) {
            return;
        }
        for (String line : text.split("\n")) {
            to.sendMessage(ChatColor.translateAlternateColorCodes('&', line));
        }
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        List<String> out = new ArrayList<String>();
        if (!allowed(sender, "use")) {
            return out;
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            for (String action : Arrays.asList("pay", "top", "add", "remove", "set", "help")) {
                if (allowed(sender, action.equals("help") ? "use" : action)) {
                    out.add(currency.primarySub(action));
                }
            }
            addOnlineNames(out);
        } else if (args.length == 2) {
            String action = currency.actionOf(args[0]);
            if (action != null && TARGET_ACTIONS.contains(action)) {
                addOnlineNames(out);
            }
        }
        List<String> filtered = new ArrayList<String>();
        for (String s : out) {
            if (s.toLowerCase(Locale.ROOT).startsWith(last)) {
                filtered.add(s);
            }
        }
        return filtered;
    }

    private void addOnlineNames(List<String> out) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            out.add(p.getName());
        }
    }
}
