package com.imsvet.ims.purchasing.application;

import com.imsvet.ims.catalog.application.ProductLookup;
import com.imsvet.ims.catalog.application.ProductLookup.ProductRef;
import com.imsvet.ims.catalog.application.ProductLookup.UnitRef;
import com.imsvet.ims.catalog.domain.UnitConversion;
import com.imsvet.ims.catalog.domain.UnitConversion.InexactConversionException;
import com.imsvet.ims.common.actor.ActorProvider;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.idempotency.IdempotencyKeys;
import com.imsvet.ims.common.idempotency.IdempotencyKeys.Claim;
import com.imsvet.ims.common.paging.PageRequests;
import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.common.time.BusinessCalendar;
import com.imsvet.ims.common.time.BusinessSettings;
import com.imsvet.ims.inventory.application.LotIdentities;
import com.imsvet.ims.inventory.application.StockEntries;
import com.imsvet.ims.inventory.application.StockEntries.Batch;
import com.imsvet.ims.inventory.application.StockEntries.Entered;
import com.imsvet.ims.inventory.application.StockEntries.Entry;
import com.imsvet.ims.inventory.domain.MovementType;
import com.imsvet.ims.numbering.application.NumberingService;
import com.imsvet.ims.numbering.domain.DocumentKey;
import com.imsvet.ims.purchasing.application.ReceiptViews.ReceiptDetail;
import com.imsvet.ims.purchasing.application.ReceiptViews.ReceiptSummary;
import com.imsvet.ims.purchasing.domain.GoodsReceipt;
import com.imsvet.ims.purchasing.domain.PurchaseOrder;
import com.imsvet.ims.purchasing.domain.PurchaseOrderLine;
import com.imsvet.ims.purchasing.domain.ReceiptStatus;
import com.imsvet.ims.purchasing.domain.Supplier;
import com.imsvet.ims.purchasing.domain.SupplierPrice;
import com.imsvet.ims.purchasing.infrastructure.GoodsReceiptRepository;
import com.imsvet.ims.purchasing.infrastructure.OrderLocks;
import com.imsvet.ims.purchasing.infrastructure.PurchaseOrderRepository;
import com.imsvet.ims.purchasing.infrastructure.SupplierPriceRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Goods receipts (FR-PURCH-005, W1b, api.md §5.4), one transaction (BR-PURCH-007). In the lock order of
 * database.md §7: the idempotency key (a repeat returns the first receipt, ADR-104); then everything that needs no
 * lock — supplier active (BR-PURCH-011), order receivable and its lines (ADR-096), products not archived, lot number
 * and expiry (BR-LOT-001), quantity converted exactly, cost per base unit with 4 decimals, an expired conforming
 * line refused (BR-LOT-012), one line per lot; then the GR number (ADR-097); then the lots through
 * {@link StockEntries} (find or create, weighted-average cost, supplier, movements, non-conforming lots blocked,
 * ADR-095/098/105); then the order, locked and checked again against what is outstanding (BR-PURCH-004); then the
 * receipt lines with the lots' previous state (ADR-106) and the price history (BR-PURCH-008, ADR-100).
 */
@Service
public class GoodsReceipts {

    public static final String ENDPOINT = "POST /api/goods-receipts";

    public record ReceiptInput(Long supplierId, Long purchaseOrderId, String supplierDocRef, String notes,
            boolean confirmLotBlocking, List<ReceivedLine> lines) {
    }

    /** {@code conforming} defaults to true; {@code unitCost} is per base unit. */
    public record ReceivedLine(Long poLineId, Long productId, Long unitId, BigDecimal quantity, String lotNumber,
            LocalDate expiryDate, BigDecimal unitCost, Boolean conforming) {
    }

    /** A line once validated. */
    private record ValidLine(PurchaseOrderLine poLine, ProductRef product, BigDecimal quantityBase, String lotNumber,
            LocalDate expiryDate, BigDecimal unitCost, boolean conforming) {
    }

    private static final int LOT_NUMBER_MAX = 60;
    private static final int DOC_REF_MAX = 60;
    private static final int COST_SCALE = 4;

    private final GoodsReceiptRepository receipts;
    private final PurchaseOrderRepository orders;
    private final OrderLocks orderLocks;
    private final SupplierPriceRepository prices;
    private final ActiveSuppliers suppliers;
    private final ProductLookup products;
    private final StockEntries entries;
    private final NumberingService numbering;
    private final IdempotencyKeys keys;
    private final ReceiptViewer viewer;
    private final ActorProvider actors;
    private final BusinessCalendar calendar;
    private final BusinessSettings business;
    private final Clock clock;

