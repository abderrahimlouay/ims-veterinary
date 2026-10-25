package com.imsvet.ims.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.sales.application.CustomerPricing;
import com.imsvet.ims.sales.domain.PriceResolver.Resolution;
import com.imsvet.ims.sales.domain.PriceResolver.Source;
import com.imsvet.ims.support.TestActor;
import com.imsvet.ims.support.TestLots;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

/**
 * The pricing port for Phase 7 (BR-SALE-006/007, ADR-107, ADR-113) on stored data: it reads the customer's category
 * and own price, the product and the unit, and takes {@code price.edit} / {@code sale.discount} from the signed-in
 * user, not from its caller. The precedence itself is {@link PriceResolverTest}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class CustomerPricingTest {

    @Autowired
    private CustomerPricing pricing;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    private long user;
    private long product;
    private long box;
    private long carton;
    private long withCategory;
    private long withOwnPrice;
    private long plain;

    @BeforeEach
    void data() {
        TestLots lots = new TestLots(jdbc);
        user = lots.fixtureUser();
        product = lots.product("PRICING-1", "Pricing demo");
        jdbc.update("UPDATE products SET sale_price = 0.5000 WHERE id = ?", product);
        box = unit("Box of 10", "10", null, true);
        carton = unit("Carton of 100", "100", "45.00", true);
        jdbc.update("""
                INSERT INTO customer_categories (name, discount_pct, created_at, updated_at)
                VALUES ('Pricing breeders', 5.00, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""");
        int category = jdbc.queryForObject("SELECT id FROM customer_categories WHERE name = 'Pricing breeders'",
                Integer.class);
        withCategory = customer("Pricing farm A", category);
        withOwnPrice = customer("Pricing farm B", category);
        plain = customer("Pricing farm C", null);
        jdbc.update("""
                INSERT INTO customer_product_prices (customer_id, product_id, unit_price, created_at, updated_at)
                VALUES (?, ?, 0.4000, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""", withOwnPrice, product);
    }

    @AfterEach
    void signOut() {
        TestActor.signOut();
    }

    /** Unrounded: the stored factor has 4 decimals, so price × factor has 8 (ADR-093 rounds the line). */
    @Test
    void theStoredFactsAreResolved() {
        TestActor.signIn(user, "sale.create");

        assertResolved(pricing.resolve(plain, product, null, null, null), "0.5000", Source.PRODUCT, "0");
        assertResolved(pricing.resolve(withCategory, product, box, null, null), "5.00000000", Source.PRODUCT, "5");
        assertResolved(pricing.resolve(withCategory, product, carton, null, null), "45.00", Source.UNIT, "5");
        assertResolved(pricing.resolve(withOwnPrice, product, carton, null, null), "40.00000000", Source.CUSTOMER, "0");
    }

    @Test
    void theRightsComeFromTheSignedInUser() {
        TestActor.signIn(user, "sale.create");
        assertThatThrownBy(() -> pricing.resolve(plain, product, null, new BigDecimal("0.30"), null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> pricing.resolve(withCategory, product, null, null, new BigDecimal("6")))
                .isInstanceOf(AccessDeniedException.class);
        assertResolved(pricing.resolve(withCategory, product, null, null, new BigDecimal("4")), "0.5000",
                Source.PRODUCT, "4");

        TestActor.signIn(user, "sale.create", "price.edit", "sale.discount");
        assertResolved(pricing.resolve(plain, product, null, new BigDecimal("0.30"), null), "0.30", Source.MANUAL,
                "0");
        assertResolved(pricing.resolve(withCategory, product, null, null, new BigDecimal("6")), "0.5000",
                Source.PRODUCT, "6");
    }

    @Test
    void anArchivedProductAndAnUnsellableOrForeignUnitAreRefused() {
        TestActor.signIn(user, "sale.create");
        long other = new TestLots(jdbc).product("PRICING-2", "Pricing other");
        jdbc.update("UPDATE product_units SET is_sellable = FALSE WHERE id = ?", box);

        assertCode(() -> pricing.resolve(plain, product, box, null, null), ErrorCode.UNIT_NOT_SELLABLE);
        assertCode(() -> pricing.resolve(plain, other, carton, null, null), ErrorCode.NOT_FOUND);
        jdbc.update("UPDATE products SET is_archived = TRUE WHERE id = ?", product);
        entityManager.clear();
        assertCode(() -> pricing.resolve(withOwnPrice, product, null, null, null), ErrorCode.PRODUCT_ARCHIVED);
    }

    private static void assertResolved(Resolution r, String price, Source source, String discount) {
        assertThat(r.unitPrice()).isEqualTo(new BigDecimal(price));
        assertThat(r.source()).isEqualTo(source);
        assertThat(r.discountPct()).isEqualByComparingTo(discount);
    }

    private static void assertCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }

    private long unit(String name, String factor, String salePrice, boolean sellable) {
        jdbc.update("""
                INSERT INTO product_units (product_id, name, factor_to_base, sale_price, is_sellable)
                VALUES (?, ?, ?, ?, ?)""", product, name, new BigDecimal(factor),
                salePrice == null ? null : new BigDecimal(salePrice), sellable);
        return jdbc.queryForObject("SELECT id FROM product_units WHERE product_id = ? AND name = ?", Long.class,
                product, name);
    }

    private long customer(String name, Integer category) {
        jdbc.update("""
                INSERT INTO customers (name, category_id, created_at, updated_at)
                VALUES (?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""", name, category);
        return jdbc.queryForObject("SELECT id FROM customers WHERE name = ?", Long.class, name);
    }
}
