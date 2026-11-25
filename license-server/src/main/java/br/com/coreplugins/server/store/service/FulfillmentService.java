package br.com.coreplugins.server.store.service;

import br.com.coreplugins.server.model.Product;
import br.com.coreplugins.server.repo.ProductRepository;
import br.com.coreplugins.server.store.model.Coupon;
import br.com.coreplugins.server.store.model.Entitlement;
import br.com.coreplugins.server.store.model.OrderItem;
import br.com.coreplugins.server.store.model.StoreOrder;
import br.com.coreplugins.server.store.model.WalletTx;
import br.com.coreplugins.server.store.repo.CouponRepository;
import br.com.coreplugins.server.store.repo.EntitlementRepository;
import br.com.coreplugins.server.store.repo.OrderItemRepository;
import br.com.coreplugins.server.store.repo.StoreOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Pedido pago → efeitos: compra libera plugins ("entitlements"); depósito credita o saldo (valor + bônus).
 * Idempotente: polling e webhook podem chamar ao mesmo tempo, só quem "ganha" a transição para PAID aplica os efeitos.
 */
@Service
public class FulfillmentService {

    private final StoreOrderRepository orders;
    private final OrderItemRepository items;
    private final EntitlementRepository entitlements;
    private final ProductRepository products;
    private final WalletService wallet;
    private final CouponRepository coupons;

    public FulfillmentService(StoreOrderRepository orders, OrderItemRepository items, EntitlementRepository entitlements,
                              ProductRepository products, WalletService wallet, CouponRepository coupons) {
        this.products = products;
        this.orders = orders;
        this.items = items;
        this.entitlements = entitlements;
        this.wallet = wallet;
        this.coupons = coupons;
    }

    @Transactional
    public StoreOrder markPaid(Long orderId) {
        if (orders.claimPaid(orderId, Instant.now()) == 0) {
            return orders.findById(orderId).orElseThrow(); // já estava pago (ou outra chamada acabou de aplicar)
        }
        StoreOrder o = orders.findById(orderId).orElseThrow();
        applyPaidEffects(o);
        return o;
    }

    /**
     * Compra paga com o saldo (ou 100% coberta por cupom): cria o pedido já PAGO, tira o saldo e libera os plugins
     * numa transação só. Se o saldo não bastar, nada é gravado.
     */
    @Transactional
    public StoreOrder createPaidWithBalance(Long customerId, List<Product> chosen, int subtotalCents, int discountCents,
                                            Coupon coupon, int payCents) {
        StoreOrder o = new StoreOrder(customerId, payCents, Instant.now());
        o.setPurchase(subtotalCents, discountCents, coupon == null ? null : coupon.getId(),
                coupon == null ? null : coupon.getCode(), payCents > 0);
        o.setStatus(StoreOrder.PAID);
        o.setPaidAt(Instant.now());
        o = orders.save(o);
        for (Product p : chosen) {
            items.save(new OrderItem(o.getId(), p.getId(), p.getPriceCents()));
        }
        if (payCents > 0) {
            wallet.debit(customerId, payCents, WalletTx.PURCHASE, "Compra (pedido #" + o.getId() + ")", o.getId());
        }
        applyPaidEffects(o);
        return o;
    }

    private void applyPaidEffects(StoreOrder o) {
        if (o.isDeposit()) {
            wallet.credit(o.getCustomerId(), o.getTotalCents(), WalletTx.DEPOSIT,
                    "Depósito via PIX (pedido #" + o.getId() + ")", o.getId());
            if (o.getBonusCents() > 0) {
                String why = o.getCouponCode() == null ? "" : " (cupom " + o.getCouponCode() + ")";
                wallet.credit(o.getCustomerId(), o.getBonusCents(), WalletTx.BONUS,
                        "Bônus de depósito" + why, o.getId());
            }
        } else {
            for (OrderItem it : items.findByOrderId(o.getId())) {
                if (products.existsById(it.getProductId())
                        && !entitlements.existsByCustomerIdAndProductId(o.getCustomerId(), it.getProductId())) {
                    entitlements.save(new Entitlement(o.getCustomerId(), it.getProductId(), o.getId()));
                }
            }
        }
        if (o.getCouponId() != null) {
            coupons.incrementUsed(o.getCouponId());
        }
    }

    @Transactional
    public void setStatus(Long orderId, String status) {
        StoreOrder o = orders.findById(orderId).orElseThrow();
        if (StoreOrder.PENDING.equals(o.getStatus())) {
            o.setStatus(status);
            orders.save(o);
        }
    }
}