    public GoodsReceipts(GoodsReceiptRepository receipts, PurchaseOrderRepository orders, OrderLocks orderLocks,
            SupplierPriceRepository prices, ActiveSuppliers suppliers, ProductLookup products, StockEntries entries,
            NumberingService numbering, IdempotencyKeys keys, ReceiptViewer viewer, ActorProvider actors,
            BusinessCalendar calendar, BusinessSettings business, Clock clock) {
        this.receipts = receipts;
        this.orders = orders;
        this.orderLocks = orderLocks;
        this.prices = prices;
        this.suppliers = suppliers;
        this.products = products;
        this.entries = entries;
        this.numbering = numbering;
        this.keys = keys;
        this.viewer = viewer;
        this.actors = actors;
        this.calendar = calendar;
        this.business = business;
        this.clock = clock;
    }

    /** Every filter optional; {@code from}/{@code to} business dates, both inclusive; newest first. */
    @PreAuthorize("hasAuthority('receipt.read')")
    @Transactional(readOnly = true)
    public PageResponse<ReceiptSummary> search(Long supplierId, Long purchaseOrderId, String status, LocalDate from,
            LocalDate to, int page, int size) {
        ReceiptStatus wanted = status == null || status.isBlank() ? null : ReceiptStatus.fromCode(status)
                .orElseThrow(() -> OrderLineValidator.invalid("status", "UNKNOWN", "Unknown status."));
        Page<GoodsReceipt> found = receipts.search(supplierId, purchaseOrderId, wanted, startOf(from),
                to == null ? null : startOf(to.plusDays(1)), PageRequests.of(page, size, Sort.unsorted()));
        return PageResponse.of(found, viewer::summary);
    }

    @PreAuthorize("hasAuthority('receipt.read')")
    @Transactional(readOnly = true)
    public ReceiptDetail get(long id) {
        return viewer.detail(find(id));
    }

