package br.com.coreplugins.server.store.service;

import br.com.coreplugins.server.model.AuthorizedServer;
import br.com.coreplugins.server.repo.AuthorizedServerRepository;
import br.com.coreplugins.server.service.AdminService;
import br.com.coreplugins.server.store.StoreProperties;
import br.com.coreplugins.server.store.model.Entitlement;
import br.com.coreplugins.server.store.repo.EntitlementRepository;
import br.com.coreplugins.server.web.ApiException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A Área do Cliente: cadastrar IP:porta, trocar IP e marcar os plugins ("Atualizar"). Tudo restrito ao dono e só
 * com plugins que ele comprou — é aqui que "pagou → liberado" vira a autorização que o loader consulta.
 */
@Service
public class StoreServerService {

    /** Plugin marcado no servidor; {@code version} vazia = acompanhar a mais recente. */
    public record Selection(String productId, String version) {
    }

    private final AdminService admin;
    private final AuthorizedServerRepository servers;
    private final EntitlementRepository entitlements;
    private final StoreProperties props;

    public StoreServerService(AdminService admin, AuthorizedServerRepository servers,
                              EntitlementRepository entitlements, StoreProperties props) {
        this.admin = admin;
        this.servers = servers;
        this.entitlements = entitlements;
        this.props = props;
    }

    public List<AdminService.ServerView> list(Long customerId) {
        return admin.listServers(customerId);
    }

    public AdminService.ServerView create(Long customerId, String ip, Integer port, String label) {
        if (servers.findByCustomerId(customerId).size() >= props.maxServersPerCustomer()) {
            throw ApiException.bad("Limite de " + props.maxServersPerCustomer() + " servidores por conta.");
        }
        return admin.createServer(customerId, requireIp(ip), requirePort(port), cleanLabel(label), List.of());
    }

    public AdminService.ServerView changeAddress(Long customerId, Long serverId, String ip, Integer port) {
        owned(customerId, serverId);
        return admin.updateAddress(serverId, requireIp(ip), requirePort(port));
    }

    public AdminService.ServerView setProducts(Long customerId, Long serverId, List<Selection> selections) {
        owned(customerId, serverId);
        Set<String> mine = new HashSet<>();
        for (Entitlement e : entitlements.findByCustomerId(customerId)) {
            mine.add(e.getProductId());
        }
        List<AdminService.ProductGrantReq> grants = new ArrayList<>();
        for (Selection sel : selections == null ? List.<Selection>of() : selections) {
            if (sel == null || sel.productId() == null || !mine.contains(sel.productId())) {
                throw ApiException.bad("Você não possui o plugin '" + (sel == null ? "" : sel.productId()) + "'.");
            }
            grants.add(new AdminService.ProductGrantReq(sel.productId(), null, sel.version()));
        }
        return admin.setProducts(serverId, grants);
    }

    private AuthorizedServer owned(Long customerId, Long serverId) {
        AuthorizedServer s = servers.findById(serverId).orElseThrow(() -> ApiException.notFound("Servidor não encontrado."));
        if (!s.getCustomerId().equals(customerId)) {
            throw ApiException.notFound("Servidor não encontrado.");
        }
        return s;
    }

    private static String requireIp(String ip) {
        if (ip == null || ip.isBlank() || ip.length() > 64) {
            throw ApiException.bad("Informe o IP do servidor.");
        }
        return ip.trim();
    }

    private static int requirePort(Integer port) {
        if (port == null || port < 1 || port > 65535) {
            throw ApiException.bad("Porta inválida (1 a 65535).");
        }
        return port;
    }

    private static String cleanLabel(String label) {
        if (label == null) {
            return null;
        }
        String l = label.trim();
        return l.isEmpty() ? null : l.substring(0, Math.min(l.length(), 60));
    }
}
