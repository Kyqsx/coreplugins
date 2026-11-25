package br.com.coreplugins.server.admin;

import br.com.coreplugins.server.config.AdminAuthFilter;
import br.com.coreplugins.server.config.LicenseProperties;
import br.com.coreplugins.server.model.Customer;
import br.com.coreplugins.server.repo.CustomerRepository;
import br.com.coreplugins.server.service.ClientIp;
import br.com.coreplugins.server.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Login do painel de administração. /login e /verify são abertos; /me exige a sessão (filtro). */
@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {

    public record LoginReq(String email, String password) {
    }

    public record VerifyReq(String challenge, String code) {
    }

    public record Me(Long id, String name, String email) {
    }

    private final AdminAuthService auth;
    private final CustomerRepository customers;
    private final LicenseProperties props;

    public AdminAuthController(AdminAuthService auth, CustomerRepository customers, LicenseProperties props) {
        this.auth = auth;
        this.customers = customers;
        this.props = props;
    }

    @PostMapping("/login")
    public AdminAuthService.AuthResult login(@RequestBody LoginReq r, HttpServletRequest req) {
        return auth.login(r.email(), r.password(), ClientIp.safe(req, props.trustForwardedFor()));
    }

    @PostMapping("/verify")
    public AdminAuthService.AuthResult verify(@RequestBody VerifyReq r, HttpServletRequest req) {
        return auth.verify(r.challenge(), r.code(), ClientIp.safe(req, props.trustForwardedFor()));
    }

    /** Revoga as sessões da conta. Exige a sessão (não está na lista OPEN do filtro). */
    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest req) {
        Object id = req.getAttribute(AdminAuthFilter.ADMIN_ATTR);
        if (id instanceof Long l) {
            auth.logout(l);
        }
        return Map.of("ok", true);
    }

    @GetMapping("/me")
    public Me me(HttpServletRequest req) {
        Object id = req.getAttribute(AdminAuthFilter.ADMIN_ATTR);
        Customer c = id instanceof Long l ? customers.findById(l).orElse(null) : null;
        if (c == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Sessão de administrador inválida ou expirada.");
        }
        return new Me(c.getId(), c.getName(), c.getEmail());
    }
}
