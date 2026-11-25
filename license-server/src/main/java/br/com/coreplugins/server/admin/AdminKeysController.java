package br.com.coreplugins.server.admin;

import br.com.coreplugins.server.crypto.SigningService;
import br.com.coreplugins.server.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Map;

/** Rotação da chave de assinatura. Protegido pelo AdminAuthFilter (qualquer rota em /api/admin/**). */
@RestController
@RequestMapping("/api/admin/keys")
public class AdminKeysController {

    public record RotateReq(String confirm) {
    }

    private final SigningService signing;

    public AdminKeysController(SigningService signing) {
        this.signing = signing;
    }

    @GetMapping
    public Map<String, List<String>> keys() {
        return Map.of("publicKeys", signing.publicKeysBase64());
    }

    /** Exige {"confirm":"ROTATE"} para não rotacionar por engano. */
    @PostMapping("/rotate")
    public Map<String, List<String>> rotate(@RequestBody RotateReq r) throws GeneralSecurityException {
        if (r == null || !"ROTATE".equals(r.confirm())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Envie {\"confirm\":\"ROTATE\"} para confirmar a rotação.");
        }
        signing.rotate();
        return Map.of("publicKeys", signing.publicKeysBase64());
    }
}
