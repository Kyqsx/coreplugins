package br.com.coreplugins.server.store.repo;

import br.com.coreplugins.server.store.model.WalletTx;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WalletTxRepository extends JpaRepository<WalletTx, Long> {
    List<WalletTx> findTop100ByCustomerIdOrderByIdDesc(Long customerId);
}
