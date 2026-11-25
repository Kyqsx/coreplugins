package br.com.coreplugins.spawners.util;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Dados persistentes no item (1.14+). Fica numa classe separada para que o plugin continue carregando
 * em servidores antigos: esta classe só é tocada quando {@link Items#pdcAvailable()} é verdadeiro.
 */
final class PdcTag {

    private PdcTag() {
    }

    static void write(Plugin owner, ItemMeta meta, String key, String value) {
        meta.getPersistentDataContainer().set(new NamespacedKey(owner, key), PersistentDataType.STRING, value);
    }

    static String read(Plugin owner, ItemMeta meta, String key) {
        PersistentDataContainer c = meta.getPersistentDataContainer();
        return c.get(new NamespacedKey(owner, key), PersistentDataType.STRING);
    }
}
