package br.com.coreplugins.server.store.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "store_orders")
public class StoreOrder {

    public static final String PENDING = "PENDING";
    public static final String PAID = "PAID";
    public static final String EXPIRED = "EXPIRED";
    public static final String CANCELLED = "CANCELLED";
    /** PIX pago depois de o pedido expirar, sem saldo para refazer a parte em saldo: o valor virou saldo na carteira. */
    public static final String CREDITED = "CREDITED";

    public static final String KIND_PURCHASE = "PURCHASE";
    public static final String KIND_DEPOSIT = "DEPOSIT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "customer_id", nullable = false)
    private Long customerId;
    @Column(nullable = false, length = 16)
    private String status = PENDING;
    @Column(name = "total_cents", nullable = false)
    private int totalCents;
    @Column(name = "payment_id", length = 64)
    private String paymentId;
    @Column(name = "pix_code", length = 2048)
    private String pixCode;
    @Column(name = "pix_qr_base64", length = 16384)
    private String pixQrBase64;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "paid_at")
    private Instant paidAt;
    /** PURCHASE (compra de plugins) ou DEPOSIT (depósito de saldo). Nulo (pedidos antigos) vale PURCHASE. */
    @Column(length = 16)
    private String kind;
    /** Soma dos itens antes do cupom. Nulo (pedidos antigos) vale totalCents. */
    @Column(name = "subtotal_cents")
    private Integer subtotalCents;
    @Column(name = "discount_cents")
    private Integer discountCents;
    /** Só em depósito: saldo extra creditado junto com o valor pago (cupom de bônus). */
    @Column(name = "bonus_cents")
    private Integer bonusCents;
    @Column(name = "coupon_id")
    private Long couponId;
    @Column(name = "coupon_code", length = 32)
    private String couponCode;
    /** Compra paga com saldo da carteira (sem PIX). */
    @Column(name = "paid_with_balance")
    private Boolean paidWithBalance;
    /** Pagamento misto: parte do pedido paga com saldo (reservada ao criar); {@code totalCents} fica só com o PIX. */
    @Column(name = "balance_cents")
    private Integer balanceCents;
    /** O saldo reservado já voltou para a carteira (pedido expirou/cancelou). */
    @Column(name = "balance_refunded")
    private Boolean balanceRefunded;

    protected StoreOrder() {
    }

    public StoreOrder(Long customerId, int totalCents, Instant expiresAt) {
        this.customerId = customerId;
        this.totalCents = totalCents;
        this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public Long getCustomerId() { return customerId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getTotalCents() { return totalCents; }
    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }
    public String getPixCode() { return pixCode; }
    public void setPixCode(String pixCode) { this.pixCode = pixCode; }
    public String getPixQrBase64() { return pixQrBase64; }
    public void setPixQrBase64(String pixQrBase64) { this.pixQrBase64 = pixQrBase64; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getPaidAt() { return paidAt; }
    public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }

    public String getKind() { return kind == null ? KIND_PURCHASE : kind; }
    public boolean isDeposit() { return KIND_DEPOSIT.equals(kind); }
    public int getSubtotalCents() { return subtotalCents == null ? totalCents : subtotalCents; }
    public int getDiscountCents() { return discountCents == null ? 0 : discountCents; }
    public int getBonusCents() { return bonusCents == null ? 0 : bonusCents; }
    public Long getCouponId() { return couponId; }
    public String getCouponCode() { return couponCode; }
    public boolean isPaidWithBalance() { return Boolean.TRUE.equals(paidWithBalance); }
    /** Parte paga com saldo, em qualquer modalidade (total ou mista). */
    public int getBalanceCents() { return isPaidWithBalance() ? totalCents : (balanceCents == null ? 0 : balanceCents); }
    /** Só pagamento misto: valor do saldo reservado neste pedido (0 se não for misto). */
    public int getPartialBalanceCents() { return balanceCents == null ? 0 : balanceCents; }
    public boolean isBalanceRefunded() { return Boolean.TRUE.equals(balanceRefunded); }
    public void setBalanceRefunded(boolean v) { this.balanceRefunded = v; }
    public void setPartialBalance(int cents) { this.balanceCents = cents; }

    /** Compra: valores do carrinho e cupom de desconto (se houver). */
    public void setPurchase(int subtotalCents, int discountCents, Long couponId, String couponCode, boolean paidWithBalance) {
        this.kind = KIND_PURCHASE;
        this.subtotalCents = subtotalCents;
        this.discountCents = discountCents;
        this.couponId = couponId;
        this.couponCode = couponCode;
        this.paidWithBalance = paidWithBalance;
    }

    /** Depósito: totalCents é o valor pago no PIX; bonusCents é o extra do cupom de bônus. */
    public void setDeposit(int bonusCents, Long couponId, String couponCode) {
        this.kind = KIND_DEPOSIT;
        this.bonusCents = bonusCents;
        this.couponId = couponId;
        this.couponCode = couponCode;
    }
}
