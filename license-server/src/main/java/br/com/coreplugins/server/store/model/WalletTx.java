package br.com.coreplugins.server.store.model;

import jakarta.persistence.*;
import java.time.Instant;

/** Extrato da carteira: cada entrada/saída de saldo. {@code amountCents} é positivo (entrou) ou negativo (saiu). */
@Entity
@Table(name = "wallet_transactions", indexes = @Index(name = "ix_wallet_tx_customer", columnList = "customer_id"))
public class WalletTx {

    public static final String DEPOSIT = "DEPOSIT";
    public static final String BONUS = "BONUS";
    public static final String PURCHASE = "PURCHASE";
    public static final String ADMIN_CREDIT = "ADMIN_CREDIT";
    public static final String ADMIN_DEBIT = "ADMIN_DEBIT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "customer_id", nullable = false)
    private Long customerId;
    @Column(nullable = false, length = 16)
    private String type;
    @Column(name = "amount_cents", nullable = false)
    private long amountCents;
    @Column(length = 200)
    private String description;
    @Column(name = "order_id")
    private Long orderId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected WalletTx() {
    }

    public WalletTx(Long customerId, String type, long amountCents, String description, Long orderId) {
        this.customerId = customerId;
        this.type = type;
        this.amountCents = amountCents;
        this.description = description == null ? null : (description.length() > 200 ? description.substring(0, 200) : description);
        this.orderId = orderId;
    }

    public Long getId() { return id; }
    public Long getCustomerId() { return customerId; }
    public String getType() { return type; }
    public long getAmountCents() { return amountCents; }
    public String getDescription() { return description; }
    public Long getOrderId() { return orderId; }
    public Instant getCreatedAt() { return createdAt; }
}
