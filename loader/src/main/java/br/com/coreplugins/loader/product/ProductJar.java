package br.com.coreplugins.loader.product;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Conteúdo de um jar de produto mantido só na memória. Nada disto é gravado em disco. */
public final class ProductJar {

    private static final long MAX_TOTAL_BYTES = 512L * 1024 * 1024; // proteção contra zip bomb
    private static final int MAX_ENTRIES = 50_000;

    private final Map<String, byte[]> entries;
    private final Set<String> directories;

    private ProductJar(Map<String, byte[]> entries, Set<String> directories) {
        this.entries = entries;
        this.directories = directories;
    }

    public static ProductJar parse(byte[] jar) throws IOException {
        Map<String, byte[]> map = new HashMap<String, byte[]>();
        Set<String> dirs = new LinkedHashSet<String>();
        long total = 0;
        ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar));
        try {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    dirs.add(e.getName());
                    continue;
                }
                if (map.size() >= MAX_ENTRIES) {
                    throw new IOException("Jar com entradas demais");
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = zip.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_TOTAL_BYTES) {
                        throw new IOException("Jar descomprimido grande demais");
                    }
                    out.write(buf, 0, n);
                }
                map.put(e.getName(), out.toByteArray());
            }
        } finally {
            zip.close();
        }
        return new ProductJar(Collections.unmodifiableMap(map), Collections.unmodifiableSet(dirs));
    }

    public byte[] get(String path) {
        return entries.get(path);
    }

    public byte[] classBytes(String className) {
        return entries.get(className.replace('.', '/') + ".class");
    }

    public byte[] pluginYml() {
        return entries.get("plugin.yml");
    }

    public InputStream open(String path) {
        byte[] b = entries.get(path);
        return b == null ? null : new ByteArrayInputStream(b);
    }

    /** Pastas vazias declaradas no jar (ex.: {@code addons/}); as com arquivos nascem junto com eles. */
    public Set<String> directories() {
        return directories;
    }

    /** Todos os caminhos de arquivos do jar. */
    public Set<String> paths() {
        return entries.keySet();
    }

    public int size() {
        return entries.size();
    }
}
