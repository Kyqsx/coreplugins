package br.com.coreplugins.economias.listener;

import br.com.coreplugins.economias.EconomiasProduct;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;
import java.util.logging.Level;

public final class PlayerListener implements Listener {

    private final EconomiasProduct plugin;

    public PlayerListener(EconomiasProduct plugin) {
        this.plugin = plugin;
    }

    /**
     * Carrega os saldos antes de o jogador entrar. Se o banco falhar, o login é negado:
     * é melhor do que deixar o jogador entrar "zerado" e sobrescrever o saldo real.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        try {
            plugin.getService().load(e.getUniqueId(), e.getName());
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "Falha ao carregar os saldos de " + e.getName(), ex);
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, plugin.getMessages().get("load-failed"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        final UUID id = e.getPlayer().getUniqueId();
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin.getLoader(), new Runnable() {
                @Override
                public void run() {
                    plugin.getService().unload(id);
                }
            });
        } catch (RuntimeException ignored) {
            // desligando: o onDisable grava tudo
        }
    }
}
