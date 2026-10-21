package com.imsvet.ims.catalog.infrastructure;

import com.imsvet.ims.catalog.domain.ReferenceEntry;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

/**
 * Operations shared by every reference list; one concrete repository per table.
 */
@NoRepositoryBean
public interface ReferenceRepository<T extends ReferenceEntry> extends Repository<T, Integer> {

    List<T> findAllByOrderByNameAsc();

    Optional<T> findById(Integer id);

    /** Case- and accent-insensitive through the column collation (ADR-051). */
    Optional<T> findByName(String name);

    T saveAndFlush(T entry);

    void delete(T entry);

    void flush();
}
