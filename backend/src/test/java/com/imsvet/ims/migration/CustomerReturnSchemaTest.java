package com.imsvet.ims.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.TestLots;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository test (R) of V08_002…004 (ADR-127, ADR-130, ADR-131, SD-140, SD-141): a sale line's returned quantity
 * never exceeds its sold quantity (the database backstop of BR-RET-001); a credit-note line's stored amounts replace
 * the formula, which is unchanged for every other line (ADR-016 parity); a return has a unique number, a non-blank
 * reason and its own credit note; a return line has a lot of its product, a positive quantity, a known disposition, a
 * discard reason exactly when discarded and a note for "other" (BR-RET-010), at most once per sale line.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class CustomerReturnSchemaTest {

    @Autowired
    private JdbcTemplate jdbc;

    private TestLots lots;
    private long user;
    private long product;
    private long lot;
    private long receipt;
    private long saleLine;
    private long creditNote;
    private long creditLine;

    @BeforeEach
    void sale() {
        lots = new TestLots(jdbc);
        user = lots.fixtureUser();
        product = lots.product("CRS-1", "Schema customer return");
        lot = lots.lot(product, "CRS-L1", LocalDate.of(2027, 5, 31), "5");
        receipt = document("receipt", "RCT-TEST-000001", null);
        saleLine = line(receipt, "3", "0.3350", "19.00");
        creditNote = document("credit_note", "CN-TEST-000001", receipt);
        creditLine = line(creditNote, "1", "0.3350", "19.00");
    }

    @Test
    void theReturnedQuantityStartsAtZeroAndNeverExceedsTheSoldQuantity() {
        assertThat(jdbc.queryForObject("SELECT returned_quantity_base FROM sales_document_lines WHERE id = ?",
                String.class, saleLine)).isEqualTo("0.000");

        jdbc.update("UPDATE sales_document_lines SET returned_quantity_base = 3 WHERE id = ?", saleLine);

        checkViolation(catchThrowable(() -> jdbc.update(
                "UPDATE sales_document_lines SET returned_quantity_base = 3.001 WHERE id = ?", saleLine)),
                "ck_sales_document_lines_returned");
        checkViolation(catchThrowable(() -> jdbc.update(
                "UPDATE sales_document_lines SET returned_quantity_base = -1 WHERE id = ?", saleLine)),
                "ck_sales_document_lines_returned");
    }

    @Test
    void storedCreditAmountsReplaceTheFormulaWhichIsUnchangedOtherwise() {
        // 3 × 0.335 = 1.005 → 1.01 HT; VAT 19 % of 1.01 = 0.1919 → 0.19 (ADR-093, ADR-115).
        assertThat(amounts(saleLine)).containsExactly("1.01", "0.19", "1.20");
        assertThat(amounts(creditLine)).containsExactly("0.34", "0.06", "0.40");

        jdbc.update("UPDATE sales_document_lines SET credited_ht = 0.33, credited_vat = 0.07 WHERE id = ?", creditLine);

        assertThat(amounts(creditLine)).containsExactly("0.33", "0.07", "0.40");
    }

    @Test
    void creditAmountsGoTogetherAndAreNotNegative() {
        checkViolation(catchThrowable(() -> jdbc.update(
                "UPDATE sales_document_lines SET credited_ht = 0.33 WHERE id = ?", creditLine)),
                "ck_sales_document_lines_credited");
        checkViolation(catchThrowable(() -> jdbc.update(
                "UPDATE sales_document_lines SET credited_ht = -0.01, credited_vat = 0 WHERE id = ?", creditLine)),
                "ck_sales_document_lines_credited");
    }

    @Test
    void aReturnHasAUniqueNumberAReasonAndItsOwnCreditNote() {
        checkViolation(catchThrowable(() -> header("CR-TEST-000001", " ", creditNote)),
                "ck_customer_returns_reason_not_blank");
        checkViolation(catchThrowable(() -> header("CR-TEST-000001", "Wrong product", receipt)),
                "ck_customer_returns_distinct_documents");
        header("CR-TEST-000001", "Wrong product", creditNote);

        assertThatThrownBy(() -> header("CR-TEST-000001", "Again", document("credit_note", "CN-TEST-000002", receipt)))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_customer_returns_number");
        assertThatThrownBy(() -> header("CR-TEST-000002", "Same credit note", creditNote))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_customer_returns_credit_note");
    }

    @Test
    void aLineHasALotOfItsProductAPositiveQuantityAndAKnownDisposition() {
        long header = header("CR-TEST-000003", "Wrong product", creditNote);
        long other = lots.product("CRS-2", "Other product");

        assertThatThrownBy(() -> line(header, other, "1", "restock", null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_customer_return_lines_lot_product");
        checkViolation(catchThrowable(() -> line(header, product, "0", "restock", null, null)),
                "ck_customer_return_lines_quantity");
        checkViolation(catchThrowable(() -> line(header, product, "1", "quarantine", null, null)),
                "ck_customer_return_lines_disposition");
        line(header, product, "1", "restock", null, null);

        assertThatThrownBy(() -> line(header, product, "1", "restock", null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_customer_return_lines_sale_line");
    }

    @Test
    void aDiscardHasAKnownReasonAndOtherNeedsANote() {
        long header = header("CR-TEST-000004", "Damaged", creditNote);

        checkViolation(catchThrowable(() -> line(header, product, "1", "discard", null, null)),
                "ck_customer_return_lines_reason_when_discard");
        checkViolation(catchThrowable(() -> line(header, product, "1", "restock", "opened", null)),
                "ck_customer_return_lines_reason_when_discard");
        checkViolation(catchThrowable(() -> line(header, product, "1", "discard", "melted", null)),
                "ck_customer_return_lines_discard_reason");
        checkViolation(catchThrowable(() -> line(header, product, "1", "discard", "other", " ")),
                "ck_customer_return_lines_note");
        checkViolation(catchThrowable(() -> line(header, product, "1", "restock", null, "A note")),
                "ck_customer_return_lines_note");

        line(header, product, "1", "discard", "other", "Bottle opened at the farm");
    }

    @Test
    void everyDiscardReasonOfBrRet010IsAccepted() {
        String[] reasons = { "opened", "packaging_damaged", "expired", "recalled", "cold_chain_doubt" };
        for (int i = 0; i < reasons.length; i++) {
            long header = header("CR-TEST-1" + i, "Discard " + reasons[i],
                    document("credit_note", "CN-TEST-1" + i, receipt));
            line(header, product, "1", "discard", reasons[i], null);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_return_lines WHERE disposition = 'discard'",
                Integer.class)).isEqualTo(reasons.length);
    }

    private long document(String type, String number, Long parent) {
        jdbc.update("""
                INSERT INTO sales_documents (doc_type, number, status, parent_id, issued_at, created_by, created_at,
                                             updated_at)
                VALUES (?, ?, 'confirmed', ?, UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""",
                type, number, parent, user);
        return jdbc.queryForObject("SELECT id FROM sales_documents WHERE doc_type = ? AND number = ?", Long.class,
                type, number);
    }

    private long line(long document, String quantity, String price, String vat) {
        jdbc.update("""
                INSERT INTO sales_document_lines (document_id, product_id, lot_id, quantity, quantity_base, unit_price,
                                                  vat_rate)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", document, product, lot, quantity, quantity, price, vat);
        return jdbc.queryForObject("SELECT MAX(id) FROM sales_document_lines WHERE document_id = ?", Long.class,
                document);
    }

    private long header(String number, String reason, long creditNoteId) {
        jdbc.update("""
                INSERT INTO customer_returns (number, document_id, credit_note_id, reason, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6))""", number, receipt, creditNoteId, reason, user);
        return jdbc.queryForObject("SELECT id FROM customer_returns WHERE number = ?", Long.class, number);
    }

    private void line(long header, long productId, String quantity, String disposition, String reason, String note) {
        long creditNoteId = jdbc.queryForObject("SELECT credit_note_id FROM customer_returns WHERE id = ?", Long.class,
                header);
        long credited = line(creditNoteId, "1", "0.3350", "19.00");
        jdbc.update("""
                INSERT INTO customer_return_lines (return_id, sale_line_id, lot_id, product_id, quantity_base, sealed,
                                                   disposition, discard_reason, discard_note, credit_note_line_id)
                VALUES (?, ?, ?, ?, ?, TRUE, ?, ?, ?, ?)""",
                header, saleLine, lot, productId, quantity, disposition, reason, note, credited);
    }

    private Object[] amounts(long line) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT CAST(line_ht AS CHAR) AS ht, CAST(line_vat AS CHAR) AS vat, CAST(line_ttc AS CHAR) AS ttc
                FROM sales_document_lines WHERE id = ?""", line);
        return new Object[] { row.get("ht"), row.get("vat"), row.get("ttc") };
    }

    /** MySQL reports a violated CHECK as error 3819 naming the constraint. */
    private static void checkViolation(Throwable thrown, String constraint) {
        assertThat(thrown).isInstanceOf(DataAccessException.class);
        assertThat(((DataAccessException) thrown).getMostSpecificCause())
                .isInstanceOfSatisfying(SQLException.class, sql -> {
                    assertThat(sql.getErrorCode()).isEqualTo(3819);
                    assertThat(sql.getMessage()).contains(constraint);
                });
    }
}
