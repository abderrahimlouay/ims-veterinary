package com.imsvet.ims.catalog.application;

import com.imsvet.ims.audit.application.AuditRecorder;
import com.imsvet.ims.audit.domain.AuditAction;
import com.imsvet.ims.catalog.application.ProductViews.ProductDetail;
import com.imsvet.ims.catalog.application.ProductStock.Condition;
import com.imsvet.ims.catalog.application.ProductStock.StockFilter;
import com.imsvet.ims.catalog.application.ProductViews.ProductSummary;
import com.imsvet.ims.catalog.domain.Product;
import com.imsvet.ims.catalog.domain.ReferenceEntry;
import com.imsvet.ims.catalog.infrastructure.DosageFormRepository;
import com.imsvet.ims.catalog.infrastructure.ManufacturerRepository;
import com.imsvet.ims.catalog.infrastructure.ProductRepository;
import com.imsvet.ims.catalog.infrastructure.ProductSearchRepository;
import com.imsvet.ims.catalog.infrastructure.ReferenceRepository;
import com.imsvet.ims.catalog.infrastructure.TherapeuticCategoryRepository;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.common.paging.PageRequests;
import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.common.security.Permissions;
import com.imsvet.ims.settings.application.SettingsProvider;
import com.imsvet.ims.settings.domain.SettingDefinition;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Product sheets (FR-PROD-001/002/009). Validates BR-PROD-003/005 and ADR-050 (whole thresholds unless
 * divisible); SKU unique (BR-PROD-001). Prices per base unit (ADR-049): setting or changing one needs
 * {@code price.edit}, and every change after creation is audited {@code PRICE_CHANGE} in the same
 * transaction, before the save (BR-PROD-006).
 */
@Service
public class ProductCatalog {

    /**
     * The request fields. {@code vatRate} {@code null} at creation takes the {@code default_vat_rate} setting; a
     * {@code null} price is 0 at creation and unchanged on update.
     */
    public record ProductInput(String sku, String commercialName, Integer categoryId, Integer dosageFormId,
            Integer manufacturerId, String baseUnit, boolean divisible, BigDecimal vatRate, BigDecimal minStock,
            BigDecimal maxStock, BigDecimal reorderPoint, BigDecimal purchasePrice, BigDecimal salePrice) {
    }

    private static final String PRICE_EDIT = "price.edit";

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final ProductRepository products;
    private final TherapeuticCategoryRepository categories;
    private final DosageFormRepository dosageForms;
    private final ManufacturerRepository manufacturers;
    private final SettingsProvider settings;
    private final AuditRecorder audit;
    private final Clock clock;
    private final ProductSearchRepository search;
    private final ProductStock stock;

    public ProductCatalog(ProductRepository products, TherapeuticCategoryRepository categories,
            DosageFormRepository dosageForms, ManufacturerRepository manufacturers, SettingsProvider settings,
            AuditRecorder audit, Clock clock, ProductSearchRepository search, ProductStock stock) {
        this.products = products;
        this.categories = categories;
        this.dosageForms = dosageForms;
        this.manufacturers = manufacturers;
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
        this.search = search;
        this.stock = stock;
    }

    /** The search criteria of {@code GET /api/products} (ADR-047, ADR-048, barcode exact); all optional. */
    public record SearchCriteria(String q, Integer categoryId, Integer manufacturerId, Integer ingredientId,
            Integer speciesId, String barcode, boolean includeArchived, boolean inStockOnly) {
    }

    /**
     * NFR-002 search: {@code q} anywhere in the name, the SKU or an active-ingredient name, ignoring case and
     * accents (collation); exact SKU first, then names starting with {@code q}, then the others, by name. With
     * {@code inStockOnly}, only products with sellable stock, filtered in the database (ADR-090).
     */
    @PreAuthorize("hasAuthority('product.read')")
    @Transactional(readOnly = true)
    public PageResponse<ProductSummary> search(SearchCriteria c, int page, int size) {
        var request = PageRequests.of(page, size, Sort.unsorted());
        String term = c.q() == null || c.q().isBlank() ? null : c.q().trim();
        String escaped = term == null ? null : term.replaceAll("([!%_])", "!$1");
        String pattern = escaped == null ? null : "%" + escaped + "%";
        String prefix = escaped == null ? null : escaped + "%";
        String barcode = c.barcode() == null || c.barcode().isEmpty() ? null : c.barcode();
        ProductSearchRepository.Criteria criteria = new ProductSearchRepository.Criteria(term, pattern, prefix,
                c.categoryId(), c.manufacturerId(), c.ingredientId(), c.speciesId(), barcode, c.includeArchived());
        List<Condition> conditions = c.inStockOnly() ? List.of(stock.condition(StockFilter.HAS_SELLABLE)) : List.of();
        List<Product> found = search.search(criteria, conditions, request);
        long total = search.count(criteria, conditions);
        return PageResponse.of(new PageImpl<>(ProductViews.summaries(found, stock), request, total), s -> s);
    }

