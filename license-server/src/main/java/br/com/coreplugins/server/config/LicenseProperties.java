package br.com.coreplugins.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "license")
public record LicenseProperties(
        @DefaultValue("") String adminEmail,
        @DefaultValue("") String adminPassword,
        @DefaultValue("true") boolean adminRequire2fa,
        @DefaultValue("false") boolean adminReset,
        @DefaultValue("false") boolean trustForwardedFor,
        /** Só para a PRIMEIRA subida num banco vazio: reaproveita o par RSA que já está embutido nos loaders. */
        @DefaultValue("") String seedRsaPrivateKey,
        @DefaultValue("") String seedRsaPublicKey,
        /** Só para a PRIMEIRA subida: reaproveita o segredo que assina as sessões (antigo token.secret). */
        @DefaultValue("") String seedTokenSecret,
        /** Chave mestra (Base64, 32 bytes) que cifra os segredos no banco. Só no ambiente, nunca no banco. */
        @DefaultValue("") String masterKey,
        /** true = o servidor recusa subir sem MASTER_KEY (ligado no perfil prod). */
        @DefaultValue("false") boolean requireMasterKey) {
}
