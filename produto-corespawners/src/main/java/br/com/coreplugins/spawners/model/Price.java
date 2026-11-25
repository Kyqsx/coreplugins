package br.com.coreplugins.spawners.model;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

/** Um valor em uma economia ({@code currency} é o nome usado nos arquivos; 'auto' = economia padrão). */
public final class Price {

    public final String currency;
    public final double amount;

    public Price(String currency, double amount) {
        this.currency = currency == null || currency.trim().isEmpty() ? "auto" : currency.trim();
        this.amount = amount;
    }

    public static Price of(ConfigurationSection sec) {
        if (sec == null) {
            return null;
        }
        double amount = sec.getDouble("amount", sec.getDouble("price", 0D));
        return new Price(sec.getString("currency", "auto"), amount);
    }

    /** Lê {@code price:} (um valor) e/ou {@code prices:} (vários, um por chave) de uma seção. */
    public static List<Price> readAll(ConfigurationSection parent) {
        List<Price> out = new ArrayList<Price>();
        if (parent == null) {
            return out;
        }
        Price single = of(parent.getConfigurationSection("price"));
        if (single != null) {
            out.add(single);
        }
        ConfigurationSection many = parent.getConfigurationSection("prices");
        if (many != null) {
            for (String key : many.getKeys(false)) {
                Price p = of(many.getConfigurationSection(key));
                if (p != null) {
                    out.add(p);
                }
            }
        }
        return out;
    }
}
