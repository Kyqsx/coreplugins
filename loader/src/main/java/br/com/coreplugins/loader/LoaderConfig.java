package br.com.coreplugins.loader;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Configuração do loader, lida de {@code pluginstore.properties} DENTRO do jar (preenchida no build).
 * Não existe config.yml do loader: o cliente não configura nada, e opções como
 * "desligar se negado" não ficam ao alcance dele. Só o modo debug pode ser ligado,
 * com {@code -Dpluginstore.debug=true} na JVM.
 */
public final class LoaderConfig {

    private static final String RESOURCE = "/pluginstore.properties";

    private final String apiUrl;
    private final int checkIntervalMinutes;
    private final int retryIntervalSeconds;
    private final int timeoutSeconds;
    private final boolean shutdownOnDenied;
    private final int deniedGraceMinutes;
    private final boolean debug;

    private LoaderConfig(Properties p) {
        this.apiUrl = p.getProperty("api-url", "").trim();
        this.checkIntervalMinutes = Math.max(1, intOf(p, "check-interval-minutes", 30));
        this.retryIntervalSeconds = Math.max(10, intOf(p, "retry-interval-seconds", 60));
        this.timeoutSeconds = Math.max(3, intOf(p, "timeout-seconds", 10));
        this.shutdownOnDenied = Boolean.parseBoolean(p.getProperty("shutdown-on-denied", "true").trim());
        this.deniedGraceMinutes = Math.max(0, intOf(p, "denied-grace-minutes", 5));
        this.debug = Boolean.getBoolean("pluginstore.debug");
    }

    public static LoaderConfig load() throws IOException {
        InputStream in = LoaderConfig.class.getResourceAsStream(RESOURCE);
        if (in == null) {
            throw new IOException("pluginstore.properties ausente no jar do loader");
        }
        Properties p = new Properties();
        try {
            p.load(in);
        } finally {
            in.close();
        }
        return new LoaderConfig(p);
    }

    private static int intOf(Properties p, String key, int def) {
        try {
            return Integer.parseInt(p.getProperty(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public int getCheckIntervalMinutes() {
        return checkIntervalMinutes;
    }

    public int getRetryIntervalSeconds() {
        return retryIntervalSeconds;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public boolean isShutdownOnDenied() {
        return shutdownOnDenied;
    }

    /** Minutos entre uma negação durante o jogo e o desligamento do servidor. */
    public int getDeniedGraceMinutes() {
        return deniedGraceMinutes;
    }

    public boolean isDebug() {
        return debug;
    }

    /** {@code false} se o build não recebeu uma URL real (ficou o valor padrão do pom). */
    public boolean isConfigured() {
        return !apiUrl.isEmpty() && !apiUrl.contains("${") && !apiUrl.contains("seudominio");
    }
}
