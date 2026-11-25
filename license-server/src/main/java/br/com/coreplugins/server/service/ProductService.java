package br.com.coreplugins.server.service;

import br.com.coreplugins.server.model.Product;
import br.com.coreplugins.server.model.ProductImage;
import br.com.coreplugins.server.model.ProductJar;
import br.com.coreplugins.server.model.ProductVersion;
import br.com.coreplugins.server.repo.ProductImageRepository;
import br.com.coreplugins.server.repo.ProductJarRepository;
import br.com.coreplugins.server.repo.ProductRepository;
import br.com.coreplugins.server.repo.ProductVersionRepository;
import br.com.coreplugins.server.repo.ServerProductRepository;
import br.com.coreplugins.server.store.repo.EntitlementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Cadastro de produtos e armazenamento dos jars no PostgreSQL (tabela product_jars). */
@Service
public class ProductService {

    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private final ProductRepository products;
    private final ProductVersionRepository versions;
    private final ServerProductRepository serverProducts;
    private final EntitlementRepository entitlements;
    private final ProductJarRepository jars;
    private final ProductImageRepository images;

    /** Limites da galeria: protegem o banco (as imagens ficam no PostgreSQL) e o tempo de carregamento da página. */
    public static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    public static final int MAX_IMAGES_PER_PRODUCT = 10;

    public ProductService(ProductRepository products, ProductVersionRepository versions,
                          ServerProductRepository serverProducts, EntitlementRepository entitlements,
                          ProductJarRepository jars, ProductImageRepository images) {
        this.products = products;
        this.versions = versions;
        this.serverProducts = serverProducts;
        this.entitlements = entitlements;
        this.jars = jars;
        this.images = images;
    }

    public static void requireSafe(String value, String what) {
        if (value == null || !SAFE.matcher(value).matches() || value.contains("..")) {
            throw new IllegalArgumentException(what + " inválido (use letras, números, ponto, hífen e sublinhado)");
        }
    }

    @Transactional
    public Product create(String id, String name, String description, Integer priceCents, String category, String command) {
        requireSafe(id, "id");
        if (products.existsById(id)) {
            throw new IllegalArgumentException("Produto '" + id + "' já existe");
        }
        Product p = new Product(id, name, description);
        p.setPriceCents(priceCents);
        p.setCategory(category);
        p.setCommand(command);
        return products.save(p);
    }

    /** Recebe o jar, confere se tem plugin.yml, grava no banco e (opcionalmente) passa a ser a versão mais recente. */
    @Transactional
    public ProductVersion addVersion(String productId, String version, MultipartFile file, boolean makeLatest)
            throws IOException {
        requireSafe(version, "version");
        Product product = products.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Produto '" + productId + "' não existe"));
        if (versions.findByProductIdAndVersion(productId, version).isPresent()) {
            throw new IllegalArgumentException("Versão " + version + " já existe (versões são imutáveis)");
        }
        byte[] bytes = file.getBytes();
        if (!hasPluginYml(bytes)) {
            throw new IllegalArgumentException("O jar precisa conter plugin.yml na raiz");
        }
        ProductVersion pv = versions.save(new ProductVersion(productId, version, sha256(bytes), bytes.length));
        jars.save(new ProductJar(pv, bytes));
        if (makeLatest) {
            product.setLatestVersion(version);
        }
        return pv;
    }

