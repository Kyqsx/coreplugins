package br.com.coreplugins.server.store.repo;

import br.com.coreplugins.server.store.model.Checkin;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CheckinRepository extends JpaRepository<Checkin, Long> {
    /** O check-in mais recente do cliente (maior dia). */
    Optional<Checkin> findTopByCustomerIdOrderByDayDesc(Long customerId);
}
