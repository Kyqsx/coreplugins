package br.com.coreplugins.server.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** O corePlugins.jar (loader) que o cliente baixa na Área do Cliente. Uma única linha (id = 1), guardada no banco como os jars dos produtos. */
@Entity
@Table(name = "loader_jars")
public class LoaderJar {

    public static final long ID = 1L;

    @Id
    private Long id = ID;
    @Column(nullable = false)
    private long sizeBytes;
    @Column(nullable = false, length = 64)
    private String sha256;
    @Column(nullable = false)
    private Instant updatedAt = Instant.now();
    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] data;

    protected LoaderJar() {
    }

    public LoaderJar(byte[] data, String sha256) {
        this.data = data;
        this.sha256 = sha256;
        this.sizeBytes = data.length;
    }

    public void replace(byte[] data, String sha256) {
        this.data = data;
        this.sha256 = sha256;
        this.sizeBytes = data.length;
        this.updatedAt = Instant.now();
    }
}