    /** {@code idempotencyKey}: the {@code Idempotency-Key} header, {@code null} when absent. */
    @PreAuthorize("hasAuthority('receipt.create')")
    @Transactional
    public ReceiptDetail confirm(ReceiptInput in, String idempotencyKey) {
        Long userId = actors.current().userId();
        Claim claim = keys.claim(userId, ENDPOINT, idempotencyKey, in, "goods_receipt");
        if (claim.replayed().isPresent()) {
            return viewer.detail(find(claim.replayed().get()));
        }
        PurchaseOrder order = in.purchaseOrderId() == null ? null : receivableOrder(in.purchaseOrderId());
        Supplier supplier = suppliers.require(order == null ? in.supplierId() : supplierOf(order, in.supplierId()));
        String docRef = docRef(in.supplierDocRef());
        List<ValidLine> lines = lines(in.lines(), order);
        if (order != null) {
            checkOutstanding(order, lines);
        }

        String number = numbering.next(DocumentKey.GR);
        Instant now = clock.instant();
        GoodsReceipt receipt = receipts.saveAndFlush(new GoodsReceipt(number, order == null ? null : order.id(),
                supplier.id(), docRef, blankToNull(in.notes()), userId, now));
        List<Entered> entered = entries.enter(
                new Batch(MovementType.PURCHASE_RECEIPT, "goods_receipt", receipt.id(), supplier.id(),
                        "Non-conforming at receipt " + number, in.confirmLotBlocking()),
                lines.stream().map(l -> new Entry(l.product().id(), l.lotNumber(), l.expiryDate(), l.quantityBase(),
                        l.unitCost(), !l.conforming())).toList());
        if (order != null) {
            orderLocks.lockAndRefresh(order);
            if (!order.status().receivable()) {
                throw notReceivable(order);
            }
            checkOutstanding(order, lines);
            Map<Long, BigDecimal> received = new LinkedHashMap<>();
            for (ValidLine l : lines) {
                received.merge(l.poLine().id(), l.quantityBase(), BigDecimal::add);
            }
            order.receive(received, userId, now);
            orders.flush();
        }
        List<GoodsReceipt.LineInput> receiptLines = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            ValidLine l = lines.get(i);
            Entered e = entered.get(i);
            receiptLines.add(new GoodsReceipt.LineInput(l.poLine() == null ? null : l.poLine().id(), l.product().id(),
                    e.lotId(), l.quantityBase(), l.unitCost(), l.conforming(), e.movementId(), e.created(),
                    e.costBefore(), e.statusBefore(), e.blocked()));
        }
        receipt.addLines(receiptLines);
        receipts.flush();
        appendPrices(receipt, lines, userId, now);
        claim.complete(receipt.id());
        return viewer.detail(receipt);
    }

    /** BR-PURCH-008, ADR-100: an entry per line whose price or discount differs from the latest entry in force. */
    private void appendPrices(GoodsReceipt receipt, List<ValidLine> lines, Long userId, Instant now) {
        LocalDate today = calendar.today();
        for (ValidLine l : lines) {
            BigDecimal discount = l.poLine() == null ? BigDecimal.ZERO.setScale(2) : l.poLine().discountPct();
            List<SupplierPrice> latest = prices.inForce(receipt.supplierId(), l.product().id(), today,
                    PageRequest.of(0, 1));
            boolean same = !latest.isEmpty() && latest.getFirst().unitPrice().compareTo(l.unitCost()) == 0
                    && latest.getFirst().discountPct().compareTo(discount) == 0;
            if (!same) {
                prices.saveAndFlush(SupplierPrice.fromReceipt(receipt.supplierId(), l.product().id(), l.unitCost(),
                        discount, today, userId, now, receipt.id()));
            }
        }
    }

    private PurchaseOrder receivableOrder(long id) {
        PurchaseOrder order = orders.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "Purchase order not found.", Map.of("id", id)));
        if (!order.status().receivable()) {
            throw notReceivable(order);
        }
        return order;
    }

    /** The receipt's supplier is the order's; another one given is refused. */
    private static Long supplierOf(PurchaseOrder order, Long given) {
        if (given != null && !given.equals(order.supplierId())) {
            throw OrderLineValidator.invalid("supplierId", "NOT_ORDER_SUPPLIER",
                    "The supplier of a receipt is the supplier of its order.");
        }
        return order.supplierId();
    }

    private static BusinessException notReceivable(PurchaseOrder order) {
        return new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                "Only a sent or partially received order can be received.",
                Map.of("status", order.status().code(), "action", "receive"));
    }

    /** ADR-096: per order line, everything received here at most what is still outstanding. */
    private static void checkOutstanding(PurchaseOrder order, List<ValidLine> lines) {
        Map<Long, BigDecimal> requested = new LinkedHashMap<>();
        for (ValidLine l : lines) {
            requested.merge(l.poLine().id(), l.quantityBase(), BigDecimal::add);
        }
        for (PurchaseOrderLine line : order.lines()) {
            BigDecimal wanted = requested.get(line.id());
            if (wanted != null && wanted.compareTo(line.outstandingBase()) > 0) {
                Map<String, Object> context = new LinkedHashMap<>();
                context.put("poLineId", line.id());
                context.put("productId", line.productId());
                context.put("outstandingBase", line.outstandingBase().toPlainString());
                context.put("requestedBase", wanted.setScale(UnitConversion.BASE_SCALE).toPlainString());
                throw new BusinessException(ErrorCode.RECEIPT_QUANTITY_EXCEEDS_ORDER,
                        "More than the quantity still outstanding on the order.", context);
            }
        }
    }

    private List<ValidLine> lines(List<ReceivedLine> lines, PurchaseOrder order) {
        if (lines == null || lines.isEmpty()) {
            throw OrderLineValidator.invalid("lines", "REQUIRED", "A receipt has at least one line.");
        }
        List<ValidLine> valid = new ArrayList<>();
        Set<String> lots = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            ValidLine line = line(lines.get(i), order, "lines[" + i + "].");
            if (!lots.add(LotIdentities.key(line.product().id(), line.lotNumber()))) {
                throw OrderLineValidator.invalid("lines[" + i + "].lotNumber", "DUPLICATE",
                        "A lot appears once per receipt.");
            }
            valid.add(line);
        }
        return valid;
    }

    private ValidLine line(ReceivedLine in, PurchaseOrder order, String prefix) {
        if (in.productId() == null) {
            throw OrderLineValidator.invalid(prefix + "productId", "REQUIRED", "The product is required.");
        }
        PurchaseOrderLine poLine = orderLine(in, order, prefix);
        ProductRef product = products.find(in.productId())
                .orElseThrow(() -> OrderLineValidator.invalid(prefix + "productId", "UNKNOWN", "Unknown product."));
        if (product.archived()) {
            throw new BusinessException(ErrorCode.PRODUCT_ARCHIVED, "This product is archived.",
                    Map.of("productId", product.id()));
        }
        BigDecimal quantityBase = quantityBase(in, product, prefix);
        String lotNumber = in.lotNumber() == null ? "" : in.lotNumber().trim();
        if (lotNumber.isEmpty()) {
            throw OrderLineValidator.invalid(prefix + "lotNumber", "REQUIRED", "The lot number is required.");
        }
        if (lotNumber.length() > LOT_NUMBER_MAX) {
            throw OrderLineValidator.invalid(prefix + "lotNumber", "SIZE", "At most 60 characters.");
        }
        if (in.expiryDate() == null) {
            throw OrderLineValidator.invalid(prefix + "expiryDate", "REQUIRED", "The expiry date is required.");
        }
        boolean conforming = in.conforming() == null || in.conforming();
        if (conforming && in.expiryDate().isBefore(calendar.today())) {
            throw OrderLineValidator.invalid(prefix + "expiryDate", "EXPIRED",
                    "This lot has expired; receive it only as non-conforming.");
        }
        return new ValidLine(poLine, product, quantityBase, lotNumber, in.expiryDate(), cost(in.unitCost(), prefix),
                conforming);
    }

    /** ADR-096: with an order, a line of that order for the same product; without, no order line. */
    private static PurchaseOrderLine orderLine(ReceivedLine in, PurchaseOrder order, String prefix) {
        if (order == null) {
            if (in.poLineId() != null) {
                throw OrderLineValidator.invalid(prefix + "poLineId", "NOT_ON_ORDER",
                        "A receipt without an order has no order lines.");
            }
            return null;
        }
        if (in.poLineId() == null) {
            throw OrderLineValidator.invalid(prefix + "poLineId", "REQUIRED", "The order line is required.");
        }
        return order.lines().stream()
                .filter(l -> l.id().equals(in.poLineId()) && l.productId().equals(in.productId())).findFirst()
                .orElseThrow(() -> OrderLineValidator.invalid(prefix + "productId", "NOT_ON_ORDER",
                        "This product is not on this line of the order."));
    }

    private BigDecimal quantityBase(ReceivedLine in, ProductRef product, String prefix) {
        BigDecimal factor = BigDecimal.ONE;
        if (in.unitId() != null) {
            UnitRef unit = products.unit(product.id(), in.unitId()).orElseThrow(
                    () -> OrderLineValidator.invalid(prefix + "unitId", "UNKNOWN", "Not a unit of this product."));
            factor = unit.factorToBase();
        }
        if (in.quantity() == null) {
            throw OrderLineValidator.invalid(prefix + "quantity", "REQUIRED", "The quantity is required.");
        }
        try {
            return UnitConversion.toBase(in.quantity(), factor, product.divisible());
        } catch (InexactConversionException e) {
            throw switch (e.refusal()) {
                case NOT_POSITIVE -> OrderLineValidator.invalid(prefix + "quantity", "POSITIVE",
                        "The quantity must be greater than zero.");
                case SCALE -> OrderLineValidator.invalid(prefix + "quantity", "SCALE",
                        "This quantity cannot be converted exactly.");
                case WHOLE_NUMBER -> OrderLineValidator.invalid(prefix + "quantity", "WHOLE_NUMBER",
                        "This product is counted in whole units.");
            };
        }
    }

    private static BigDecimal cost(BigDecimal value, String prefix) {
        if (value == null) {
            throw OrderLineValidator.invalid(prefix + "unitCost", "REQUIRED", "The unit cost is required.");
        }
        if (value.signum() < 0) {
            throw OrderLineValidator.invalid(prefix + "unitCost", "NOT_NEGATIVE", "A cost cannot be negative.");
        }
        if (value.stripTrailingZeros().scale() > COST_SCALE) {
            throw OrderLineValidator.invalid(prefix + "unitCost", "SCALE", "At most four decimals.");
        }
        return value.setScale(COST_SCALE);
    }

    private static String docRef(String value) {
        String ref = blankToNull(value);
        if (ref != null && ref.length() > DOC_REF_MAX) {
            throw OrderLineValidator.invalid("supplierDocRef", "SIZE", "At most 60 characters.");
        }
        return ref;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Instant startOf(LocalDate day) {
        return day == null ? null : day.atStartOfDay(business.timeZone()).toInstant();
    }

    private GoodsReceipt find(long id) {
        return receipts.findById(id).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "Goods receipt not found.", Map.of("id", id)));
    }
}
