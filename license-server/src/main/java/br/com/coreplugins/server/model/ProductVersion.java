package br.com.coreplugins.server.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "product_versions", uniqueConstraints = @UniqueConstraint(columnNames = {"product_id", "version"}))
public class ProductVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "product_id", nullable = false, length = 64)
    private String productId;
    @Column(nullable = false, length = 64)
    private String version;
    @Column(nullable = false, length = 64)
    private String sha256;
    @Column(nullable = false)
    private long sizeBytes;
    @Column(nullable = false)
    private Instant createdAt = Instant.now();
    /** Notas da versão (texto puro), mostradas aos clientes em "Novidades" na Área do Cliente. Null = sem notas. */
    @Column(length = 4000)
    private String changelog;

    protected ProductVersion() {
    }

    public ProductVersion(String productId, String version, String sha256, long sizeBytes) {
        this.productId = productId;
        this.version = version;
        this.sha256 = sha256;
        this.sizeBytes = sizeBytes;
    }

    public Long getId() { return id; }
    public String getProductId() { return productId; }
    public String getVersion() { return version; }
    public String getSha256() { return sha256; }
    public long getSizeBytes() { return sizeBytes; }
    public Instant getCreatedAt() { return createdAt; }
    public String getChangelog() { return changelog; }
    public void setChangelog(String changelog) { this.changelog = changelog; }
    public void setVersion(String version) { this.version = version; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }
}
