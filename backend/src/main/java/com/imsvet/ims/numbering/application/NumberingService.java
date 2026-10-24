package com.imsvet.ims.numbering.application;

import com.imsvet.ims.common.security.InternalService;
import com.imsvet.ims.common.time.BusinessCalendar;
import com.imsvet.ims.numbering.domain.DocumentKey;
import com.imsvet.ims.numbering.domain.DocumentSequence;
import com.imsvet.ims.numbering.infrastructure.DocumentSequenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only issuer of document numbers (BR-DOC-001, ADR-011, ADR-097): {@code KEY-YYYY-NNNNNN}, one counter per key
 * and business year (the year of the business date in the business time zone, never the UTC date). Runs only in
 * the caller's transaction: the counter row stays locked until it commits, so documents of one key are serialised
 * and a rollback gives the number back (no gap). Lock order (database.md §7): the idempotency key if any, this row,
 * then lots in ascending id, then the customer; callers allocate after their validation and before locking lots.
 */
@Service
@InternalService
public class NumberingService {

    private final DocumentSequenceRepository sequences;
    private final BusinessCalendar calendar;

    public NumberingService(DocumentSequenceRepository sequences, BusinessCalendar calendar) {
        this.sequences = sequences;
        this.calendar = calendar;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(DocumentKey key) {
        short year = (short) calendar.today().getYear();
        sequences.createIfAbsent(key.name(), year);
        DocumentSequence sequence = sequences.findForUpdate(key.name(), year).orElseThrow();
        int value = sequence.increment();
        sequences.flush();
        return "%s-%04d-%06d".formatted(key.name(), year, value);
    }
}
