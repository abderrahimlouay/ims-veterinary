package com.imsvet.ims.numbering.infrastructure;

import com.imsvet.ims.numbering.domain.DocumentSequence;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Counters are created on first use and only ever incremented; never deleted. */
public interface DocumentSequenceRepository extends Repository<DocumentSequence, DocumentSequence.Key> {

    /** Creates the year's counter at 0 unless it exists; a concurrent creation waits on the key, then does nothing. */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO document_sequences (doc_key, year, last_number) VALUES (:docKey, :year, 0)
            ON DUPLICATE KEY UPDATE last_number = last_number""")
    int createIfAbsent(String docKey, short year);

    /** {@code SELECT … FOR UPDATE}: the row stays locked until the caller's transaction ends (ADR-011). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM DocumentSequence s WHERE s.id.docKey = :docKey AND s.id.year = :year")
    Optional<DocumentSequence> findForUpdate(String docKey, short year);

    void flush();
}
