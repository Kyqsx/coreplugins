package br.com.coreplugins.server.config;

import br.com.coreplugins.server.model.Customer;
import br.com.coreplugins.server.repo.CustomerRepository;
import br.com.coreplugins.server.store.security.TokenCodec;
import br.com.coreplugins.server.store.security.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

/**
 * Protege /api/admin/** com a sessão de administrador (Authorization: Bearer ...). A cada requisição o admin é
 * relido do banco: tirar o papel ADMIN (ou desligar a conta) derruba a sessão na hora, sem esperar o token expirar.
 *
 * <p>Fail-closed: o filtro vale para QUALQUER caminho que, na visão do Tomcat (servletPath, já normalizado) ou na
 * URI bruta, contenha /api/admin. Antes só valia para URIs que começavam com "/api/admin/", e algo como
 * {@code /api/admin;x=1/products} passava direto pelo filtro mas o Tomcat roteava para o controller.
 */
@Component
public class AdminAuthFilter extends OncePerRequestFilter {

    public static final String ADMIN_ATTR = "coreplugins.adminId";

    /** Únicas rotas sem sessão: comparadas com a URI bruta, exata. */
    private static final Set<String> OPEN = Set.of("/api/admin/auth/login", "/api/admin/auth/verify");

    private static final Logger audit = LoggerFactory.getLogger("coreplugins.admin.audit");

    private final TokenService tokens;
    private final CustomerRepository customers;
    private final LicenseProperties props;

    public AdminAuthFilter(TokenService tokens, CustomerRepository customers, LicenseProperties props) {
        this.tokens = tokens;
        this.customers = customers;
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String raw = request.getRequestURI();
        String servletPath = request.getServletPath();
        if (OPEN.contains(raw) && OPEN.contains(servletPath)) {
            return true;
        }
        return !(raw.contains("/api/admin") || servletPath.contains("/api/admin"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        Optional<TokenCodec.Claims> claims = Optional.empty();
        if (header != null && header.startsWith("Bearer ")) {
            claims = tokens.verifyAdmin(header.substring(7).trim());
        }
        Customer admin = claims.isPresent() ? customers.findById(claims.get().id()).orElse(null) : null;
        // A versão do token precisa ser a atual da conta: "Sair do painel" ou trocar a senha derruba o token na hora.
        boolean ok = admin != null && admin.isAdmin() && admin.getSessionVersion() == claims.get().version()
                && (!props.adminRequire2fa() || admin.isTotpEnabled());
        if (!ok) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getOutputStream().write("{\"error\":\"Sessão de administrador inválida ou expirada.\"}"
                    .getBytes(StandardCharsets.UTF_8));
            return;
        }
        String method = request.getMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method) && !"OPTIONS".equals(method)) {
            audit.info("admin={} ({}) {} {}", admin.getId(), admin.getEmail(), method, request.getServletPath());
        }
        request.setAttribute(ADMIN_ATTR, admin.getId());
        chain.doFilter(request, response);
    }
}
