package br.com.coreplugins.server.store.security;

import br.com.coreplugins.server.config.LicenseProperties;
import br.com.coreplugins.server.crypto.SecretStore;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.OptionalLong;

/** Emite e valida tokens de sessão. O segredo é gerado na primeira execução e fica no PostgreSQL (server_secrets/token-secret). */
@Service
public class TokenService {

    private static final long TTL_MILLIS = 7L * 24 * 60 * 60 * 1000;
    /** Sessão do painel admin: curta de propósito. */
    public static final long ADMIN_TTL_MILLIS = 60L * 60 * 1000;
    /** Tempo para digitar o código 2FA depois de acertar a senha. */
    private static final long CHALLENGE_TTL_MILLIS = 5L * 60 * 1000;

    private final LicenseProperties props;
    private final SecretStore secrets;
    private TokenCodec codec;
    // Cada finalidade tem um segredo derivado próprio: um token de cliente nunca vale como sessão de admin
    // (nem o contrário), e o "desafio" do 2FA nunca vale como sessão.
    private TokenCodec adminCodec;
    private TokenCodec challengeCodec;

    public TokenService(LicenseProperties props, SecretStore secrets) {
        this.props = props;
        this.secrets = secrets;
    }

    @PostConstruct
    void init() {
        String stored = secrets.getOrCreate("token-secret", props.seedTokenSecret(), () -> {
            byte[] random = new byte[48];
            new SecureRandom().nextBytes(random);
            return Base64.getEncoder().encodeToString(random);
        });
        byte[] secret = Base64.getDecoder().decode(stored.trim());
        codec = new TokenCodec(secret);
        adminCodec = new TokenCodec(derive(secret, "admin-session"));
        challengeCodec = new TokenCodec(derive(secret, "admin-2fa-challenge"));
    }

    private static byte[] derive(byte[] secret, String label) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(secret, "HmacSHA256"));
            return m.doFinal(label.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code version} = versão de sessão atual da conta; subir esse número no banco invalida todos os tokens emitidos antes. */
    public String issue(long userId, int version) {
        return codec.issue(userId, System.currentTimeMillis() + TTL_MILLIS, version);
    }

    public Optional<TokenCodec.Claims> verify(String token) {
        return codec.verify(token, System.currentTimeMillis());
    }

    public String issueAdmin(long adminId, int version) {
        return adminCodec.issue(adminId, System.currentTimeMillis() + ADMIN_TTL_MILLIS, version);
    }

    public Optional<TokenCodec.Claims> verifyAdmin(String token) {
        return adminCodec.verify(token, System.currentTimeMillis());
    }

    /** O desafio do 2FA vale 5 min e não é sessão; a versão não se aplica (sempre 0). */
    public String issueChallenge(long adminId) {
        return challengeCodec.issue(adminId, System.currentTimeMillis() + CHALLENGE_TTL_MILLIS, 0);
    }

    public OptionalLong verifyChallenge(String token) {
        Optional<TokenCodec.Claims> c = challengeCodec.verify(token, System.currentTimeMillis());
        return c.isPresent() ? OptionalLong.of(c.get().id()) : OptionalLong.empty();
    }
}
