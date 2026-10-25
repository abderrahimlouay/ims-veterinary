package com.imsvet.ims.sales.infrastructure;

import com.imsvet.ims.sales.domain.CustomerCategory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/** Customer categories; never deleted (ADR-112). */
public interface CustomerCategoryRepository extends Repository<CustomerCategory, Integer> {

    CustomerCategory saveAndFlush(CustomerCategory category);

    Optional<CustomerCategory> findById(Integer id);

    /** Ignoring case and accents (collation of the column). */
    Optional<CustomerCategory> findByName(String name);

    List<CustomerCategory> findAllByOrderByNameAsc();
}
