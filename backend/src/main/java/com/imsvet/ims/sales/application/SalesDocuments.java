package com.imsvet.ims.sales.application;

import com.imsvet.ims.common.actor.ActorProvider;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.common.paging.PageRequests;
import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.common.time.BusinessCalendar;
import com.imsvet.ims.sales.application.SalesViews.CustomerRef;
import com.imsvet.ims.sales.application.SalesViews.SaleDetail;
import com.imsvet.ims.sales.application.SalesViews.SaleSummary;
import com.imsvet.ims.sales.domain.Customer;
import com.imsvet.ims.sales.domain.SalesDocStatus;
import com.imsvet.ims.sales.domain.SalesDocType;
import com.imsvet.ims.sales.domain.SalesDocument;
import com.imsvet.ims.sales.infrastructure.CustomerRepository;
import com.imsvet.ims.sales.infrastructure.SalesDocumentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Draft sales documents (FR-SALE-001/002). A draft has no number and no side effect (ADR-122). Only a counter receipt
 * may be anonymous, a credit sale needs a known active customer and is never a receipt (BR-SALE-012, BR-CUST-004);
 * only drafts change (BR-SALE-004, optimistic lock, ADR-027). A credit note comes from a return (Phase 8) and a
 * derived document from conversion, so neither is created here.
 */
@Service
public class SalesDocuments {

    public record DraftInput(String docType, Long customerId, Boolean creditSale, LocalDate dueDate, String notes) {
    }

    private static final int NOTES_MAX = 2000;

    private final SalesDocumentRepository documents;
    private final CustomerRepository customers;
    private final SaleViewer viewer;
    private final ActorProvider actors;
    private final BusinessCalendar calendar;
    private final Clock clock;

    public SalesDocuments(SalesDocumentRepository documents, CustomerRepository customers, SaleViewer viewer,
            ActorProvider actors, BusinessCalendar calendar, Clock clock) {
        this.documents = documents;
        this.customers = customers;
        this.viewer = viewer;
        this.actors = actors;
        this.calendar = calendar;
        this.clock = clock;
    }

    /** All filters optional; {@code from} and {@code to} are business dates, both inclusive; newest first. */
    @PreAuthorize("hasAuthority('sale.read')")
    @Transactional(readOnly = true)
    public PageResponse<SaleSummary> search(String docType, String status, Long customerId, String number,
            LocalDate from, LocalDate to, int page, int size) {
        SalesDocType type = docType == null || docType.isBlank() ? null : type(docType);
        SalesDocStatus wanted = status == null || status.isBlank() ? null : SalesDocStatus.fromCode(status)
                .orElseThrow(() -> invalid("status", "UNKNOWN", "Unknown status."));
        Instant start = from == null ? null : calendar.startOf(from);
        Instant end = to == null ? null : calendar.startOf(to.plusDays(1));
        Page<SalesDocument> found = documents.search(type, wanted, customerId,
                number == null || number.isBlank() ? null : number.trim(), start, end,
                PageRequests.of(page, size, Sort.unsorted()));
        Map<Long, CustomerRef> names = customerRefs(found.getContent());
        return PageResponse.of(found, d -> SalesViews.summary(d, names.get(d.customerId())));
    }

    @PreAuthorize("hasAuthority('sale.read')")
    @Transactional(readOnly = true)
    public SaleDetail get(long id) {
        return viewer.detail(find(id));
    }

    @PreAuthorize("hasAuthority('sale.create')")
    @Transactional
    public SaleDetail create(DraftInput in) {
        SalesDocType type = type(in.docType());
        if (!type.creatableDirectly()) {
            throw invalid("docType", "NOT_CREATABLE", "A credit note is issued by a customer return.");
        }
        boolean credit = Boolean.TRUE.equals(in.creditSale());
        requireParties(type, in.customerId(), credit);
        return viewer.detail(documents.saveAndFlush(new SalesDocument(type, in.customerId(), credit, dueDate(in, credit),
                notes(in.notes()), actors.current().userId(), clock.instant())));
    }

    /** A full replacement of the header, while draft and with the current version; the type never changes. */
    @PreAuthorize("hasAuthority('sale.create')")
    @Transactional
    public SaleDetail update(long id, DraftInput in, long version) {
        SalesDocument document = draft(id);
        if (document.version() != version) {
            throw new ObjectOptimisticLockingFailureException(SalesDocument.class, id);
        }
        boolean credit = Boolean.TRUE.equals(in.creditSale());
        requireParties(document.docType(), in.customerId(), credit);
        document.updateHeader(in.customerId(), credit, dueDate(in, credit), notes(in.notes()), clock.instant());
        return viewer.detail(documents.saveAndFlush(document));
    }

    /** Abandons a draft: it stays cancelled and without a number (ADR-122). */
    @PreAuthorize("hasAuthority('sale.create')")
    @Transactional
    public void abandon(long id) {
        SalesDocument document = draft(id);
        document.abandon(actors.current().userId(), clock.instant());
        documents.saveAndFlush(document);
    }

    private void requireParties(SalesDocType type, Long customerId, boolean credit) {
        if (credit && type.counterReceipt()) {
            throw invalid("creditSale", "NOT_ALLOWED", "A receipt is never a credit sale.");
        }
        if (customerId == null) {
            if (credit) {
                throw new BusinessException(ErrorCode.CUSTOMER_REQUIRED_FOR_CREDIT,
                        "A credit sale needs a customer.", Map.of());
            }
            if (!type.counterReceipt()) {
                throw invalid("customerId", "REQUIRED", "Only a counter receipt can be anonymous.");
            }
            return;
        }
        Customer customer = customers.findById(customerId)
                .orElseThrow(() -> invalid("customerId", "UNKNOWN", "Unknown customer."));
        if (!customer.active()) {
            throw new BusinessException(ErrorCode.CUSTOMER_INACTIVE, "This customer is inactive.",
                    Map.of("customerId", customerId));
        }
    }

    private static LocalDate dueDate(DraftInput in, boolean credit) {
        if (in.dueDate() != null && !credit) {
            throw invalid("dueDate", "NOT_ALLOWED", "Only a credit sale has a due date.");
        }
        return in.dueDate();
    }

    private static String notes(String value) {
        String notes = value == null || value.isBlank() ? null : value.trim();
        if (notes != null && notes.length() > NOTES_MAX) {
            throw invalid("notes", "SIZE", "At most " + NOTES_MAX + " characters.");
        }
        return notes;
    }

    private static SalesDocType type(String code) {
        if (code == null || code.isBlank()) {
            throw invalid("docType", "REQUIRED", "The document type is required.");
        }
        return SalesDocType.fromCode(code).orElseThrow(() -> invalid("docType", "UNKNOWN", "Unknown document type."));
    }

    private SalesDocument draft(long id) {
        SalesDocument document = find(id);
        if (document.status() != SalesDocStatus.DRAFT) {
            throw new BusinessException(ErrorCode.DOCUMENT_IMMUTABLE, "Only a draft can be changed.",
                    Map.of("status", document.status().code()));
        }
        return document;
    }

    private SalesDocument find(long id) {
        return documents.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "Sales document not found.", Map.of("id", id)));
    }

    private Map<Long, CustomerRef> customerRefs(List<SalesDocument> found) {
        return customers.findAllByIdIn(found.stream().map(SalesDocument::customerId)
                        .filter(Objects::nonNull).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Customer::id, c -> new CustomerRef(c.id(), c.name()), (a, b) -> a));
    }

    private static ValidationException invalid(String field, String code, String message) {
        return new ValidationException("The sales document is invalid.", List.of(new Violation(field, code, message)));
    }
}
