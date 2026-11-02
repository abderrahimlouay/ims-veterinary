package com.imsvet.ims.sales.infrastructure;

import com.imsvet.ims.sales.domain.CustomerReturn;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/** Insert and read only (BR-RET-007): no update or delete is exposed. */
public interface CustomerReturnRepository extends Repository<CustomerReturn, Long> {

    CustomerReturn saveAndFlush(CustomerReturn customerReturn);

    Optional<CustomerReturn> findById(Long id);

    /** A document's returns, oldest first. */
    List<CustomerReturn> findByDocumentIdOrderByIdAsc(Long documentId);
}
