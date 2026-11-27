package br.com.coreplugins.server.store.repo;

import br.com.coreplugins.server.store.model.CheckinConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckinConfigRepository extends JpaRepository<CheckinConfig, Long> {
}
