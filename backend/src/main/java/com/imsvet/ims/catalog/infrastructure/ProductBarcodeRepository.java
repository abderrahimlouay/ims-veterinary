package com.imsvet.ims.catalog.infrastructure;

import com.imsvet.ims.catalog.domain.ProductBarcode;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Barcodes; {@code code} comparisons are exact and case-sensitive through the column collation (ADR-058). */
public interface ProductBarcodeRepository extends Repository<ProductBarcode, Long> {

    ProductBarcode saveAndFlush(ProductBarcode barcode);

    void delete(ProductBarcode barcode);

    void flush();

    @Query("SELECT b FROM ProductBarcode b JOIN FETCH b.product p LEFT JOIN FETCH p.category WHERE b.code = :code")
    Optional<ProductBarcode> findByCode(String code);

    @Query("SELECT b FROM ProductBarcode b WHERE b.id = :id AND b.product.id = :productId")
    Optional<ProductBarcode> findByIdAndProductId(long id, long productId);

    @Query("SELECT b FROM ProductBarcode b WHERE b.product.id = :productId ORDER BY b.id")
    List<ProductBarcode> findByProductId(long productId);
}
