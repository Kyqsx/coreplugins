package br.com.coreplugins.spawners.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.Properties;

/** Qual build está rodando: versão e hora de compilação (build.properties) e o jar de onde o código foi carregado. */
public final class BuildInfo {

    public final String version;
    public final String built;
    public final String jarName;
    public final String jarPath;
    public final long jarSize;
    public final long jarModified;
    public final String jarSha;

    private BuildInfo(String version, String built, String jarName, String jarPath, long jarSize, long jarModified,
                      String jarSha) {
        this.version = version;
        this.built = built;
        this.jarName = jarName;
        this.jarPath = jarPath;
        this.jarSize = jarSize;
        this.jarModified = jarModified;
        this.jarSha = jarSha;
    }

    /** {@code props}: o build.properties embutido no jar (plugin.getResource), ou null. */
    public static BuildInfo read(Class<?> anchor, InputStream in) {
        String version = "?";
        String built = "?";
        if (in != null) {
            try {
                Properties p = new Properties();
                p.load(in);
                version = clean(p.getProperty("version"));
                built = clean(p.getProperty("built"));
            } catch (IOException ignored) {
                // fica "?"
            } finally {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // nada a fazer
                }
            }
        }
        String name = "?";
        String path = "?";
        long size = -1L;
        long modified = -1L;
        String sha = "?";
        try {
            ProtectionDomain pd = anchor.getProtectionDomain();
            if (pd != null && pd.getCodeSource() != null && pd.getCodeSource().getLocation() != null) {
                File f = new File(pd.getCodeSource().getLocation().toURI());
                if (f.isFile()) {
                    name = f.getName();
                    path = f.getAbsolutePath();
                    size = f.length();
                    modified = f.lastModified();
                    sha = sha256(f);
                }
            }
        } catch (URISyntaxException ignored) {
            // fica "?"
        } catch (RuntimeException ignored) {
            // fica "?"
        }
        return new BuildInfo(version, built, name, path, size, modified, sha);
    }

    /** Valor ainda com ${...} = o jar não passou pelo filtro do Maven (compilado fora dele). */
    private static String clean(String v) {
        if (v == null || v.trim().isEmpty() || v.contains("${")) {
            return "?";
        }
        return v.trim();
    }

    private static String sha256(File f) {
        InputStream in = null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            in = new FileInputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 12);
        } catch (Exception e) {
            return "?";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // nada a fazer
                }
            }
        }
    }
}
