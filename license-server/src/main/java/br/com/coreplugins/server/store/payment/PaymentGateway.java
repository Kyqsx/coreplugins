package br.com.coreplugins.server.store.payment;

import java.io.IOException;
import java.time.Instant;

public interface PaymentGateway {

    /**
     * Cria uma cobrança PIX. {@code orderId} vai como referência externa; {@code expiresAt} é o prazo do pedido,
     * para o PIX não continuar pagável no gateway depois que o pedido expirou aqui.
     */
    PixCharge createPix(long orderId, int amountCents, String description, String payerEmail, Instant expiresAt) throws IOException;

    /** Consulta o estado real do pagamento no gateway (nunca confie no corpo do webhook). */
    PaymentState state(String paymentId) throws IOException;

    /** Referência externa gravada na cobrança (para conferir que o pagamento é deste pedido). */
    String externalReference(String paymentId) throws IOException;

    /** true no gateway de simulação (libera o botão "simular pagamento"). */
    boolean simulated();
}
