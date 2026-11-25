package br.com.coreplugins.server.model;

import jakarta.persistence.*;
import java.time.Instant;

/** "Este IP:porta pode rodar este produto" (os plugins marcados na Área do Cliente). */
@Entity
@Table(name = "server_products", uniqueConstraints = @UniqueConstraint(columnNames = {"server_id", "product_id"}))
public class ServerProduct {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "server_id", nullable = false)
    private Long serverId;
    @Column(name = "product_id", nullable = false, length = 64)
    private String productId;
    /** Null = sem expiração. */
    private Instant expiresAt;
    /** Versão escolhida pelo cliente. Null = acompanhar a mais recente. */
    @Column(name = "version", length = 64)
    private String version;

    protected ServerProduct() {
    }

    public ServerProduct(Long serverId, String productId, Instant expiresAt, String version) {
        this.serverId = serverId;
        this.productId = productId;
        this.expiresAt = expiresAt;
        this.version = version;
    }

    public Long getId() { return id; }
    public Long getServerId() { return serverId; }
    public String getProductId() { return productId; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
}
