package br.com.coreplugins.server.store.service;

import br.com.coreplugins.server.model.Customer;
import br.com.coreplugins.server.repo.CustomerRepository;
import br.com.coreplugins.server.store.security.LoginRateLimiter;
import br.com.coreplugins.server.store.security.PasswordHasher;
import br.com.coreplugins.server.store.security.TokenService;
import br.com.coreplugins.server.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class AuthService {

    public record Session(String token, Long id, String name, String email) {
    }

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,255}\\.[^@\\s]{2,}$");

    private final CustomerRepository customers;
    private final TokenService tokens;
    private final LoginRateLimiter limiter;

    public AuthService(CustomerRepository customers, TokenService tokens, LoginRateLimiter limiter) {
        this.customers = customers;
        this.tokens = tokens;
        this.limiter = limiter;
    }

    public Session register(String name, String email, String password, String clientIp) {
        if (!limiter.allow("reg:" + clientIp)) {
            throw tooMany();
        }
        String n = name == null ? "" : name.trim();
        String e = normalize(email);
        if (n.length() < 2 || n.length() > 80) {
            throw ApiException.bad("Informe seu nome (2 a 80 caracteres).");
        }
        if (!EMAIL.matcher(e).matches()) {
            throw ApiException.bad("E-mail inválido.");
        }
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw ApiException.bad("A senha precisa ter de 8 a 128 caracteres.");
        }
        if (customers.findByEmailIgnoreCase(e).isPresent()) {
            throw ApiException.bad("Este e-mail já está cadastrado. Entre na sua conta.");
        }
        Customer c = new Customer(n, e);
        c.setPasswordHash(PasswordHasher.hash(password));
        c = customers.save(c);
        return session(c);
    }

    public Session login(String email, String password, String clientIp) {
        String e = normalize(email);
        String key = "login:" + clientIp + ":" + e;
        if (!limiter.allow(key)) {
            throw tooMany();
        }
        Customer c = customers.findByEmailIgnoreCase(e).orElse(null);
        String stored = c == null ? PasswordHasher.dummy() : c.getPasswordHash();
        boolean ok = PasswordHasher.verify(password == null ? "" : password, stored) && c != null;
        if (!ok) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "E-mail ou senha incorretos.");
        }
        limiter.reset(key);
        return session(c);
    }

    public Customer require(long id) {
        return customers.findById(id).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Sessão inválida."));
    }

    private Session session(Customer c) {
        return new Session(tokens.issue(c.getId(), c.getSessionVersion()), c.getId(), c.getName(), c.getEmail());
    }

    /** "Sair": derruba todos os tokens desta conta, inclusive os de outros navegadores. */
    public void logout(long id) {
        Customer c = require(id);
        c.revokeSessions();
        customers.save(c);
    }

    /**
     * Troca a senha conferindo a atual. Derruba todas as sessões antigas e devolve uma sessão nova para quem trocou
     * continuar logado.
     */
    public Session changePassword(long id, String current, String next, String clientIp) {
        String key = "chpass:" + id;
        if (!limiter.allow(key) || !limiter.allow("chpass-ip:" + clientIp)) {
            throw tooMany();
        }
        Customer c = require(id);
        if (c.getPasswordHash() == null || !PasswordHasher.verify(current == null ? "" : current, c.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Senha atual incorreta.");
        }
        int min = c.isAdmin() ? 12 : 8;
        if (next == null || next.length() < min || next.length() > 128) {
            throw ApiException.bad("A nova senha precisa ter de " + min + " a 128 caracteres.");
        }
        c.setPasswordHash(PasswordHasher.hash(next));
        c.revokeSessions();
        c = customers.save(c);
        limiter.reset(key);
        return session(c);
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static ApiException tooMany() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Muitas tentativas. Aguarde alguns minutos.");
    }
}
