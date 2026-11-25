package br.com.coreplugins.economias.util;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/** Leitura de YAML sempre em UTF-8 (a 1.8 usa o charset do sistema por padrão). */
public final class Yml {

    private Yml() {
    }

    public static YamlConfiguration load(File file) {
        YamlConfiguration yaml = new YamlConfiguration();
        if (!file.isFile()) {
            return yaml;
        }
        Reader reader = null;
        try {
            reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8);
            yaml.load(reader);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao ler " + file.getName() + ": " + e.getMessage(), e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                    // nada a fazer
                }
            }
        }
        return yaml;
    }
}