    /**
     * Edita uma versão: troca o número e/ou substitui o jar. Pelo menos um dos dois precisa ser informado.
     * Quem tinha a versão antiga fixada num servidor passa a apontar para o novo número; o hash do jar novo
     * vai no próximo login do loader, então os servidores baixam o arquivo atualizado.
     * Renomear não mexe nos bytes: o jar acompanha o id da versão. Tudo acontece numa transação só.
     */
    @Transactional(rollbackFor = Exception.class)
    public ProductVersion updateVersion(String productId, String oldVersion, String newVersion, MultipartFile file)
            throws IOException {
        requireSafe(oldVersion, "version");
        Product product = products.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Produto '" + productId + "' não existe"));
        ProductVersion pv = versions.findByProductIdAndVersion(productId, oldVersion)
                .orElseThrow(() -> new IllegalArgumentException("Versão " + oldVersion + " não existe"));
        String target = (newVersion == null || newVersion.isBlank()) ? oldVersion : newVersion.trim();
        requireSafe(target, "version");
        boolean rename = !target.equals(oldVersion);
        boolean replaceJar = file != null && !file.isEmpty();
        if (!rename && !replaceJar) {
            throw new IllegalArgumentException("Nada para alterar: informe um novo número de versão e/ou um novo jar");
        }
        if (rename && versions.findByProductIdAndVersion(productId, target).isPresent()) {
            throw new IllegalArgumentException("Já existe a versão " + target);
        }
        byte[] bytes = null;
        if (replaceJar) {
            bytes = file.getBytes();
            if (!hasPluginYml(bytes)) {
                throw new IllegalArgumentException("O jar precisa conter plugin.yml na raiz");
            }
        }

        if (rename) {
            pv.setVersion(target);
            if (oldVersion.equals(product.getLatestVersion())) {
                product.setLatestVersion(target);
            }
            serverProducts.repinVersion(productId, oldVersion, target);
        }
        if (replaceJar) {
            pv.setSha256(sha256(bytes));
            pv.setSizeBytes(bytes.length);
        }
        versions.saveAndFlush(pv);

        if (replaceJar) {
            ProductJar jar = jars.findById(pv.getId()).orElseGet(() -> new ProductJar(pv, null));
            jar.setData(bytes);
            jars.save(jar);
        }
        return pv;
    }

    /** Apaga uma versão (o jar some junto, por ON DELETE CASCADE). Se era a mais recente, a mais nova das restantes assume. */
    @Transactional(rollbackFor = Exception.class)
    public void deleteVersion(String productId, String version) throws IOException {
        requireSafe(version, "version");
        Product product = products.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Produto '" + productId + "' não existe"));
        ProductVersion pv = versions.findByProductIdAndVersion(productId, version)
                .orElseThrow(() -> new IllegalArgumentException("Versão " + version + " não existe"));
        versions.delete(pv);
        versions.flush();
        if (version.equals(product.getLatestVersion())) {
            product.setLatestVersion(versions.findByProductIdOrderByCreatedAtDesc(productId).stream()
                    .map(ProductVersion::getVersion).findFirst().orElse(null));
        }
        // Servidores que tinham esta versão fixada passam a acompanhar a mais recente.
        serverProducts.repinVersion(productId, version, null);
    }

    /**
     * Apaga o produto inteiro: versões, jars, liberações nos servidores e compras dos clientes.
     * O histórico de pedidos é mantido (aparece só com o id do produto).
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteProduct(String productId) throws IOException {
        requireSafe(productId, "id");
        Product product = products.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Produto '" + productId + "' não existe"));
        serverProducts.deleteByProductId(productId);
        entitlements.deleteByProductId(productId);
        images.deleteByProductId(productId);
        versions.deleteAll(versions.findByProductIdOrderByCreatedAtDesc(productId));
        products.delete(product);
        products.flush();
    }

    /** Bytes do jar da versão {@code versionId}, direto do banco. */
    @Transactional(readOnly = true)
    public Optional<byte[]> jarBytes(Long versionId) {
        return jars.findData(versionId);
    }


    // ------------------------------------------------------------------ mídia (imagens e vídeo do YouTube)

