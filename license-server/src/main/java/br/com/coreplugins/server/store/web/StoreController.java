package br.com.coreplugins.server.store.web;

import br.com.coreplugins.server.config.LicenseProperties;
import br.com.coreplugins.server.model.Customer;
import br.com.coreplugins.server.model.Product;
import br.com.coreplugins.server.model.ProductVersion;
import br.com.coreplugins.server.repo.ProductRepository;
import br.com.coreplugins.server.repo.ProductVersionRepository;
import br.com.coreplugins.server.service.AdminService;
import br.com.coreplugins.server.service.ClientIp;
import br.com.coreplugins.server.service.LoaderService;
import br.com.coreplugins.server.service.ProductService;
import br.com.coreplugins.server.store.model.Entitlement;
import br.com.coreplugins.server.store.repo.EntitlementRepository;
import br.com.coreplugins.server.store.security.UserAuthFilter;
import br.com.coreplugins.server.store.service.AuthService;
import br.com.coreplugins.server.store.service.CheckinService;
import br.com.coreplugins.server.store.service.OrderService;
import br.com.coreplugins.server.store.service.StoreServerService;
import br.com.coreplugins.server.store.service.WalletService;
import br.com.coreplugins.server.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;

/**
 * API do site: catálogo, conta, pedidos, biblioteca e servidores (Área do
 * Cliente).
 */
@RestController
@RequestMapping("/api/store")
public class StoreController {

    public record RegisterReq(String name, String email, String password) {
    }

    public record LoginReq(String email, String password) {
    }

    public record PasswordReq(String currentPassword, String newPassword) {
    }

    /** {@code useBalance}: usa o saldo no pagamento (cobre tudo ou, se não bastar, parte, e o resto vai no PIX). {@code couponCode}: cupom de desconto (opcional). */
    public record CheckoutReq(List<String> productIds, String couponCode, Boolean useBalance) {
    }

    public record QuoteReq(List<String> productIds, String couponCode) {
    }

    public record DepositReq(Integer amountCents, String couponCode) {
    }

    public record ServerReq(String ip, Integer port, String label) {
    }

    public record AddressReq(String ip, Integer port) {
    }

    public record ProductsReq(List<StoreServerService.Selection> products) {
    }

    /**
     * {@code images}: ids das imagens na ordem da galeria (a primeira é a capa), servidas em
     * /api/store/products/{id}/images/{imageId}. {@code youtubeId}: vídeo de showcase (null = sem vídeo).
     */
    public record CatalogItem(String id, String name, String description, String category, String command,
            int priceCents, String version, List<Long> images, String youtubeId) {
    }

    public record VersionView(String version, Instant createdAt) {
    }

    /**
     * Plugin comprado + todas as versões que o cliente pode escolher (mais nova
     * primeiro).
     */
    public record LibraryItem(String id, String name, String description, String category, String command,
            int priceCents, String version, List<VersionView> versions) {
    }

    public record Me(Long id, String name, String email, long balanceCents) {
    }

    /** Uma versão publicada de um plugin que o cliente comprou, com as notas ({@code changelog}, pode ser null). */
    public record UpdateView(String productId, String productName, String category, String version,
            String changelog, Instant createdAt, boolean latest) {
    }

    /** O corePlugins.jar (o loader) que o cliente instala: se há arquivo para baixar, o tamanho e a data dele. */
    public record LoaderInfo(boolean available, long sizeBytes, Instant updatedAt) {
    }

    private final AuthService auth;
    private final OrderService orders;
    private final StoreServerService servers;
    private final ProductRepository products;
    private final EntitlementRepository entitlements;
    private final ProductVersionRepository versions;
    private final LicenseProperties props;
    private final WalletService wallet;
    private final ProductService productService;
    private final CheckinService checkin;
    private final LoaderService loader;

    public StoreController(AuthService auth, OrderService orders, StoreServerService servers,
            ProductRepository products, EntitlementRepository entitlements,
            ProductVersionRepository versions, LicenseProperties props, WalletService wallet,
            ProductService productService, CheckinService checkin, LoaderService loader) {
        this.auth = auth;
        this.orders = orders;
        this.servers = servers;
        this.products = products;
        this.entitlements = entitlements;
        this.versions = versions;
        this.props = props;
        this.wallet = wallet;
        this.productService = productService;
        this.checkin = checkin;
        this.loader = loader;
    }

    // ---- público

