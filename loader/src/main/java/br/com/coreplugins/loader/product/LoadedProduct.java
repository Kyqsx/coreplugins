package br.com.coreplugins.loader.product;

import br.com.coreplugins.api.ProductPlugin;
import org.bukkit.command.PluginCommand;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.PluginDescriptionFile;

import java.util.List;
import java.util.Map;

/** Um produto carregado e em execução. */
final class LoadedProduct {

    final String id;
    final String version;
    final String sha256;
    final ProductPlugin plugin;
    final ProductClassLoader classLoader;
    final PluginDescriptionFile description;
    final Map<String, PluginCommand> commands;
    final List<Permission> permissions;

    LoadedProduct(String id, String version, String sha256, ProductPlugin plugin, ProductClassLoader classLoader,
                  PluginDescriptionFile description, Map<String, PluginCommand> commands, List<Permission> permissions) {
        this.id = id;
        this.version = version;
        this.sha256 = sha256;
        this.plugin = plugin;
        this.classLoader = classLoader;
        this.description = description;
        this.commands = commands;
        this.permissions = permissions;
    }
}
