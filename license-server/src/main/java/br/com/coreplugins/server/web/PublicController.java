package br.com.coreplugins.server.web;

import br.com.coreplugins.server.config.LicenseProperties;
import br.com.coreplugins.server.crypto.SigningService;
import br.com.coreplugins.server.model.ProductVersion;
import br.com.coreplugins.server.service.ClientIp;
import br.com.coreplugins.server.service.LicenseService;
import br.com.coreplugins.server.service.ProductService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/** API chamada pelo loader dentro do servidor Minecraft do cliente. */
@RestController
public class PublicController {

    public record AuthRequest(@Min(1) @Max(65535) int port,
                              @NotBlank @Size(min = 8, max = 64) String nonce,
                              @Size(max = 32) String loader) {
    }

    private final LicenseService licenses;
    private final ProductService productService;
    private final SigningService signing;
    private final LicenseProperties props;

    public PublicController(LicenseService licenses, ProductService productService, SigningService signing,
                            LicenseProperties props) {
        this.licenses = licenses;
        this.productService = productService;
        this.signing = signing;
        this.props = props;
    }

    /**
     * O IP vem da conexão (não do corpo): é ele que vale como licença. Todas as respostas, inclusive
     * negações, são assinadas. O HTTP status acompanha o status só por conveniência de depuração.
     */
    @PostMapping("/api/v1/auth")
    public ResponseEntity<LicenseService.AuthResponse> auth(@Valid @RequestBody AuthRequest body,
                                                            HttpServletRequest request) {
        String ip = ClientIp.resolve(request, props.trustForwardedFor());
        LicenseService.AuthResponse response = licenses.authenticate(ip, body.port(), body.nonce());
        int http = switch (response.status()) {
            case "ok" -> 200;
            case "revoked" -> 403;
            default -> 404;
        };
        return ResponseEntity.status(http).contentType(MediaType.APPLICATION_JSON).body(response);
    }

    @GetMapping("/api/v1/products/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable String id,
                                             @RequestParam @Min(1) @Max(65535) int port,
                                             @RequestParam(required = false) String version,
                                             HttpServletRequest request) throws Exception {
        String ip = ClientIp.resolve(request, props.trustForwardedFor());
        // 404 também para "sem licença": não revela quais produtos existem
        Optional<ProductVersion> pv = licenses.authorizeDownload(ip, port, id, version);
        if (pv.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Optional<byte[]> jar = productService.jarBytes(pv.get().getId());
        if (jar.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(jar.get().length)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(jar.get());
    }

    /** Chave pública para embutir no loader (loader/src/main/resources/license-public.key). */
    @GetMapping(value = "/api/v1/public-key", produces = MediaType.TEXT_PLAIN_VALUE)
    public String publicKey() {
        return signing.publicKeyBase64();
    }

    /** As duas chaves públicas (atual e próxima), uma por linha, prontas para loader/src/main/resources/license-public.key. */
    @GetMapping(value = "/api/v1/public-keys", produces = MediaType.TEXT_PLAIN_VALUE)
    public String publicKeys() {
        return String.join("\n", signing.publicKeysBase64()) + "\n";
    }
}
