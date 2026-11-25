package br.com.coreplugins.loader.license;

import java.io.IOException;

/**
 * Resposta do servidor que não passou na verificação (assinatura inválida, nonce diferente,
 * relógio fora da tolerância, hash do jar diferente). É tratada como falha de rede,
 * nunca como negação: só uma resposta <b>assinada</b> com status de negação derruba o servidor.
 */
public final class LicenseException extends IOException {

    public LicenseException(String message) {
        super(message);
    }
}
