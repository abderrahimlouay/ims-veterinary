package com.imsvet.ims.inventory.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** The count DTOs of api.md §4.5 ({@code CountSummary}, {@code CountDetail}, {@code CountLine}). */
public final class CountViews {

    public record UserRef(long id, String fullName) {
    }

    public record CountSummary(long id, String type, String status, Instant startedAt, UserRef startedBy,
            Instant closedAt, long lineCount, long countedCount) {
    }

    /** {@code categoryId} or {@code productIds} for a cycle count (ADR-073); both empty for a full count. */
    public record Scope(Integer categoryId, List<Long> productIds) {
    }

    public record CountDetail(long id, String type, String status, Scope scope, String notes, Instant startedAt,
            UserRef startedBy, Instant closedAt, UserRef closedBy, long version, List<Line> lines) {
    }

    public record LineProduct(long id, String sku, String commercialName, String baseUnit) {
    }

    public record LineLot(long id, String lotNumber, LocalDate expiryDate) {
    }

    /** A lot unknown to the system, created at regularisation (ADR-074). */
    public record FoundLot(String lotNumber, LocalDate expiryDate, BigDecimal unitCost) {
    }

    /**
     * api.md {@code CountLine} (ADR-072): {@code varianceQty} = counted − balance at count;
     * {@code movementsSinceCount} is the signed sum of the lot's movements after the watermark (null while not
     * counted); {@code currentBalance} null for a found lot.
     */
    public record Line(long id, LineProduct product, LineLot lot, FoundLot foundLot, BigDecimal theoreticalQty,
            BigDecimal countedQty, Instant countedAt, UserRef countedBy, BigDecimal balanceAtCount,
            BigDecimal varianceQty, BigDecimal movementsSinceCount, BigDecimal currentBalance, boolean regularized,
            Long adjustmentMovementId) {
    }

    private CountViews() {
    }
}
