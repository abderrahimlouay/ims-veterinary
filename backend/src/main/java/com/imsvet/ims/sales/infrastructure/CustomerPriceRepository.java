package com.imsvet.ims.sales.infrastructure;

import com.imsvet.ims.sales.domain.CustomerPrice;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/** Customer prices, set and removed one at a time (ADR-111). */
public interface CustomerPriceRepository extends Repository<CustomerPrice, CustomerPrice.Key> {

    CustomerPrice saveAndFlush(CustomerPrice price);

    void delete(CustomerPrice price);

    void flush();

    Optional<CustomerPrice> findById(CustomerPrice.Key key);

    List<CustomerPrice> findByIdCustomerId(Long customerId);
}
