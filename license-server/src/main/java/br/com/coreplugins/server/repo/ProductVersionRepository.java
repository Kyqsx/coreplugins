package br.com.coreplugins.server.repo;

import br.com.coreplugins.server.model.ProductVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ProductVersionRepository extends JpaRepository<ProductVersion, Long> {
    Optional<ProductVersion> findByProductIdAndVersion(String productId, String version);

    List<ProductVersion> findByProductIdOrderByCreatedAtDesc(String productId);
}
