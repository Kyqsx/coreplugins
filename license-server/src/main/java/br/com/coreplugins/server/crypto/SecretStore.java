package br.com.coreplugins.server.crypto;

import br.com.coreplugins.server.config.LicenseProperties;
import br.com.coreplugins.server.repo.ServerSecretRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Guarda e devolve segredos do servidor na tabela server_secrets (PostgreSQL), cifrados com AES-GCM quando
 * MASTER_KEY está definida. Valores antigos em texto puro continuam legíveis e são cifrados na primeira leitura.
 */
@Service
public class SecretStore {

    private static final Logger log = LoggerFactory.getLogger(SecretStore.class);

    private final ServerSecretRepository repo;
    private final SecretCipher cipher; // null = sem chave mestra (só aceito em desenvolvimento)

    public SecretStore(ServerSecretRepository repo, LicenseProperties props) {
        this.repo = repo;
        String mk = props.masterKey() == null ? "" : props.masterKey().trim();
        if (mk.isEmpty()) {
            if (props.requireMasterKey()) {
                throw new IllegalStateException("MASTER_KEY é obrigatória neste perfil. Gere uma com: openssl rand -base64 32");
            }
            log.warn("MASTER_KEY não definida: a chave privada e os segredos ficam em TEXTO PURO no banco. "
                    + "Use apenas em desenvolvimento.");
            this.cipher = null;
        } else {
            this.cipher = SecretCipher.fromBase64(mk);
        }
    }

    /**
     * Devolve o segredo {@code name}. Se ainda não existe, grava {@code seed} (quando informado, para migrar um
     * segredo antigo) ou o que {@code generator} produzir.
     */
    @Transactional
    public String getOrCreate(String name, String seed, Supplier<String> generator) {
        Optional<String> existing = repo.findValue(name);
        if (existing.isPresent()) {
            return open(name, existing.get());
        }
        String value = (seed != null && !seed.isBlank()) ? seed.trim() : generator.get();
        repo.insertIfAbsent(name, seal(name, value));
        String stored = repo.findValue(name)
                .orElseThrow(() -> new IllegalStateException("Não foi possível gravar o segredo " + name));
        return open(name, stored);
    }

    /** Grava (ou substitui) vários segredos de uma vez, na mesma transação: tudo ou nada. */
    @Transactional
    public void putAll(Map<String, String> values) {
        for (Map.Entry<String, String> e : values.entrySet()) {
            repo.upsert(e.getKey(), seal(e.getKey(), e.getValue()));
        }
    }

    private String seal(String name, String plain) {
        return cipher == null ? plain : cipher.encrypt(name, plain);
    }

    /** Decifra; se o valor ainda estiver em texto puro e já há chave mestra, cifra no lugar (migração automática). */
    private String open(String name, String stored) {
        if (SecretCipher.isEncrypted(stored)) {
            if (cipher == null) {
                throw new IllegalStateException("O segredo " + name + " está cifrado no banco, mas MASTER_KEY não foi definida.");
            }
            return cipher.decrypt(name, stored);
        }
        if (cipher != null) {
            repo.upsert(name, cipher.encrypt(name, stored));
            log.info("Segredo {} estava em texto puro e agora está cifrado no banco.", name);
        }
        return stored;
    }
}
