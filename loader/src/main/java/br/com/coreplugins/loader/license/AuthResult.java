package br.com.coreplugins.loader.license;

import br.com.coreplugins.protocol.Protocol;

import java.util.Collections;
import java.util.List;

/** Resposta já verificada (assinatura, nonce e relógio) do license-server. */
public final class AuthResult {

    private final String status;
    private final String address;
    private final List<Protocol.Grant> products;

    public AuthResult(String status, String address, List<Protocol.Grant> products) {
        this.status = status;
        this.address = address;
        this.products = Collections.unmodifiableList(products);
    }

    public String getStatus() {
        return status;
    }

    /** IP:porta como o license-server enxergou este servidor. É o que o cliente cadastra no painel. */
    public String getAddress() {
        return address;
    }

    public List<Protocol.Grant> getProducts() {
        return products;
    }

    public boolean isOk() {
        return Protocol.STATUS_OK.equals(status);
    }
}
