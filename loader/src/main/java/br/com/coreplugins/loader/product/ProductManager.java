package br.com.coreplugins.loader.product;

import br.com.coreplugins.api.ProductPlugin;
import br.com.coreplugins.protocol.Protocol;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Carrega, habilita e descarrega os produtos. <b>Só use na thread principal.</b>
 */
public final class ProductManager {

    private final JavaPlugin loader;
    // ConcurrentHashMap: a thread assíncrona consulta isLoaded/isSameVersion enquanto a principal altera
    private final Map<String, LoadedProduct> loaded = new ConcurrentHashMap<String, LoadedProduct>();
    /** Versões pendentes já avisadas (evita repetir o aviso a cada verificação). */
    private final Set<String> notified = new HashSet<String>();

    private final InstalledProducts installed;

    public ProductManager(JavaPlugin loader) {
        this.loader = loader;
        this.installed = new InstalledProducts(loader.getDataFolder(), loader.getDataFolder().getParentFile());
    }

    /** O servidor já tem alguma pasta de produto gerada pelo loader? (false = instalação nova do corePlugins) */
    public boolean hasGeneratedFolders() {
        return installed.anyFolderExists();
    }

    /**
     * Aplica o resultado de uma autenticação: habilita o que foi liberado e ainda não está rodando,
     * desabilita o que deixou de ser liberado, avisa sobre versões novas.
     */
    public void reconcile(List<Protocol.Grant> grants, Map<String, byte[]> jars) {
        Set<String> granted = new HashSet<String>();
        for (Protocol.Grant g : grants) {
            granted.add(g.getId());
        }
        // revogados / expirados
        for (String id : new ArrayList<String>(loaded.keySet())) {
            if (!granted.contains(id)) {
                disable(id, "licença revogada ou expirada");
            }
        }
        // liberados
        for (Protocol.Grant g : grants) {
            LoadedProduct current = loaded.get(g.getId());
            if (current != null) {
                if (!current.sha256.equalsIgnoreCase(g.getSha256())) {
                    byte[] newJar = jars.get(g.getId());
                    if (newJar != null) {
                        // /coreplugins reload: o cliente pediu para aplicar a versão escolhida no site
                        loader.getLogger().info("Trocando " + g.getId() + " " + current.version + " -> " + g.getVersion()
                                + " sem reiniciar o servidor.");
                        disable(g.getId(), "troca de versão");
                        enable(g, newJar);
                    } else if (notified.add(g.getId() + ":" + g.getSha256())) {
                        loader.getLogger().info("Versão escolhida para " + g.getId() + ": " + g.getVersion()
                                + " (rodando " + current.version + "). Aplique com /coreplugins reload ou reinicie o servidor.");
                    }
                }
                continue;
            }
            byte[] jar = jars.get(g.getId());
            if (jar == null) {
                loader.getLogger().warning("Jar de " + g.getId() + " não foi baixado; tentarei de novo na próxima verificação.");
                continue;
            }
            enable(g, jar);
        }
    }

    public boolean isLoaded(String id) {
        return loaded.containsKey(id);
    }

    public boolean isSameVersion(Protocol.Grant g) {
        LoadedProduct p = loaded.get(g.getId());
        return p != null && p.sha256.equalsIgnoreCase(g.getSha256());
    }

    public Collection<String> describe() {
        List<String> out = new ArrayList<String>();
        for (LoadedProduct p : loaded.values()) {
            out.add(p.description.getName() + " v" + p.version + (p.plugin.isEnabled() ? "" : " (desabilitado)"));
        }
        return out;
    }

    // ------------------------------------------------------------------ enable

