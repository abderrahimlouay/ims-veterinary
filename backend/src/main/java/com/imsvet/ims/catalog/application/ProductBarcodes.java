package com.imsvet.ims.catalog.application;

import com.imsvet.ims.catalog.application.ProductViews.BarcodeView;
import com.imsvet.ims.catalog.application.ProductViews.ScanResult;
import com.imsvet.ims.catalog.application.ProductViews.UnitView;
import com.imsvet.ims.catalog.domain.BarcodeFormat;
import com.imsvet.ims.catalog.domain.BarcodeType;
import com.imsvet.ims.catalog.domain.Product;
import com.imsvet.ims.catalog.domain.ProductBarcode;
import com.imsvet.ims.catalog.domain.ProductUnit;
import com.imsvet.ims.catalog.infrastructure.ProductBarcodeRepository;
import com.imsvet.ims.catalog.infrastructure.ProductRepository;
import com.imsvet.ims.catalog.infrastructure.ProductUnitRepository;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Barcodes of a product (FR-PROD-003). BR-PROD-002: unique across products, exact and case-sensitive (ADR-058).
 * BR-PROD-008: format per type (ADR-067). BR-UNIT-005: a barcode may name a unit of its own product, and a scan
 * returns that unit (or the base unit) with a quantity of 1 (ADR-057).
 */
@Service
public class ProductBarcodes {

    /** {@code codeType} as sent; {@code unitId} {@code null} means the base unit. */
    public record BarcodeInput(String code, String codeType, Long unitId) {
    }

    private final ProductRepository products;
    private final ProductUnitRepository units;
    private final ProductBarcodeRepository barcodes;

    public ProductBarcodes(ProductRepository products, ProductUnitRepository units, ProductBarcodeRepository barcodes) {
        this.products = products;
        this.units = units;
        this.barcodes = barcodes;
    }

    @PreAuthorize("hasAuthority('product.read')")
    @Transactional(readOnly = true)
    public List<BarcodeView> list(long productId) {
        findProduct(productId);
        Map<Long, ProductUnit> unitsById = units.findByProductId(productId).stream()
                .collect(Collectors.toMap(ProductUnit::id, Function.identity()));
        return barcodes.findByProductId(productId).stream()
                .map(b -> ProductViews.barcode(b, b.unitId() == null ? null : unitsById.get(b.unitId()))).toList();
    }

    @PreAuthorize("hasAuthority('product.update')")
    @Transactional
    public BarcodeView add(long productId, BarcodeInput input) {
        Product product = findProduct(productId);
        BarcodeType type = type(input.codeType());
        BarcodeFormat.check(type, input.code()).ifPresent(problem -> {
            throw invalid("code", problem.name(), "The code does not match its barcode type.");
        });
        ProductUnit unit = input.unitId() == null ? null : units.findByIdAndProductId(input.unitId(), productId)
                .orElseThrow(() -> invalid("unitId", "UNKNOWN", "Not a unit of this product."));
        if (barcodes.findByCode(input.code()).isPresent()) {
            throw duplicate();
        }
        try {
            ProductBarcode saved = barcodes.saveAndFlush(new ProductBarcode(product, input.code(), type,
                    unit == null ? null : unit.id()));
            return ProductViews.barcode(saved, unit);
        } catch (DataIntegrityViolationException e) {
            throw duplicate();
        }
    }

    @PreAuthorize("hasAuthority('product.update')")
    @Transactional
    public void remove(long productId, long barcodeId) {
        ProductBarcode barcode = barcodes.findByIdAndProductId(barcodeId, productId).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "Barcode not found.", Map.of("id", barcodeId)));
        barcodes.delete(barcode);
        barcodes.flush();
    }

    /** BR-UNIT-005: exact, case-sensitive lookup; the quantity is always 1 of the matched unit. */
    @PreAuthorize("hasAuthority('product.read')")
    @Transactional(readOnly = true)
    public ScanResult scan(String code) {
        ProductBarcode barcode = barcodes.findByCode(code).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "No product has this barcode."));
        Product product = barcode.product();
        UnitView unit = barcode.unitId() == null ? null
                : units.findByIdAndProductId(barcode.unitId(), product.id()).map(ProductViews::unit).orElseThrow();
        return ProductViews.scan(product, unit);
    }

    private static BarcodeType type(String codeType) {
        if (codeType == null) {
            throw invalid("codeType", "REQUIRED", "The barcode type is required.");
        }
        return Arrays.stream(BarcodeType.values()).filter(t -> t.name().equals(codeType)).findFirst()
                .orElseThrow(() -> invalid("codeType", "UNKNOWN", "Unknown barcode type."));
    }

    private Product findProduct(long id) {
        return products.findById(id).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "Product not found.", Map.of("id", id)));
    }

    private static ValidationException invalid(String field, String code, String message) {
        return new ValidationException("The barcode is invalid.", List.of(new Violation(field, code, message)));
    }

    private static BusinessException duplicate() {
        return new BusinessException(ErrorCode.BARCODE_DUPLICATE, "This barcode is already used.",
                Map.of("field", "code"));
    }
}
