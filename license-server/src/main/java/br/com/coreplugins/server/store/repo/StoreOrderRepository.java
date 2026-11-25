package br.com.coreplugins.server.store.repo;

import br.com.coreplugins.server.store.model.StoreOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

import java.util.List;
import java.util.Optional;

public interface StoreOrderRepository extends JpaRepository<StoreOrder, Long> {
    List<StoreOrder> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    Optional<StoreOrder> findByPaymentId(String paymentId);

    /**
     * Quem chegar primeiro "ganha" a transição para PAID (devolve 1); polling e webhook simultâneos recebem 0 e
     * não repetem os efeitos (liberar plugin, creditar saldo).
     */
    @Modifying(flushAutomatically = true)
    @Query("update StoreOrder o set o.status = 'PAID', o.paidAt = :now where o.id = :id and o.status <> 'PAID'")
    int claimPaid(@Param("id") Long id, @Param("now") Instant now);

    /** Usos de um cupom por um cliente: pedidos pagos + pendentes ainda dentro do prazo. */
    @Query("select count(o) from StoreOrder o where o.customerId = :customerId and o.couponId = :couponId "
            + "and (o.status = 'PAID' or (o.status = 'PENDING' and o.expiresAt > :now))")
    long countCouponUses(@Param("customerId") Long customerId, @Param("couponId") Long couponId, @Param("now") Instant now);
}
