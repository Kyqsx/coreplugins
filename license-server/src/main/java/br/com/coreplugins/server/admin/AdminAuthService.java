package br.com.coreplugins.server.admin;

import br.com.coreplugins.server.config.LicenseProperties;
import br.com.coreplugins.server.model.Customer;
import br.com.coreplugins.server.repo.CustomerRepository;
import br.com.coreplugins.server.store.security.LoginRateLimiter;
import br.com.coreplugins.server.store.security.PasswordHasher;
import br.com.coreplugins.server.store.security.TokenService;
import br.com.coreplugins.server.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.OptionalLong;

/**
 * Login do painel em duas etapas: (1) e-mail + senha → "desafio" de 5 min; (2) código do app de autenticação → sessão
 * de 1 h. Na primeira vez, a etapa 1 devolve o segredo para cadastrar no app (step=enroll) e a etapa 2 confirma.
 */
@Service
public class AdminAuthService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthService.class);
    private static final String ISSUER = "CorePlugins";

    /** Campos que não se aplicam ao passo ficam nulos. {@code step}: totp | enroll | done. */
    public record AuthResult(String step, String challenge, String secret, String otpauthUri,
                             String token, Long expiresAt, String name, String email) {
    }

    private final CustomerRepository customers;
    private final TokenService tokens;
    private final LoginRateLimiter limiter;
    private final LicenseProperties props;

    public AdminAuthService(CustomerRepository customers, TokenService tokens, LoginRateLimiter limiter,
                            LicenseProperties props) {
        this.customers = customers;
        this.tokens = tokens;
        this.limiter = limiter;
        this.props = props;
    }

    public AuthResult login(String email, String password, String clientIp) {
        String e = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        // Limite por IP (conta toda tentativa) e por e-mail (conta só as falhas): barra tanto quem martela uma
        // conta de vários IPs quanto quem testa várias contas de um IP só.
        if (!limiter.allow("adminlogin:" + clientIp)) {
            throw tooMany();
        }
        String emailKey = "adminlogin-email:" + e;
        if (limiter.blocked(emailKey)) {
            throw tooMany();
        }
        Customer c = e.isEmpty() ? null : customers.findByEmailIgnoreCase(e).orElse(null);
        String stored = c == null ? PasswordHasher.dummy() : c.getPasswordHash();
        boolean passwordOk = PasswordHasher.verify(password == null ? "" : password, stored);
        // Mesma resposta para "não existe", "senha errada" e "não é admin": não revela quais contas são de admin.
        if (!passwordOk || c == null || !c.isAdmin()) {
            limiter.allow(emailKey);
            log.warn("Login de admin recusado: {} (ip {})", e, clientIp);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "E-mail ou senha incorretos.");
        }
        limiter.reset(emailKey);

        if (!props.adminRequire2fa()) {
            return session(c);
        }
        String challenge = tokens.issueChallenge(c.getId());
        if (c.isTotpEnabled()) {
            return new AuthResult("totp", challenge, null, null, null, null, null, null);
        }
        if (c.getTotpSecret() == null) {
            c.setTotpSecret(Totp.newSecret());
            customers.save(c);
        }
        return new AuthResult("enroll", challenge, c.getTotpSecret(),
                Totp.uri(ISSUER, c.getEmail(), c.getTotpSecret()), null, null, null, null);
    }

    public AuthResult verify(String challenge, String code, String clientIp) {
        OptionalLong id = tokens.verifyChallenge(challenge);
        if (id.isEmpty()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "O login expirou. Entre de novo com e-mail e senha.");
        }
        String userKey = "admin2fa:" + id.getAsLong();
        String ipKey = "admin2fa-ip:" + clientIp;
        if (limiter.blocked(userKey) || limiter.blocked(ipKey)) {
            throw tooMany();
        }
        Customer c = customers.findById(id.getAsLong()).orElse(null);
        if (c == null || !c.isAdmin() || c.getTotpSecret() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "O login expirou. Entre de novo com e-mail e senha.");
        }
        long step = Totp.verify(c.getTotpSecret(), code, System.currentTimeMillis(), c.getTotpLastStep());
        if (step < 0) {
            limiter.allow(userKey);
            limiter.allow(ipKey);
            log.warn("Código 2FA inválido para admin {} (ip {})", c.getEmail(), clientIp);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Código inválido ou já usado. Espere o próximo código.");
        }
        c.setTotpLastStep(step);
        c.setTotpEnabled(true);
        customers.save(c);
        limiter.reset(userKey);
        return session(c);
    }

    /** "Sair do painel": derruba TODAS as sessões desta conta (painel e site), em qualquer navegador. */
    public void logout(long adminId) {
        customers.findById(adminId).ifPresent(c -> {
            c.revokeSessions();
            customers.save(c);
            log.info("Admin {} saiu do painel; sessões revogadas", c.getEmail());
        });
    }

    private AuthResult session(Customer c) {
        long exp = System.currentTimeMillis() + TokenService.ADMIN_TTL_MILLIS;
        log.info("Admin {} entrou no painel", c.getEmail());
        return new AuthResult("done", null, null, null, tokens.issueAdmin(c.getId(), c.getSessionVersion()), exp, c.getName(), c.getEmail());
    }

    private static ApiException tooMany() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Muitas tentativas. Aguarde alguns minutos.");
    }
}
