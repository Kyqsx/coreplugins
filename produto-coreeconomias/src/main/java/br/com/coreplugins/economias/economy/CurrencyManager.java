package br.com.coreplugins.economias.economy;

import br.com.coreplugins.economias.EconomiasProduct;
import br.com.coreplugins.economias.command.CurrencyCommand;
import br.com.coreplugins.economias.util.Yml;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lê economies/*.yml e registra o comando e as permissões de cada economia.
 * Os nomes dos comandos vêm da config do cliente, então não dá para declará-los no plugin.yml:
 * eles entram direto no CommandMap do servidor (por reflexão, que funciona da 1.8 à mais nova).
 */
public final class CurrencyManager {

    /** Pacote global: quem tem este nó usa os comandos comuns (use, look, pay, top) de TODAS as economias. */
    private static final String PLAYER_PACK = "coreeconomias.player";
    private static final List<String> PLAYER_ACTIONS = Arrays.asList("use", "look", "pay", "top");
    private static final List<String> ADMIN_ACTIONS = Arrays.asList("add", "remove", "set", "forceupdate");

    private final EconomiasProduct plugin;
    private final Map<String, Currency> currencies = new LinkedHashMap<String, Currency>();
    private final List<CurrencyCommand> commands = new ArrayList<CurrencyCommand>();
    private final List<String> permissions = new ArrayList<String>();

    public CurrencyManager(EconomiasProduct plugin) {
        this.plugin = plugin;
    }

    /** Lê os arquivos (não registra comandos). */
    public void load() {
        currencies.clear();
        File dir = new File(plugin.getDataFolder(), "economies");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            plugin.getLogger().warning("Não foi possível criar a pasta economies/.");
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (File f : files) {
            String fileName = f.getName();
            if (!f.isFile() || !fileName.toLowerCase(Locale.ROOT).endsWith(".yml")) {
                continue;
            }
            String id = fileName.substring(0, fileName.length() - 4).toLowerCase(Locale.ROOT);
            try {
                currencies.put(id, new Currency(id, Yml.load(f)));
            } catch (Exception e) {
                plugin.getLogger().warning("Economia '" + id + "' ignorada: " + e.getMessage());
            }
        }
        if (currencies.isEmpty()) {
            plugin.getLogger().warning("Nenhuma economia encontrada em economies/.");
        }
    }

    public void registerAll() {
        CommandMap map = commandMap();
        for (Currency c : currencies.values()) {
            registerPermissions(c);
            if (map == null) {
                continue;
            }
            CurrencyCommand cmd = new CurrencyCommand(plugin, c);
            map.register("economias", cmd);
            commands.add(cmd);
        }
        if (map == null) {
            plugin.getLogger().severe("CommandMap não encontrado: os comandos das economias não foram registrados.");
        }
        syncCommands();
    }

