package br.com.coreplugins.server.service;

import br.com.coreplugins.server.model.AuthorizedServer;
import br.com.coreplugins.server.model.Customer;
import br.com.coreplugins.server.model.ServerProduct;
import br.com.coreplugins.server.repo.AuthorizedServerRepository;
import br.com.coreplugins.server.repo.CustomerRepository;
import br.com.coreplugins.server.repo.ProductRepository;
import br.com.coreplugins.server.repo.ProductVersionRepository;
import br.com.coreplugins.server.repo.ServerProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Operações do painel admin / da Área do Cliente (cadastrar IP:porta, marcar plugins, trocar IP, revogar). */
@Service
public class AdminService {

    /** {@code version} nula = acompanhar a mais recente. */
    public record ProductGrantReq(String productId, Instant expiresAt, String version) {
    }

    public record ServerView(Long id, Long customerId, String ip, int port, String label, boolean active,
                             Instant lastSeenAt, List<ProductGrantReq> products) {
    }

    private final CustomerRepository customers;
    private final AuthorizedServerRepository servers;
    private final ServerProductRepository serverProducts;
    private final ProductRepository products;
    private final ProductVersionRepository versions;

    public AdminService(CustomerRepository customers, AuthorizedServerRepository servers,
                        ServerProductRepository serverProducts, ProductRepository products,
                        ProductVersionRepository versions) {
        this.customers = customers;
        this.servers = servers;
        this.serverProducts = serverProducts;
        this.products = products;
        this.versions = versions;
    }

    @Transactional
    public Customer createCustomer(String name, String email) {
        if (customers.existsByEmail(email)) {
            throw new IllegalArgumentException("Já existe cliente com este e-mail");
        }
        return customers.save(new Customer(name, email));
    }

    @Transactional
    public ServerView createServer(Long customerId, String ip, int port, String label, List<ProductGrantReq> grants) {
        customers.findById(customerId).orElseThrow(() -> new IllegalArgumentException("Cliente não existe"));
        String normalized = ClientIp.normalize(ip);
        if (servers.findByIpAndPort(normalized, port).isPresent()) {
            throw new IllegalArgumentException("IP:porta já cadastrado");
        }
        AuthorizedServer s = servers.save(new AuthorizedServer(customerId, normalized, port, label));
        replaceProducts(s.getId(), grants);
        return view(s);
    }

    /** "Trocar IP". */
    @Transactional
    public ServerView updateAddress(Long id, String ip, int port) {
        AuthorizedServer s = get(id);
        String normalized = ClientIp.normalize(ip);
        servers.findByIpAndPort(normalized, port).ifPresent(other -> {
            if (!other.getId().equals(id)) {
                throw new IllegalArgumentException("IP:porta já cadastrado em outro servidor");
            }
        });
        s.setIp(normalized);
        s.setPort(port);
        return view(s);
    }

    /** O "Atualizar" da Área do Cliente: define exatamente quais plugins rodam neste IP:porta. */
    @Transactional
    public ServerView setProducts(Long id, List<ProductGrantReq> grants) {
        AuthorizedServer s = get(id);
        replaceProducts(id, grants);
        return view(s);
    }

    @Transactional
    public ServerView setActive(Long id, boolean active) {
        AuthorizedServer s = get(id);
        s.setActive(active);
        return view(s);
    }

    @Transactional(readOnly = true)
    public List<ServerView> listServers(Long customerId) {
        List<AuthorizedServer> all = customerId == null ? servers.findAll() : servers.findByCustomerId(customerId);
        List<ServerView> out = new ArrayList<>();
        for (AuthorizedServer s : all) {
            out.add(view(s));
        }
        return out;
    }

    private void replaceProducts(Long serverId, List<ProductGrantReq> grants) {
        Set<String> seen = new HashSet<>();
        List<ServerProduct> toSave = new ArrayList<>();
        for (ProductGrantReq g : grants == null ? List.<ProductGrantReq>of() : grants) {
            if (!products.existsById(g.productId())) {
                throw new IllegalArgumentException("Produto '" + g.productId() + "' não existe");
            }
            String v = g.version() == null || g.version().isBlank() ? null : g.version().trim();
            if (v != null && versions.findByProductIdAndVersion(g.productId(), v).isEmpty()) {
                throw new IllegalArgumentException("Versão '" + v + "' do produto '" + g.productId() + "' não existe");
            }
            if (seen.add(g.productId())) {
                toSave.add(new ServerProduct(serverId, g.productId(), g.expiresAt(), v));
            }
        }
        serverProducts.deleteByServerId(serverId);
        serverProducts.flush();
        serverProducts.saveAll(toSave);
    }

    private AuthorizedServer get(Long id) {
        return servers.findById(id).orElseThrow(() -> new IllegalArgumentException("Servidor não existe"));
    }

    private ServerView view(AuthorizedServer s) {
        List<ProductGrantReq> list = new ArrayList<>();
        for (ServerProduct sp : serverProducts.findByServerId(s.getId())) {
            list.add(new ProductGrantReq(sp.getProductId(), sp.getExpiresAt(), sp.getVersion()));
        }
        return new ServerView(s.getId(), s.getCustomerId(), s.getIp(), s.getPort(), s.getLabel(), s.isActive(),
                s.getLastSeenAt(), list);
    }
}
