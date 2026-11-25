package br.com.coreplugins.server;

import br.com.coreplugins.server.config.LicenseProperties;
import br.com.coreplugins.server.store.StoreProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({LicenseProperties.class, StoreProperties.class})
public class LicenseServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(LicenseServerApplication.class, args);
    }
}
