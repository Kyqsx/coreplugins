package br.com.coreplugins.server.repo;

import br.com.coreplugins.server.model.LoaderJar;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;

public interface LoaderJarRepository extends JpaRepository<LoaderJar, Long> {

    /** Só os metadados (a listagem não precisa carregar o arquivo). */
    interface Meta {
        long getSizeBytes();

        String getSha256();

        Instant getUpdatedAt();
    }

    Optional<Meta> findMetaById(Long id);

    @Query("select j.data from LoaderJar j where j.id = 1")
    Optional<byte[]> findData();
}
