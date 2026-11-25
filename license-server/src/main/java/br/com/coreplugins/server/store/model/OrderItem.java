package br.com.coreplugins.server.store.model;

import jakarta.persistence.*;

@Entity
@Table(name = "order_items")
public class OrderItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "order_id", nullable = false)
    private Long orderId;
    @Column(name = "product_id", nullable = false, length = 64)
    private String productId;
    @Column(name = "price_cents", nullable = false)
    private int priceCents;

    protected OrderItem() {
    }

    public OrderItem(Long orderId, String productId, int priceCents) {
        this.orderId = orderId;
        this.productId = productId;
        this.priceCents = priceCents;
    }

    public Long getOrderId() { return orderId; }
    public String getProductId() { return productId; }
    public int getPriceCents() { return priceCents; }
}