    private void enable(Protocol.Grant grant, byte[] jarBytes) {
        ProductClassLoader cl = null;
        ProductPlugin plugin = null;
        Map<String, PluginCommand> commands = new java.util.LinkedHashMap<String, PluginCommand>();
        List<Permission> permissions = new ArrayList<Permission>();
        try {
            ProductJar jar = ProductJar.parse(jarBytes);
            byte[] yml = jar.pluginYml();
            if (yml == null) {
                throw new IllegalStateException("jar sem plugin.yml");
            }
            PluginDescriptionFile desc = new PluginDescriptionFile(new ByteArrayInputStream(yml));

            for (String dep : desc.getDepend()) {
                if (Bukkit.getPluginManager().getPlugin(dep) == null) {
                    throw new IllegalStateException("depende do plugin '" + dep + "', que não está instalado");
                }
            }

            cl = new ProductClassLoader(loader.getClass().getClassLoader(), jar, desc.getName());
            Class<?> main = Class.forName(desc.getMain(), true, cl);
            if (!ProductPlugin.class.isAssignableFrom(main)) {
                throw new IllegalStateException(desc.getMain() + " precisa estender ProductPlugin");
            }
            plugin = (ProductPlugin) main.getDeclaredConstructor().newInstance();

            File dataFolder = new File(loader.getDataFolder().getParentFile(), desc.getName());
            migrateLegacyDataFolder(dataFolder, desc.getName());
            if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                throw new IllegalStateException("não foi possível criar " + dataFolder);
            }

            // Como a yPlugins: a pasta do produto nasce já com todos os arquivos padrão do jar (sem sobrescrever).
            DataFolderSeeder.seed(jar, dataFolder, loader.getLogger());
            installed.add(desc.getName());

            commands.putAll(CommandRegistrar.register(loader, desc));
            for (Permission p : desc.getPermissions()) {
                if (Bukkit.getPluginManager().getPermission(p.getName()) == null) {
                    Bukkit.getPluginManager().addPermission(p);
                    permissions.add(p);
                }
            }

            plugin.attach(loader, desc, dataFolder, commands);

            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(cl);
            try {
                plugin.onLoad();
                plugin.onEnable();
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
            plugin.markEnabled(true);
            bindDefaultExecutor(plugin, commands.values());

            loaded.put(grant.getId(), new LoadedProduct(grant.getId(), desc.getVersion(), grant.getSha256(),
                    plugin, cl, desc, commands, permissions));
            loader.getLogger().info("Produto habilitado: " + desc.getName() + " v" + desc.getVersion());
        } catch (Throwable t) {
            loader.getLogger().log(Level.SEVERE, "Falha ao habilitar o produto '" + grant.getId() + "'", t);
            cleanupFailed(plugin, cl, commands.values(), permissions);
        }
    }

    /** Se a classe principal implementa CommandExecutor/TabCompleter e o comando ficou sem executor, liga automaticamente. */
    private void bindDefaultExecutor(ProductPlugin plugin, Collection<PluginCommand> commands) {
        for (PluginCommand cmd : commands) {
            if (cmd.getExecutor() == loader && plugin instanceof CommandExecutor) {
                cmd.setExecutor((CommandExecutor) plugin);
            }
            if (cmd.getTabCompleter() == null && plugin instanceof TabCompleter) {
                cmd.setTabCompleter((TabCompleter) plugin);
            }
        }
    }

    private void cleanupFailed(ProductPlugin plugin, ProductClassLoader cl, Collection<PluginCommand> commands,
                               List<Permission> permissions) {
        try {
            if (plugin != null && plugin.getLoader() != null) {
                plugin.markEnabled(false);
                plugin.releaseResources();
            }
        } catch (Throwable ignored) {
            // limpeza de melhor esforço
        }
        CommandRegistrar.unregister(commands);
        for (Permission p : permissions) {
            Bukkit.getPluginManager().removePermission(p);
        }
        if (cl != null) {
            cl.close();
        }
    }

    // ------------------------------------------------------------------ disable

    public void disable(String id, String reason) {
        LoadedProduct p = loaded.remove(id);
        if (p == null) {
            return;
        }
        loader.getLogger().warning("Desabilitando " + p.description.getName() + ": " + reason);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(p.classLoader);
        try {
            p.plugin.onDisable();
        } catch (Throwable t) {
            loader.getLogger().log(Level.SEVERE, "Erro no onDisable de " + p.description.getName(), t);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
        cleanupFailed(p.plugin, p.classLoader, p.commands.values(), p.permissions);
    }

    public void disableAll(String reason) {
        for (String id : new ArrayList<String>(loaded.keySet())) {
            disable(id, reason);
        }
    }

    /** Produtos que mudaram de nome na troca de marca: a pasta de dados antiga é movida para a nova. */
    private static final java.util.Map<String, String> LEGACY_FOLDERS = new java.util.HashMap<String, String>();

    static {
        LEGACY_FOLDERS.put("coreEconomias", "kyEconomias");
    }

    private void migrateLegacyDataFolder(File dataFolder, String name) {
        String legacyName = LEGACY_FOLDERS.get(name);
        if (legacyName == null || dataFolder.exists()) {
            return;
        }
        File legacy = new File(dataFolder.getParentFile(), legacyName);
        if (!legacy.isDirectory()) {
            return;
        }
        if (legacy.renameTo(dataFolder)) {
            loader.getLogger().info("Pasta de dados migrada: plugins/" + legacyName + " -> plugins/" + name);
        } else {
            loader.getLogger().warning("Não foi possível renomear plugins/" + legacyName + " para plugins/" + name
                    + ". Mova a pasta manualmente para manter a configuração.");
        }
    }
}
