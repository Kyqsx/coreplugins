package br.com.coreplugins.server.store;

import br.com.coreplugins.server.store.payment.MercadoPagoGateway;
import br.com.coreplugins.server.store.payment.PaymentGateway;
import br.com.coreplugins.server.store.payment.SimulatedGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StoreConfig {

    private static final Logger log = LoggerFactory.getLogger(StoreConfig.class);

    @Bean
    PaymentGateway paymentGateway(StoreProperties p) {
        if ("mercadopago".equalsIgnoreCase(p.paymentMode())) {
            if (p.mercadopagoAccessToken().isBlank()) {
                throw new IllegalStateException("PAYMENT_MODE=mercadopago exige MP_ACCESS_TOKEN");
            }
            String hook = p.publicUrl().isBlank() ? "" : p.publicUrl().replaceAll("/+$", "") + "/api/store/mercadopago/webhook";
            log.info("Pagamentos: Mercado Pago Orders API{} (webhook: cadastre {} em Suas integrações; sem ele só o polling confirma)",
                    p.mercadopagoTestMode() ? " em MODO DE TESTE (payer APRO)" : "", hook.isEmpty() ? "a URL pública + /api/store/mercadopago/webhook" : hook);
            if (p.mercadopagoTestMode()) {
                log.warn("MP_TEST_MODE=true: use SOMENTE com credencial de teste. Desligue antes de cobrar de verdade.");
            }
            return new MercadoPagoGateway(p.mercadopagoAccessToken(), hook, p.mercadopagoTestMode());
        }
        log.warn("Pagamentos: SIMULADO (modo de teste). Para cobrar de verdade: PAYMENT_MODE=mercadopago e MP_ACCESS_TOKEN.");
        return new SimulatedGateway();
    }
}