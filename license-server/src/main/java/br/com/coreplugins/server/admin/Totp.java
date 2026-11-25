package br.com.coreplugins.server.admin;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * TOTP (RFC 6238): HMAC-SHA1, 6 dígitos, janela de 30 s. Compatível com Google Authenticator, Authy, 1Password etc.
 * Só JDK. O segredo trafega em Base32 (RFC 4648, sem preenchimento).
 */
public final class Totp {

    public static final long STEP_MILLIS = 30_000L;
    private static final int DIGITS = 6;
    private static final int SECRET_BYTES = 20;
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    public static String newSecret() {
        byte[] b = new byte[SECRET_BYTES];
        RANDOM.nextBytes(b);
        return base32(b);
    }

    /** URI no formato que os aplicativos de autenticação importam (otpauth://totp/...). */
    public static String uri(String issuer, String account, String secret) {
        String label = enc(issuer) + ":" + enc(account);
        return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + enc(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + (STEP_MILLIS / 1000);
    }

    /**
     * Confere o código aceitando o passo atual e um para cada lado (relógios levemente fora de hora).
     * Um passo só vale uma vez: passos menores ou iguais a {@code lastUsedStep} são recusados (anti-replay).
     *
     * @return o passo que casou (guarde-o como "último usado") ou {@code -1} se o código não vale
     */
    public static long verify(String secret, String code, long nowMillis, Long lastUsedStep) {
        if (secret == null || code == null) {
            return -1;
        }
        String c = code.replaceAll("[\\s-]", "");
        if (!c.matches("\\d{" + DIGITS + "}")) {
            return -1;
        }
        byte[] key;
        try {
            key = unbase32(secret);
        } catch (IllegalArgumentException e) {
            return -1;
        }
        long current = nowMillis / STEP_MILLIS;
        long floor = lastUsedStep == null ? Long.MIN_VALUE : lastUsedStep;
        long matched = -1;
        // percorre as três janelas sempre, para o tempo de resposta não revelar qual casou
        for (long step = current - 1; step <= current + 1; step++) {
            boolean same = MessageDigest.isEqual(code(key, step).getBytes(StandardCharsets.US_ASCII),
                    c.getBytes(StandardCharsets.US_ASCII));
            if (same && step > floor) {
                matched = step;
            }
        }
        return matched;
    }

    /** Código de um passo (público para testes). */
    static String code(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] msg = new byte[8];
            for (int i = 7; i >= 0; i--) {
                msg[i] = (byte) (step & 0xff);
                step >>= 8;
            }
            byte[] h = mac.doFinal(msg);
            int off = h[h.length - 1] & 0x0f;
            int bin = ((h[off] & 0x7f) << 24) | ((h[off + 1] & 0xff) << 16) | ((h[off + 2] & 0xff) << 8) | (h[off + 3] & 0xff);
            int mod = 1;
            for (int i = 0; i < DIGITS; i++) {
                mod *= 10;
            }
            return String.format("%0" + DIGITS + "d", bin % mod);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String base32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        }
        return sb.toString();
    }

    static byte[] unbase32(String s) {
        String in = s.replace("=", "").replace(" ", "").toUpperCase();
        byte[] out = new byte[in.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int n = 0;
        for (int i = 0; i < in.length(); i++) {
            int v = ALPHABET.indexOf(in.charAt(i));
            if (v < 0) {
                throw new IllegalArgumentException("base32 inválido");
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out[n++] = (byte) ((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
