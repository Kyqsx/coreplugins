package br.com.coreplugins.server.store.service;

import br.com.coreplugins.server.model.Customer;
import br.com.coreplugins.server.model.Product;
import br.com.coreplugins.server.repo.ProductRepository;
import br.com.coreplugins.server.store.StoreProperties;
import br.com.coreplugins.server.store.model.Coupon;
import br.com.coreplugins.server.store.model.OrderItem;
import br.com.coreplugins.server.store.model.StoreOrder;
import br.com.coreplugins.server.store.payment.PaymentGateway;
import br.com.coreplugins.server.store.payment.PaymentState;
import br.com.coreplugins.server.store.payment.PixCharge;
import br.com.coreplugins.server.store.repo.EntitlementRepository;
import br.com.coreplugins.server.store.repo.OrderItemRepository;
import br.com.coreplugins.server.store.repo.StoreOrderRepository;
import br.com.coreplugins.server.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    public record ItemView(String productId, String name, int priceCents) {
    }

    /**
     * {@code totalCents} é o valor efetivamente cobrado (PIX ou saldo). {@code kind}: PURCHASE ou DEPOSIT.
     * Em depósito, {@code bonusCents} é o extra creditado pelo cupom de bônus.
     */
    public record OrderView(Long id, String status, int totalCents, String pixCode, String pixQrBase64,
                            Instant createdAt, Instant expiresAt, Instant paidAt, List<ItemView> items,
                            boolean simulated, String kind, int subtotalCents, int discountCents, int bonusCents,
                            String couponCode, boolean paidWithBalance) {
    }

    /** Prévia do carrinho com cupom e saldo (tudo calculado no servidor). */
    public record Quote(int subtotalCents, int discountCents, int totalCents, String couponCode, long balanceCents) {
    }

    public record DepositQuote(int amountCents, int bonusCents, int creditCents, String couponCode) {
    }

    public static final int MIN_DEPOSIT_CENTS = 500;
    public static final int MAX_DEPOSIT_CENTS = 200_000;

    private record Cart(List<Product> products, int subtotalCents) {
    }

    private final StoreOrderRepository orders;
    private final OrderItemRepository items;
    private final ProductRepository products;
    private final EntitlementRepository entitlements;
    private final FulfillmentService fulfillment;
    private final PaymentGateway gateway;
    private final StoreProperties props;
    private final CouponService coupons;
    private final WalletService wallet;

    public OrderService(StoreOrderRepository orders, OrderItemRepository items, ProductRepository products,
                        EntitlementRepository entitlements, FulfillmentService fulfillment, PaymentGateway gateway,
                        StoreProperties props, CouponService coupons, WalletService wallet) {
        this.orders = orders;
        this.items = items;
        this.products = products;
        this.entitlements = entitlements;
        this.fulfillment = fulfillment;
        this.gateway = gateway;
        this.props = props;
        this.coupons = coupons;
        this.wallet = wallet;
    }

    /** Valida os plugins escolhidos e soma o subtotal (preços SEMPRE lidos do banco, nunca do cliente). */
    private Cart priceCart(Customer customer, List<String> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            throw ApiException.bad("Escolha ao menos um plugin.");
        }
        Set<String> unique = new LinkedHashSet<>(productIds);
        if (unique.size() > 20) {
            throw ApiException.bad("Pedido grande demais.");
        }
        List<Product> chosen = new ArrayList<>();
        int total = 0;
        for (String id : unique) {
            Product p = products.findById(id).orElseThrow(() -> ApiException.bad("Plugin '" + id + "' não existe."));
            if (!p.isActive() || p.getPriceCents() <= 0 || p.getLatestVersion() == null) {
                throw ApiException.bad("O plugin " + p.getName() + " não está à venda.");
            }
            if (entitlements.existsByCustomerIdAndProductId(customer.getId(), id)) {
                throw ApiException.bad("Você já possui o plugin " + p.getName() + ".");
            }
            chosen.add(p);
            total += p.getPriceCents();
        }
        return new Cart(chosen, total);
    }

    private static boolean has(String code) {
        return code != null && !code.isBlank();
    }

    /** Mostra subtotal, desconto do cupom e total do carrinho, sem criar nada. */
    public Quote quote(Customer customer, List<String> productIds, String couponCode) {
        Cart cart = priceCart(customer, productIds);
        int discount = 0;
        String code = null;
        if (has(couponCode)) {
            Coupon c = coupons.validateDiscount(customer.getId(), couponCode, cart.subtotalCents());
            discount = c.compute(cart.subtotalCents());
            code = c.getCode();
        }
        return new Quote(cart.subtotalCents(), discount, cart.subtotalCents() - discount, code,
                wallet.balance(customer.getId()));
    }

    /**
     * Cria o pedido. Com {@code useBalance} paga na hora com o saldo (tudo ou nada); sem saldo, gera a cobrança PIX.
     * Se o cupom cobrir 100%, o pedido é liberado na hora, sem cobrar nada.
     */
    public OrderView checkout(Customer customer, List<String> productIds, String couponCode, boolean useBalance) {
        Cart cart = priceCart(customer, productIds);
        Coupon coupon = null;
        int discount = 0;
        if (has(couponCode)) {
            coupon = coupons.validateDiscount(customer.getId(), couponCode, cart.subtotalCents());
            discount = coupon.compute(cart.subtotalCents());
        }
        int total = cart.subtotalCents() - discount;

        if (useBalance || total == 0) {
            StoreOrder paid = fulfillment.createPaidWithBalance(customer.getId(), cart.products(), cart.subtotalCents(),
                    discount, coupon, total);
            return view(paid);
        }

        Instant expires = Instant.now().plus(props.orderExpiresMinutes(), ChronoUnit.MINUTES);
        StoreOrder order = new StoreOrder(customer.getId(), total, expires);
        order.setPurchase(cart.subtotalCents(), discount, coupon == null ? null : coupon.getId(),
                coupon == null ? null : coupon.getCode(), false);
        order = orders.save(order);
        for (Product p : cart.products()) {
            items.save(new OrderItem(order.getId(), p.getId(), p.getPriceCents()));
        }
        return view(attachPix(order, customer, "CorePlugins - pedido #" + order.getId()));
    }

    // ---- depósito de saldo

    public DepositQuote quoteDeposit(Customer customer, int amountCents, String couponCode) {
        checkDepositAmount(amountCents);
        int bonus = 0;
        String code = null;
        if (has(couponCode)) {
            Coupon c = coupons.validateBonus(customer.getId(), couponCode, amountCents);
            bonus = c.compute(amountCents);
            code = c.getCode();
        }
        return new DepositQuote(amountCents, bonus, amountCents + bonus, code);
    }

    /** Cria um pedido de depósito (PIX). Quando pago, o servidor credita o valor + o bônus do cupom no saldo. */
    public OrderView createDeposit(Customer customer, int amountCents, String couponCode) {
        checkDepositAmount(amountCents);
        Coupon coupon = null;
        int bonus = 0;
        if (has(couponCode)) {
            coupon = coupons.validateBonus(customer.getId(), couponCode, amountCents);
            bonus = coupon.compute(amountCents);
        }
        Instant expires = Instant.now().plus(props.orderExpiresMinutes(), ChronoUnit.MINUTES);
        StoreOrder order = new StoreOrder(customer.getId(), amountCents, expires);
        order.setDeposit(bonus, coupon == null ? null : coupon.getId(), coupon == null ? null : coupon.getCode());
        order = orders.save(order);
        return view(attachPix(order, customer, "CorePlugins - depósito #" + order.getId()));
    }

    private static void checkDepositAmount(int amountCents) {
        if (amountCents < MIN_DEPOSIT_CENTS || amountCents > MAX_DEPOSIT_CENTS) {
            throw ApiException.bad("O depósito deve ser de R$ " + MIN_DEPOSIT_CENTS / 100 + " a R$ "
                    + String.format("%,d", MAX_DEPOSIT_CENTS / 100).replace(',', '.') + ".");
        }
    }

    /** Cria a cobrança PIX do pedido; se o gateway falhar, cancela o pedido. */
    private StoreOrder attachPix(StoreOrder order, Customer customer, String desc) {
        try {
            PixCharge c = gateway.createPix(order.getId(), order.getTotalCents(), desc, customer.getEmail(), order.getExpiresAt());
            order.setPaymentId(c.paymentId());
            order.setPixCode(c.pixCode());
            order.setPixQrBase64(c.qrBase64());
            return orders.save(order);
        } catch (IOException e) {
            log.error("Falha ao criar PIX do pedido {}: {}", order.getId(), e.getMessage());
            fulfillment.setStatus(order.getId(), StoreOrder.CANCELLED);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Não foi possível gerar o PIX agora. Tente novamente em instantes.");
        }
    }

    public List<OrderView> list(Long customerId) {
        List<OrderView> out = new ArrayList<>();
        for (StoreOrder o : orders.findByCustomerIdOrderByCreatedAtDesc(customerId)) {
            out.add(view(o));
        }
        return out;
    }

    /** Consulta o pedido; se ainda pendente, pergunta ao gateway (funciona sem webhook, ex.: localhost). */
    public OrderView get(Long customerId, Long orderId) {
        StoreOrder o = owned(customerId, orderId);
        if (StoreOrder.PENDING.equals(o.getStatus())) {
            o = refresh(o);
        }
        return view(o);
    }

    /** Só no modo simulado: aprova o pedido como se o PIX tivesse sido pago. */
    public OrderView simulatePay(Long customerId, Long orderId) {
        if (!gateway.simulated()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Pagamento simulado desativado.");
        }
        StoreOrder o = owned(customerId, orderId);
        if (!StoreOrder.PENDING.equals(o.getStatus())) {
            return view(o);
        }
        return view(fulfillment.markPaid(o.getId()));
    }

    /** Webhook do Mercado Pago: o corpo só indica QUAL pagamento consultar; o estado vem da API do gateway. */
    public void onPaymentNotification(String paymentId) {
        if (paymentId == null || paymentId.isBlank()) {
            return;
        }
        try {
            String ref = gateway.externalReference(paymentId);
            if (ref == null || !ref.matches("\\d{1,18}")) {
                return;
            }
            StoreOrder o = orders.findById(Long.parseLong(ref)).orElse(null);
            if (o == null || !paymentId.equals(o.getPaymentId())) {
                return;
            }
            if (StoreOrder.PENDING.equals(o.getStatus())) {
                refresh(o);
            } else if (!StoreOrder.PAID.equals(o.getStatus())
                    && gateway.state(paymentId) == PaymentState.APPROVED) {
                // Pago depois de o pedido expirar/cancelar aqui: o dinheiro entrou, então o plugin é liberado.
                log.warn("Pedido {} estava {} mas o pagamento {} foi aprovado; liberando.", o.getId(), o.getStatus(), paymentId);
                fulfillment.markPaid(o.getId());
            }
        } catch (IOException e) {
            log.warn("Webhook: não consegui consultar o pagamento {}: {}", paymentId, e.getMessage());
        }
    }

    private StoreOrder refresh(StoreOrder o) {
        try {
            if (o.getPaymentId() != null) {
                PaymentState s = gateway.state(o.getPaymentId());
                if (s == PaymentState.APPROVED) {
                    return fulfillment.markPaid(o.getId());
                }
                if (s == PaymentState.REJECTED) {
                    fulfillment.setStatus(o.getId(), StoreOrder.CANCELLED);
                    return orders.findById(o.getId()).orElse(o);
                }
            }
        } catch (IOException e) {
            log.warn("Não consegui consultar o pagamento do pedido {}: {}", o.getId(), e.getMessage());
        }
        if (Instant.now().isAfter(o.getExpiresAt())) {
            fulfillment.setStatus(o.getId(), StoreOrder.EXPIRED);
            return orders.findById(o.getId()).orElse(o);
        }
        return o;
    }

    private StoreOrder owned(Long customerId, Long orderId) {
        StoreOrder o = orders.findById(orderId).orElseThrow(() -> ApiException.notFound("Pedido não encontrado."));
        if (!o.getCustomerId().equals(customerId)) {
            throw ApiException.notFound("Pedido não encontrado.");
        }
        return o;
    }

    private OrderView view(StoreOrder o) {
        List<ItemView> list = new ArrayList<>();
        for (OrderItem it : items.findByOrderId(o.getId())) {
            String name = products.findById(it.getProductId()).map(Product::getName).orElse(it.getProductId());
            list.add(new ItemView(it.getProductId(), name, it.getPriceCents()));
        }
        boolean pending = StoreOrder.PENDING.equals(o.getStatus());
        return new OrderView(o.getId(), o.getStatus(), o.getTotalCents(), pending ? o.getPixCode() : null,
                pending ? o.getPixQrBase64() : null, o.getCreatedAt(), o.getExpiresAt(), o.getPaidAt(), list,
                gateway.simulated(), o.getKind(), o.getSubtotalCents(), o.getDiscountCents(), o.getBonusCents(),
                o.getCouponCode(), o.isPaidWithBalance());
    }
}
