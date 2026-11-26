package br.com.coreplugins.server.store.web;

import br.com.coreplugins.server.model.AuthorizedServer;
import br.com.coreplugins.server.model.Customer;
import br.com.coreplugins.server.model.Product;
import br.com.coreplugins.server.repo.AuthorizedServerRepository;
import br.com.coreplugins.server.repo.CustomerRepository;
import br.com.coreplugins.server.repo.ProductRepository;
import br.com.coreplugins.server.repo.ProductVersionRepository;
import br.com.coreplugins.server.repo.ServerProductRepository;
import br.com.coreplugins.server.service.ProductService;
import br.com.coreplugins.server.store.model.Coupon;
import br.com.coreplugins.server.store.model.Entitlement;
import br.com.coreplugins.server.store.model.OrderItem;
import br.com.coreplugins.server.store.model.StoreOrder;
import br.com.coreplugins.server.store.repo.CouponRepository;
import br.com.coreplugins.server.store.repo.EntitlementRepository;
import br.com.coreplugins.server.store.repo.OrderItemRepository;
import br.com.coreplugins.server.store.repo.StoreOrderRepository;
import br.com.coreplugins.server.store.service.CouponService;
import br.com.coreplugins.server.store.service.WalletService;
import br.com.coreplugins.server.web.ApiException;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Endpoints do painel de administração do site (protegidos pela sessão de administrador, como todo /api/admin/**). */
@RestController
@RequestMapping("/api/admin")
public class AdminStoreController {

    public record OrderRow(Long id, Long customerId, String customerEmail, String status, int totalCents,
                           Instant createdAt, Instant paidAt, List<String> items, String kind, int discountCents,
                           int bonusCents, String couponCode, boolean paidWithBalance, int balanceCents) {
    }

    public record CouponRow(Long id, String code, String kind, String valueType, int value, int minCents, Integer capCents,
                            Integer maxUses, Integer maxUsesPerCustomer, int usedCount, Instant expiresAt, boolean active,
                            Instant createdAt) {
    }

    public record ActiveReq(Boolean active) {
    }

    public record BalanceReq(Long amountCents, String note) {
    }

    public record EntitlementRow(Long customerId, String productId, Instant createdAt) {
    }

    public record ProductUpdate(String name, String description, Integer priceCents, String category, String command,
                                Boolean active, String youtubeUrl) {
    }

    public record LatestReq(String version) {
    }

    public record GrantReq(String productId) {
    }

    private final StoreOrderRepository orders;
    private final OrderItemRepository items;
    private final CustomerRepository customers;
    private final ProductRepository products;
    private final ProductVersionRepository versions;
    private final EntitlementRepository entitlements;
    private final AuthorizedServerRepository servers;
    private final ServerProductRepository serverProducts;
    private final CouponRepository couponRepo;
    private final CouponService couponService;
    private final WalletService wallet;

    public AdminStoreController(StoreOrderRepository orders, OrderItemRepository items, CustomerRepository customers,
                                ProductRepository products, ProductVersionRepository versions,
                                EntitlementRepository entitlements, AuthorizedServerRepository servers,
                                ServerProductRepository serverProducts, CouponRepository couponRepo,
                                CouponService couponService, WalletService wallet) {
        this.orders = orders;
        this.items = items;
        this.customers = customers;
        this.products = products;
        this.versions = versions;
        this.entitlements = entitlements;
        this.servers = servers;
        this.serverProducts = serverProducts;
        this.couponRepo = couponRepo;
        this.couponService = couponService;
        this.wallet = wallet;
    }

    @GetMapping("/orders")
    public List<OrderRow> orders() {
        List<OrderRow> out = new ArrayList<>();
        for (StoreOrder o : orders.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))) {
            String email = customers.findById(o.getCustomerId()).map(Customer::getEmail).orElse("?");
            List<String> names = new ArrayList<>();
            for (OrderItem it : items.findByOrderId(o.getId())) {
                names.add(products.findById(it.getProductId()).map(Product::getName).orElse(it.getProductId()));
            }
            if (o.isDeposit()) {
                names.add(o.getBonusCents() > 0 ? "Depósito de saldo (+ bônus)" : "Depósito de saldo");
            }
            out.add(new OrderRow(o.getId(), o.getCustomerId(), email, o.getStatus(), o.getTotalCents(),
                    o.getCreatedAt(), o.getPaidAt(), names, o.getKind(), o.getDiscountCents(), o.getBonusCents(),
                    o.getCouponCode(), o.isPaidWithBalance(), o.getBalanceCents()));
        }
        return out;
    }

    @GetMapping("/entitlements")
    public List<EntitlementRow> entitlements() {
        List<EntitlementRow> out = new ArrayList<>();
        for (Entitlement e : entitlements.findAll()) {
            out.add(new EntitlementRow(e.getCustomerId(), e.getProductId(), e.getCreatedAt()));
        }
        return out;
    }

    /** Cortesia / suporte: libera um plugin para o cliente sem pedido. */
    @PostMapping("/customers/{id}/entitlements")
    @Transactional
    public Map<String, Object> grant(@PathVariable Long id, @RequestBody GrantReq r) {
        if (!customers.existsById(id)) {
            throw ApiException.notFound("Cliente não existe.");
        }
        if (r.productId() == null || !products.existsById(r.productId())) {
            throw ApiException.bad("Produto não existe.");
        }
        if (!entitlements.existsByCustomerIdAndProductId(id, r.productId())) {
            entitlements.save(new Entitlement(id, r.productId(), null));
        }
        return Map.of("ok", true);
    }

    /** Tira um plugin do cliente: apaga o direito de uso e remove o plugin dos servidores dele. Não dá para desfazer (só liberando de novo). */
    @DeleteMapping("/customers/{id}/entitlements/{productId}")
    @Transactional
    public Map<String, Object> revoke(@PathVariable Long id, @PathVariable String productId) {
        if (!customers.existsById(id)) {
            throw ApiException.notFound("Cliente não existe.");
        }
        if (!entitlements.existsByCustomerIdAndProductId(id, productId)) {
            throw ApiException.notFound("O cliente não tem este plugin.");
        }
        entitlements.deleteByCustomerIdAndProductId(id, productId);
        for (AuthorizedServer s : servers.findByCustomerId(id)) {
            serverProducts.deleteByServerIdAndProductId(s.getId(), productId);
        }
        return Map.of("ok", true);
    }

    // ---- saldo e cupons

    /** Credita (valor positivo) ou debita (negativo) o saldo de um cliente. Fica registrado no extrato dele. */
    @PostMapping("/customers/{id}/balance")
    public Map<String, Object> adjustBalance(@PathVariable Long id, @RequestBody BalanceReq r) {
        if (!customers.existsById(id)) {
            throw ApiException.notFound("Cliente não existe.");
        }
        long cents = r.amountCents() == null ? 0L : r.amountCents();
        long balance = wallet.adjust(id, cents, r.note());
        return Map.of("ok", true, "balanceCents", balance);
    }

    @GetMapping("/coupons")
    public List<CouponRow> coupons() {
        List<CouponRow> out = new ArrayList<>();
        for (Coupon c : couponRepo.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))) {
            out.add(row(c));
        }
        return out;
    }

    @PostMapping("/coupons")
    public CouponRow createCoupon(@RequestBody CouponService.CouponReq r) {
        return row(couponService.create(r));
    }

    @PutMapping("/coupons/{id}/active")
    @Transactional
    public CouponRow setCouponActive(@PathVariable Long id, @RequestBody ActiveReq r) {
        Coupon c = couponRepo.findById(id).orElseThrow(() -> ApiException.notFound("Cupom não existe."));
        c.setActive(Boolean.TRUE.equals(r.active()));
        return row(c);
    }

    @DeleteMapping("/coupons/{id}")
    public Map<String, Object> deleteCoupon(@PathVariable Long id) {
        if (!couponRepo.existsById(id)) {
            throw ApiException.notFound("Cupom não existe.");
        }
        couponRepo.deleteById(id);
        return Map.of("ok", true);
    }

    private static CouponRow row(Coupon c) {
        return new CouponRow(c.getId(), c.getCode(), c.getKind(), c.getValueType(), c.getValue(), c.getMinCents(),
                c.getCapCents(), c.getMaxUses(), c.getMaxUsesPerCustomer(), c.getUsedCount(), c.getExpiresAt(),
                c.isActive(), c.getCreatedAt());
    }

    @PutMapping("/products/{id}")
    @Transactional
    public Product update(@PathVariable String id, @RequestBody ProductUpdate r) {
        Product p = products.findById(id).orElseThrow(() -> ApiException.notFound("Produto não existe."));
        if (r.name() != null) {
            if (r.name().isBlank()) {
                throw ApiException.bad("Nome não pode ficar vazio.");
            }
            p.setName(r.name().trim());
        }
        if (r.description() != null) {
            p.setDescription(r.description());
        }
        if (r.priceCents() != null) {
            if (r.priceCents() < 0) {
                throw ApiException.bad("Preço inválido.");
            }
            p.setPriceCents(r.priceCents());
        }
        if (r.category() != null) {
            p.setCategory(r.category().isBlank() ? null : r.category().trim());
        }
        if (r.command() != null) {
            p.setCommand(r.command().isBlank() ? null : r.command().trim());
        }
        if (r.active() != null) {
            p.setActive(r.active());
        }
        // Vídeo de showcase: link do YouTube (ou só o ID). Texto vazio remove; null = não mexe.
        if (r.youtubeUrl() != null) {
            try {
                p.setYoutubeId(ProductService.parseYoutubeId(r.youtubeUrl()));
            } catch (IllegalArgumentException e) {
                throw ApiException.bad(e.getMessage());
            }
        }
        return p;
    }

    /** Define qual versão os clientes que "acompanham a mais recente" recebem. */
    @PutMapping("/products/{id}/latest")
    @Transactional
    public Product setLatest(@PathVariable String id, @RequestBody LatestReq r) {
        Product p = products.findById(id).orElseThrow(() -> ApiException.notFound("Produto não existe."));
        if (r.version() == null || versions.findByProductIdAndVersion(id, r.version()).isEmpty()) {
            throw ApiException.bad("Versão não existe.");
        }
        p.setLatestVersion(r.version());
        return p;
    }
}
