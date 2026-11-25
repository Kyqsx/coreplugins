package br.com.coreplugins.server.store.repo;

import br.com.coreplugins.server.store.model.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CouponRepository extends JpaRepository<Coupon, Long> {
    Optional<Coupon> findByCode(String code);

    boolean existsByCode(String code);

    @Modifying(flushAutomatically = true)
    @Query("update Coupon c set c.usedCount = c.usedCount + 1 where c.id = :id")
    int incrementUsed(@Param("id") Long id);
}
