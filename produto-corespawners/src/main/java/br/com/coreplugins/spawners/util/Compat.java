package br.com.coreplugins.spawners.util;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Chamadas que não existem em todas as versões (1.8 até as atuais). Cada uma tenta o método moderno
 * e, se o servidor for antigo, cai para uma alternativa ou simplesmente não faz nada.
 */
public final class Compat {

    /** Marca as entidades criadas pelo plugin (para limpar sobras depois de um crash). */
    public static final String TAG = "coreSpawners";

    private Compat() {
    }

    public static void tag(Entity e) {
        try {
            e.addScoreboardTag(TAG);
        } catch (Throwable ignored) {
            // servidor antigo (< 1.11): sem marca; as entidades são removidas ao descarregar o chunk
        }
    }

    public static boolean tagged(Entity e) {
        try {
            return e.getScoreboardTags().contains(TAG);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Sem IA, sem som, sem despawn e sem pegar itens. */
    public static void freeze(LivingEntity e) {
        try {
            e.setAI(false);
        } catch (Throwable t) {
            try {
                e.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, Integer.MAX_VALUE, 255, false, false));
            } catch (Throwable ignored) {
                // sem alternativa
            }
        }
        try {
            e.setSilent(true);
        } catch (Throwable ignored) {
            // < 1.9
        }
        try {
            e.setRemoveWhenFarAway(false);
        } catch (Throwable ignored) {
            // sem suporte
        }
        try {
            e.setCanPickupItems(false);
        } catch (Throwable ignored) {
            // sem suporte
        }
        try {
            e.setCollidable(false);
        } catch (Throwable ignored) {
            // < 1.9
        }
    }

    @SuppressWarnings("deprecation")
    public static void setMaxHealth(LivingEntity e, double health) {
        if (!(health > 0D)) {
            return;
        }
        try {
            e.setMaxHealth(health);
            e.setHealth(health);
        } catch (Throwable ignored) {
            // mantém a vida padrão da entidade
        }
    }

    @SuppressWarnings("deprecation")
    public static double maxHealth(LivingEntity e) {
        try {
            return e.getMaxHealth();
        } catch (Throwable t) {
            return 20D;
        }
    }
}
