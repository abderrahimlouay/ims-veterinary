package com.imsvet.ims.purchasing.application;

import com.imsvet.ims.inventory.application.ProposalConversions;
import com.imsvet.ims.inventory.application.ProposalConversions.PendingProposal;
import com.imsvet.ims.purchasing.application.OrderViews.PricePrefill;
import com.imsvet.ims.purchasing.application.OrderViews.PurchaseOrderDetail;
import com.imsvet.ims.purchasing.application.PurchaseOrders.OrderInput;
import com.imsvet.ims.purchasing.application.PurchaseOrders.RequestedLine;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Converts pending reorder proposals into draft purchase orders (BR-PURCH-010, ADR-102, W10), one transaction: per
 * group, one new draft for the chosen supplier (active, BR-PURCH-011) with one line per proposal — base unit,
 * suggested quantity, price and discount prefilled as for a new line (ADR-099) — then the proposals marked
 * converted with that supplier. A proposal converts once: the proposals are locked and checked pending after the
 * order's number is taken (database.md §7), so a second conversion waits and is refused.
 */
@Service
public class ReorderConversion {

    public record Group(Long supplierId, List<Long> proposalIds) {
    }

    private final PurchaseOrders orders;
    private final OrderPrefill prefill;
    private final ActiveSuppliers suppliers;
    private final ProposalConversions proposals;

    public ReorderConversion(PurchaseOrders orders, OrderPrefill prefill, ActiveSuppliers suppliers,
            ProposalConversions proposals) {
        this.orders = orders;
        this.prefill = prefill;
        this.suppliers = suppliers;
        this.proposals = proposals;
    }

    @PreAuthorize("hasAuthority('reorder.manage') and hasAuthority('purchase.create')")
    @Transactional
    public List<PurchaseOrderDetail> convert(List<Group> groups) {
        validate(groups);
        List<PurchaseOrderDetail> created = new ArrayList<>();
        for (Group group : groups) {
            long supplierId = suppliers.require(group.supplierId()).id();
            List<PendingProposal> pending = proposals.lockPending(group.proposalIds());
            List<RequestedLine> lines = pending.stream().map(p -> {
                PricePrefill price = prefill.prefill(supplierId, p.productId(), null);
                return new RequestedLine(p.productId(), null, p.suggestedQty(), price.unitPrice(), price.discountPct(),
                        null);
            }).toList();
            created.add(orders.create(new OrderInput(supplierId, null, lines)));
            proposals.markConverted(group.proposalIds(), supplierId);
        }
        return created;
    }

    private static void validate(List<Group> groups) {
        if (groups == null || groups.isEmpty()) {
            throw OrderLineValidator.invalid("groups", "REQUIRED", "Choose at least one group of proposals.");
        }
        Set<Long> suppliersSeen = new HashSet<>();
        Set<Long> proposalsSeen = new HashSet<>();
        for (int i = 0; i < groups.size(); i++) {
            Group group = groups.get(i);
            String prefix = "groups[" + i + "].";
            if (group.supplierId() == null) {
                throw OrderLineValidator.invalid(prefix + "supplierId", "REQUIRED", "The supplier is required.");
            }
            if (!suppliersSeen.add(group.supplierId())) {
                throw OrderLineValidator.invalid(prefix + "supplierId", "DUPLICATE", "One group per supplier.");
            }
            if (group.proposalIds() == null || group.proposalIds().isEmpty()) {
                throw OrderLineValidator.invalid(prefix + "proposalIds", "REQUIRED", "Choose at least one proposal.");
            }
            for (Long id : group.proposalIds()) {
                if (id == null || !proposalsSeen.add(id)) {
                    throw OrderLineValidator.invalid(prefix + "proposalIds", "DUPLICATE",
                            "A proposal is converted once.");
                }
            }
        }
    }
}
