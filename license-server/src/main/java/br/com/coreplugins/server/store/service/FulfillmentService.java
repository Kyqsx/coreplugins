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
        if (o.isBalanceRefunded() && o.getPartialBalanceCents() > 0) {
            // PIX pago depois de o pedido expirar: o saldo reservado já tinha voltado, então precisa ser tirado de novo.
            if (wallet.tryDebit(o.getCustomerId(), o.getPartialBalanceCents(), WalletTx.PURCHASE,
                    "Parte paga com saldo (pedido #" + o.getId() + ", PIX pago após expirar)", o.getId())) {
                o.setBalanceRefunded(false);
                orders.save(o);
            } else {
                // O cliente já gastou esse saldo: o dinheiro do PIX não se perde, vira saldo e o pedido não é liberado.
                o.setStatus(StoreOrder.CREDITED);
                orders.save(o);
                wallet.credit(o.getCustomerId(), o.getTotalCents(), WalletTx.DEPOSIT,
                        "PIX pago após o pedido #" + o.getId() + " expirar (convertido em saldo)", o.getId());
                return o;
            }
        }
        applyPaidEffects(o);
        return o;
    }

    /**
     * Pagamento misto: cria o pedido PENDENTE e reserva (debita) a parte em saldo numa transação só.
     * Se o saldo não bastar, nada é gravado. O PIX do restante é gerado depois, pelo chamador.
     */
    @Transactional
    public StoreOrder createPendingWithBalance(Long customerId, List<Product> chosen, int subtotalCents, int discountCents,
                                               Coupon coupon, int pixCents, int balanceCents, Instant expiresAt) {
        StoreOrder o = new StoreOrder(customerId, pixCents, expiresAt);
        o.setPurchase(subtotalCents, discountCents, coupon == null ? null : coupon.getId(),
                coupon == null ? null : coupon.getCode(), false);
        o.setPartialBalance(balanceCents);
        o = orders.save(o);
        for (Product p : chosen) {
            items.save(new OrderItem(o.getId(), p.getId(), p.getPriceCents()));
        }
        wallet.debit(customerId, balanceCents, WalletTx.PURCHASE,
                "Parte paga com saldo (pedido #" + o.getId() + ")", o.getId());
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
        if (orders.claimStatus(orderId, status) == 0) {
            return; // não estava pendente (ou outra chamada acabou de mudar)
        }
        StoreOrder o = orders.findById(orderId).orElseThrow();
        if (!o.isDeposit() && o.getPartialBalanceCents() > 0 && !o.isBalanceRefunded()) {
            wallet.credit(o.getCustomerId(), o.getPartialBalanceCents(), WalletTx.REFUND,
                    "Saldo devolvido (pedido #" + o.getId() + " não foi pago)", o.getId());
            o.setBalanceRefunded(true);
            orders.save(o);
        }
    }
}
