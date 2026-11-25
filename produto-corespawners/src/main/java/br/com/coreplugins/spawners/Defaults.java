package br.com.coreplugins.spawners;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Lê o arquivo padrão embutido no jar (para completar chaves que faltam no arquivo do servidor). */
public final class Defaults {

    private Defaults() {
    }

    public static YamlConfiguration load(SpawnersProduct plugin, String resource) {
        InputStream in = plugin.getResource(resource);
        if (in == null) {
            return null;
        }
        try {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // nada a fazer
            }
        }
    }
}
