package br.com.coreplugins.server.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Cifra os segredos guardados no banco com AES-256-GCM. A chave mestra vem só do ambiente (MASTER_KEY), então um
 * {@code pg_dump} vazado sozinho não entrega a chave privada RSA nem o segredo das sessões.
 *
 * <p>Formato guardado: {@code enc:v1:} + base64url(iv(12) || texto cifrado || tag(16)). O nome do segredo entra como
 * dado autenticado (AAD): copiar o valor cifrado de uma linha para outra faz a decifragem falhar.
 */
public final class SecretCipher {

    public static final String PREFIX = "enc:v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(byte[] masterKey) {
        if (masterKey == null || masterKey.length != 32) {
            throw new IllegalArgumentException("MASTER_KEY precisa ter exatamente 32 bytes em Base64 (gere com: openssl rand -base64 32)");
        }
        this.key = new SecretKeySpec(masterKey, "AES");
    }

    public static SecretCipher fromBase64(String base64) {
        try {
            return new SecretCipher(Base64.getDecoder().decode(base64.trim()));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("MASTER_KEY inválida: " + e.getMessage());
        }
    }

    public static boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    public String encrypt(String name, String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao cifrar o segredo " + name, e);
        }
    }

    /** @throws IllegalStateException se a chave mestra estiver errada ou o valor tiver sido adulterado/copiado de outra linha */
    public String decrypt(String name, String stored) {
        if (!isEncrypted(stored)) {
            throw new IllegalArgumentException("valor não está cifrado");
        }
        try {
            byte[] all = Base64.getUrlDecoder().decode(stored.substring(PREFIX.length()));
            if (all.length < IV_BYTES + TAG_BITS / 8) {
                throw new IllegalStateException("Segredo " + name + " cifrado está truncado no banco");
            }
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            c.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            return new String(c.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Não foi possível decifrar o segredo " + name
                    + ": MASTER_KEY diferente da usada para gravar, ou o valor foi alterado no banco.");
        }
    }
}
