package br.com.coreplugins.loader.product;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Gera a pasta de dados do produto (plugins/NomeDoProduto/) a partir do jar, como a yPlugins faz:
 * {@code config.yml}, {@code messages.yml}, {@code menus/...} etc. já aparecem prontos para o cliente editar.
 *
 * <p>Regras: arquivo que já existe <b>nunca</b> é sobrescrito (as edições do cliente ficam); não se extrai
 * {@code .class}, {@code META-INF/}, os descritores de plugin nem a pasta {@code ky-internal/} (o produto
 * pode guardar ali recursos internos que o cliente não deve ver).</p>
 */
public final class DataFolderSeeder {

    /** Prefixo reservado para recursos internos do produto, que nunca vão para a pasta de dados. */
    public static final String INTERNAL_PREFIX = "ky-internal/";

    private DataFolderSeeder() {
    }

    public static void seed(ProductJar jar, File dataFolder, Logger log) {
        for (String dir : jar.directories()) {
            if (shouldExtract(dir + "x")) {
                File d = safe(dataFolder, dir);
                if (d != null && !d.exists() && !d.mkdirs()) {
                    log.warning("Não foi possível criar a pasta " + d);
                }
            }
        }
        for (String path : jar.paths()) {
            if (!shouldExtract(path)) {
                continue;
            }
            File out = safe(dataFolder, path);
            if (out == null || out.exists()) {
                continue;
            }
            try {
                File parent = out.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("não foi possível criar " + parent);
                }
                OutputStream os = new FileOutputStream(out);
                try {
                    os.write(jar.get(path));
                } finally {
                    os.close();
                }
            } catch (IOException e) {
                log.log(Level.WARNING, "Não foi possível gerar " + out + ": " + e.getMessage());
            }
        }
    }

    static boolean shouldExtract(String path) {
        if (path.endsWith(".class") || path.startsWith("META-INF/") || path.startsWith(INTERNAL_PREFIX)) {
            return false;
        }
        return !(path.equals("plugin.yml") || path.equals("paper-plugin.yml")
                || path.equals("bungee.yml") || path.equals("velocity-plugin.json"));
    }

    /** Resolve dentro da pasta de dados; recusa {@code ..}, caminho absoluto e qualquer coisa que escape dela. */
    private static File safe(File dataFolder, String path) {
        if (path.startsWith("/") || path.contains("..") || path.contains("\\") || path.indexOf(':') >= 0) {
            return null;
        }
        File out = new File(dataFolder, path);
        try {
            String base = dataFolder.getCanonicalPath() + File.separator;
            return out.getCanonicalPath().startsWith(base) ? out : null;
        } catch (IOException e) {
            return null;
        }
    }
}
