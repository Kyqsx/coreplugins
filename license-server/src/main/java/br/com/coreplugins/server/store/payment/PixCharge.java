package br.com.coreplugins.server.store.payment;

/** Cobrança PIX criada no gateway: código copia-e-cola e QR em base64 (PNG). */
public record PixCharge(String paymentId, String pixCode, String qrBase64) {
}
