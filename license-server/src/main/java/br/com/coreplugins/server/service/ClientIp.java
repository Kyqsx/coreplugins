package br.com.coreplugins.server.service;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/** Descobre o IP de origem da requisição, que é o IP que vale como licença. */
public final class ClientIp {

    private static final Pattern LITERAL = Pattern.compile("^[0-9a-fA-F:.]+$");

    private ClientIp() {
    }

    public static String resolve(HttpServletRequest req, boolean trustForwardedFor) {
        String raw = req.getRemoteAddr();
        if (trustForwardedFor) {
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                // com proxy próprio na frente, o último IP acrescentado é o confiável
                String[] parts = xff.split(",");
                raw = parts[parts.length - 1].trim();
            }
        }
        return normalize(raw);
    }

    /** Como {@link #resolve}, mas nunca lança: serve de chave para limites de tentativas (login, token de admin). */
    public static String safe(HttpServletRequest req, boolean trustForwardedFor) {
        try {
            return resolve(req, trustForwardedFor);
        } catch (IllegalArgumentException e) {
            return String.valueOf(req.getRemoteAddr());
        }
    }

    /** "::ffff:1.2.3.4" vira "1.2.3.4"; IPv6 vira a forma canônica. Só aceita IP literal (nada de DNS). */
    public static String normalize(String ip) {
        if (ip == null || !LITERAL.matcher(ip).matches()) {
            throw new IllegalArgumentException("IP inválido");
        }
        try {
            return InetAddress.getByName(ip).getHostAddress();
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("IP inválido");
        }
    }
}
