package br.com.coreplugins.server.store.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Cupom. {@code DISCOUNT} abate o valor de uma compra; {@code DEPOSIT_BONUS} soma saldo extra a um depósito.
 * {@code PERCENT}: value é a porcentagem (1-100 no desconto). {@code FIXED}: value são centavos.
 */
@Entity
@Table(name = "coupons")
public class Coupon {

    public static final String DISCOUNT = "DISCOUNT";
    public static final String DEPOSIT_BONUS = "DEPOSIT_BONUS";
    public static final String PERCENT = "PERCENT";
    public static final String FIXED = "FIXED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 32)
    private String code;
    @Column(nullable = false, length = 16)
    private String kind;
    @Column(name = "value_type", nullable = false, length = 8)
    private String valueType;
    @Column(nullable = false)
    private int value;
    /** Compra (ou depósito) mínima, em centavos, para o cupom valer. */
    @Column(name = "min_cents", nullable = false)
    private int minCents;
    /** Teto do desconto/bônus em centavos (útil em cupom percentual). Nulo = sem teto. */
    @Column(name = "cap_cents")
    private Integer capCents;
    /** Máximo de usos no total. Nulo = ilimitado. */
    @Column(name = "max_uses")
    private Integer maxUses;
    /** Máximo de usos por cliente. Nulo = ilimitado. */
    @Column(name = "max_uses_per_customer")
    private Integer maxUsesPerCustomer;
    @Column(name = "used_count", nullable = false)
    private int usedCount;
    @Column(name = "expires_at")
    private Instant expiresAt;
    @Column(nullable = false)
    private boolean active = true;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Coupon() {
    }

    public Coupon(String code, String kind, String valueType, int value, int minCents, Integer capCents,
                  Integer maxUses, Integer maxUsesPerCustomer, Instant expiresAt) {
        this.code = code;
        this.kind = kind;
        this.valueType = valueType;
        this.value = value;
        this.minCents = minCents;
        this.capCents = capCents;
        this.maxUses = maxUses;
        this.maxUsesPerCustomer = maxUsesPerCustomer;
        this.expiresAt = expiresAt;
    }

    /** Desconto (nunca maior que a base) ou bônus, em centavos, para o valor-base informado. */
    public int compute(int baseCents) {
        long raw = PERCENT.equals(valueType) ? (long) baseCents * value / 100L : value;
        if (capCents != null && raw > capCents) {
            raw = capCents;
        }
        if (DISCOUNT.equals(kind) && raw > baseCents) {
            raw = baseCents;
        }
        return (int) Math.max(0L, raw);
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getKind() { return kind; }
    public String getValueType() { return valueType; }
    public int getValue() { return value; }
    public int getMinCents() { return minCents; }
    public Integer getCapCents() { return capCents; }
    public Integer getMaxUses() { return maxUses; }
    public Integer getMaxUsesPerCustomer() { return maxUsesPerCustomer; }
    public int getUsedCount() { return usedCount; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
}
