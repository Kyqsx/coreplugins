package br.com.coreplugins.spawners.util;

import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Locale;

/** Reconhece ovos de spawn em qualquer versão (1.8 até as atuais). */
public final class Eggs {

    private Eggs() {
    }

    /** O item é um ovo de spawn (de qualquer mob)? */
    public static boolean isEgg(ItemStack it) {
        if (it == null) {
            return false;
        }
        String n = it.getType().name();
        return n.endsWith("_SPAWN_EGG") || n.equals("MONSTER_EGG");
    }

    /** O ovo é do mob informado? */
    public static boolean matches(ItemStack it, EntityType type) {
        if (!isEgg(it) || type == null) {
            return false;
        }
        String n = it.getType().name();
        if (n.endsWith("_SPAWN_EGG")) {
            String name = n.substring(0, n.length() - "_SPAWN_EGG".length());
            return same(name, type.name());
        }
        // 1.8 - 1.12: o mob fica nos dados do item (SpawnEggMeta). Reflexão porque a classe sumiu nas versões novas.
        try {
            ItemMeta meta = it.getItemMeta();
            Object spawned = meta.getClass().getMethod("getSpawnedType").invoke(meta);
            return spawned != null && same(((EntityType) spawned).name(), type.name());
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean same(String a, String b) {
        return canonical(a).equals(canonical(b));
    }

    /** Nomes que mudaram entre versões. */
    private static String canonical(String n) {
        String u = n.toUpperCase(Locale.ROOT);
        switch (u) {
            case "MUSHROOM_COW":
                return "MOOSHROOM";
            case "PIG_ZOMBIE":
                return "ZOMBIFIED_PIGLIN";
            case "SNOWMAN":
                return "SNOW_GOLEM";
            case "VILLAGER_GOLEM":
                return "IRON_GOLEM";
            default:
                return u;
        }
    }
}
