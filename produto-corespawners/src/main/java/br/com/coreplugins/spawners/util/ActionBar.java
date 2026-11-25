package br.com.coreplugins.spawners.util;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.entity.Player;

/** Mensagem acima da hotbar. Em servidores sem suporte (1.8) simplesmente não é mostrada. */
public final class ActionBar {

    private ActionBar() {
    }

    public static void send(Player p, String text) {
        try {
            p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(text));
        } catch (Throwable ignored) {
            // sem action bar nesta versão
        }
    }
}
