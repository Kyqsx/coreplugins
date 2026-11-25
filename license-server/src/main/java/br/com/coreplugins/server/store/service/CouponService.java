package br.com.coreplugins.server.store.service;

import br.com.coreplugins.server.store.model.Coupon;
import br.com.coreplugins.server.store.repo.CouponRepository;
import br.com.coreplugins.server.store.repo.StoreOrderRepository;
import br.com.coreplugins.server.web.ApiException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

/** Regras de cupom (desconto em compra e bônus em depósito). Quem usa: OrderService (cliente) e painel admin. */
@Service
public class CouponService {

    private static final Pattern CODE = Pattern.compile("^[A-Z0-9_-]{3,32}$");
    private static final String GENERIC = "Cupom inválido ou expirado.";

    private final CouponRepository coupons;
    private final StoreOrderRepository orders;

    public CouponService(CouponRepository coupons, StoreOrderRepository orders) {
        this.coupons = coupons;
        this.orders = orders;
    }

    public static String normalize(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    /** Cupom de desconto válido para este cliente e este subtotal; lança erro com a mensagem para o site. */
    public Coupon validateDiscount(Long customerId, String code, int subtotalCents) {
        return validate(customerId, code, Coupon.DISCOUNT, subtotalCents, "compra");
    }

    /** Cupom de bônus de depósito válido para este cliente e este valor de depósito. */
    public Coupon validateBonus(Long customerId, String code, int depositCents) {
        return validate(customerId, code, Coupon.DEPOSIT_BONUS, depositCents, "depósito");
    }

    private Coupon validate(Long customerId, String raw, String kind, int baseCents, String what) {
        String code = normalize(raw);
        Coupon c = CODE.matcher(code).matches() ? coupons.findByCode(code).orElse(null) : null;
        Instant now = Instant.now();
        if (c == null || !c.isActive() || !kind.equals(c.getKind())
                || (c.getExpiresAt() != null && !c.getExpiresAt().isAfter(now))) {
            throw ApiException.bad(GENERIC);
        }
        if (c.getMaxUses() != null && c.getUsedCount() >= c.getMaxUses()) {
            throw ApiException.bad("Este cupom já atingiu o limite de usos.");
        }
        // Sem limite definido, o cupom é de uso único por conta.
        int perCustomer = c.getMaxUsesPerCustomer() == null ? 1 : c.getMaxUsesPerCustomer();
        if (orders.countCouponUses(customerId, c.getId(), now) >= perCustomer) {
            throw ApiException.bad("Você já usou este cupom.");
        }
        if (baseCents < c.getMinCents()) {
            throw ApiException.bad("Este cupom vale para " + what + " a partir de R$ "
                    + String.format(Locale.forLanguageTag("pt-BR"), "%.2f", c.getMinCents() / 100.0) + ".");
        }
        if (c.compute(baseCents) <= 0) {
            throw ApiException.bad(GENERIC);
        }
        return c;
    }

    // ---- painel admin

    public record CouponReq(String code, String kind, String valueType, Integer value, Integer minCents, Integer capCents,
                            Integer maxUses, Integer maxUsesPerCustomer, Instant expiresAt) {
    }

    public Coupon create(CouponReq r) {
        String code = normalize(r.code());
        if (!CODE.matcher(code).matches()) {
            throw ApiException.bad("Código: 3 a 32 caracteres (letras, números, - ou _).");
        }
        if (coupons.existsByCode(code)) {
            throw ApiException.bad("Já existe um cupom com este código.");
        }
        if (!Coupon.DISCOUNT.equals(r.kind()) && !Coupon.DEPOSIT_BONUS.equals(r.kind())) {
            throw ApiException.bad("Tipo de cupom inválido.");
        }
        if (!Coupon.PERCENT.equals(r.valueType()) && !Coupon.FIXED.equals(r.valueType())) {
            throw ApiException.bad("Forma do valor inválida (percentual ou fixo).");
        }
        int value = r.value() == null ? 0 : r.value();
        if (value <= 0) {
            throw ApiException.bad("Informe o valor do cupom.");
        }
        if (Coupon.PERCENT.equals(r.valueType())) {
            int max = Coupon.DISCOUNT.equals(r.kind()) ? 100 : 200;
            if (value > max) {
                throw ApiException.bad("Percentual máximo: " + max + "%.");
            }
        } else if (value > 10_000_000) {
            throw ApiException.bad("Valor grande demais.");
        }
        int min = r.minCents() == null ? 0 : r.minCents();
        if (min < 0 || min > 100_000_000) {
            throw ApiException.bad("Valor mínimo inválido.");
        }
        if (r.capCents() != null && r.capCents() <= 0) {
            throw ApiException.bad("O teto precisa ser maior que zero (ou deixe em branco).");
        }
        if (r.maxUses() != null && r.maxUses() <= 0) {
            throw ApiException.bad("O limite de usos precisa ser maior que zero (ou deixe em branco).");
        }
        if (r.maxUsesPerCustomer() != null && r.maxUsesPerCustomer() <= 0) {
            throw ApiException.bad("O limite por cliente precisa ser maior que zero (ou deixe em branco).");
        }
        if (r.expiresAt() != null && !r.expiresAt().isAfter(Instant.now())) {
            throw ApiException.bad("A validade precisa estar no futuro.");
        }
        // Sem limite por cliente informado: uso único por conta.
        int perCustomer = r.maxUsesPerCustomer() == null ? 1 : r.maxUsesPerCustomer();
        return coupons.save(new Coupon(code, r.kind(), r.valueType(), value, min, r.capCents(), r.maxUses(),
                perCustomer, r.expiresAt()));
    }
}