    @PreAuthorize("hasAuthority('product.read')")
    @Transactional(readOnly = true)
    public ProductDetail get(long id) {
        return ProductViews.detail(find(id));
    }

    @PreAuthorize("hasAuthority('product.create')")
    @Transactional
    public ProductDetail create(ProductInput input) {
        BigDecimal vat = input.vatRate() != null ? input.vatRate()
                : new BigDecimal(settings.value(SettingDefinition.DEFAULT_VAT_RATE));
        Product.Core core = validate(input, vat);
        BigDecimal purchase = orZero(input.purchasePrice());
        BigDecimal sale = orZero(input.salePrice());
        if ((purchase.signum() != 0 || sale.signum() != 0) && !Permissions.granted(PRICE_EDIT)) {
            throw new AccessDeniedException("Setting a price needs price.edit");
        }
        rejectDuplicateSku(core.sku(), null);
        Product product = new Product(core, clock.instant());
        product.setPrices(purchase, sale, clock.instant());
        return ProductViews.detail(save(product));
    }

    /** An archived product stays editable (user decision 2026-10-21). */
    @PreAuthorize("hasAuthority('product.update')")
    @Transactional
    public ProductDetail update(long id, ProductInput input, long version) {
        Product product = find(id);
        if (product.version() != version) {
            throw new ObjectOptimisticLockingFailureException(Product.class, id);
        }
        if (input.vatRate() == null) {
            throw invalid(List.of(new Violation("vatRate", "REQUIRED", "VAT is required.")));
        }
        Product.Core core = validate(input, input.vatRate());
        BigDecimal purchase = input.purchasePrice() == null ? product.purchasePrice() : price(input.purchasePrice());
        BigDecimal sale = input.salePrice() == null ? product.salePrice() : price(input.salePrice());
        Map<String, Object> oldPrices = new LinkedHashMap<>();
        Map<String, Object> newPrices = new LinkedHashMap<>();
        collectChange("purchasePrice", product.purchasePrice(), purchase, oldPrices, newPrices);
        collectChange("salePrice", product.salePrice(), sale, oldPrices, newPrices);
        if (!newPrices.isEmpty() && !Permissions.granted(PRICE_EDIT)) {
            throw new AccessDeniedException("Changing a price needs price.edit");
        }
        rejectDuplicateSku(core.sku(), product.id());
        product.update(core, clock.instant());
        if (!newPrices.isEmpty()) {
            product.setPrices(purchase, sale, clock.instant());
            audit.record(AuditAction.PRICE_CHANGE, "products", product.id(), oldPrices, newPrices);
        }
        return ProductViews.detail(save(product));
    }

    @PreAuthorize("hasAuthority('product.archive')")
    @Transactional
    public ProductDetail archive(long id) {
        Product product = find(id);
        if (!product.archived()) {
            product.archive(clock.instant());
        }
        return ProductViews.detail(save(product));
    }

    @PreAuthorize("hasAuthority('product.archive')")
    @Transactional
    public ProductDetail restore(long id) {
        Product product = find(id);
        if (product.archived()) {
            product.restore(clock.instant());
        }
        return ProductViews.detail(save(product));
    }

    private Product save(Product product) {
        try {
            return products.saveAndFlush(product);
        } catch (DataIntegrityViolationException e) {
            throw duplicateSku();
        }
    }

