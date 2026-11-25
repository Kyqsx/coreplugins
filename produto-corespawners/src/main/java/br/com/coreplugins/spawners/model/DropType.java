package br.com.coreplugins.spawners.model;

import java.util.List;

/** Um tipo de drop de um gerador (ex.: couro). */
public final class DropType {

    public final String key;
    public final String name;
    public final double chance;
    public final boolean collect;
    public final String materialSpec;
    public final String itemName;
    public final List<String> itemLore;
    public final List<Price> prices;

    public DropType(String key, String name, double chance, boolean collect, String materialSpec,
                    String itemName, List<String> itemLore, List<Price> prices) {
        this.key = key;
        this.name = name;
        this.chance = chance;
        this.collect = collect;
        this.materialSpec = materialSpec;
        this.itemName = itemName;
        this.itemLore = itemLore;
        this.prices = prices;
    }
}
