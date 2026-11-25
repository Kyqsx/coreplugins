package br.com.coreplugins.server.model;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** Conteúdo (bytes) do jar de uma versão. Separado de ProductVersion para listagens não carregarem o arquivo. */
@Entity
@Table(name = "product_jars")
public class ProductJar {
    @Id
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ProductVersion version;

    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] data;

    protected ProductJar() {
    }

    public ProductJar(ProductVersion version, byte[] data) {
        this.version = version;
        this.data = data;
    }

    public Long getId() { return id; }
    public byte[] getData() { return data; }
    public void setData(byte[] data) { this.data = data; }
}
