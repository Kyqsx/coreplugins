package br.com.coreplugins.server.store.service;

import br.com.coreplugins.server.repo.CustomerRepository;
import br.com.coreplugins.server.store.model.WalletTx;
import br.com.coreplugins.server.store.repo.WalletTxRepository;
import br.com.coreplugins.server.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Carteira do cliente. O saldo só muda por UPDATEs atômicos no banco; cada mudança gera uma linha no extrato. */
@Service
public class WalletService {

    public record TxView(Long id, String type, long amountCents, String description, Long orderId, Instant createdAt) {
    }

    public record WalletView(long balanceCents, List<TxView> transactions) {
    }

    private final CustomerRepository customers;
    private final WalletTxRepository txs;

    public WalletService(CustomerRepository customers, WalletTxRepository txs) {
        this.customers = customers;
        this.txs = txs;
    }

    public long balance(Long customerId) {
        return customers.findBalance(customerId).orElse(0L);
    }

    public WalletView view(Long customerId) {
        List<TxView> out = new ArrayList<>();
        for (WalletTx t : txs.findTop100ByCustomerIdOrderByIdDesc(customerId)) {
            out.add(new TxView(t.getId(), t.getType(), t.getAmountCents(), t.getDescription(), t.getOrderId(), t.getCreatedAt()));
        }
        return new WalletView(balance(customerId), out);
    }

    @Transactional
    public void credit(Long customerId, long cents, String type, String description, Long orderId) {
        if (cents <= 0) {
            throw new IllegalArgumentException("Valor inválido.");
        }
        if (customers.credit(customerId, cents) == 0) {
            throw ApiException.notFound("Cliente não existe.");
        }
        txs.save(new WalletTx(customerId, type, cents, description, orderId));
    }

    /** Tira saldo; se não houver o suficiente, lança erro e (dentro de transação) desfaz tudo o que veio antes. */
    @Transactional
    public void debit(Long customerId, long cents, String type, String description, Long orderId) {
        if (cents <= 0) {
            throw new IllegalArgumentException("Valor inválido.");
        }
        if (customers.debit(customerId, cents) == 0) {
            throw ApiException.bad("Saldo insuficiente.");
        }
        txs.save(new WalletTx(customerId, type, -cents, description, orderId));
    }

    /** Ajuste manual do admin: positivo credita, negativo debita (sem deixar o saldo negativo). */
    @Transactional
    public long adjust(Long customerId, long cents, String note) {
        if (cents == 0) {
            throw ApiException.bad("Informe um valor diferente de zero.");
        }
        if (Math.abs(cents) > 100_000_000L) {
            throw ApiException.bad("Valor grande demais.");
        }
        String text = note == null || note.isBlank() ? "Ajuste do administrador" : "Ajuste do administrador: " + note.trim();
        if (cents > 0) {
            credit(customerId, cents, WalletTx.ADMIN_CREDIT, text, null);
        } else {
            debit(customerId, -cents, WalletTx.ADMIN_DEBIT, text, null);
        }
        return balance(customerId);
    }
}
