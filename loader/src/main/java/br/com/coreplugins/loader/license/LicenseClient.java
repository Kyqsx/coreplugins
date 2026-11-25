package br.com.coreplugins.loader.license;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import br.com.coreplugins.protocol.Protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Cliente HTTP do license-server. Não depende do Bukkit. */
public final class LicenseClient {

    private static final int MAX_JSON_BYTES = 1024 * 1024;
    private static final int MAX_JAR_BYTES = 128 * 1024 * 1024;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final String baseUrl;
    private final SignatureVerifier verifier;
    private final int timeoutMillis;
    private final String loaderVersion;
    private final SecureRandom random = new SecureRandom();

    public LicenseClient(String baseUrl, SignatureVerifier verifier, int timeoutMillis, String loaderVersion) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.verifier = verifier;
        this.timeoutMillis = timeoutMillis;
        this.loaderVersion = loaderVersion;
    }

    /**
     * Pergunta ao servidor quais produtos este IP:porta pode usar.
     *
     * @throws IOException falha de rede ou resposta que não passou na verificação ({@link LicenseException})
     */
    public AuthResult authenticate(int port) throws IOException {
        String nonce = newNonce();
        JsonObject body = new JsonObject();
        body.addProperty("port", port);
        body.addProperty("nonce", nonce);
        body.addProperty("loader", loaderVersion);

        HttpURLConnection c = open("/api/v1/auth", "POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        OutputStream os = c.getOutputStream();
        try {
            os.write(payload);
        } finally {
            os.close();
        }

        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) {
            throw new IOException("Resposta vazia do license-server (HTTP " + code + ")");
        }
        String text = new String(readAll(in, MAX_JSON_BYTES), StandardCharsets.UTF_8);

        JsonObject json;
        try {
            JsonElement el = new JsonParser().parse(text);
            if (!el.isJsonObject()) {
                throw new LicenseException("Resposta não é um objeto JSON (HTTP " + code + ")");
            }
            json = el.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new LicenseException("Resposta inválida do license-server (HTTP " + code + ")");
        }
        return verify(json, nonce, System.currentTimeMillis());
    }

    /** Valida assinatura, nonce e relógio. Separado de {@link #authenticate} para ser testável. */
    AuthResult verify(JsonObject json, String expectedNonce, long nowMillis) throws LicenseException {
        String status = str(json, "status");
        String address = str(json, "address");
        String nonce = str(json, "nonce");
        String signature = str(json, "signature");
        if (status == null || address == null || nonce == null || signature == null || !json.has("issuedAt")) {
            throw new LicenseException("Resposta sem campos obrigatórios");
        }
        long issuedAt;
        try {
            issuedAt = json.get("issuedAt").getAsLong();
        } catch (RuntimeException e) {
            throw new LicenseException("issuedAt inválido");
        }

        List<Protocol.Grant> grants = new ArrayList<Protocol.Grant>();
        if (json.has("products") && json.get("products").isJsonArray()) {
            JsonArray arr = json.getAsJsonArray("products");
            for (JsonElement el : arr) {
                JsonObject p = el.getAsJsonObject();
                String id = str(p, "id");
                String version = str(p, "version");
                String sha = str(p, "sha256");
                if (id == null || version == null || sha == null) {
                    throw new LicenseException("Produto incompleto na resposta");
                }
                grants.add(new Protocol.Grant(id, version, sha.toLowerCase(Locale.ROOT)));
            }
        }

        String canonical = Protocol.canonical(status, address, issuedAt, nonce, grants);
        if (!verifier.verify(canonical, signature)) {
            throw new LicenseException("Assinatura inválida (chave pública do loader não confere com o servidor?)");
        }
        if (!nonce.equals(expectedNonce)) {
            throw new LicenseException("Nonce diferente do enviado (resposta repetida?)");
        }
        if (Math.abs(nowMillis - issuedAt) > Protocol.MAX_CLOCK_SKEW_MILLIS) {
            throw new LicenseException("Relógio do servidor Minecraft fora de sincronia com o license-server");
        }
        return new AuthResult(status, address, grants);
    }

    /** Baixa o jar do produto para a memória e confere o SHA-256 contra o valor assinado. */
    public byte[] download(Protocol.Grant grant, int port) throws IOException {
        String path = "/api/v1/products/" + URLEncoder.encode(grant.getId(), "UTF-8")
                + "/download?port=" + port + "&version=" + URLEncoder.encode(grant.getVersion(), "UTF-8");
        HttpURLConnection c = open(path, "GET");
        int code = c.getResponseCode();
        if (code != 200) {
            throw new IOException("Download de " + grant.getId() + " falhou (HTTP " + code + ")");
        }
        InputStream in = c.getInputStream();
        byte[] data = readAll(in, MAX_JAR_BYTES);
        String actual = sha256(data);
        if (!actual.equalsIgnoreCase(grant.getSha256())) {
            throw new LicenseException("SHA-256 de " + grant.getId() + " não confere com o valor assinado");
        }
        return data;
    }

    private HttpURLConnection open(String path, String method) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(timeoutMillis);
        c.setReadTimeout(timeoutMillis * 3);
        c.setRequestProperty("User-Agent", "corePlugins/" + loaderVersion);
        c.setRequestProperty("Accept", "application/json, application/octet-stream");
        return c;
    }

    private String newNonce() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        return hex(b);
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (out.size() + n > max) {
                    throw new IOException("Resposta maior que o limite de " + max + " bytes");
                }
                out.write(buf, 0, n);
            }
        } finally {
            in.close();
        }
        return out.toByteArray();
    }

    public static String sha256(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[i * 2] = HEX[(b[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[b[i] & 0xF];
        }
        return new String(out);
    }
}
