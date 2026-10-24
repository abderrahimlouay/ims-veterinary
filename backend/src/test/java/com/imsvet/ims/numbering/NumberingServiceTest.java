package com.imsvet.ims.numbering;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.numbering.application.NumberingService;
import com.imsvet.ims.numbering.domain.DocumentKey;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * BR-DOC-001, ADR-097: {@code KEY-YYYY-NNNNNN}, one counter per key and business year; the year is that of the
 * business date in Africa/Algiers (UTC+1), not the UTC date. Years far from the other tests keep counts exact.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class NumberingServiceTest {

    @Autowired
    private NumberingService numbering;

    @Autowired
    private TestClock clock;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void numbersFollowEachOtherPerKey() {
        clock.set(Instant.parse("2031-03-10T09:00:00Z"));

        assertThat(numbering.next(DocumentKey.PO)).isEqualTo("PO-2031-000001");
        assertThat(numbering.next(DocumentKey.PO)).isEqualTo("PO-2031-000002");
        assertThat(numbering.next(DocumentKey.GR)).isEqualTo("GR-2031-000001");
        assertThat(numbering.next(DocumentKey.SR)).isEqualTo("SR-2031-000001");
        assertThat(numbering.next(DocumentKey.PO)).isEqualTo("PO-2031-000003");
    }

    @Test
    void theYearIsTheBusinessYearInAlgiers() {
        clock.set(Instant.parse("2032-12-31T22:30:00Z"));
        assertThat(numbering.next(DocumentKey.GR)).isEqualTo("GR-2032-000001");

        clock.set(Instant.parse("2032-12-31T23:30:00Z"));
        assertThat(numbering.next(DocumentKey.GR)).as("31 December 23:30 UTC is 1 January 00:30 in Algiers")
                .isEqualTo("GR-2033-000001");
        assertThat(numbering.next(DocumentKey.GR)).isEqualTo("GR-2033-000002");

        clock.set(Instant.parse("2032-12-31T22:59:00Z"));
        assertThat(numbering.next(DocumentKey.GR)).as("the old year's counter continues").isEqualTo("GR-2032-000002");
    }
}
