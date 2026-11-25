package br.com.coreplugins.spawners.model;

import org.bukkit.configuration.ConfigurationSection;

/** Configuração de um upgrade (drops ou velocidade). */
public final class UpgradeConfig {

    public final int maxLevel;
    /** Drops extras por nível (upgrade de drops) ou segundos a menos por nível (velocidade). */
    public final double perLevel;
    /** Menor intervalo possível, em segundos (só velocidade). */
    public final double minSeconds;
    public final String currency;
    public final double price;
    public final double multiplier;

    public UpgradeConfig(ConfigurationSection s, String perLevelKey) {
        this.maxLevel = s == null ? 0 : Math.max(0, s.getInt("max-level", 0));
        this.perLevel = s == null ? 0D : s.getDouble(perLevelKey, 1D);
        this.minSeconds = s == null ? 1D : Math.max(1D, s.getDouble("min-seconds", 1D));
        this.currency = s == null ? "auto" : s.getString("currency", "auto");
        this.price = s == null ? 0D : Math.max(0D, s.getDouble("price", 0D));
        this.multiplier = s == null ? 1D : Math.max(1D, s.getDouble("price-multiplier", 1D));
    }

    public boolean enabled() {
        return maxLevel > 0;
    }

    /** Preço para passar do nível {@code currentLevel} para o seguinte. */
    public double priceFor(int currentLevel) {
        return Math.ceil(price * Math.pow(multiplier, currentLevel));
    }
}
