package br.com.coreplugins.server.crypto;

import br.com.coreplugins.server.config.LicenseProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Pares de chaves RSA-2048 do servidor, no PostgreSQL (tabela server_secrets, cifrados pelo {@link SecretStore}).
 *
 * <p>Há sempre DOIS pares: o <b>atual</b> ({@code rsa-keypair}), que assina as respostas, e o <b>próximo</b>
 * ({@code rsa-keypair-next}), que ainda não assina nada. O loader embute as duas chaves públicas e aceita
 * qualquer uma. Para trocar a chave sem redistribuir loaders, chame {@link #rotate()}: o próximo vira o atual e
 * um novo "próximo" é gerado. A privada NUNCA vai para o cliente.
 */
@Service
public class SigningService {

    static final String CURRENT = "rsa-keypair";
    static final String NEXT = "rsa-keypair-next";
    private static final Logger log = LoggerFactory.getLogger(SigningService.class);

    private record Keys(PrivateKey currentPrivate, PublicKey currentPublic, PublicKey nextPublic) {
    }

    private final LicenseProperties props;
    private final SecretStore secrets;
    private volatile Keys keys;

    public SigningService(LicenseProperties props, SecretStore secrets) {
        this.props = props;
        this.secrets = secrets;
    }

    @PostConstruct
    void init() throws GeneralSecurityException {
        String seed = null;
        boolean hasPriv = !props.seedRsaPrivateKey().isBlank();
        boolean hasPub = !props.seedRsaPublicKey().isBlank();
        if (hasPriv != hasPub) {
            throw new IllegalStateException("Informe SEED_RSA_PRIVATE_KEY e SEED_RSA_PUBLIC_KEY juntas (ou nenhuma).");
        }
        if (hasPriv) {
            seed = props.seedRsaPrivateKey().trim() + ":" + props.seedRsaPublicKey().trim();
        }
        keys = load(secrets.getOrCreate(CURRENT, seed, SigningService::generatePair),
                secrets.getOrCreate(NEXT, null, SigningService::generatePair));
    }

    private static Keys load(String currentStored, String nextStored) throws GeneralSecurityException {
        String[] cur = split(currentStored, CURRENT);
        String[] nxt = split(nextStored, NEXT);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return new Keys(
                kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(cur[0]))),
                kf.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(cur[1]))),
                kf.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(nxt[1]))));
    }

    private static String[] split(String stored, String name) {
        String[] parts = stored.split(":");
        if (parts.length != 2) {
            throw new IllegalStateException("Par de chaves RSA corrompido no banco (server_secrets/" + name + ")");
        }
        return parts;
    }

    private static String generatePair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            return Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded())
                    + ":" + Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String sign(String canonical) {
        try {
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(keys.currentPrivate());
            s.update(canonical.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(s.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Chave pública atual (compatível com o endpoint antigo /api/v1/public-key). */
    public String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(keys.currentPublic().getEncoded());
    }

    /** As duas públicas que o loader deve embutir: [atual, próxima]. */
    public List<String> publicKeysBase64() {
        Keys k = keys;
        return List.of(Base64.getEncoder().encodeToString(k.currentPublic().getEncoded()),
                Base64.getEncoder().encodeToString(k.nextPublic().getEncoded()));
    }

    /**
     * Troca a chave de assinatura: a "próxima" passa a assinar e uma nova "próxima" é gerada. Loaders que já embutem
     * as duas públicas continuam funcionando. Com várias instâncias do servidor, reinicie as outras depois.
     */
    public synchronized void rotate() throws GeneralSecurityException {
        String nextStored = secrets.getOrCreate(NEXT, null, SigningService::generatePair);
        String fresh = generatePair();
        secrets.putAll(Map.of(CURRENT, nextStored, NEXT, fresh));
        keys = load(nextStored, fresh);
        log.warn("Chave de assinatura RSA rotacionada. Gere um novo loader com as chaves de /api/v1/public-keys.");
    }
}
