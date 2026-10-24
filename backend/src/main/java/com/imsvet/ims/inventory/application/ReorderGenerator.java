package com.imsvet.ims.inventory.application;

import com.imsvet.ims.catalog.application.ProductLookup;
import com.imsvet.ims.catalog.application.ProductLookup.ProductRef;
import com.imsvet.ims.catalog.application.ProductQueries;
import com.imsvet.ims.catalog.application.ProductStock.Figures;
import com.imsvet.ims.catalog.application.ProductStock.ProductThresholds;
import com.imsvet.ims.catalog.application.ProductStock.StockFilter;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.paging.PageRequests;
import com.imsvet.ims.inventory.domain.ProposalStatus;
import com.imsvet.ims.inventory.domain.ReorderProposal;
import com.imsvet.ims.inventory.domain.ReorderQuantity;
import com.imsvet.ims.inventory.domain.ReorderRun;
import com.imsvet.ims.inventory.infrastructure.ReorderProposalRepository;
import com.imsvet.ims.inventory.infrastructure.ReorderRunRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The generation of reorder proposals (W10, BR-STOCK-010), shared by the daily job and "Generate now" (ADR-078):
 * first lifts the suppression of dismissed products whose sellable stock is now above the reorder point (ADR-079),
 * then, for every product not archived with a reorder point > 0 and sellable ≤ reorder point (ADR-076, found in the
 * database through the catalog port, ADR-090), creates or updates its one pending proposal with max − sellable
 * (ADR-077), or reports it thresholds incomplete (no max stock) or inconsistent (max stock at or below the reorder
 * point, user decision 2026-10-23). From Phase 5 what is on open orders is subtracted, and nothing is proposed when
 * that covers the need (an existing pending proposal is then left as is, ADR-102). Idempotent; the run is recorded. Runs in the caller's transaction.
 */
@Component
public class ReorderGenerator {

    public record ProductBrief(long id, String sku, String commercialName) {
    }

    /**
     * api.md §4.5 generation result: {@code thresholdsIncomplete} have no max stock (ADR-077);
     * {@code thresholdsInconsistent} have a max stock at or below the reorder point (user decision 2026-10-23).
     */
    public record GenerationResult(int created, int updated, List<ProductBrief> thresholdsIncomplete,
            List<ProductBrief> thresholdsInconsistent) {
    }

    private final ProductQueries productQueries;
    private final ProductLookup products;
    private final ProductStockConditions stock;
    private final ReorderProposalRepository proposals;
    private final ReorderRunRepository runs;
    private final OpenOrders openOrders;
    private final Clock clock;

    public ReorderGenerator(ProductQueries productQueries, ProductLookup products, ProductStockConditions stock,
            ReorderProposalRepository proposals, ReorderRunRepository runs, OpenOrders openOrders, Clock clock) {
        this.productQueries = productQueries;
        this.products = products;
        this.stock = stock;
        this.proposals = proposals;
        this.runs = runs;
        this.openOrders = openOrders;
        this.clock = clock;
    }

    /** {@code triggeredBy} null: the daily job. A simultaneous generation is {@code CONCURRENT_MODIFICATION}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public GenerationResult generate(Long triggeredBy) {
        Instant now = clock.instant();
        Set<Long> suppressed = liftRecovered(now);
        int created = 0;
        int updated = 0;
        List<ProductBrief> incomplete = new ArrayList<>();
        List<ProductBrief> inconsistent = new ArrayList<>();
        int page = 0;
        Page<ProductRef> candidates;
        do {
            candidates = productQueries.find(null, null, false, List.of(stock.condition(StockFilter.BELOW_REORDER)),
                    page++, PageRequests.MAX_SIZE);
            List<ProductRef> wanted = candidates.stream().filter(p -> !suppressed.contains(p.id())).toList();
            Map<Long, Figures> figures = figures(wanted);
            Map<Long, ReorderProposal> pending = wanted.isEmpty() ? Map.of()
                    : proposals.findByStatusAndProductIdIn(ProposalStatus.PENDING,
                            wanted.stream().map(ProductRef::id).toList()).stream()
                            .collect(Collectors.toMap(ReorderProposal::productId, Function.identity()));
            Map<Long, BigDecimal> onOrder = wanted.isEmpty() ? Map.of()
                    : openOrders.outstandingBase(wanted.stream().map(ProductRef::id).toList());
            for (ProductRef product : wanted) {
                if (product.maxStock() != null && product.maxStock().compareTo(product.reorderPoint()) <= 0) {
                    inconsistent.add(new ProductBrief(product.id(), product.sku(), product.commercialName()));
                    continue;
                }
                BigDecimal sellable = figures.get(product.id()).sellable();
                if (product.maxStock() == null) {
                    incomplete.add(new ProductBrief(product.id(), product.sku(), product.commercialName()));
                    continue;
                }
                Optional<BigDecimal> quantity = ReorderQuantity.suggested(product.maxStock(), sellable,
                        onOrder.getOrDefault(product.id(), BigDecimal.ZERO), product.divisible());
                if (quantity.isEmpty()) {
                    continue;
                }
                if (pending.containsKey(product.id())) {
                    pending.get(product.id()).refresh(sellable, quantity.get(), now);
                    updated++;
                } else {
                    proposals.save(new ReorderProposal(product.id(), sellable, quantity.get(), now));
                    created++;
                }
            }
        } while (candidates.hasNext());
        try {
            proposals.flush();
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,
                    "Another generation of reorder proposals is running; try again.");
        }
        runs.save(new ReorderRun(now, triggeredBy, created, updated, incomplete.size()));
        return new GenerationResult(created, updated, incomplete, inconsistent);
    }

    /** Returns the products still suppressed. */
    private Set<Long> liftRecovered(Instant now) {
        List<ReorderProposal> dismissed = proposals.findBySuppressedTrue();
        if (dismissed.isEmpty()) {
            return Set.of();
        }
        Map<Long, ProductRef> refs = products.findAll(dismissed.stream().map(ReorderProposal::productId)
                .collect(Collectors.toSet()));
        Map<Long, Figures> figures = figures(refs.values());
        Set<Long> still = new HashSet<>();
        for (ReorderProposal proposal : dismissed) {
            ProductRef product = refs.get(proposal.productId());
            if (figures.get(product.id()).sellable().compareTo(product.reorderPoint()) > 0) {
                proposal.liftSuppression(now);
            } else {
                still.add(product.id());
            }
        }
        return still;
    }

    private Map<Long, Figures> figures(Collection<ProductRef> refs) {
        return refs.isEmpty() ? Map.of() : stock.figures(refs.stream()
                .map(p -> new ProductThresholds(p.id(), p.minStock(), p.reorderPoint())).toList());
    }
}
