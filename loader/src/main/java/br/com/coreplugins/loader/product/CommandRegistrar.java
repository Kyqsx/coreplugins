package br.com.coreplugins.loader.product;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Registra os {@code commands:} do plugin.yml do produto com o loader como dono.
 * O Bukkit não expõe isso, então usa reflexão (padrão em plugins que registram comandos dinamicamente).
 */
final class CommandRegistrar {

    private CommandRegistrar() {
    }

    static Map<String, PluginCommand> register(Plugin owner, PluginDescriptionFile desc) throws ReflectiveOperationException {
        Map<String, PluginCommand> result = new HashMap<String, PluginCommand>();
        Map<String, Map<String, Object>> declared = desc.getCommands();
        if (declared == null || declared.isEmpty()) {
            return result;
        }
        CommandMap map = commandMap();
        Constructor<PluginCommand> ctor = PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
        ctor.setAccessible(true);

        for (Map.Entry<String, Map<String, Object>> e : declared.entrySet()) {
            String name = e.getKey();
            Map<String, Object> meta = e.getValue();
            PluginCommand cmd = ctor.newInstance(name, owner);
            if (meta != null) {
                Object description = meta.get("description");
                if (description != null) {
                    cmd.setDescription(description.toString());
                }
                Object usage = meta.get("usage");
                if (usage != null) {
                    cmd.setUsage(usage.toString());
                }
                Object permission = meta.get("permission");
                if (permission != null) {
                    cmd.setPermission(permission.toString());
                }
                Object permMessage = meta.get("permission-message");
                if (permMessage != null) {
                    cmd.setPermissionMessage(permMessage.toString());
                }
                cmd.setAliases(aliases(meta.get("aliases")));
            }
            map.register(desc.getName().toLowerCase(Locale.ROOT), cmd);
            result.put(name.toLowerCase(Locale.ROOT), cmd);
        }
        return result;
    }

    static void unregister(Collection<PluginCommand> commands) {
        if (commands.isEmpty()) {
            return;
        }
        try {
            CommandMap map = commandMap();
            Map<String, Command> known = knownCommands(map);
            for (PluginCommand cmd : commands) {
                if (known != null) {
                    Iterator<Map.Entry<String, Command>> it = known.entrySet().iterator();
                    while (it.hasNext()) {
                        if (it.next().getValue() == cmd) {
                            it.remove();
                        }
                    }
                }
                cmd.unregister(map);
            }
        } catch (ReflectiveOperationException ignored) {
            // melhor esforço: o comando fica órfão até reiniciar, mas sem executor ele não faz nada
        }
    }

    private static List<String> aliases(Object raw) {
        if (raw == null) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>();
        if (raw instanceof Collection) {
            for (Object o : (Collection<?>) raw) {
                out.add(String.valueOf(o));
            }
        } else {
            out.add(raw.toString());
        }
        return out;
    }

    private static CommandMap commandMap() throws ReflectiveOperationException {
        Object server = Bukkit.getServer();
        Method m = server.getClass().getMethod("getCommandMap");
        return (CommandMap) m.invoke(server);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Command> knownCommands(CommandMap map) throws ReflectiveOperationException {
        try {
            Method m = map.getClass().getMethod("getKnownCommands");
            return (Map<String, Command>) m.invoke(map);
        } catch (NoSuchMethodException old) {
            // Spigot antigo (1.8): campo privado
            Field f = SimpleCommandMap.class.getDeclaredField("knownCommands");
            f.setAccessible(true);
            return (Map<String, Command>) f.get(map);
        }
    }
}
