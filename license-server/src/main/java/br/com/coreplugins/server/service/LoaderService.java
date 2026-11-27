package br.com.coreplugins.server.service;

import br.com.coreplugins.server.model.LoaderJar;
import br.com.coreplugins.server.repo.LoaderJarRepository;
import br.com.coreplugins.server.store.StoreProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * O corePlugins.jar que o cliente baixa. Fonte principal: o jar enviado pelo admin e guardado no banco (funciona em qualquer
 * hospedagem, com várias instâncias e sem depender de disco). Sem upload, cai para um arquivo em disco
 * ({@code store.loader-jar-path} ou {@code loader/target/corePlugins.jar}), o que basta para desenvolver localmente.
 */
@Service
public class LoaderService {

    /** {@code source}: "database", "disk" ou null (indisponível). */
    public record Info(boolean available, long sizeBytes, String sha256, Instant updatedAt, String source) {
        static Info none() {
            return new Info(false, 0, null, null, null);
        }
    }

    private final LoaderJarRepository repo;
    private final StoreProperties props;

    public LoaderService(LoaderJarRepository repo, StoreProperties props) {
        this.repo = repo;
        this.props = props;
    }

    public Info info() {
        Optional<LoaderJarRepository.Meta> m = repo.findMetaById(LoaderJar.ID);
        if (m.isPresent()) {
            return new Info(true, m.get().getSizeBytes(), m.get().getSha256(), m.get().getUpdatedAt(), "database");
        }
        Path f = diskJar();
        if (f == null) {
            return Info.none();
        }
        try {
            return new Info(true, Files.size(f), null, Files.getLastModifiedTime(f).toInstant(), "disk");
        } catch (IOException e) {
            return Info.none();
        }
    }

    public Optional<byte[]> bytes() throws IOException {
        Optional<byte[]> db = repo.findData();
        if (db.isPresent()) {
            return db;
        }
        Path f = diskJar();
        return f == null ? Optional.empty() : Optional.of(Files.readAllBytes(f));
    }

    /** Guarda o jar enviado (substitui o anterior). Exige um jar válido com plugin.yml, como o loader é. */
    @Transactional
    public Info upload(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Escolha o arquivo corePlugins.jar");
        }
        byte[] bytes = file.getBytes();
        if (!hasPluginYml(bytes)) {
            throw new IllegalArgumentException("O arquivo precisa ser um .jar com plugin.yml na raiz");
        }
        String sha = sha256(bytes);
        LoaderJar row = repo.findById(LoaderJar.ID).orElse(null);
        if (row == null) {
            repo.save(new LoaderJar(bytes, sha));
        } else {
            row.replace(bytes, sha);
        }
        return new Info(true, bytes.length, sha, Instant.now(), "database");
    }

    private Path diskJar() {
        List<String> candidates = new ArrayList<>();
        if (!props.loaderJarPath().isBlank()) {
            candidates.add(props.loaderJarPath().trim());
        }
        candidates.add("loader/target/corePlugins.jar");
        candidates.add("../loader/target/corePlugins.jar");
        for (String c : candidates) {
            Path p = Path.of(c);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    private static boolean hasPluginYml(byte[] jar) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.getName().equals("plugin.yml")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
