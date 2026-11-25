package br.com.coreplugins.spawners.model;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Contadores acumulados de um jogador (mobs mortos e vendas). Os métodos são sincronizados porque o
 * PlaceholderAPI pode ler daqui fora da thread principal.
 */
public final class PlayerStats {

    public final UUID id;
    private String name;
    private long kills;
    private long soldItems;
    private final Map<String, Double> sales = new HashMap<String, Double>();
    public transient volatile boolean dirty;

    public PlayerStats(UUID id, String name) {
        this.id = id;
        this.name = name == null ? "?" : name;
    }

    public synchronized String name() {
        return name;
    }

    public synchronized void name(String n) {
        if (n != null && !n.isEmpty() && !n.equals(name)) {
            name = n;
            dirty = true;
        }
    }

    public synchronized long kills() {
        return kills;
    }

    public synchronized long soldItems() {
        return soldItems;
    }

    /** Total vendido na moeda (id da moeda, minúsculo). */
    public synchronized double sales(String currency) {
        Double v = sales.get(currency);
        return v == null ? 0D : v;
    }

    public synchronized Map<String, Double> salesCopy() {
        return new HashMap<String, Double>(sales);
    }

    public synchronized void addKills(long n) {
        if (n > 0) {
            kills += n;
            dirty = true;
        }
    }

    public synchronized void addSold(long items, String currency, double value) {
        if (items > 0) {
            soldItems += items;
            dirty = true;
        }
        if (currency != null && value > 0D) {
            sales.put(currency, sales(currency) + value);
            dirty = true;
        }
    }

    /** Carga do banco (não marca como alterado). */
    public synchronized void load(long kills, long soldItems) {
        this.kills = kills;
        this.soldItems = soldItems;
    }

    public synchronized void loadSale(String currency, double total) {
        sales.put(currency, total);
    }

    public synchronized PlayerStats snapshot() {
        PlayerStats c = new PlayerStats(id, name);
        c.kills = kills;
        c.soldItems = soldItems;
        c.sales.putAll(sales);
        return c;
    }
}