    public void unregisterAll() {
        CommandMap map = commandMap();
        if (map != null) {
            for (CurrencyCommand cmd : commands) {
                try {
                    Field f = SimpleCommandMap.class.getDeclaredField("knownCommands");
                    f.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    Map<String, Command> known = (Map<String, Command>) f.get(map);
                    Iterator<Map.Entry<String, Command>> it = known.entrySet().iterator();
                    while (it.hasNext()) {
                        if (it.next().getValue() == cmd) {
                            it.remove();
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Não foi possível remover o comando /" + cmd.getName() + ": " + e.getMessage());
                }
                cmd.unregister(map);
            }
        }
        commands.clear();
        Permission star = Bukkit.getPluginManager().getPermission("coreeconomias.*");
        Permission players = Bukkit.getPluginManager().getPermission(PLAYER_PACK);
        for (String node : permissions) {
            if (star != null) {
                star.getChildren().remove(node);
            }
            if (players != null) {
                players.getChildren().remove(node);
            }
            Bukkit.getPluginManager().removePermission(node);
        }
        if (star != null) {
            star.recalculatePermissibles();
        }
        if (players != null) {
            players.recalculatePermissibles();
        }
        permissions.clear();
        syncCommands();
    }

    public void reload() {
        unregisterAll();
        load();
        registerAll();
    }

    public Currency get(String id) {
        return id == null ? null : currencies.get(id.toLowerCase(Locale.ROOT));
    }

    public Collection<Currency> all() {
        return Collections.unmodifiableCollection(currencies.values());
    }

    /**
     * Registra coreeconomias.<id>.<ação>, o curinga coreeconomias.<id>.* (filho de coreeconomias.*) e os pacotes de
     * jogador: coreeconomias.<id>.player (use, look, pay, top de uma economia) e coreeconomias.player (todas as economias).
     * Nenhuma ação de jogador é liberada por padrão: é preciso dar a permissão (ou um dos pacotes) ao jogador.
     * Registrar na árvore do Bukkit faz LuckPerms, PermissionsEx e afins mostrarem e resolverem os nós corretamente.
     */
    private void registerPermissions(Currency c) {
        String all = "coreeconomias." + c.getId() + ".*";
        Permission parent = Bukkit.getPluginManager().getPermission(all);
        if (parent == null) {
            parent = new Permission(all, "Todas as permissões da economia " + c.getId(), PermissionDefault.OP);
            Bukkit.getPluginManager().addPermission(parent);
            permissions.add(all);
        }
        String pack = "coreeconomias." + c.getId() + ".player";
        Permission packPerm = Bukkit.getPluginManager().getPermission(pack);
        if (packPerm == null) {
            packPerm = new Permission(pack, "Comandos de jogador da economia " + c.getId()
                    + " (use, look, pay, top)", PermissionDefault.FALSE);
            Bukkit.getPluginManager().addPermission(packPerm);
            permissions.add(pack);
        }
        for (String action : PLAYER_ACTIONS) {
            String node = "coreeconomias." + c.getId() + "." + action;
            addPermission(node, PermissionDefault.FALSE, all);
            Permission perm = Bukkit.getPluginManager().getPermission(node);
            if (perm != null) {
                perm.addParent(pack, true);
            }
        }
        for (String action : ADMIN_ACTIONS) {
            addPermission("coreeconomias." + c.getId() + "." + action, PermissionDefault.OP, all);
        }
        packPerm.addParent(PLAYER_PACK, true);
        parent.addParent("coreeconomias.*", true);
        packPerm.recalculatePermissibles();
        parent.recalculatePermissibles();
    }

    private void addPermission(String node, PermissionDefault def, String parentNode) {
        Permission perm = Bukkit.getPluginManager().getPermission(node);
        if (perm == null) {
            perm = new Permission(node, def);
            Bukkit.getPluginManager().addPermission(perm);
            permissions.add(node);
        }
        perm.addParent(parentNode, true);
    }

    /** Já existe um comando com este nome (de outro plugin ou de outra economia)? */
    public boolean commandNameTaken(String name, String selfId) {
        CommandMap map = commandMap();
        if (map == null) {
            return false;
        }
        Command existing = map.getCommand(name.toLowerCase(Locale.ROOT));
        if (existing == null) {
            return false;
        }
        return !(existing instanceof CurrencyCommand && ((CurrencyCommand) existing).currencyId().equals(selfId));
    }

    private CommandMap commandMap() {
        Object server = Bukkit.getServer();
        try {
            return (CommandMap) server.getClass().getMethod("getCommandMap").invoke(server);
        } catch (Exception ignored) {
            // versões antigas: cai para o campo
        }
        try {
            Field f = server.getClass().getDeclaredField("commandMap");
            f.setAccessible(true);
            return (CommandMap) f.get(server);
        } catch (Exception e) {
            return null;
        }
    }

    /** 1.13+: reenvia a lista de comandos aos jogadores (tab-complete). Falha silenciosa nas antigas. */
    private void syncCommands() {
        try {
            Object server = Bukkit.getServer();
            server.getClass().getMethod("syncCommands").invoke(server);
        } catch (Exception ignored) {
            // não existe nesta versão
        }
    }
}
