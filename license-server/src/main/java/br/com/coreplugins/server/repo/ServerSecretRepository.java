package br.com.coreplugins.server.repo;

import br.com.coreplugins.server.model.ServerSecret;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ServerSecretRepository extends JpaRepository<ServerSecret, String> {
    @Query("select s.value from ServerSecret s where s.name = :name")
    Optional<String> findValue(@Param("name") String name);

    /** Atômico: se duas instâncias subirem juntas, só uma grava e a outra lê o mesmo valor depois. */
    @Modifying
    @Query(value = "insert into server_secrets (name, secret_value, created_at) values (:name, :value, now()) "
            + "on conflict (name) do nothing", nativeQuery = true)
    int insertIfAbsent(@Param("name") String name, @Param("value") String value);

    /** Insere ou substitui o valor (usado para cifrar segredos antigos e para a rotação de chaves). */
    @Modifying
    @Query(value = "insert into server_secrets (name, secret_value, created_at) values (:name, :value, now()) "
            + "on conflict (name) do update set secret_value = excluded.secret_value, created_at = now()", nativeQuery = true)
    int upsert(@Param("name") String name, @Param("value") String value);
}