    private static final Pattern YT_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final Pattern YT_URL = Pattern.compile(
            "^(?:https?://)?(?:www\\.|m\\.|music\\.)?(youtube\\.com|youtube-nocookie\\.com|youtu\\.be)/(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern YT_SHORT = Pattern.compile("^([A-Za-z0-9_-]{11})(?:[/?#&].*)?$");
    private static final Pattern YT_PATH = Pattern.compile("^(?:embed/|shorts/|live/|v/)([A-Za-z0-9_-]{11})(?:[/?#&].*)?$");
    private static final Pattern YT_WATCH = Pattern.compile("^watch\\?(?:.*&)?v=([A-Za-z0-9_-]{11})(?:[&#].*)?$");

    /**
     * Aceita o que o admin colar (link completo, link curto ou só o ID) e devolve o ID de 11 caracteres.
     * Vazio/nulo devolve null (= remover o vídeo). Qualquer outra coisa é rejeitada: só o ID validado é guardado e
     * é com ele que o site monta a URL do player, então nada digitado vira HTML nem URL arbitrária.
     */
    public static String parseYoutubeId(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String t = input.trim();
        if (YT_ID.matcher(t).matches()) {
            return t;
        }
        java.util.regex.Matcher m = YT_URL.matcher(t);
        if (m.matches()) {
            String host = m.group(1).toLowerCase();
            String rest = m.group(2);
            java.util.regex.Matcher id = host.equals("youtu.be") ? YT_SHORT.matcher(rest) : YT_PATH.matcher(rest);
            if (id.matches()) {
                return id.group(1);
            }
            if (!host.equals("youtu.be")) {
                java.util.regex.Matcher w = YT_WATCH.matcher(rest);
                if (w.matches()) {
                    return w.group(1);
                }
            }
        }
        throw new IllegalArgumentException("Link do YouTube inválido. Cole o endereço do vídeo (youtube.com/watch?v=… ou youtu.be/…).");
    }

    /** Descobre o tipo pelos primeiros bytes (o Content-Type enviado pelo navegador não é confiável). SVG fica de fora de propósito. */
    static String detectImageType(byte[] b) {
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return "image/png";
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8'
                && (b[4] == '7' || b[4] == '9') && b[5] == 'a') {
            return "image/gif";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    public record ImageMeta(Long id, int position, String contentType, int sizeBytes) {
    }

    private Product requireProduct(String productId) {
        return products.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Produto '" + productId + "' não existe"));
    }

    /** Valida (tipo pelos bytes, tamanho, limite por produto) e grava no fim da galeria. */
    @Transactional(rollbackFor = Exception.class)
    public ImageMeta addImage(String productId, MultipartFile file) throws IOException {
        requireProduct(productId);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Escolha uma imagem.");
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("Imagem grande demais (máximo " + (MAX_IMAGE_BYTES / 1048576) + " MB).");
        }
        if (images.countByProduct(productId) >= MAX_IMAGES_PER_PRODUCT) {
            throw new IllegalArgumentException("Limite de " + MAX_IMAGES_PER_PRODUCT + " imagens por plugin. Apague alguma antes.");
        }
        byte[] bytes = file.getBytes();
        String type = detectImageType(bytes);
        if (type == null) {
            throw new IllegalArgumentException("Formato não aceito. Envie PNG, JPG, WebP ou GIF.");
        }
        ProductImage saved = images.save(new ProductImage(productId, images.maxPosition(productId) + 1, type, bytes));
        return new ImageMeta(saved.getId(), saved.getPosition(), saved.getContentType(), saved.getSizeBytes());
    }

    @Transactional(readOnly = true)
    public List<ImageMeta> listImages(String productId) {
        requireProduct(productId);
        return images.findMeta(productId).stream()
                .map(r -> new ImageMeta((Long) r[0], ((Number) r[1]).intValue(), (String) r[2], ((Number) r[3]).intValue()))
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteImage(String productId, Long imageId) {
        requireProduct(productId);
        if (images.findIdIfOwned(productId, imageId).isEmpty()) {
            throw new IllegalArgumentException("Imagem não existe neste plugin.");
        }
        images.deleteById(imageId);
        images.flush();
        renumber(productId, images.findIds(productId));
    }

    /** Nova ordem da galeria: precisa listar exatamente as imagens do produto (nenhuma a mais, nenhuma a menos). A primeira vira a capa. */
    @Transactional(rollbackFor = Exception.class)
    public void reorderImages(String productId, List<Long> orderedIds) {
        requireProduct(productId);
        List<Long> current = images.findIds(productId);
        Set<Long> want = orderedIds == null ? Set.of() : new HashSet<>(orderedIds);
        if (orderedIds == null || want.size() != orderedIds.size() || !want.equals(new HashSet<>(current))) {
            throw new IllegalArgumentException("A ordem precisa conter exatamente as imagens do plugin.");
        }
        renumber(productId, orderedIds);
    }

    private void renumber(String productId, List<Long> ordered) {
        int pos = 0;
        for (Long id : ordered) {
            images.updatePosition(id, pos++);
        }
    }

    /** Ids das imagens de cada produto, na ordem da galeria: uma consulta só para o catálogo inteiro. */
    @Transactional(readOnly = true)
    public java.util.Map<String, List<Long>> imageIdsByProduct() {
        java.util.Map<String, List<Long>> out = new java.util.HashMap<>();
        for (Object[] r : images.findAllRefs()) {
            out.computeIfAbsent((String) r[0], k -> new java.util.ArrayList<>()).add((Long) r[1]);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<Long> imageIds(String productId) {
        return images.findIds(productId);
    }

    public record ImageContent(String contentType, byte[] data) {
    }

    @Transactional(readOnly = true)
    public Optional<ImageContent> imageContent(String productId, Long imageId) {
        List<Object[]> rows = images.findContent(productId, imageId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ImageContent((String) rows.get(0)[0], (byte[]) rows.get(0)[1]));
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

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
