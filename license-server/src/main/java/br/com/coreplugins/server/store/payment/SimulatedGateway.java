package br.com.coreplugins.server.store.payment;

import java.time.Instant;

/** Gateway local de teste: gera um PIX falso e nunca aprova sozinho — o site tem um botão "simular pagamento". */
public class SimulatedGateway implements PaymentGateway {

    @Override
    public PixCharge createPix(long orderId, int amountCents, String description, String payerEmail, Instant expiresAt) {
        String code = "00020126SIMULADO-COREPLUGINS-PEDIDO-" + orderId + "-VALOR-" + amountCents + "-NAO-PAGUE";
        return new PixCharge("sim-" + orderId, code, null);
    }

    @Override
    public PaymentState state(String paymentId) {
        return PaymentState.PENDING;
    }

    @Override
    public String externalReference(String paymentId) {
        return paymentId.startsWith("sim-") ? paymentId.substring(4) : null;
    }

    @Override
    public boolean simulated() {
        return true;
    }
}
