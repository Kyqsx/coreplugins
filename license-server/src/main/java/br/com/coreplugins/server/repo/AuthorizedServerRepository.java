package br.com.coreplugins.server.repo;

import br.com.coreplugins.server.model.AuthorizedServer;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface AuthorizedServerRepository extends JpaRepository<AuthorizedServer, Long> {
    Optional<AuthorizedServer> findByIpAndPort(String ip, int port);

    List<AuthorizedServer> findByCustomerId(Long customerId);
}
