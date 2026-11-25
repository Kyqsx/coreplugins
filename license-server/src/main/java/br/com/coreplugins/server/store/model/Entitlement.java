package br.com.coreplugins.server.store.model;

import jakarta.persistence.*;
import java.time.Instant;

/** "Este cliente comprou este plugin": é o que ele pode marcar em cada servidor. */
@Entity
@Table(name = "entitlements", uniqueConstraints = @UniqueConstraint(columnNames = {"customer_id", "product_id"}))
public class Entitlement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "customer_id", nullable = false)
    private Long customerId;
    @Column(name = "product_id", nullable = false, length = 64)
    private String productId;
    @Column(name = "order_id")
    private Long orderId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Entitlement() {
    }

    public Entitlement(Long customerId, String productId, Long orderId) {
        this.customerId = customerId;
        this.productId = productId;
        this.orderId = orderId;
    }

    public Long getCustomerId() { return customerId; }
    public String getProductId() { return productId; }
    public Instant getCreatedAt() { return createdAt; }
}
