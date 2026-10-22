package com.imsvet.ims.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.inventory.domain.ExpiryPolicy;
import com.imsvet.ims.inventory.domain.FefoAllocator;
import com.imsvet.ims.inventory.domain.FefoAllocator.Allocation;
import com.imsvet.ims.inventory.domain.FefoAllocator.Candidate;
import com.imsvet.ims.inventory.domain.LotStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * BR-LOT-005: FEFO proposes sellable lots (BR-STOCK-011) by expiry date then id, splitting the quantity across
 * lots and reporting what is missing; blocked, recalled, expired and empty lots are skipped.
 */
class FefoAllocatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);
    private static final ExpiryPolicy POLICY = new ExpiryPolicy(TODAY, 60, 30, 15);

    @Test
    void ordersByExpiryThenIdWhateverTheInputOrder() {
        Allocation a = FefoAllocator.allocate(List.of(
                lot(3, TODAY.plusDays(90), "10"), lot(2, TODAY.plusDays(30), "10"), lot(1, TODAY.plusDays(30), "10")),
                dec("25"), POLICY);

        assertThat(a.lines()).extracting(l -> l.candidate().id()).containsExactly(1L, 2L, 3L);
        assertThat(a.lines()).extracting(FefoAllocator.Line::quantityBase)
                .containsExactly(dec("10.000"), dec("10.000"), dec("5.000"));
        assertThat(a.shortfallBase()).isEqualTo(dec("0.000"));
    }

    @Test
    void takesOnlyWhatIsNeeded() {
        Allocation a = FefoAllocator.allocate(List.of(lot(1, TODAY.plusDays(10), "10"), lot(2, TODAY.plusDays(20), "10")),
                dec("4"), POLICY);

        assertThat(a.lines()).hasSize(1);
        assertThat(a.lines().getFirst().quantityBase()).isEqualTo(dec("4.000"));
    }

    @Test
    void skipsLotsThatCannotBeSold() {
        Allocation a = FefoAllocator.allocate(List.of(
                new Candidate(1, TODAY.plusDays(5), dec("10"), LotStatus.BLOCKED),
                new Candidate(2, TODAY.plusDays(6), dec("10"), LotStatus.RECALLED),
                lot(3, TODAY.minusDays(1), "10"),
                lot(4, TODAY.plusDays(7), "0"),
                lot(5, TODAY, "2"),
                lot(6, TODAY.plusDays(8), "10")), dec("5"), POLICY);

        assertThat(a.lines()).extracting(l -> l.candidate().id()).containsExactly(5L, 6L);
    }

    @Test
    void reportsTheShortfall() {
        Allocation a = FefoAllocator.allocate(List.of(lot(1, TODAY.plusDays(10), "2.5")), dec("4"), POLICY);

        assertThat(a.lines().getFirst().quantityBase()).isEqualTo(dec("2.500"));
        assertThat(a.shortfallBase()).isEqualTo(dec("1.500"));
        assertThat(FefoAllocator.allocate(List.of(), dec("3"), POLICY).shortfallBase()).isEqualTo(dec("3.000"));
    }

    private static Candidate lot(long id, LocalDate expiry, String quantity) {
        return new Candidate(id, expiry, dec(quantity), LotStatus.ACTIVE);
    }

    private static BigDecimal dec(String value) {
        return new BigDecimal(value);
    }
}
