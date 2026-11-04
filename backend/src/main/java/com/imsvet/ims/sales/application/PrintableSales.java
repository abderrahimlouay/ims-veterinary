package com.imsvet.ims.sales.application;

import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.security.InternalService;
import com.imsvet.ims.sales.application.SalesViews.SaleDetail;
import com.imsvet.ims.sales.application.SalesViews.SaleWarning;
import com.imsvet.ims.sales.domain.Customer;
import com.imsvet.ims.sales.domain.CustomerReturn;
import com.imsvet.ims.sales.domain.SalesDocument;
import com.imsvet.ims.sales.infrastructure.CustomerRepository;
import com.imsvet.ims.sales.infrastructure.CustomerReturnRepository;
import com.imsvet.ims.sales.infrastructure.SalesDocumentRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read port the {@code printing} module prints a sales document from (ADR-138, ADR-141, ADR-142): the detail as
 * the API shows it, the customer's printed identity, the number of the document it derives from, and the withdrawal
 * warnings once per product and species. The caller checks {@code sale.read}.
 */
@Service
@InternalService
public class PrintableSales {

    /** What an invoice prints of its customer (ADR-141); legal numbers are {@code null} when unknown. */
    public record PrintedCustomer(String name, String address, String commune, String wilaya, String phone,
            String nif, String nis, String rc, String ai) {
    }

    public record SalePrint(SaleDetail detail, PrintedCustomer customer, String parentNumber,
            List<SaleWarning> withdrawals) {
    }

    private final SalesDocumentRepository documents;
    private final CustomerRepository customers;
    private final SaleViewer viewer;
    private final CustomerReturnRepository returns;

    PrintableSales(SalesDocumentRepository documents, CustomerRepository customers, SaleViewer viewer,
            CustomerReturnRepository returns) {
        this.documents = documents;
        this.customers = customers;
        this.viewer = viewer;
        this.returns = returns;
    }

    @Transactional(readOnly = true)
    public SalePrint of(long id) {
        SalesDocument document = documents.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "Unknown sales document.", Map.of("id", id)));
        PrintedCustomer customer = document.customerId() == null ? null
                : customers.findById(document.customerId()).map(PrintableSales::printed).orElse(null);
        String parent = document.parentId() == null ? null
                : documents.findById(document.parentId()).map(SalesDocument::number).orElse(null);
        return new SalePrint(viewer.detail(document), customer, parent, viewer.withdrawalWarnings(document));
    }

    /** The credit note a customer return issued (ADR-143: printed with {@code return.read}). */
    @Transactional(readOnly = true)
    public long creditNoteOfReturn(long returnId) {
        return returns.findById(returnId).map(CustomerReturn::creditNoteId).orElseThrow(() -> new BusinessException(
                ErrorCode.NOT_FOUND, "Unknown customer return.", Map.of("id", returnId)));
    }

    private static PrintedCustomer printed(Customer c) {
        return new PrintedCustomer(c.name(), c.address(), c.commune(), c.wilaya(), c.phone(), c.taxId(), c.nis(),
                c.rc(), c.ai());
    }
}
