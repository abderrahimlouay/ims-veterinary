package com.imsvet.ims.sales.infrastructure;

import com.imsvet.ims.sales.domain.SalesDocumentLine;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface SalesDocumentLineRepository extends Repository<SalesDocumentLine, Long> {

    SalesDocumentLine saveAndFlush(SalesDocumentLine line);

    List<SalesDocumentLine> findByDocumentIdOrderByIdAsc(Long documentId);

    Optional<SalesDocumentLine> findByIdAndDocumentId(Long id, Long documentId);

    void delete(SalesDocumentLine line);

    void flush();
}
