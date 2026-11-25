package br.com.coreplugins.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Constantes e regras de assinatura compartilhadas entre o loader (cliente) e o license-server.
 *
 * <p>Toda resposta de /api/v1/auth é assinada (RSA SHA-256) sobre a string canônica
 * produzida por {@link #canonical}. Isso vale também para negações: sem a assinatura, um
 * proxy ou atacante no meio do caminho não consegue forjar um "not_found" para desligar o servidor.</p>
 */
public final class Protocol {

    public static final String STATUS_OK = "ok";
    public static final String STATUS_NOT_FOUND = "not_found";
    public static final String STATUS_REVOKED = "revoked";

    /** Tolerância de relógio entre servidor Minecraft e license-server. */
    public static final long MAX_CLOCK_SKEW_MILLIS = 10L * 60L * 1000L;

    private Protocol() {
    }

    /** Um produto liberado para o servidor, como aparece na resposta assinada. */
    public static final class Grant {
        private final String id;
        private final String version;
        private final String sha256;

        public Grant(String id, String version, String sha256) {
            this.id = id;
            this.version = version;
            this.sha256 = sha256;
        }

        public String getId() {
            return id;
        }

        public String getVersion() {
            return version;
        }

        public String getSha256() {
            return sha256;
        }
    }

    /** IPv4 → "1.2.3.4:25565"; IPv6 → "[::1]:25565". */
    public static String address(String ip, int port) {
        String host = ip.indexOf(':') >= 0 && !ip.startsWith("[") ? "[" + ip + "]" : ip;
        return host + ":" + port;
    }

    /**
     * String canônica: {@code status|address|issuedAt|nonce|id:version:sha256;id:version:sha256}.
     * Os produtos entram ordenados por id para que a ordem do JSON não importe.
     */
    public static String canonical(String status, String address, long issuedAt, String nonce, List<Grant> grants) {
        List<Grant> sorted = new ArrayList<Grant>(grants);
        Collections.sort(sorted, new Comparator<Grant>() {
            @Override
            public int compare(Grant a, Grant b) {
                return a.getId().compareTo(b.getId());
            }
        });
        StringBuilder sb = new StringBuilder();
        sb.append(status).append('|').append(address).append('|').append(issuedAt).append('|').append(nonce).append('|');
        for (int i = 0; i < sorted.size(); i++) {
            Grant g = sorted.get(i);
            if (i > 0) {
                sb.append(';');
            }
            sb.append(g.getId()).append(':').append(g.getVersion()).append(':').append(g.getSha256());
        }
        return sb.toString();
    }
}
