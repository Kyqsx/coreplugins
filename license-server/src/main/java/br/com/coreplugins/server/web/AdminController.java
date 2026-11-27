package br.com.coreplugins.server.web;

import br.com.coreplugins.server.model.Customer;
import br.com.coreplugins.server.model.Product;
import br.com.coreplugins.server.model.ProductVersion;
import br.com.coreplugins.server.repo.CustomerRepository;
import br.com.coreplugins.server.repo.ProductRepository;
import br.com.coreplugins.server.repo.ProductVersionRepository;
import br.com.coreplugins.server.service.AdminService;
import br.com.coreplugins.server.service.AdminService.ProductGrantReq;
import br.com.coreplugins.server.service.AdminService.ServerView;
import br.com.coreplugins.server.service.ProductService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/** Painel admin (sessão de administrador + 2FA, ver AdminAuthFilter). É o que a loja chama depois de uma compra aprovada. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    public record CustomerReq(@NotBlank String name, @NotBlank @Email String email) {
    }

    public record ProductReq(@NotBlank String id, @NotBlank String name, String description,
                             @Min(0) Integer priceCents, String category, String command) {
    }

    public record ServerReq(@NotNull Long customerId, @NotBlank String ip, @Min(1) @Max(65535) int port,
                            String label, List<ProductGrantReq> products) {
    }

    public record AddressReq(@NotBlank String ip, @Min(1) @Max(65535) int port) {
    }

    public record ProductsReq(List<ProductGrantReq> products) {
    }

    /** Nova ordem da galeria de um plugin (a primeira imagem é a capa). */
    public record ImageOrderReq(List<Long> ids) {
    }

    private final AdminService admin;
    private final ProductService productService;
    private final CustomerRepository customers;
    private final ProductRepository products;
    private final ProductVersionRepository versions;

    public AdminController(AdminService admin, ProductService productService, CustomerRepository customers,
                           ProductRepository products, ProductVersionRepository versions) {
        this.admin = admin;
        this.productService = productService;
        this.customers = customers;
        this.products = products;
        this.versions = versions;
    }

    // ---- clientes
    @PostMapping("/customers")
    public Customer createCustomer(@Valid @RequestBody CustomerReq r) {
        return admin.createCustomer(r.name(), r.email());
    }

    @GetMapping("/customers")
    public List<Customer> customers() {
        return customers.findAll();
    }

    // ---- servidores (IP:porta) e plugins marcados
    @PostMapping("/servers")
    public ServerView createServer(@Valid @RequestBody ServerReq r) {
        return admin.createServer(r.customerId(), r.ip(), r.port(), r.label(), r.products());
    }

    @GetMapping("/servers")
    public List<ServerView> servers(@RequestParam(required = false) Long customerId) {
        return admin.listServers(customerId);
    }

    @PutMapping("/servers/{id}/address")
    public ServerView changeAddress(@PathVariable Long id, @Valid @RequestBody AddressReq r) {
        return admin.updateAddress(id, r.ip(), r.port());
    }

    @PutMapping("/servers/{id}/products")
    public ServerView setProducts(@PathVariable Long id, @RequestBody ProductsReq r) {
        return admin.setProducts(id, r.products());
    }

    @PostMapping("/servers/{id}/revoke")
    public ServerView revoke(@PathVariable Long id) {
        return admin.setActive(id, false);
    }

    @PostMapping("/servers/{id}/activate")
    public ServerView activate(@PathVariable Long id) {
        return admin.setActive(id, true);
    }

    // ---- produtos e versões (jars)
    @PostMapping("/products")
    public Product createProduct(@Valid @RequestBody ProductReq r) {
        return productService.create(r.id(), r.name(), r.description(), r.priceCents(), r.category(), r.command());
    }

    @GetMapping("/products")
    public List<Product> products() {
        return products.findAll();
    }

    @GetMapping("/products/{id}/versions")
    public List<ProductVersion> versions(@PathVariable String id) {
        return versions.findByProductIdOrderByCreatedAtDesc(id);
    }

    @PostMapping(value = "/products/{id}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProductVersion upload(@PathVariable String id, @RequestParam String version,
                                 @RequestParam(defaultValue = "true") boolean latest,
                                 @RequestParam(required = false) String changelog,
                                 @RequestParam("file") MultipartFile file) throws IOException {
        return productService.addVersion(id, version, file, latest, changelog);
    }

    public record ChangelogReq(String changelog) {
    }

    /** Notas da versão (as que aparecem em "Novidades" para quem comprou o plugin). */
    @PutMapping("/products/{id}/versions/{version}/changelog")
    public ProductVersion setChangelog(@PathVariable String id, @PathVariable String version,
                                       @RequestBody ChangelogReq r) {
        return productService.setChangelog(id, version, r.changelog());
    }

    /** Edita a versão: novo número e/ou novo jar (ambos opcionais, mas ao menos um). */
    @PostMapping(value = "/products/{id}/versions/{version}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProductVersion editVersion(@PathVariable String id, @PathVariable String version,
                                      @RequestParam(required = false) String newVersion,
                                      @RequestParam(value = "file", required = false) MultipartFile file)
            throws IOException {
        return productService.updateVersion(id, version, newVersion, file);
    }

    // ---- galeria de imagens do plugin (o link do YouTube vai no PUT /products/{id}, campo youtubeUrl)
    @GetMapping("/products/{id}/images")
    public List<ProductService.ImageMeta> images(@PathVariable String id) {
        return productService.listImages(id);
    }

    @PostMapping(value = "/products/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProductService.ImageMeta uploadImage(@PathVariable String id, @RequestParam("file") MultipartFile file)
            throws IOException {
        return productService.addImage(id, file);
    }

    @PutMapping("/products/{id}/images/order")
    public java.util.Map<String, Object> reorderImages(@PathVariable String id, @RequestBody ImageOrderReq r) {
        productService.reorderImages(id, r.ids());
        return java.util.Map.of("ok", true);
    }

    @DeleteMapping("/products/{id}/images/{imageId}")
    public java.util.Map<String, Object> deleteImage(@PathVariable String id, @PathVariable Long imageId) {
        productService.deleteImage(id, imageId);
        return java.util.Map.of("ok", true);
    }

    @DeleteMapping("/products/{id}/versions/{version}")
    public java.util.Map<String, Object> deleteVersion(@PathVariable String id, @PathVariable String version)
            throws IOException {
        productService.deleteVersion(id, version);
        return java.util.Map.of("ok", true);
    }

    @DeleteMapping("/products/{id}")
    public java.util.Map<String, Object> deleteProduct(@PathVariable String id) throws IOException {
        productService.deleteProduct(id);
        return java.util.Map.of("ok", true);
    }
}
