package br.com.coreplugins.server.repo;

import br.com.coreplugins.server.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, String> {

}