    @GetMapping("/catalog")
    public List<CatalogItem> catalog() {
        List<CatalogItem> out = new ArrayList<>();
        Map<String, List<Long>> media = productService.imageIdsByProduct();
        for (Product p : products.findAll()) {
            if (p.isActive() && p.getPriceCents() > 0 && p.getLatestVersion() != null) {
                out.add(item(p, media.getOrDefault(p.getId(), List.of())));
            }
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    /**
     * Imagem da vitrine (pública, sem login). O conteúdo de um id nunca muda, então o navegador pode guardar para sempre.
     * O tipo vem do que foi detectado nos bytes no upload (só png/jpeg/webp/gif); nosniff + CSP restrita evitam que
     * algo estranho seja interpretado como página.
     */
    @GetMapping("/products/{id}/images/{imageId}")
    public ResponseEntity<byte[]> image(@PathVariable String id, @PathVariable Long imageId) {
        return productService.imageContent(id, imageId)
                .map(img -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(img.contentType()))
                        .contentLength(img.data().length)
                        .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
                        .header("X-Content-Type-Options", "nosniff")
                        .header("Content-Security-Policy", "default-src 'none'; sandbox")
                        .body(img.data()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/auth/register")
    public AuthService.Session register(@RequestBody RegisterReq r, HttpServletRequest req) {
        return auth.register(r.name(), r.email(), r.password(), ClientIp.safe(req, props.trustForwardedFor()));
    }

    @PostMapping("/auth/login")
    public AuthService.Session login(@RequestBody LoginReq r, HttpServletRequest req) {
        return auth.login(r.email(), r.password(), ClientIp.safe(req, props.trustForwardedFor()));
    }

    /**
     * Webhook do Mercado Pago: o corpo só diz qual pagamento consultar. Sempre 200
     * para não gerar reentregas à toa.
     */
    @PostMapping("/mercadopago/webhook")
    public Map<String, Object> webhook(@RequestBody(required = false) JsonNode body,
            @RequestParam(value = "data.id", required = false) String queryId) {
        String id = queryId;
        if (id == null && body != null) {
            id = body.path("data").path("id").asText(null);
        }
        orders.onPaymentNotification(id);
        return Map.of("ok", true);
    }

    // ---- logado

    /**
     * Fora de /auth/ de propósito: tudo em /auth/ é público, e estas duas exigem
     * estar logado.
     */
    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest req) {
        auth.logout((Long) req.getAttribute(UserAuthFilter.USER_ATTR));
        return Map.of("ok", true);
    }

    @PutMapping("/password")
    public AuthService.Session changePassword(@RequestBody PasswordReq r, HttpServletRequest req) {
        return auth.changePassword((Long) req.getAttribute(UserAuthFilter.USER_ATTR), r.currentPassword(),
                r.newPassword(),
                ClientIp.safe(req, props.trustForwardedFor()));
    }

    @GetMapping("/me")
    public Me me(HttpServletRequest req) {
        Customer c = customer(req);
        orders.releaseExpired(c.getId());
        return new Me(c.getId(), c.getName(), c.getEmail(), wallet.balance(c.getId()));
    }

    @PostMapping("/orders")
    public OrderService.OrderView checkout(@RequestBody CheckoutReq r, HttpServletRequest req) {
        return orders.checkout(customer(req), r.productIds(), r.couponCode(), Boolean.TRUE.equals(r.useBalance()));
    }

    /** Prévia do carrinho: subtotal, desconto do cupom, total e saldo do cliente. */
    @PostMapping("/quote")
    public OrderService.Quote quote(@RequestBody QuoteReq r, HttpServletRequest req) {
        return orders.quote(customer(req), r.productIds(), r.couponCode());
    }

    /** Saldo e extrato da carteira. */
    @GetMapping("/wallet")
    public WalletService.WalletView wallet(HttpServletRequest req) {
        orders.releaseExpired(customer(req).getId());
        return wallet.view(customer(req).getId());
    }

    @PostMapping("/deposits/quote")
    public OrderService.DepositQuote quoteDeposit(@RequestBody DepositReq r, HttpServletRequest req) {
        return orders.quoteDeposit(customer(req), r.amountCents() == null ? 0 : r.amountCents(), r.couponCode());
    }

    /** Cria um depósito (PIX). O saldo só entra quando o pagamento é confirmado. */
    @PostMapping("/deposits")
    public OrderService.OrderView deposit(@RequestBody DepositReq r, HttpServletRequest req) {
        return orders.createDeposit(customer(req), r.amountCents() == null ? 0 : r.amountCents(), r.couponCode());
    }

    @GetMapping("/orders")
    public List<OrderService.OrderView> orders(HttpServletRequest req) {
        return orders.list(customer(req).getId());
    }

    @GetMapping("/orders/{id}")
    public OrderService.OrderView order(@PathVariable Long id, HttpServletRequest req) {
        return orders.get(customer(req).getId(), id);
    }

    @PostMapping("/orders/{id}/simulate-pay")
    public OrderService.OrderView simulatePay(@PathVariable Long id, HttpServletRequest req) {
        return orders.simulatePay(customer(req).getId(), id);
    }

    /** Plugins que o cliente comprou. */
    @GetMapping("/library")
    public List<LibraryItem> library(HttpServletRequest req) {
        List<LibraryItem> out = new ArrayList<>();
        for (Entitlement e : entitlements.findByCustomerId(customer(req).getId())) {
            products.findById(e.getProductId()).ifPresent(p -> {
                List<VersionView> vs = new ArrayList<>();
                for (ProductVersion v : versions.findByProductIdOrderByCreatedAtDesc(p.getId())) {
                    vs.add(new VersionView(v.getVersion(), v.getCreatedAt()));
                }
                out.add(new LibraryItem(p.getId(), p.getName(), p.getDescription(), p.getCategory(), p.getCommand(),
                        p.getPriceCents(), p.getLatestVersion(), vs));
            });
        }
        return out;
    }

    /**
     * Novidades: versões publicadas dos plugins que o cliente comprou, da mais nova para a mais antiga (até 20).
     * Versões enviadas depois da "mais recente" (ainda não liberadas) não aparecem.
     */
    @GetMapping("/updates")
    public List<UpdateView> updates(HttpServletRequest req) {
        List<UpdateView> out = new ArrayList<>();
        for (Entitlement e : entitlements.findByCustomerId(customer(req).getId())) {
            products.findById(e.getProductId()).ifPresent(p -> {
                List<ProductVersion> vs = versions.findByProductIdOrderByCreatedAtDesc(p.getId());
                Instant released = null;
                for (ProductVersion v : vs) {
                    if (v.getVersion().equals(p.getLatestVersion())) {
                        released = v.getCreatedAt();
                    }
                }
                for (ProductVersion v : vs) {
                    if (released != null && !v.getCreatedAt().isAfter(released)) {
                        out.add(new UpdateView(p.getId(), p.getName(), p.getCategory(), v.getVersion(), v.getChangelog(),
                                v.getCreatedAt(), v.getVersion().equals(p.getLatestVersion())));
                    }
                }
            });
        }
        out.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
        return out.size() > 20 ? new ArrayList<>(out.subList(0, 20)) : out;
    }

    // ---- check-in diário

    @GetMapping("/checkin")
    public CheckinService.View checkinView(HttpServletRequest req) {
        return checkin.view(customer(req).getId());
    }

    @PostMapping("/checkin")
    public CheckinService.Claimed checkinClaim(HttpServletRequest req) {
        Long id = customer(req).getId();
        try {
            return checkin.claim(id);
        } catch (DataIntegrityViolationException e) {
            // dois cliques ao mesmo tempo: o segundo bateu na restrição (cliente, dia) e nada foi creditado
            throw ApiException.bad("Você já fez o check-in de hoje. Volte amanhã!");
        }
    }

    // ---- download do loader (corePlugins.jar). Exige login: não está na lista de rotas públicas do UserAuthFilter.

    @GetMapping("/loader")
    public LoaderInfo loaderInfo() {
        LoaderService.Info i = loader.info();
        return new LoaderInfo(i.available(), i.sizeBytes(), i.updatedAt());
    }

    @GetMapping("/loader/download")
    public ResponseEntity<byte[]> loaderDownload() throws IOException {
        byte[] data = loader.bytes()
                .orElseThrow(() -> ApiException.notFound("O corePlugins.jar ainda não está disponível para download."));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/java-archive"))
                .contentLength(data.length)
                .cacheControl(CacheControl.noStore())
                .header("Content-Disposition", "attachment; filename=\"corePlugins.jar\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(data);
    }

    @GetMapping("/servers")
    public List<AdminService.ServerView> servers(HttpServletRequest req) {
        return servers.list(customer(req).getId());
    }

    @PostMapping("/servers")
    public AdminService.ServerView createServer(@RequestBody ServerReq r, HttpServletRequest req) {
        return servers.create(customer(req).getId(), r.ip(), r.port(), r.label());
    }

    @PutMapping("/servers/{id}/address")
    public AdminService.ServerView changeAddress(@PathVariable Long id, @RequestBody AddressReq r,
            HttpServletRequest req) {
        return servers.changeAddress(customer(req).getId(), id, r.ip(), r.port());
    }

    /**
     * O botão "Atualizar": define exatamente quais plugins rodam neste IP:porta.
     */
    @PutMapping("/servers/{id}/products")
    public AdminService.ServerView setProducts(@PathVariable Long id, @RequestBody ProductsReq r,
            HttpServletRequest req) {
        return servers.setProducts(customer(req).getId(), id, r.products());
    }

    private Customer customer(HttpServletRequest req) {
        return auth.require((Long) req.getAttribute(UserAuthFilter.USER_ATTR));
    }

    private static CatalogItem item(Product p, List<Long> images) {
        return new CatalogItem(p.getId(), p.getName(), p.getDescription(), p.getCategory(), p.getCommand(),
                p.getPriceCents(), p.getLatestVersion(), images, p.getYoutubeId());
    }
}
