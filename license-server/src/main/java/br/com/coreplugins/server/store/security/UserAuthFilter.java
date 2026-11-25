package br.com.coreplugins.server.store.security;

import br.com.coreplugins.server.repo.CustomerRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.OptionalLong;

/** Exige login (Authorization: Bearer ...) nas rotas /api/store/**, exceto as públicas. */
@Component
public class UserAuthFilter extends OncePerRequestFilter {

    public static final String USER_ATTR = "coreplugins.userId";

    private final TokenService tokens;
    private final CustomerRepository customers;

    public UserAuthFilter(TokenService tokens, CustomerRepository customers) {
        this.tokens = tokens;
        this.customers = customers;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Fail-closed: vale para qualquer caminho que, na URI bruta ou no servletPath normalizado, seja /api/store.
        // As rotas públicas só escapam se as DUAS visões concordarem.
        String raw = request.getRequestURI();
        String servletPath = request.getServletPath();
        if (!(raw.contains("/api/store") || servletPath.contains("/api/store"))) {
            return true;
        }
        return isPublic(raw) && isPublic(servletPath);
    }

    /** Imagens do plugin (vitrine pública): caminho exato, só dígitos no id da imagem. */
    private static final java.util.regex.Pattern PUBLIC_IMAGE =
            java.util.regex.Pattern.compile("^/api/store/products/[A-Za-z0-9._-]{1,64}/images/[0-9]{1,18}$");

    private static boolean isPublic(String uri) {
        return uri.equals("/api/store/catalog")
                || PUBLIC_IMAGE.matcher(uri).matches()
                || uri.startsWith("/api/store/auth/")
                || uri.equals("/api/store/mercadopago/webhook");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        OptionalLong id = OptionalLong.empty();
        if (header != null && header.startsWith("Bearer ")) {
            Optional<TokenCodec.Claims> claims = tokens.verify(header.substring(7).trim());
            // O token só vale se a versão dele ainda for a da conta: sair ou trocar a senha derruba os tokens antigos.
            if (claims.isPresent()) {
                Optional<Integer> current = customers.findSessionVersion(claims.get().id());
                if (current.isPresent() && current.get() == claims.get().version()) {
                    id = OptionalLong.of(claims.get().id());
                }
            }
        }
        if (id.isEmpty()) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getOutputStream().write("{\"error\":\"Faça login para continuar.\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        request.setAttribute(USER_ATTR, id.getAsLong());
        chain.doFilter(request, response);
    }
}
