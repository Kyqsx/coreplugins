package br.com.coreplugins.economias.economy;

import br.com.coreplugins.economias.util.Amounts;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Uma economia, lida de economies/&lt;id&gt;.yml. */
public final class Currency {

    public enum Format { LETTER, NUMBER }

    private static final Map<String, List<String>> DEFAULT_SUBS = new LinkedHashMap<String, List<String>>();

    static {
        DEFAULT_SUBS.put("add", Arrays.asList("add", "adicionar", "depositar"));
        DEFAULT_SUBS.put("remove", Arrays.asList("remove", "remover", "sacar"));
        DEFAULT_SUBS.put("set", Arrays.asList("set", "setar", "definir"));
        DEFAULT_SUBS.put("pay", Arrays.asList("pay", "pagar", "enviar"));
        DEFAULT_SUBS.put("top", Arrays.asList("top"));
        DEFAULT_SUBS.put("help", Arrays.asList("help", "ajuda"));
        DEFAULT_SUBS.put("forceupdate", Arrays.asList("forceupdate"));
    }

    private final String id;
    private final String singular;
    private final String plural;
    private final Format format;
    private final double defaultBalance;
    private final String commandName;
    private final List<String> aliases;
    private final Map<String, List<String>> subCommands = new LinkedHashMap<String, List<String>>();
    private final int commandDelay;
    private final boolean payEnabled;
    private final boolean blockOfflinePay;
    private final double minPay;
    private final int topSize;
    private final Map<String, String> messages = new HashMap<String, String>();

    public Currency(String id, FileConfiguration c) {
        this.id = id;
        this.singular = c.getString("name.singular", id);
        this.plural = c.getString("name.plural", id);
        this.format = "NUMBER".equalsIgnoreCase(c.getString("format", "LETTER")) ? Format.NUMBER : Format.LETTER;
        this.defaultBalance = Math.max(0D, c.getDouble("default", 0D));
        this.commandName = c.getString("command.name", id).toLowerCase(Locale.ROOT);
        this.aliases = new ArrayList<String>();
        for (String alias : c.getStringList("command.aliases")) {
            this.aliases.add(alias.toLowerCase(Locale.ROOT));
        }
        for (Map.Entry<String, List<String>> e : DEFAULT_SUBS.entrySet()) {
            List<String> configured = c.getStringList("sub-command." + e.getKey());
            List<String> names = new ArrayList<String>();
            for (String s : (configured.isEmpty() ? e.getValue() : configured)) {
                names.add(s.toLowerCase(Locale.ROOT));
            }
            subCommands.put(e.getKey(), names);
        }
        this.commandDelay = Math.max(0, c.getInt("command-delay", 0));
        this.payEnabled = c.getBoolean("pay.enabled", true);
        this.blockOfflinePay = c.getBoolean("pay.block-offline", false);
        this.minPay = Math.max(0D, c.getDouble("pay.min", 1D));
        this.topSize = Math.max(1, Math.min(50, c.getInt("top.size", 10)));
        ConfigurationSection ms = c.getConfigurationSection("messages");
        if (ms != null) {
            for (String key : ms.getKeys(false)) {
                String value = ms.isList(key) ? join(ms.getStringList(key)) : ms.getString(key);
                messages.put(key, value);
            }
        }
    }

    private static String join(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    /** Qual ação o texto representa (add, pay...) ou null se não for subcomando. */
    public String actionOf(String arg) {
        String low = arg.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, List<String>> e : subCommands.entrySet()) {
            if (e.getValue().contains(low)) {
                return e.getKey();
            }
        }
        return null;
    }

    public String primarySub(String action) {
        List<String> l = subCommands.get(action);
        return (l == null || l.isEmpty()) ? action : l.get(0);
    }

    public String format(double amount) {
        return format == Format.NUMBER ? Amounts.number(amount) : Amounts.letter(amount);
    }

    public String unit(double amount) {
        return amount == 1D ? singular : plural;
    }

    public String message(String key) {
        String m = messages.get(key);
        return m == null ? "&c[mensagem ausente: " + key + "]" : m;
    }

    public String getId() { return id; }
    public String getSingular() { return singular; }
    public String getPlural() { return plural; }
    public Format getFormat() { return format; }
    public double getDefaultBalance() { return defaultBalance; }
    public String getCommandName() { return commandName; }
    public List<String> getAliases() { return aliases; }
    public int getCommandDelay() { return commandDelay; }
    public boolean isPayEnabled() { return payEnabled; }
    public boolean isBlockOfflinePay() { return blockOfflinePay; }
    public double getMinPay() { return minPay; }
    public int getTopSize() { return topSize; }
}
