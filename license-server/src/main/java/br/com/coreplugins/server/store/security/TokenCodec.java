package br.com.coreplugins.server.store.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;

/**
 * Token de sessão assinado com HMAC-SHA256: {@code base64url("id.expiraEm.versao") + "." + base64url(assinatura)}. Só JDK.
 * A {@code versao} é a "versão de sessão" da conta: quem confere se ela ainda é a atual (e assim revoga tokens
 * antigos) é o filtro, que consulta o banco; o codec só garante que o número não foi adulterado.
 */
public final class TokenCodec {

    /** Conteúdo de um token válido. */
    public record Claims(long id, int version) {
    }

    private final byte[] secret;

    public TokenCodec(byte[] secret) {
        if (secret == null || secret.length < 32) {
            throw new IllegalArgumentException("segredo precisa ter ao menos 32 bytes");
        }
        this.secret = secret.clone();
    }

    public String issue(long userId, long expiresAtMillis, int version) {
        String payload = b64(userId + "." + expiresAtMillis + "." + version);
        return payload + "." + b64(mac(payload));
    }

    /** @return id e versão, ou vazio se o token for inválido, adulterado, expirado ou do formato antigo (sem versão) */
    public Optional<Claims> verify(String token, long nowMillis) {
        if (token == null) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return Optional.empty();
        }
        String payload = token.substring(0, dot);
        byte[] given;
        try {
            given = Base64.getUrlDecoder().decode(token.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(given, mac(payload))) {
            return Optional.empty();
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8).split("\\.");
            if (parts.length != 3) {
                return Optional.empty();
            }
            long id = Long.parseLong(parts[0]);
            long exp = Long.parseLong(parts[1]);
            int version = Integer.parseInt(parts[2]);
            return nowMillis < exp ? Optional.of(new Claims(id, version)) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private byte[] mac(String payload) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(secret, "HmacSHA256"));
            return m.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(String s) {
        return b64(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
