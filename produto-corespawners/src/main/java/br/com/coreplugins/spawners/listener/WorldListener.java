package br.com.coreplugins.spawners.listener;

import br.com.coreplugins.spawners.SpawnersProduct;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

/** Mobs e hologramas só existem enquanto o chunk do gerador está carregado. */
public final class WorldListener implements Listener {

    private final SpawnersProduct plugin;

    public WorldListener(SpawnersProduct plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onLoad(ChunkLoadEvent e) {
        final Chunk c = e.getChunk();
        // 1 tick depois: dentro do evento o chunk ainda não aceita criar entidades com segurança
        Bukkit.getScheduler().runTask(plugin.getLoader(), new Runnable() {
            @Override
            public void run() {
                if (c.isLoaded()) {
                    plugin.spawners().onChunkLoad(c);
                }
            }
        });
    }

    @EventHandler
    public void onUnload(ChunkUnloadEvent e) {
        plugin.spawners().onChunkUnload(e.getChunk());
    }
}
