package br.com.coreplugins.spawners.model;

import java.util.List;

/** Um booster definido em boosters.yml. */
public final class BoosterType {

    /** O que o booster multiplica. */
    public enum Kind {
        /** Quantidade de drops gerados. */
        DROPS,
        /** Velocidade de geração de mobs (divide o intervalo). */
        SPEED,
        /** Valor recebido ao vender os drops. */
        SELL
    }

    public final String id;
    public final Kind kind;
    public final double multiplier;
    public final long durationSeconds;
    public final String display;
    public final String material;
    public final String itemName;
    public final List<String> itemLore;

    public BoosterType(String id, Kind kind, double multiplier, long durationSeconds, String display,
                       String material, String itemName, List<String> itemLore) {
        this.id = id;
        this.kind = kind;
        this.multiplier = multiplier;
        this.durationSeconds = durationSeconds;
        this.display = display;
        this.material = material;
        this.itemName = itemName;
        this.itemLore = itemLore;
    }
}
