package br.com.coreplugins.server.repo;

import br.com.coreplugins.server.model.ServerProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ServerProductRepository extends JpaRepository<ServerProduct, Long> {
    List<ServerProduct> findByServerId(Long serverId);

    void deleteByServerId(Long serverId);

    void deleteByProductId(String productId);

    void deleteByServerIdAndProductId(Long serverId, String productId);

    /** Quem tinha uma versão fixada passa a acompanhar a mais recente (version = null). */
    @Modifying(flushAutomatically = true)
    @Query("update ServerProduct s set s.version = :newVersion where s.productId = :productId and s.version = :oldVersion")
    int repinVersion(@Param("productId") String productId, @Param("oldVersion") String oldVersion,
                     @Param("newVersion") String newVersion);
}
