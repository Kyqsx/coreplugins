package br.com.coreplugins.server.repo;

import br.com.coreplugins.server.model.ProductImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** As consultas de listagem selecionam só colunas escalares: os bytes das imagens nunca são carregados à toa. */
public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {

    /** (productId, imageId) de todas as imagens, na ordem da galeria. Usado pelo catálogo (uma consulta só). */
    @Query("select i.productId, i.id from ProductImage i order by i.productId, i.position, i.id")
    List<Object[]> findAllRefs();

    /** (id, position, contentType, sizeBytes) das imagens de um produto, na ordem da galeria. */
    @Query("select i.id, i.position, i.contentType, i.sizeBytes from ProductImage i where i.productId = :productId order by i.position, i.id")
    List<Object[]> findMeta(@Param("productId") String productId);

    @Query("select i.id from ProductImage i where i.productId = :productId order by i.position, i.id")
    List<Long> findIds(@Param("productId") String productId);

    @Query("select count(i) from ProductImage i where i.productId = :productId")
    long countByProduct(@Param("productId") String productId);

    @Query("select coalesce(max(i.position), -1) from ProductImage i where i.productId = :productId")
    int maxPosition(@Param("productId") String productId);

    /** (contentType, bytes) de uma imagem, só se ela pertencer ao produto informado. */
    @Query("select i.contentType, i.data from ProductImage i where i.id = :imageId and i.productId = :productId")
    List<Object[]> findContent(@Param("productId") String productId, @Param("imageId") Long imageId);

    @Query("select i.id from ProductImage i where i.id = :imageId and i.productId = :productId")
    List<Long> findIdIfOwned(@Param("productId") String productId, @Param("imageId") Long imageId);

    @Modifying
    @Query("delete from ProductImage i where i.productId = :productId")
    void deleteByProductId(@Param("productId") String productId);

    @Modifying
    @Query("update ProductImage i set i.position = :position where i.id = :id")
    void updatePosition(@Param("id") Long id, @Param("position") int position);
}