    private Product find(long id) {
        return products.findById(id).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "Product not found.", Map.of("id", id)));
    }

    private void rejectDuplicateSku(String sku, Long self) {
        if (products.findBySku(sku).filter(p -> !p.id().equals(self)).isPresent()) {
            throw duplicateSku();
        }
    }

    private static BusinessException duplicateSku() {
        return new BusinessException(ErrorCode.PRODUCT_SKU_DUPLICATE, "This SKU is already used.",
                Map.of("field", "sku"));
    }

    private static void collectChange(String field, BigDecimal before, BigDecimal after, Map<String, Object> oldValues,
            Map<String, Object> newValues) {
        if (before.compareTo(after) != 0) {
            oldValues.put(field, before.toPlainString());
            newValues.put(field, after.toPlainString());
        }
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(4) : price(value);
    }

    /** Already validated (≥ 0, at most 4 decimals). */
    private static BigDecimal price(BigDecimal value) {
        return value.setScale(4);
    }

    private Product.Core validate(ProductInput in, BigDecimal vat) {
        List<Violation> violations = new ArrayList<>();
        priceViolation("purchasePrice", in.purchasePrice(), violations);
        priceViolation("salePrice", in.salePrice(), violations);
        if (vat.signum() < 0 || vat.compareTo(HUNDRED) > 0 || vat.stripTrailingZeros().scale() > 2) {
            violations.add(new Violation("vatRate", "RANGE", "VAT is a percentage from 0 to 100, two decimals."));
        }
        BigDecimal min = quantity("minStock", in.minStock(), BigDecimal.ZERO, in.divisible(), violations);
        BigDecimal max = in.maxStock() == null ? null
                : quantity("maxStock", in.maxStock(), null, in.divisible(), violations);
        BigDecimal reorder = quantity("reorderPoint", in.reorderPoint(), BigDecimal.ZERO, in.divisible(), violations);
        if (min != null && max != null && max.compareTo(min) < 0) {
            violations.add(new Violation("maxStock", "BELOW_MIN", "The maximum stock is below the minimum."));
        }
        var category = reference(categories, in.categoryId(), "categoryId", violations);
        var dosageForm = reference(dosageForms, in.dosageFormId(), "dosageFormId", violations);
        var manufacturer = reference(manufacturers, in.manufacturerId(), "manufacturerId", violations);
        if (!violations.isEmpty()) {
            throw invalid(violations);
        }
        return new Product.Core(in.sku().trim(), in.commercialName().trim(), category, dosageForm, manufacturer,
                in.baseUnit().trim(), in.divisible(), vat.setScale(2), min, max, reorder);
    }

    /** ADR-049: ≥ 0 with at most 4 decimals. */
    private static void priceViolation(String field, BigDecimal value, List<Violation> violations) {
        if (value == null) {
            return;
        }
        if (value.signum() < 0) {
            violations.add(new Violation(field, "NOT_NEGATIVE", "A price cannot be negative."));
        } else if (value.stripTrailingZeros().scale() > 4) {
            violations.add(new Violation(field, "SCALE", "At most four decimals."));
        }
    }

    /** ≥ 0, at most 3 decimals, whole for a non-divisible product (ADR-050). */
    private static BigDecimal quantity(String field, BigDecimal value, BigDecimal fallback, boolean divisible,
            List<Violation> violations) {
        BigDecimal v = value == null ? fallback : value;
        if (v.signum() < 0) {
            violations.add(new Violation(field, "NOT_NEGATIVE", "The value cannot be negative."));
        } else if (v.stripTrailingZeros().scale() > 3) {
            violations.add(new Violation(field, "SCALE", "At most three decimals."));
        } else if (!divisible && v.stripTrailingZeros().scale() > 0) {
            violations.add(new Violation(field, "WHOLE_NUMBER", "This product is counted in whole units."));
        } else {
            return v.setScale(3);
        }
        return null;
    }

    private static <T extends ReferenceEntry> T reference(ReferenceRepository<T> repository, Integer id, String field,
            List<Violation> violations) {
        if (id == null) {
            return null;
        }
        T entry = repository.findById(id).orElse(null);
        if (entry == null) {
            violations.add(new Violation(field, "UNKNOWN", "This entry does not exist."));
        }
        return entry;
    }

    private static ValidationException invalid(List<Violation> violations) {
        return new ValidationException("The product contains invalid fields.", violations);
    }
}
