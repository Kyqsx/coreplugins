package br.com.coreplugins.loader.license;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * Verifica a assinatura RSA (SHA-256) das respostas do license-server.
 * As chaves públicas vêm <b>embutidas no jar do loader</b> ({@code license-public.key}); não ficam no config.yml,
 * onde o cliente poderia trocá-las.
 *
 * <p>O arquivo pode ter mais de uma chave, uma por linha (linhas vazias e começando com {@code #} são ignoradas).
 * Use a <b>atual</b> e a <b>próxima</b> ({@code GET /api/v1/public-keys}): assim o servidor troca a chave de
 * assinatura sem quebrar os loaders já distribuídos. A assinatura vale se QUALQUER chave da lista a validar.
 */
public final class SignatureVerifier {

    public static final String RESOURCE = "/license-public.key";

    private final List<PublicKey> keys;

    public SignatureVerifier(PublicKey key) {
        this(Collections.singletonList(key));
    }

    public SignatureVerifier(List<PublicKey> keys) {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalStateException("nenhuma chave pública configurada");
        }
        this.keys = Collections.unmodifiableList(new ArrayList<PublicKey>(keys));
    }

    /** @throws IllegalStateException se a chave não foi configurada no build */
    public static SignatureVerifier fromResource() throws IOException, GeneralSecurityException {
        InputStream in = SignatureVerifier.class.getResourceAsStream(RESOURCE);
        if (in == null) {
            throw new IllegalStateException("license-public.key ausente no jar do loader");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            in.close();
        }
        return fromBase64(new String(out.toByteArray(), StandardCharsets.UTF_8));
    }

    /** Aceita uma ou várias chaves Base64 (X.509/DER), uma por linha. */
    public static SignatureVerifier fromBase64(String text) throws GeneralSecurityException {
        List<PublicKey> parsed = new ArrayList<PublicKey>();
        KeyFactory kf = KeyFactory.getInstance("RSA");
        for (String line : text.split("\\r?\\n")) {
            String clean = line.replaceAll("\\s+", "");
            if (clean.isEmpty() || clean.startsWith("#")) {
                continue;
            }
            if (clean.startsWith("REPLACE")) {
                throw notConfigured();
            }
            byte[] der = Base64.getDecoder().decode(clean);
            parsed.add(kf.generatePublic(new X509EncodedKeySpec(der)));
        }
        if (parsed.isEmpty()) {
            throw notConfigured();
        }
        return new SignatureVerifier(parsed);
    }

    private static IllegalStateException notConfigured() {
        return new IllegalStateException(
                "license-public.key não configurada. Copie as chaves de GET /api/v1/public-keys para "
                        + "loader/src/main/resources/license-public.key e recompile o loader.");
    }

    public boolean verify(String canonical, String signatureBase64) {
        byte[] data = canonical.getBytes(StandardCharsets.UTF_8);
        byte[] sig;
        try {
            sig = Base64.getDecoder().decode(signatureBase64);
        } catch (IllegalArgumentException e) {
            return false;
        }
        for (PublicKey key : keys) {
            try {
                Signature s = Signature.getInstance("SHA256withRSA");
                s.initVerify(key);
                s.update(data);
                if (s.verify(sig)) {
                    return true;
                }
            } catch (GeneralSecurityException e) {
                // tenta a próxima chave
            }
        }
        return false;
    }
}
