package br.com.coreplugins.server.repo;

import br.com.coreplugins.server.model.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
    boolean existsByEmail(String email);

    java.util.Optional<Customer> findByEmailIgnoreCase(String email);

    /** Só a versão de sessão (consulta leve para o filtro); vazio se a conta não existe mais. */
    @Query("select coalesce(c.sessionVersion, 0) from Customer c where c.id = :id")
    java.util.Optional<Integer> findSessionVersion(@Param("id") Long id);

    @Query("select coalesce(c.balanceCents, 0L) from Customer c where c.id = :id")
    java.util.Optional<Long> findBalance(@Param("id") Long id);

    /** Soma ao saldo, atômico no banco (sem ler-e-gravar). */
    @Modifying(flushAutomatically = true)
    @Query("update Customer c set c.balanceCents = coalesce(c.balanceCents, 0L) + :amount where c.id = :id")
    int credit(@Param("id") Long id, @Param("amount") long amount);

    /** Subtrai só se houver saldo suficiente; devolve 0 linhas se não houver (nunca fica negativo). */
    @Modifying(flushAutomatically = true)
    @Query("update Customer c set c.balanceCents = coalesce(c.balanceCents, 0L) - :amount where c.id = :id and coalesce(c.balanceCents, 0L) >= :amount")
    int debit(@Param("id") Long id, @Param("amount") long amount);
}
