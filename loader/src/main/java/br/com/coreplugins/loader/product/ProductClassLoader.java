package br.com.coreplugins.loader.product;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;

/**
 * ClassLoader que serve as classes e resources do produto direto da memória.
 *
 * <ul>
 *   <li>Classes do próprio produto: <b>child-first</b> (o produto pode embutir libs suas).</li>
 *   <li>Java, Bukkit/Spigot/Paper, a API e o loader: sempre do pai.</li>
 *   <li>Resources (config.yml, plugin.yml, messages.yml...): child-first. Sem isso o
 *       {@code getResource("config.yml")} devolveria o config do loader.</li>
 * </ul>
 */
public final class ProductClassLoader extends ClassLoader {

    static {
        ClassLoader.registerAsParallelCapable();
    }

    private static final String[] PARENT_ONLY = {
            "java.", "javax.", "jdk.", "sun.", "org.bukkit.", "org.spigotmc.", "net.md_5.", "net.minecraft.",
            "com.mojang.", "io.papermc.", "com.destroystokyo.", "net.kyori.", "org.slf4j.",
            "br.com.coreplugins.api.", "br.com.coreplugins.protocol.", "br.com.coreplugins.loader."
    };

    private final ProductJar jar;
    private final String productName;
    private volatile boolean closed;

    public ProductClassLoader(ClassLoader parent, ProductJar jar, String productName) {
        super(parent);
        this.jar = jar;
        this.productName = productName;
    }

    private static boolean parentOnly(String name) {
        for (String prefix : PARENT_ONLY) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                if (parentOnly(name)) {
                    return super.loadClass(name, resolve);
                }
                try {
                    c = findClass(name);
                } catch (ClassNotFoundException notInProduct) {
                    return super.loadClass(name, resolve);
                }
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (closed) {
            throw new ClassNotFoundException(name + " (produto " + productName + " descarregado)");
        }
        byte[] bytes = jar.classBytes(name);
        if (bytes == null) {
            throw new ClassNotFoundException(name);
        }
        return defineClass(name, bytes, 0, bytes.length);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        InputStream own = closed ? null : jar.open(normalize(name));
        return own != null ? own : super.getResourceAsStream(name);
    }

    @Override
    public URL getResource(String name) {
        URL own = findResource(name);
        return own != null ? own : super.getResource(name);
    }

    @Override
    protected URL findResource(String name) {
        if (closed) {
            return null;
        }
        final byte[] data = jar.get(normalize(name));
        if (data == null) {
            return null;
        }
        try {
            return new URL("pstore-mem", "", -1, "/" + productName + "/" + normalize(name), new URLStreamHandler() {
                @Override
                protected URLConnection openConnection(URL u) {
                    return new URLConnection(u) {
                        @Override
                        public void connect() {
                        }

                        @Override
                        public InputStream getInputStream() throws IOException {
                            return new ByteArrayInputStream(data);
                        }
                    };
                }
            });
        } catch (MalformedURLException e) {
            return null;
        }
    }

    private static String normalize(String name) {
        return name.startsWith("/") ? name.substring(1) : name;
    }

    /** Depois de fechado o loader não serve mais classes novas. */
    public void close() {
        closed = true;
    }
}
