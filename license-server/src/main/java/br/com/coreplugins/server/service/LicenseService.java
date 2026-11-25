package br.com.coreplugins.server.service;

import br.com.coreplugins.protocol.Protocol;
import br.com.coreplugins.server.crypto.SigningService;
import br.com.coreplugins.server.model.AuthorizedServer;
import br.com.coreplugins.server.model.Product;
import br.com.coreplugins.server.model.ProductVersion;
import br.com.coreplugins.server.model.ServerProduct;
import br.com.coreplugins.server.repo.AuthorizedServerRepository;
import br.com.coreplugins.server.repo.ProductRepository;
import br.com.coreplugins.server.repo.ProductVersionRepository;
import br.com.coreplugins.server.repo.ServerProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Regra de autenticação: o IP:porta de origem tem que estar cadastrado, ativo, e com o produto marcado. */
@Service
public class LicenseService {

    /** Resposta pronta para virar JSON. */
    public record AuthResponse(String status, String address, long issuedAt, String nonce,
                               List<GrantDto> products, String signature) {
    }

    public record GrantDto(String id, String version, String sha256) {
    }

    private final AuthorizedServerRepository servers;
    private final ServerProductRepository serverProducts;
    private final ProductRepository products;
    private final ProductVersionRepository versions;
    private final SigningService signing;

    public LicenseService(AuthorizedServerRepository servers, ServerProductRepository serverProducts,
                          ProductRepository products, ProductVersionRepository versions, SigningService signing) {
        this.servers = servers;
        this.serverProducts = serverProducts;
        this.products = products;
        this.versions = versions;
        this.signing = signing;
    }

    @Transactional
    public AuthResponse authenticate(String ip, int port, String nonce) {
        String address = Protocol.address(ip, port);
        Optional<AuthorizedServer> found = servers.findByIpAndPort(ip, port);
        if (found.isEmpty()) {
            return signed(Protocol.STATUS_NOT_FOUND, address, nonce, List.of());
        }
        AuthorizedServer server = found.get();
        if (!server.isActive()) {
            return signed(Protocol.STATUS_REVOKED, address, nonce, List.of());
        }
        server.setLastSeenAt(Instant.now());

        List<GrantDto> grants = new ArrayList<>();
        Instant now = Instant.now();
        for (ServerProduct sp : serverProducts.findByServerId(server.getId())) {
            if (sp.getExpiresAt() != null && sp.getExpiresAt().isBefore(now)) {
                continue;
            }
            Product p = products.findById(sp.getProductId()).orElse(null);
            if (p == null || !p.isActive() || p.getLatestVersion() == null) {
                continue;
            }
            // Versão escolhida pelo cliente; se não escolheu (ou ela não existe mais), a mais recente.
            Optional<ProductVersion> chosen = sp.getVersion() == null ? Optional.empty()
                    : versions.findByProductIdAndVersion(p.getId(), sp.getVersion());
            if (chosen.isEmpty()) {
                chosen = versions.findByProductIdAndVersion(p.getId(), p.getLatestVersion());
            }
            chosen.ifPresent(v -> grants.add(new GrantDto(p.getId(), v.getVersion(), v.getSha256())));
        }
        return signed(Protocol.STATUS_OK, address, nonce, grants);
    }

    /** Confere se este IP:porta pode baixar o produto. Devolve a versão pedida (ou a mais recente liberada). */
    @Transactional(readOnly = true)
    public Optional<ProductVersion> authorizeDownload(String ip, int port, String productId, String version) {
        AuthorizedServer server = servers.findByIpAndPort(ip, port).orElse(null);
        if (server == null || !server.isActive()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        boolean licensed = serverProducts.findByServerId(server.getId()).stream()
                .anyMatch(sp -> sp.getProductId().equals(productId)
                        && (sp.getExpiresAt() == null || sp.getExpiresAt().isAfter(now)));
        if (!licensed) {
            return Optional.empty();
        }
        Product p = products.findById(productId).orElse(null);
        if (p == null || !p.isActive()) {
            return Optional.empty();
        }
        String wanted = (version == null || version.isBlank()) ? p.getLatestVersion() : version;
        if (wanted == null) {
            return Optional.empty();
        }
        return versions.findByProductIdAndVersion(productId, wanted);
    }

    private AuthResponse signed(String status, String address, String nonce, List<GrantDto> grants) {
        long issuedAt = System.currentTimeMillis();
        List<Protocol.Grant> pg = new ArrayList<>();
        for (GrantDto g : grants) {
            pg.add(new Protocol.Grant(g.id(), g.version(), g.sha256()));
        }
        String signature = signing.sign(Protocol.canonical(status, address, issuedAt, nonce, pg));
        return new AuthResponse(status, address, issuedAt, nonce, grants, signature);
    }
}
