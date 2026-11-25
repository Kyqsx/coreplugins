package br.com.coreplugins.loader;

import br.com.coreplugins.loader.command.LoaderCommand;
import br.com.coreplugins.loader.license.AuthResult;
import br.com.coreplugins.loader.license.LicenseClient;
import br.com.coreplugins.loader.license.SignatureVerifier;
import br.com.coreplugins.loader.product.ProductManager;
import br.com.coreplugins.protocol.Protocol;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * O plugin que o servidor enxerga. Autentica o IP:porta no license-server e carrega os produtos liberados
 * a partir da memória. Os produtos estendem {@code ProductPlugin} e usam {@link #getLoader()} como dono de
 * eventos, tasks e comandos.
 */
public final class LoaderPlugin extends JavaPlugin {

    private LoaderConfig cfg;
    private LicenseClient client;
    private ProductManager products;
    private int port;
    private BukkitTask timer;
    private BukkitTask pendingShutdown;

    private volatile String lastAddress = "(ainda não verificado)";
    private volatile String lastStatus = "não verificado";
    private volatile long lastCheckMillis;
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private volatile boolean wasOk;

    @Override
    public void onEnable() {
        migrateLegacyFolder();
        try {
            cfg = LoaderConfig.load();
        } catch (IOException e) {
            getLogger().severe("Jar do loader corrompido: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        port = getServer().getPort();

        if (!cfg.isConfigured()) {
            getLogger().severe("Este corePlugins.jar foi compilado sem a URL do license-server. "
                    + "Recompile com -Dlicense.api.url=https://sua-api.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        SignatureVerifier verifier;
        try {
            verifier = SignatureVerifier.fromResource();
        } catch (Exception e) {
            getLogger().severe("Chave pública do license-server inválida: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        client = new LicenseClient(cfg.getApiUrl(), verifier, cfg.getTimeoutSeconds() * 1000, getDescription().getVersion());
        products = new ProductManager(this);

        if (getCommand("coreplugins") != null) {
            getCommand("coreplugins").setExecutor(new LoaderCommand(this));
        }

        // Autenticação inicial, síncrona (como o yPlugins: o servidor sobe já sabendo se está liberado).
        try {
            final Fetched f = fetch(false);
            // Habilita os produtos 1 tick depois: os outros plugins (softdepend) já terminaram de carregar.
            getServer().getScheduler().runTask(this, new Runnable() {
                @Override
                public void run() {
                    apply(f, true);
                }
            });
        } catch (IOException e) {
            getLogger().warning("License-server indisponível (" + e.getMessage() + "). Tentando de novo em "
                    + cfg.getRetryIntervalSeconds() + "s. O servidor continua ligado.");
            scheduleStartupRetry();
        }

        long period = cfg.getCheckIntervalMinutes() * 60L * 20L;
        timer = getServer().getScheduler().runTaskTimerAsynchronously(this, new Runnable() {
            @Override
            public void run() {
                refreshAsync(false, false);
            }
        }, period, period);
    }

    @Override
    public void onDisable() {
        if (timer != null) {
            timer.cancel();
        }
        if (pendingShutdown != null) {
            pendingShutdown.cancel();
        }
        if (products != null) {
            products.disableAll("servidor desligando");
        }
    }

    // ------------------------------------------------------------------ autenticação

    private static final class Fetched {
        final AuthResult result;
        final Map<String, byte[]> jars;

        Fetched(AuthResult result, Map<String, byte[]> jars) {
            this.result = result;
            this.jars = jars;
        }
    }

    /**
     * O plugin se chamava kyPlugins: move plugins/kyPlugins para plugins/corePlugins (uma única vez) para
     * não perder o registro de produtos instalados.
     */
    private void migrateLegacyFolder() {
        java.io.File dir = getDataFolder();
        java.io.File legacy = new java.io.File(dir.getParentFile(), "kyPlugins");
        if (!dir.exists() && legacy.isDirectory()) {
            if (legacy.renameTo(dir)) {
                getLogger().info("Pasta migrada: plugins/kyPlugins -> plugins/" + dir.getName());
            } else {
                getLogger().warning("Não foi possível renomear plugins/kyPlugins para plugins/" + dir.getName()
                        + ". Mova a pasta manualmente para manter os dados.");
            }
        }
    }

    /**
     * Rede apenas. Pode rodar em qualquer thread. {@code manual} (comando /coreplugins reload) também baixa o jar
     * de um produto já rodando cuja versão escolhida no site mudou; a verificação automática nunca troca código em execução.
     */
    private Fetched fetch(boolean manual) throws IOException {
        AuthResult r = client.authenticate(port);
        lastAddress = r.getAddress();
        lastStatus = r.getStatus();
        lastCheckMillis = System.currentTimeMillis();

        Map<String, byte[]> jars = new HashMap<String, byte[]>();
        if (r.isOk()) {
            for (Protocol.Grant g : r.getProducts()) {
                boolean needsJar = !products.isLoaded(g.getId()) || (manual && !products.isSameVersion(g));
                if (!needsJar) {
                    continue; // já rodando na versão certa (ou troca só em /coreplugins reload)
                }
                try {
                    jars.put(g.getId(), client.download(g, port));
                } catch (IOException e) {
                    getLogger().warning("Não foi possível baixar " + g.getId() + ": " + e.getMessage());
                }
            }
        }
        return new Fetched(r, jars);
    }

    /** Thread principal. */
    private void apply(Fetched f, boolean startup) {
        AuthResult r = f.result;
        if (!r.isOk()) {
            // Só desliga quem já usa plugins da loja (tem pasta de produto gerada). Servidor novo apenas avisa.
            boolean fresh = !products.hasGeneratedFolders();
            boolean shutdownNow = startup && cfg.isShutdownOnDenied() && !fresh;
            if (fresh) {
                getLogger().warning("Autenticação negada (" + r.getStatus() + ": " + r.getAddress() + "). Nenhum plugin da loja "
                        + "foi instalado neste servidor ainda, então ele continua ligado.");
            } else {
                getLogger().severe("Falha na autenticação (" + r.getStatus() + ": " + r.getAddress() + ")"
                        + (shutdownNow ? ", desligando servidor...." : ""));
            }
            String hint = Protocol.STATUS_REVOKED.equals(r.getStatus())
                    ? "A licença deste servidor está suspensa. Fale com o suporte da loja."
                    : "Cadastre " + r.getAddress() + " na Área do Cliente e marque os plugins desse servidor.";
            if (fresh) {
                getLogger().warning(hint);
            } else {
                getLogger().severe(hint);
            }
            products.disableAll("autenticação negada (" + r.getStatus() + ")");
            wasOk = false;
            if (shutdownNow) {
                getServer().shutdown();
            } else if (!startup && cfg.isShutdownOnDenied() && !fresh) {
                scheduleGraceShutdown();
            }
            return;
        }
        cancelGraceShutdown();
        if (!wasOk) {
            getLogger().info("Servidor autenticado: " + r.getAddress() + " (" + r.getProducts().size() + " produto(s) liberado(s))");
            wasOk = true;
        }
        products.reconcile(r.getProducts(), f.jars);
    }

    /**
     * Negação com o servidor já no ar: os produtos já foram desabilitados; o servidor só desliga depois da
     * carência (jogadores e o dono têm tempo de regularizar). Uma verificação bem-sucedida cancela o desligamento.
     */
    private void scheduleGraceShutdown() {
        if (pendingShutdown != null) {
            return;
        }
        final int minutes = cfg.getDeniedGraceMinutes();
        if (minutes <= 0) {
            getLogger().severe("Desligando servidor....");
            getServer().shutdown();
            return;
        }
        getLogger().severe("O servidor será desligado em " + minutes + " minuto(s) se a licença não for regularizada.");
        pendingShutdown = getServer().getScheduler().runTaskLater(this, new Runnable() {
            @Override
            public void run() {
                getLogger().severe("Carência encerrada, desligando servidor....");
                getServer().shutdown();
            }
        }, minutes * 60L * 20L);
    }

    private void cancelGraceShutdown() {
        if (pendingShutdown != null) {
            pendingShutdown.cancel();
            pendingShutdown = null;
            getLogger().info("Licença regularizada. Desligamento cancelado.");
        }
    }

    /** Verificação periódica / manual. Rede em thread assíncrona, mudanças na principal. */
    public void refreshAsync(final boolean startup, final boolean manual) {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        getServer().getScheduler().runTaskAsynchronously(this, new Runnable() {
            @Override
            public void run() {
                try {
                    final Fetched f = fetch(manual);
                    getServer().getScheduler().runTask(LoaderPlugin.this, new Runnable() {
                        @Override
                        public void run() {
                            apply(f, startup);
                        }
                    });
                } catch (IOException e) {
                    // Falha de rede ou resposta não verificada NÃO derruba nada.
                    if (cfg.isDebug()) {
                        getLogger().warning("Verificação falhou: " + e);
                    } else {
                        getLogger().warning("Não foi possível verificar a licença agora (" + e.getMessage() + "). Mantendo o estado atual.");
                    }
                } finally {
                    refreshing.set(false);
                }
            }
        });
    }

    private void scheduleStartupRetry() {
        getServer().getScheduler().runTaskLaterAsynchronously(this, new Runnable() {
            @Override
            public void run() {
                try {
                    final Fetched f = fetch(false);
                    getServer().getScheduler().runTask(LoaderPlugin.this, new Runnable() {
                        @Override
                        public void run() {
                            apply(f, true);
                        }
                    });
                } catch (IOException e) {
                    getLogger().warning("License-server ainda indisponível (" + e.getMessage() + ").");
                    scheduleStartupRetry();
                }
            }
        }, cfg.getRetryIntervalSeconds() * 20L);
    }

    // ------------------------------------------------------------------ usado pelo /coreplugins

    public ProductManager getProducts() {
        return products;
    }

    public String getLastAddress() {
        return lastAddress;
    }

    public String getLastStatus() {
        return lastStatus;
    }

    public long getLastCheckMillis() {
        return lastCheckMillis;
    }

    public LoaderConfig getLoaderConfig() {
        return cfg;
    }
}
