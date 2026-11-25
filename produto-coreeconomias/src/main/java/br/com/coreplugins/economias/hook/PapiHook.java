package br.com.coreplugins.economias.hook;

import br.com.coreplugins.economias.EconomiasProduct;
import br.com.coreplugins.economias.economy.Currency;
import br.com.coreplugins.economias.storage.Storage;
import br.com.coreplugins.economias.util.Amounts;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.Locale;

/**
 * Placeholders:
 * %economias_&lt;id&gt;%                  saldo puro (ex.: 1500000)
 * %economias_&lt;id&gt;_formatted%        saldo formatado conforme a economia (ex.: 1.5M)
 * %economias_top_&lt;id&gt;_&lt;pos&gt;_name%   nome da posição no TOP
 * %economias_top_&lt;id&gt;_&lt;pos&gt;_value%  saldo formatado da posição no TOP
 */
public final class PapiHook extends PlaceholderExpansion {

    private final EconomiasProduct plugin;

    private PapiHook(EconomiasProduct plugin) {
        this.plugin = plugin;
    }

    public static Object register(EconomiasProduct plugin) {
        PapiHook hook = new PapiHook(plugin);
        hook.register();
        return hook;
    }

    public static void unregister(Object hook) {
        if (hook instanceof PapiHook) {
            ((PapiHook) hook).unregister();
        }
    }

    @Override public String getIdentifier() { return "economias"; }
    @Override public String getAuthor() { return "CorePlugins"; }
    @Override public String getVersion() { return plugin.getVersion(); }
    @Override public boolean persist() { return true; }
    @Override public boolean canRegister() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String p = params.toLowerCase(Locale.ROOT);

        if (p.startsWith("top_")) {
            String[] parts = p.split("_");
            if (parts.length < 4) {
                return "";
            }
            String field = parts[parts.length - 1];
            int pos;
            try {
                pos = Integer.parseInt(parts[parts.length - 2]);
            } catch (NumberFormatException e) {
                return "";
            }
            StringBuilder id = new StringBuilder();
            for (int i = 1; i < parts.length - 2; i++) {
                if (i > 1) {
                    id.append('_');
                }
                id.append(parts[i]);
            }
            Currency c = plugin.getCurrencies().get(id.toString());
            if (c == null) {
                return "";
            }
            List<Storage.TopEntry> list = plugin.getTop().get(c.getId());
            if (pos < 1 || pos > list.size()) {
                return field.equals("name") ? "-" : "0";
            }
            Storage.TopEntry e = list.get(pos - 1);
            return field.equals("name") ? e.name : c.format(e.balance);
        }

        if (player == null) {
            return "";
        }
        boolean formatted = p.endsWith("_formatted");
        String id = formatted ? p.substring(0, p.length() - "_formatted".length()) : p;
        Currency c = plugin.getCurrencies().get(id);
        if (c == null) {
            return null;
        }
        double balance = plugin.getService().get(player.getUniqueId(), c);
        return formatted ? c.format(balance) : Amounts.raw(balance);
    }
}
