package br.com.coreplugins.server.model;

import jakarta.persistence.*;
import java.time.Instant;

/** Segredos gerados pelo próprio servidor (par RSA, segredo das sessões). Ficam no PostgreSQL, não em arquivos. */
@Entity
@Table(name = "server_secrets")
public class ServerSecret {
    @Id
    @Column(length = 64)
    private String name;

    @Column(name = "secret_value", nullable = false, columnDefinition = "text")
    private String value;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected ServerSecret() {
    }

    public String getName() { return name; }
    public String getValue() { return value; }
    public Instant getCreatedAt() { return createdAt; }
}
