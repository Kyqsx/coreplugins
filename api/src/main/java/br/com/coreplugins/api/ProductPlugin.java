package br.com.coreplugins.api;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Classe principal de todo produto. Substitui {@code JavaPlugin}.
 *
 * <p>O plugin registrado no servidor é o <b>loader</b> ({@link #getLoader()}). A classe principal
 * do produto <b>não</b> é um {@code Plugin}: sempre que a API do Bukkit pedir um {@code Plugin}
 * (eventos, scheduler, comandos), passe {@code getLoader()}.</p>
 *
 * <p>A superfície é parecida com a do {@code JavaPlugin} para facilitar a conversão:
 * {@code getConfig()}, {@code saveDefaultConfig()}, {@code saveResource()}, {@code getCommand()},
 * {@code getDataFolder()}, {@code getLogger()} etc.</p>
 */
public abstract class ProductPlugin {

    /** Pacote do classloader do loader. Usado em {@link #isLoadedByLoader()}. */
    public static final String LOADER_CLASSLOADER_PREFIX = "br.com.coreplugins.loader";

    private static final Map<Class<?>, ProductPlugin> INSTANCES = new ConcurrentHashMap<Class<?>, ProductPlugin>();

    private JavaPlugin loader;
    private PluginDescriptionFile description;
    private File dataFolder;
    private Logger logger;
    private Map<String, PluginCommand> commands = Collections.emptyMap();
    private final List<Listener> listeners = new ArrayList<Listener>();

    private File configFile;
    private FileConfiguration config;
    private volatile boolean enabled;

    // ------------------------------------------------------------------ ciclo de vida

    public void onLoad() {
    }

    public void onEnable() {
    }

    public void onDisable() {
    }

    // ------------------------------------------------------------------ uso interno do loader

    /** INTERNO: chamado pelo loader antes de {@link #onLoad()}. Não chame no produto. */
    public final void attach(JavaPlugin loader, PluginDescriptionFile description, File dataFolder,
                             Map<String, PluginCommand> commands) {
        if (this.loader != null) {
            throw new IllegalStateException("Produto já anexado ao loader");
        }
        this.loader = loader;
        this.description = description;
        this.dataFolder = dataFolder;
        this.commands = new HashMap<String, PluginCommand>(commands);
        this.logger = new ProductLogger(description.getName(), loader.getLogger());
        INSTANCES.put(getClass(), this);
    }

    /** INTERNO: marca o estado habilitado. */
    public final void markEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** INTERNO: solta listeners e a instância estática. Chamado pelo loader depois de {@link #onDisable()}. */
    public final void releaseResources() {
        for (Listener l : listeners) {
            HandlerList.unregisterAll(l);
        }
        listeners.clear();
        INSTANCES.remove(getClass());
    }

    // ------------------------------------------------------------------ API parecida com JavaPlugin

    /** O {@code JavaPlugin} real. Use em tudo que o Bukkit pedir um {@code Plugin}. */
    public final JavaPlugin getLoader() {
        return loader;
    }

    public final File getDataFolder() {
        return dataFolder;
    }

    public final Logger getLogger() {
        return logger;
    }

    public final Server getServer() {
        return Bukkit.getServer();
    }

    public final String getName() {
        return description.getName();
    }

    public final String getVersion() {
        return description.getVersion();
    }

    public final PluginDescriptionFile getDescription() {
        return description;
    }

    public final boolean isEnabled() {
        return enabled;
    }

    /**
     * Comando declarado em {@code commands:} do plugin.yml do produto. O loader já o registrou
     * com o loader como dono; defina o executor aqui. Retorna {@code null} se não foi declarado.
     */
    public final PluginCommand getCommand(String name) {
        return commands.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Registra um listener com o loader como dono e guarda a referência para que ele seja
     * removido se a licença for revogada em tempo de execução.
     */
    public final void registerEvents(Listener listener) {
        Bukkit.getPluginManager().registerEvents(listener, loader);
        listeners.add(listener);
    }

    /** {@code true} se esta classe foi carregada pelo loader (e não solta em plugins/). */
    public final boolean isLoadedByLoader() {
        String cl = getClass().getClassLoader().getClass().getName();
        return cl.startsWith(LOADER_CLASSLOADER_PREFIX);
    }

    @SuppressWarnings("unchecked")
    public static <T extends ProductPlugin> T getPlugin(Class<T> type) {
        ProductPlugin p = INSTANCES.get(type);
        if (p == null) {
            throw new IllegalStateException(type.getName() + " não está carregado");
        }
        return (T) p;
    }

    // ------------------------------------------------------------------ config.yml e resources

    public FileConfiguration getConfig() {
        if (config == null) {
            reloadConfig();
        }
        return config;
    }

    public void reloadConfig() {
        configFile = new File(dataFolder, "config.yml");
        config = YamlConfiguration.loadConfiguration(configFile);
        InputStream def = getResource("config.yml");
        if (def != null) {
            try {
                config.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(def, StandardCharsets.UTF_8)));
            } finally {
                try {
                    def.close();
                } catch (IOException ignored) {
                    // nada a fazer
                }
            }
        }
    }

    public void saveConfig() {
        try {
            getConfig().save(configFile);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Não foi possível salvar " + configFile, e);
        }
    }

    public void saveDefaultConfig() {
        if (!new File(dataFolder, "config.yml").exists()) {
            saveResource("config.yml", false);
        }
    }

    /** Resource dentro do jar do produto (o loader serve o jar da memória). */
    public InputStream getResource(String path) {
        return getClass().getClassLoader().getResourceAsStream(path);
    }

    public void saveResource(String path, boolean replace) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("path vazio");
        }
        path = path.replace('\\', '/');
        File out = new File(dataFolder, path);
        // evita sair da pasta de dados com "../"
        try {
            if (!out.getCanonicalPath().startsWith(dataFolder.getCanonicalPath() + File.separator)) {
                throw new IllegalArgumentException("path fora da pasta de dados: " + path);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
        if (out.exists() && !replace) {
            return;
        }
        InputStream in = getResource(path);
        if (in == null) {
            throw new IllegalArgumentException("Resource '" + path + "' não existe no jar do produto");
        }
        File parent = out.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Não foi possível criar " + parent);
        }
        try {
            OutputStream os = new FileOutputStream(out);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            } finally {
                os.close();
                in.close();
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Não foi possível salvar " + out, e);
        }
    }

    // ------------------------------------------------------------------ logger

    /** O servidor já mostra o nome do logger como prefixo ("[Exemplo]"), então não se soma outro. */
    private static final class ProductLogger extends Logger {
        ProductLogger(String name, Logger parent) {
            super(name, null);
            setParent(parent);
            setLevel(Level.ALL);
        }
    }
}
