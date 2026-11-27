package br.com.coreplugins.server.store;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "store")
public record StoreProperties(
        @DefaultValue("simulated") String paymentMode,
        @DefaultValue("") String mercadopagoAccessToken,
        @DefaultValue("") String publicUrl,
        @DefaultValue("30") int orderExpiresMinutes,
        @DefaultValue("5") int maxServersPerCustomer,
        @DefaultValue("false") boolean mercadopagoTestMode,
        /** Caminho do corePlugins.jar (o loader) que o cliente baixa na Área do Cliente. Vazio = procura em loader/target. */
        @DefaultValue("") String loaderJarPath) {
}