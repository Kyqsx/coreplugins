package br.com.coreplugins.spawners.manager;

import br.com.coreplugins.spawners.SpawnersProduct;
import br.com.coreplugins.spawners.model.Spawner;
import br.com.coreplugins.spawners.model.SpawnerType;
import br.com.coreplugins.spawners.util.Compat;
import br.com.coreplugins.spawners.util.Text;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Hologramas feitos com ArmorStands invisíveis acima do gerador. */
public final class HologramManager {

    private final SpawnersProduct plugin;
    private final Map<Long, List<ArmorStand>> lines = new HashMap<Long, List<ArmorStand>>();

    public HologramManager(SpawnersProduct plugin) {
        this.plugin = plugin;
    }

    /** Cria ou atualiza o holograma (some se desligado ou se o chunk não está carregado). */
    public void update(Spawner s) {
        SpawnerType t = plugin.types().get(s.typeId);
        World w = s.world();
        if (t == null || w == null || !t.hologramEnabled || !s.hologram || !w.isChunkLoaded(s.chunkX(), s.chunkZ())) {
            remove(s);
            return;
        }
        List<String> text = Text.render(t.hologramLines, plugin.spawners().vars(s));
        List<ArmorStand> cur = lines.get(s.id);
        boolean reuse = cur != null && cur.size() == text.size();
        if (reuse) {
            for (ArmorStand a : cur) {
                if (!a.isValid()) {
                    reuse = false;
                    break;
                }
            }
        }
        if (reuse) {
            for (int i = 0; i < text.size(); i++) {
                cur.get(i).setCustomName(text.get(i));
            }
            return;
        }
        remove(s);
        List<ArmorStand> created = new ArrayList<ArmorStand>();
        double top = s.y + t.hologramOffset;
        for (int i = 0; i < text.size(); i++) {
            Location loc = new Location(w, s.x + 0.5D, top + (text.size() - 1 - i) * 0.27D, s.z + 0.5D);
            try {
                ArmorStand a = w.spawn(loc, ArmorStand.class);
                a.setVisible(false);
                a.setGravity(false);
                a.setCustomNameVisible(true);
                a.setCustomName(text.get(i));
                a.setSmall(true);
                a.setBasePlate(false);
                try {
                    a.setMarker(true);
                } catch (Throwable ignored) {
                    // versão sem marker
                }
                Compat.tag(a);
                created.add(a);
            } catch (Throwable t2) {
                plugin.getLogger().warning("Não foi possível criar o holograma: " + t2);
                break;
            }
        }
        lines.put(s.id, created);
    }

    public void remove(Spawner s) {
        List<ArmorStand> cur = lines.remove(s.id);
        if (cur != null) {
            for (ArmorStand a : cur) {
                a.remove();
            }
        }
    }

    public void removeAll() {
        for (List<ArmorStand> l : lines.values()) {
            for (ArmorStand a : l) {
                a.remove();
            }
        }
        lines.clear();
    }
}
