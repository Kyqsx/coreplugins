package br.com.coreplugins.server.store.repo;

import br.com.coreplugins.server.store.model.Entitlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EntitlementRepository extends JpaRepository<Entitlement, Long> {
    List<Entitlement> findByCustomerId(Long customerId);

    void deleteByProductId(String productId);

    void deleteByCustomerIdAndProductId(Long customerId, String productId);

    boolean existsByCustomerIdAndProductId(Long customerId, String productId);
}
