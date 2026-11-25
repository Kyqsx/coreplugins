package com.example.product;

import br.com.coreplugins.api.ProductPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Exemplo de produto no modelo do yPlugins:
 * <ul>
 *   <li>estende {@link ProductPlugin} (e não JavaPlugin);</li>
 *   <li>tudo que o Bukkit pede como Plugin recebe {@link #getLoader()};</li>
 *   <li>init pesado (listeners, tasks, banco, hooks) roda 1 tick depois do onEnable;</li>
 *   <li>comandos vêm do plugin.yml; o executor é ligado com {@code getCommand(...)}.</li>
 * </ul>
 */
public final class ExampleProduct extends ProductPlugin implements Listener, CommandExecutor {

    private static ExampleProduct instance;
    private boolean disabled;

    @Override
    public void onEnable() {
        // Defesa contra o jar solto em plugins/: precisa ter sido carregado pelo loader.
        // (Proteção fraca sozinha; quem protege de verdade é o jar só existir no seu servidor.)
        if (!isLoadedByLoader()) {
            getLogger().severe("Este plugin precisa ser carregado pelo corePlugins.");
            disabled = true;
            return;
        }
        instance = this;
        saveDefaultConfig();                       // leve e síncrono: ok no tick 0

        // pesado só 1 tick depois, com o LOADER como dono da task
        Bukkit.getScheduler().runTaskLater(getLoader(), this::register, 1L);
    }

    private void register() {
        registerEvents(this);                      // equivale a registerEvents(this, getLoader())
        if (getCommand("exemplo") != null) {
            getCommand("exemplo").setExecutor(this);
        }
        getLogger().info("Sistemas registrados (v" + getVersion() + ").");
    }

    @Override
    public void onDisable() {
        if (disabled) {
            return;
        }
        getLogger().info("Descarregado.");
    }

    public static ExampleProduct get() {
        return instance;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        String msg = getConfig().getString("mensagens.boas-vindas", "&aBem-vindo!");
        e.getPlayer().sendMessage(color(msg.replace("%player%", e.getPlayer().getName())));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("exemplo.admin")) {
                sender.sendMessage(color(getConfig().getString("mensagens.sem-permissao", "&cSem permissão.")));
                return true;
            }
            reloadConfig();
            sender.sendMessage(color(getConfig().getString("mensagens.recarregado", "&aOk.")));
            return true;
        }
        sender.sendMessage(color("&eExemplo v" + getVersion() + " &7- carregado da memória pelo corePlugins."));
        return true;
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
