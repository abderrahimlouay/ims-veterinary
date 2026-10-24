package com.imsvet.ims.inventory.application;

import com.imsvet.ims.catalog.application.ProductLookup;
import com.imsvet.ims.catalog.application.ProductLookup.ProductRef;
import com.imsvet.ims.common.actor.ActorProvider;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.common.paging.PageRequests;
import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.inventory.application.CountViews.UserRef;
import com.imsvet.ims.inventory.application.ReorderGenerator.GenerationResult;
import com.imsvet.ims.inventory.domain.ProposalStatus;
import com.imsvet.ims.inventory.domain.ReorderProposal;
import com.imsvet.ims.inventory.infrastructure.ReorderProposalRepository;
import com.imsvet.ims.users.application.UserNames;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reorder proposals (W10, api.md §4.5): list, "Generate now" (same code as the daily job, ADR-078) and dismiss
 * (ADR-079). Conversion to a purchase order arrives in Phase 5.
 */
@Service
public class ReorderProposals {

    public record ProposalProduct(long id, String sku, String commercialName, String baseUnit, boolean divisible) {
    }

    /** api.md §4.5 {@code ProposalEntry}; {@code currentQty} is the sellable quantity at the last generation. */
    public record ProposalEntry(long id, ProposalProduct product, BigDecimal currentQty, BigDecimal suggestedQty,
            BigDecimal reorderPoint, BigDecimal maxStock, String status, Instant createdAt, Instant updatedAt,
            Instant dismissedAt, UserRef dismissedBy, Long supplierId) {
    }

    private final ReorderProposalRepository proposals;
    private final ReorderGenerator generator;
    private final ProductLookup products;
    private final UserNames userNames;
    private final ActorProvider actors;
    private final Clock clock;

    public ReorderProposals(ReorderProposalRepository proposals, ReorderGenerator generator, ProductLookup products,
            UserNames userNames, ActorProvider actors, Clock clock) {
        this.proposals = proposals;
        this.generator = generator;
        this.products = products;
        this.userNames = userNames;
        this.actors = actors;
        this.clock = clock;
    }

    @PreAuthorize("hasAuthority('reorder.read')")
    @Transactional(readOnly = true)
    public PageResponse<ProposalEntry> search(String status, int page, int size) {
        ProposalStatus wanted = status == null || status.isBlank() ? null : ProposalStatus.fromCode(status)
                .orElseThrow(() -> new ValidationException("The proposal filter is invalid.",
                        List.of(new Violation("status", "UNKNOWN", "Pending, converted or dismissed."))));
        Page<ReorderProposal> found = proposals.search(wanted, PageRequests.of(page, size, Sort.unsorted()));
        Map<Long, ProductRef> refs = products.findAll(found.map(ReorderProposal::productId).toSet());
        Map<Long, String> names = userNames.fullNames(found.stream().map(ReorderProposal::dismissedBy)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return PageResponse.of(found, p -> entry(p, refs.get(p.productId()), names));
    }

    @PreAuthorize("hasAuthority('reorder.manage')")
    @Transactional
    public GenerationResult generateNow() {
        return generator.generate(actors.current().userId());
    }

    @PreAuthorize("hasAuthority('reorder.manage')")
    @Transactional
    public ProposalEntry dismiss(long id) {
        ReorderProposal proposal = proposals.findByIdForUpdate(id).orElseThrow(() -> new BusinessException(
                ErrorCode.NOT_FOUND, "Reorder proposal not found.", Map.of("id", id)));
        if (proposal.status() != ProposalStatus.PENDING) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Only a pending proposal can be dismissed.", Map.of("status", proposal.status().code()));
        }
        Long userId = actors.current().userId();
        proposal.dismiss(userId, clock.instant());
        proposals.flush();
        return entry(proposal, products.find(proposal.productId()).orElseThrow(), userNames.fullNames(List.of(userId)));
    }

    private static ProposalEntry entry(ReorderProposal p, ProductRef product, Map<Long, String> names) {
        return new ProposalEntry(p.id(), new ProposalProduct(product.id(), product.sku(), product.commercialName(),
                product.baseUnit(), product.divisible()), p.currentQty(), p.suggestedQty(), product.reorderPoint(),
                product.maxStock(), p.status().code(), p.createdAt(), p.updatedAt(), p.dismissedAt(),
                p.dismissedBy() == null ? null : new UserRef(p.dismissedBy(), names.get(p.dismissedBy())),
                p.supplierId());
    }
}
