package br.com.coreplugins.loader.product;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Lembra quais pastas de produto este servidor já teve geradas pelo loader (arquivo {@code installed.txt} dentro da
 * pasta do corePlugins; é só um registro interno, não é configuração). Serve para saber se o servidor já usa plugins
 * da loja: um servidor novo, sem nenhuma pasta gerada, não deve ser desligado só porque o IP ainda não foi cadastrado.
 */
public final class InstalledProducts {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final File stateDir;
    private final File pluginsDir;
    private final Set<String> names = new LinkedHashSet<String>();

    public InstalledProducts(File stateDir, File pluginsDir) {
        this.stateDir = stateDir;
        this.pluginsDir = pluginsDir;
        File file = new File(stateDir, "installed.txt");
        if (file.isFile()) {
            try {
                List<String> lines = Files.readAllLines(file.toPath(), UTF8);
                for (String line : lines) {
                    String n = line.trim();
                    if (!n.isEmpty()) {
                        names.add(n);
                    }
                }
            } catch (IOException ignored) {
                // sem o registro, o servidor é tratado como novo
            }
        }
    }

    /** Registra a pasta de um produto que o loader acabou de gerar/usar. */
    public synchronized void add(String folderName) {
        if (!names.add(folderName)) {
            return;
        }
        try {
            if (!stateDir.exists() && !stateDir.mkdirs()) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (String n : names) {
                sb.append(n).append('\n');
            }
            Files.write(new File(stateDir, "installed.txt").toPath(), sb.toString().getBytes(UTF8));
        } catch (IOException ignored) {
            // melhor esforço: pior caso o servidor é tratado como novo no próximo boot
        }
    }

    /** true se alguma pasta de produto registrada ainda existe em plugins/. */
    public synchronized boolean anyFolderExists() {
        for (String n : names) {
            if (new File(pluginsDir, n).isDirectory()) {
                return true;
            }
        }
        return false;
    }
}
