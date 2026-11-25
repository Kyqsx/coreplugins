package br.com.coreplugins.server.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Imagem de divulgação de um produto (galeria da página do plugin). Os bytes ficam no PostgreSQL, como os jars.
 * A primeira da ordem ({@code position}) é a capa. Nunca é alterada no lugar: trocar a imagem = apagar e enviar outra,
 * por isso a URL pública (por id) pode ser cacheada para sempre.
 */
@Entity
@Table(name = "product_images", indexes = @Index(name = "idx_product_images_product", columnList = "product_id"))
public class ProductImage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "product_id", nullable = false, length = 64)
    private String productId;
    @Column(nullable = false)
    private int position;
    @Column(nullable = false, length = 32)
    private String contentType;
    @Column(nullable = false)
    private int sizeBytes;
    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] data;
    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected ProductImage() {
    }

    public ProductImage(String productId, int position, String contentType, byte[] data) {
        this.productId = productId;
        this.position = position;
        this.contentType = contentType;
        this.sizeBytes = data.length;
        this.data = data;
    }

    public Long getId() { return id; }
    public String getProductId() { return productId; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public String getContentType() { return contentType; }
    public int getSizeBytes() { return sizeBytes; }
    public byte[] getData() { return data; }
    public Instant getCreatedAt() { return createdAt; }
}
