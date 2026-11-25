package br.com.coreplugins.server.store.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PIX pela Orders API do Mercado Pago (POST /v1/orders, GET /v1/orders/{id}).
 *
 * <p>A Payments API (/v1/payments) segue funcionando, mas o Mercado Pago parou de evoluí-la e indica a Orders API
 * para integrações novas. Aqui o "paymentId" guardado no pedido é o id da order do Mercado Pago ("ORD...").</p>
 */
public class MercadoPagoGateway implements PaymentGateway {

    private static final String BASE = "https://api.mercadopago.com";

    /** Margem sobre o prazo do pedido: o PIX nunca expira no gateway antes de o pedido expirar aqui. */
    private static final Duration EXPIRY_MARGIN = Duration.ofMinutes(5);

    /** Piso do prazo do PIX no gateway (o Mercado Pago não aceita prazos muito curtos). */
    private static final Duration EXPIRY_MIN = Duration.ofMinutes(30);

    private final String accessToken;
    private final boolean testMode;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();

    /**
     * @param testMode        só com credencial de TESTE: envia payer.first_name=APRO, que faz a sandbox do Mercado Pago
     *                        aprovar o PIX sozinha. Com credencial de produção, mantenha false.
     * @param notificationUrl mantido por compatibilidade com o StoreConfig. Na Orders API a URL do webhook não vai
     *                        mais no corpo da requisição: é cadastrada no painel "Suas integrações" do Mercado Pago.
     */
    public MercadoPagoGateway(String accessToken, String notificationUrl, boolean testMode) {
        this.accessToken = accessToken;
        this.testMode = testMode;
    }

    @Override
    public PixCharge createPix(long orderId, int amountCents, String description, String payerEmail, Instant expiresAt) throws IOException {
        String amount = BigDecimal.valueOf(amountCents, 2).toPlainString();

        Map<String, Object> method = new LinkedHashMap<>();
        method.put("id", "pix");
        method.put("type", "bank_transfer");

        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("amount", amount);
        payment.put("payment_method", method);
        if (expiresAt != null) {
            payment.put("expiration_time", expirationDuration(expiresAt).toString()); // ISO 8601, ex.: PT35M
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "online");
        body.put("processing_mode", "automatic");
        body.put("external_reference", String.valueOf(orderId));
        body.put("total_amount", amount);
        body.put("description", description);
        Map<String, Object> payer = new LinkedHashMap<>();
        payer.put("email", payerEmail);
        if (testMode) {
            payer.put("first_name", "APRO");
        }
        body.put("payer", payer);
        body.put("transactions", Map.of("payments", List.of(payment)));

        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/v1/orders"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .header("X-Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        JsonNode r = send(req);

        JsonNode pm = r.path("transactions").path("payments").path(0).path("payment_method");
        String code = pm.path("qr_code").asText(null);
        if (!r.hasNonNull("id") || code == null || code.isBlank()) {
            throw new IOException("Resposta do Mercado Pago sem dados do PIX");
        }
        String qr = pm.path("qr_code_base64").asText(null);
        return new PixCharge(r.get("id").asText(), code, qr == null || qr.isBlank() ? null : qr);
    }

    @Override
    public PaymentState state(String paymentId) throws IOException {
        String status = get(paymentId).path("status").asText("");
        switch (status) {
            case "processed":
                return PaymentState.APPROVED;
            case "failed":
            case "canceled":
            case "expired":
            case "refunded":
            case "charged_back":
                return PaymentState.REJECTED;
            default: // created, action_required, processing...
                return PaymentState.PENDING;
        }
    }

    @Override
    public String externalReference(String paymentId) throws IOException {
        return get(paymentId).path("external_reference").asText(null);
    }

    @Override
    public boolean simulated() {
        return false;
    }

    /** Prazo relativo (a partir de agora) com a margem, nunca abaixo do piso do gateway. */
    private static Duration expirationDuration(Instant expiresAt) {
        Duration d = Duration.between(Instant.now(), expiresAt).plus(EXPIRY_MARGIN);
        if (d.compareTo(EXPIRY_MIN) < 0) {
            d = EXPIRY_MIN;
        }
        return Duration.ofMinutes(d.toMinutes() + 1); // arredonda para minutos inteiros
    }

    private JsonNode get(String orderId) throws IOException {
        // ids de order são alfanuméricos ("ORD01J..."); barra o resto para não montar URL com lixo
        if (orderId == null || !orderId.matches("[A-Za-z0-9]{1,64}")) {
            throw new IOException("id de order inválido");
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/v1/orders/" + orderId))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + accessToken)
                .GET().build();
        return send(req);
    }

    private JsonNode send(HttpRequest req) throws IOException {
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                throw new IOException("Mercado Pago respondeu HTTP " + res.statusCode() + ": " + trim(res.body()));
            }
            return json.readTree(res.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrompido", e);
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) : s;
    }
}