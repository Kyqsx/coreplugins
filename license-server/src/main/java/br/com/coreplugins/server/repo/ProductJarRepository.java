package br.com.coreplugins.server.repo;

import br.com.coreplugins.server.model.ProductJar;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProductJarRepository extends JpaRepository<ProductJar, Long> {
    @Query("select j.data from ProductJar j where j.id = :versionId")
    Optional<byte[]> findData(@Param("versionId") Long versionId);
}
