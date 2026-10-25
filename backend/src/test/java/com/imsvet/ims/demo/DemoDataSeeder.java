package com.imsvet.ims.demo;

import com.imsvet.ims.catalog.application.ProductBarcodes;
import com.imsvet.ims.catalog.application.ProductBarcodes.BarcodeInput;
import com.imsvet.ims.catalog.application.ProductCatalog;
import com.imsvet.ims.catalog.application.ProductCatalog.ProductInput;
import com.imsvet.ims.catalog.application.ProductSpecies;
import com.imsvet.ims.catalog.application.ProductSpecies.PeriodLine;
import com.imsvet.ims.catalog.application.ProductUnits;
import com.imsvet.ims.catalog.application.ProductUnits.UnitInput;
import com.imsvet.ims.catalog.application.ProductViews.UnitView;
import com.imsvet.ims.catalog.application.ReferenceItemView;
import com.imsvet.ims.catalog.application.ReferenceLists;
import com.imsvet.ims.catalog.domain.ReferenceList;
import com.imsvet.ims.inventory.application.CountRegularisation;
import com.imsvet.ims.inventory.application.CountViews.CountDetail;
import com.imsvet.ims.inventory.application.CountViews.Line;
import com.imsvet.ims.inventory.application.IntegrityChecks;
import com.imsvet.ims.inventory.application.InventoryCounts;
import com.imsvet.ims.inventory.application.InventoryCounts.AddInput;
import com.imsvet.ims.inventory.application.InventoryCounts.OpenInput;
import com.imsvet.ims.inventory.application.LotActions;
import com.imsvet.ims.inventory.application.OpeningStock;
import com.imsvet.ims.inventory.application.OpeningStock.OpeningInput;
import com.imsvet.ims.inventory.application.ReorderProposals;
import com.imsvet.ims.inventory.application.StockAdjustments;
import com.imsvet.ims.inventory.application.StockAdjustments.AdjustmentInput;
import com.imsvet.ims.purchasing.application.GoodsReceipts;
import com.imsvet.ims.purchasing.application.GoodsReceipts.ReceiptInput;
import com.imsvet.ims.purchasing.application.GoodsReceipts.ReceivedLine;
import com.imsvet.ims.purchasing.application.OrderViews.OrderLine;
import com.imsvet.ims.purchasing.application.OrderViews.PurchaseOrderDetail;
import com.imsvet.ims.purchasing.application.PurchaseOrders;
import com.imsvet.ims.purchasing.application.PurchaseOrders.OrderInput;
import com.imsvet.ims.purchasing.application.PurchaseOrders.RequestedLine;
import com.imsvet.ims.purchasing.application.ReceiptViews.ReceiptDetail;
import com.imsvet.ims.purchasing.application.SupplierDirectory;
import com.imsvet.ims.purchasing.application.SupplierDirectory.SupplierInput;
import com.imsvet.ims.purchasing.application.SupplierPrices;
import com.imsvet.ims.purchasing.application.SupplierPrices.PriceInput;
import com.imsvet.ims.purchasing.application.SupplierReturns;
import com.imsvet.ims.purchasing.application.SupplierReturns.ReturnInput;
import com.imsvet.ims.purchasing.application.SupplierReturns.ReturnedLine;
import com.imsvet.ims.sales.application.CustomerCategories;
import com.imsvet.ims.sales.application.CustomerCategories.CategoryInput;
import com.imsvet.ims.sales.application.CustomerCredit;
import com.imsvet.ims.sales.application.CustomerCredit.CreditLimitInput;
import com.imsvet.ims.sales.application.CustomerDirectory;
import com.imsvet.ims.sales.application.CustomerDirectory.CustomerInput;
import com.imsvet.ims.sales.application.CustomerPrices;
import com.imsvet.ims.sales.application.CustomerViews.CustomerDetail;
import com.imsvet.ims.users.application.UserAdministration;
import com.imsvet.ims.users.application.UserAdministration.NewUser;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Builds a small, varied trial history through the application services (catalogue, purchasing, stock), so the
 * ledger, balances, audit and document numbers are consistent: 5 users, 20 products in 6 categories, 4 suppliers
 * with price history, about 40 lots (healthy, expiring in 15/30/60 days, 2 expired, 1 blocked, 1 recalled), 6 orders,
 * 4 receipts (one cancelled, one with a non-conforming line), a supplier return, an inventory count, adjustments,
 * an opening-stock entry, a pending reorder proposal, 3 customer categories and 12 customers (veterinarians, breeders
 * and others; own prices; limits "cash only", "no limit" and amounts; one inactive; an Arabic and an accented name).
 * Every name is fictional and every phone number is in the reserved fake range {@code 0100 00 xx xx} (testing.md
 * §3, ADR-114). Dates are relative to today (the
 * {@link DemoClock} is pinned to each moment); quantities that vary come from a fixed seed. Runs once: a database
 * that already holds the demo data is left as is (reset = a new database, README).
 */
@Component
@Profile("demo")
class DemoDataSeeder {

    private static final Logger LOG = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final ZoneId BUSINESS = ZoneId.of("Africa/Algiers");
    private static final String MARKER_SUPPLIER = "Distrimed Ouest";

    /** One demo product; {@code units}: name → factor; {@code withdrawal}: species code → meat/milk/eggs days. */
    private record Spec(String key, String sku, String name, String category, String form, int manufacturer,
            String baseUnit, boolean divisible, String vat, String min, String max, String reorder, String purchase,
            String sale, Map<String, String> units, List<String> species, Map<String, int[]> withdrawal,
            boolean barcode) {
    }

    private final Environment environment;
    private final JdbcTemplate jdbc;
    private final DemoClock clock;
    private final UserAdministration users;
    private final ReferenceLists references;
    private final ProductCatalog catalog;
    private final ProductUnits units;
    private final ProductBarcodes barcodes;
    private final ProductSpecies species;
    private final SupplierDirectory suppliers;
    private final SupplierPrices prices;
    private final PurchaseOrders orders;
    private final GoodsReceipts receipts;
    private final SupplierReturns returns;
    private final OpeningStock openingStock;
    private final StockAdjustments adjustments;
    private final InventoryCounts counts;
    private final CountRegularisation regularisation;
    private final LotActions lotActions;
    private final ReorderProposals reorder;
    private final IntegrityChecks integrity;
    private final CustomerCategories customerCategories;
    private final CustomerDirectory customers;
    private final CustomerCredit credit;
    private final CustomerPrices customerPrices;

    private final Random random = new Random(20261024L);
    private final LocalDate today;
    private final Map<String, Long> products = new HashMap<>();
    private final Map<String, Long> lots = new HashMap<>();
    private final Map<String, Long> supplierIds = new HashMap<>();
    private int barcodeSequence = 1;

    DemoDataSeeder(Environment environment, JdbcTemplate jdbc, DemoClock clock, UserAdministration users,
            ReferenceLists references, ProductCatalog catalog, ProductUnits units, ProductBarcodes barcodes,
            ProductSpecies species, SupplierDirectory suppliers, SupplierPrices prices, PurchaseOrders orders,
            GoodsReceipts receipts, SupplierReturns returns, OpeningStock openingStock, StockAdjustments adjustments,
            InventoryCounts counts, CountRegularisation regularisation, LotActions lotActions,
            ReorderProposals reorder, IntegrityChecks integrity, CustomerCategories customerCategories,
            CustomerDirectory customers, CustomerCredit credit, CustomerPrices customerPrices) {
        this.environment = environment;
        this.jdbc = jdbc;
        this.clock = clock;
        this.users = users;
        this.references = references;
        this.catalog = catalog;
        this.units = units;
        this.barcodes = barcodes;
        this.species = species;
        this.suppliers = suppliers;
        this.prices = prices;
        this.orders = orders;
        this.receipts = receipts;
        this.returns = returns;
        this.openingStock = openingStock;
        this.adjustments = adjustments;
        this.counts = counts;
        this.regularisation = regularisation;
        this.lotActions = lotActions;
        this.reorder = reorder;
        this.integrity = integrity;
        this.customerCategories = customerCategories;
        this.customers = customers;
        this.credit = credit;
        this.customerPrices = customerPrices;
        this.today = LocalDate.now(BUSINESS);
    }

    void seedOnce() {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM suppliers WHERE name = ?", Integer.class, MARKER_SUPPLIER) > 0) {
            LOG.info("Demo data already present; nothing seeded");
            return;
        }
        String admin = environment.getProperty("IMS_BOOTSTRAP_ADMIN_USERNAME");
        List<Long> adminIds = admin == null ? List.of()
                : jdbc.queryForList("SELECT id FROM users WHERE username = ?", Long.class, admin);
        if (adminIds.isEmpty()) {
            throw new IllegalStateException("The demo profile needs the bootstrap administrator: set "
                    + "IMS_BOOTSTRAP_ADMIN_USERNAME and IMS_BOOTSTRAP_ADMIN_PASSWORD (README).");
        }
        DemoActor.signIn(adminIds.getFirst(), jdbc.queryForList("SELECT code FROM permissions", String.class));
        try {
            seed();
        } finally {
            clock.release();
            DemoActor.signOut();
        }
        LOG.info("Demo data seeded");
    }

    private void seed() {
        at(-121, 9, 0);
        createUsers();
        at(-120, 9, 30);
        Map<String, Integer> categories = categories();
        List<Integer> manufacturers = manufacturers();
        Map<String, Integer> forms = codes(ReferenceList.DOSAGE_FORMS);
        Map<String, Integer> speciesIds = codes(ReferenceList.ANIMAL_SPECIES);
        for (Spec spec : specs()) {
            createProduct(spec, categories, manufacturers, forms, speciesIds);
        }
        createSuppliers();
        at(-110, 10, 0);
        lots.put("IOD-2309", openingStock.enter(new OpeningInput(products.get("iode"), "IOD-2309", today.plusDays(90),
                new BigDecimal("1000"), null, new BigDecimal("0.0110"))).lot().id());
        purchasing();
        stockActivity();
        at(-2, 9, 0);
        createCustomers();
        at(-1, 6, 0);
        reorder.generateNow();
        clock.release();
        integrity.runNow();
    }

    private void createUsers() {
        String shared = environment.getProperty("IMS_DEMO_PASSWORD");
        Map<String, String> created = new LinkedHashMap<>();
        for (String[] u : new String[][] { { "pharmacien", "Démo Pharmacien", "Pharmacist" },
                { "caissier", "Démo Caissier", "Seller" }, { "magasinier", "Démo Magasinier", "Pharmacist" },
                { "lecture", "Démo Lecture seule", "Accountant" } }) {
            String password = shared != null ? shared : generatedPassword();
            short role = jdbc.queryForObject("SELECT id FROM roles WHERE name = ?", Short.class, u[2]);
            users.create(new NewUser(u[0], u[1], null, role, password));
            created.put(u[0] + " (" + u[2] + ")", password);
        }
        if (shared == null) {
            StringBuilder message = new StringBuilder("Demo users created (shown once, change at first login):");
            created.forEach((user, password) -> message.append(System.lineSeparator()).append("  ").append(user)
                    .append(" / ").append(password));
            System.out.println(message);
        }
    }

    private static String generatedPassword() {
        SecureRandom secure = new SecureRandom();
        String alphabet = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder password = new StringBuilder("Demo-");
        for (int i = 0; i < 14; i++) {
            password.append(alphabet.charAt(secure.nextInt(alphabet.length())));
        }
        return password.toString();
    }

    private Map<String, Integer> categories() {
        Map<String, Integer> byCode = codes(ReferenceList.CATEGORIES);
        byCode.put("DISINFECTANT", references.create(ReferenceList.CATEGORIES, "Désinfectants", null).id());
        return byCode;
    }

    private List<Integer> manufacturers() {
        List<Integer> ids = new ArrayList<>();
        for (String[] m : new String[][] { { "Atlas Vét (démo)", "Algérie" }, { "Numidia Santé Animale (démo)", "Algérie" },
                { "Saharavet (démo)", "Algérie" }, { "Medivet Méditerranée (démo)", "France" } }) {
            ids.add(references.create(ReferenceList.MANUFACTURERS, m[0], m[1]).id());
        }
        return ids;
    }

    private Map<String, Integer> codes(ReferenceList list) {
        Map<String, Integer> byCode = new HashMap<>();
        for (ReferenceItemView item : references.list(list)) {
            if (item.code() != null) {
                byCode.put(item.code(), item.id());
            }
        }
        return byCode;
    }

    private void createProduct(Spec s, Map<String, Integer> categories, List<Integer> manufacturers,
            Map<String, Integer> forms, Map<String, Integer> speciesIds) {
        long id = catalog.create(new ProductInput(s.sku(), s.name(), categories.get(s.category()), forms.get(s.form()),
                manufacturers.get(s.manufacturer()), s.baseUnit(), s.divisible(), new BigDecimal(s.vat()),
                new BigDecimal(s.min()), s.max() == null ? null : new BigDecimal(s.max()), new BigDecimal(s.reorder()),
                new BigDecimal(s.purchase()), new BigDecimal(s.sale()))).id();
        products.put(s.key(), id);
        Long firstUnit = null;
        for (Map.Entry<String, String> u : s.units().entrySet()) {
            UnitView unit = units.add(id, new UnitInput(u.getKey(), new BigDecimal(u.getValue()), null, true));
            firstUnit = firstUnit == null ? unit.id() : firstUnit;
        }
        if (s.barcode()) {
            barcodes.add(id, new BarcodeInput(ean13(), "EAN13", null));
            if (firstUnit != null) {
                barcodes.add(id, new BarcodeInput(ean13(), "EAN13", firstUnit));
            }
        }
        if (!s.species().isEmpty()) {
            species.replaceTargetSpecies(id, s.species().stream().map(speciesIds::get).toList());
        }
        if (!s.withdrawal().isEmpty()) {
            species.replaceWithdrawalPeriods(id, s.withdrawal().entrySet().stream().map(w -> new PeriodLine(
                    speciesIds.get(w.getKey()), days(w.getValue()[0]), days(w.getValue()[1]), days(w.getValue()[2])))
                    .toList());
        }
    }

    private static Integer days(int value) {
        return value < 0 ? null : value;
    }

    /** A fictional EAN-13 in the 200–299 in-store range, with a valid check digit. */
    private String ean13() {
        String base = String.format("2%011d", 900_000_000L + barcodeSequence++);
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (base.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        return base + (10 - sum % 10) % 10;
    }

    private void createSuppliers() {
        for (String[] s : new String[][] {
                { "Distrimed Ouest", "wholesaler", "30", "commandes@distrimed.example" },
                { "Pharmavet Grossiste Centre", "wholesaler", "45", "achats@pharmavet.example" },
                { "Laboratoires Atlas Vét", "laboratory", "60", "ventes@atlas-vet.example" },
                { "Medivet Méditerranée", "laboratory", "30", null } }) {
            supplierIds.put(s[0], suppliers.create(new SupplierInput(s[0], s[1], null, null, s[3],
                    "Adresse fictive de démonstration", Integer.parseInt(s[2]))).id());
        }
        price("Distrimed Ouest", "amox", "0.0800", "0");
        price("Distrimed Ouest", "oxy", "0.0450", "5");
        price("Pharmavet Grossiste Centre", "enro", "0.0300", "0");
        price("Laboratoires Atlas Vét", "spray", "2.1000", "0");
    }

    private void price(String supplier, String product, String unitPrice, String discount) {
        prices.add(supplierIds.get(supplier), new PriceInput(products.get(product), new BigDecimal(unitPrice),
                new BigDecimal(discount), null));
    }

    private void purchasing() {
        at(-100, 9, 0);
        PurchaseOrderDetail po1 = order("Distrimed Ouest", Map.of("oxy", "2500", "iver", "4000", "alben", "5000",
                "melox", "960", "fluni", "2300", "tylo", "2000", "amox", "30"), "0.0400");
        at(-100, 11, 0);
        orders.validate(po1.id());
        at(-99, 9, 0);
        orders.send(po1.id());
        at(-95, 14, 0);
        receive(po1, List.of(
                lot("oxy", "OXY-2405", 420, "2500", "0.0450"), lot("iver", "IVE-2311", 12, "1000", "0.0300"),
                lot("iver", "IVE-2402", 380, "3000", "0.0300"), lot("alben", "ALB-2312", -5, "2000", "0.0120"),
                lot("alben", "ALB-2405", 600, "3000", "0.0120"), lot("melox", "MEL-2403", 25, "640", "0.0900"),
                lot("melox", "MEL-2312", 8, "320", "0.0900"), lot("fluni", "FLU-2401", 300, "1500", "0.0700"),
                lot("fluni", "FLU-2403", 200, "800", "0.0700"), lot("tylo", "TYL-2404", 50, "1200", "0.0500"),
                lot("tylo", "TYL-2406", 700, "800", "0.0500"), lot("amox", "AMX-2310", 210, "30", "0.0800")),
                false);

        at(-70, 10, 0);
        PurchaseOrderDetail cancelled = order("Medivet Méditerranée", Map.of("newcastle", "10000"), "0.0020");
        at(-69, 9, 0);
        orders.cancel(cancelled.id(), "Commande saisie en double");

        at(-60, 9, 0);
        PurchaseOrderDetail po2 = order("Pharmavet Grossiste Centre", Map.of("enro", "5000", "pipette", "360",
                "carpro", "700", "energivet", "2000", "mineral", "50000", "arabic", "2500", "newcastle", "50000",
                "iode", "5000"), "0.0300");
        orders.validate(po2.id());
        at(-59, 9, 0);
        orders.send(po2.id());
        at(-55, 15, 0);
        receive(po2, List.of(
                lot("enro", "ENR-2402", 500, "3000", "0.0300"), lot("pipette", "PIP-2403", 400, "120", "1.9000"),
                lot("pipette", "PIP-2405", 600, "240", "1.9000"), lot("carpro", "CAR-2311", -30, "200", "0.1100"),
                lot("carpro", "CAR-2404", 330, "500", "0.1100"), lot("energivet", "ENE-2404", 600, "2000", "0.0250"),
                lot("mineral", "MIN-2405", 450, "25000", "0.0040"), lot("mineral", "MIN-2406", 700, "25000", "0.0040"),
                lot("arabic", "OXI-2402", 365, "1500", "0.0150"), lot("arabic", "OXI-2405", 500, "1000", "0.0150"),
                lot("newcastle", "NDV-2404", 40, "20000", "0.0020"), lot("newcastle", "NDV-2406", 300, "30000", "0.0020"),
                lot("iode", "IOD-2403", 700, "5000", "0.0110")),
                false);

        at(-42, 9, 0);
        PurchaseOrderDetail po3 = order("Laboratoires Atlas Vét", Map.of("spray", "288", "chlor", "1500",
                "batiment", "80000", "oxy", "500", "fluni", "500", "melox", "1000", "energivet", "1000"), "0.0500");
        orders.validate(po3.id());
        orders.send(po3.id());
        at(-38, 10, 30);
        receive(po3, List.of(
                lot("spray", "SPR-2402", 520, "144", "2.1000"), lot("spray", "SPR-2406", 700, "144", "2.2000"),
                lot("chlor", "CHX-2405", 800, "1500", "0.0080"), lot("batiment", "BAT-2404", 900, "40000", "0.0015"),
                lot("batiment", "BAT-2406", 1000, "40000", "0.0015"),
                new ReceivedLine(null, products.get("oxy"), null, new BigDecimal("500"), "OXY-2406",
                        today.plusDays(410), new BigDecimal("0.0460"), false),
                lot("fluni", "FLU-2405", 28, "500", "0.0720"), lot("melox", "MEL-2406", 560, "1000", "0.0920"),
                lot("energivet", "ENE-2406", 700, "1000", "0.0260")),
                false);

        at(-20, 11, 0);
        ReceiptDetail free = receipts.confirm(new ReceiptInput(supplierIds.get("Medivet Méditerranée"), null,
                "BL-DEMO-0042", null, false, List.of(lot("rage", "RAG-2405", 300, "100", "0.9000"))),
                UUID.nameUUIDFromBytes("demo-free-receipt".getBytes()).toString());
        at(-19, 9, 15);
        receipts.cancel(free.id(), "Erreur de saisie : livraison destinée à une autre officine");

        at(-5, 9, 0);
        PurchaseOrderDetail po4 = order("Laboratoires Atlas Vét", Map.of("chlor", "9000"), "0.0080");
        orders.validate(po4.id());
        at(-4, 9, 0);
        orders.send(po4.id());

        at(-2, 16, 0);
        order("Pharmavet Grossiste Centre", Map.of("carpro", "1000", "pipette", "120"), "0.1000");
    }

    private PurchaseOrderDetail order(String supplier, Map<String, String> quantities, String defaultPrice) {
        List<RequestedLine> lines = new ArrayList<>();
        quantities.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> lines.add(new RequestedLine(
                products.get(e.getKey()), null, new BigDecimal(e.getValue()), new BigDecimal(defaultPrice)
                        .add(new BigDecimal(random.nextInt(50)).movePointLeft(4)),
                random.nextInt(4) == 0 ? new BigDecimal("5") : null, null)));
        return orders.create(new OrderInput(supplierIds.get(supplier),
                LocalDate.ofInstant(clock.instant(), BUSINESS).plusDays(7), lines));
    }

    private ReceivedLine lot(String product, String lotNumber, int expiryInDays, String quantity, String cost) {
        return new ReceivedLine(null, products.get(product), null, new BigDecimal(quantity), lotNumber,
                today.plusDays(expiryInDays), new BigDecimal(cost), true);
    }

    /** Receives the lines against their order lines (matched by product) and records the lots by number. */
    private void receive(PurchaseOrderDetail order, List<ReceivedLine> lines, boolean confirmBlocking) {
        Map<Long, Long> lineOfProduct = new HashMap<>();
        for (OrderLine l : order.lines()) {
            lineOfProduct.put(l.product().id(), l.id());
        }
        List<ReceivedLine> onOrder = lines.stream().map(l -> new ReceivedLine(lineOfProduct.get(l.productId()),
                l.productId(), l.unitId(), l.quantity(), l.lotNumber(), l.expiryDate(), l.unitCost(), l.conforming()))
                .toList();
        ReceiptDetail receipt = receipts.confirm(new ReceiptInput(null, order.id(), "BL-" + order.number().substring(3),
                null, confirmBlocking, onOrder), UUID.nameUUIDFromBytes(order.number().getBytes()).toString());
        receipt.lines().forEach(l -> lots.put(l.lotNumber(), l.lotId()));
    }

    private void stockActivity() {
        at(-30, 10, 0);
        returns.create(new ReturnInput(supplierIds.get("Distrimed Ouest"), "Flacons abîmés pendant le transport",
                List.of(new ReturnedLine(lots.get("OXY-2405"), new BigDecimal("100"), null))));

        at(-10, 8, 0);
        CountDetail count = counts.open(new OpenInput("cycle", null, List.of(products.get("melox"),
                products.get("iode")), "Inventaire tournant de démonstration"));
        at(-10, 11, 0);
        for (Line line : count.lines()) {
            BigDecimal counted = line.theoreticalQty();
            if (line.lot() != null && line.lot().lotNumber().equals("MEL-2403")) {
                counted = counted.subtract(new BigDecimal("20"));
            } else if (line.lot() != null && line.lot().lotNumber().equals("IOD-2403")) {
                counted = counted.add(new BigDecimal("50"));
            }
            counts.enter(count.id(), line.id(), counted);
        }
        counts.add(count.id(), new AddInput(null, products.get("iode"), "IOD-2311", today.plusDays(150),
                new BigDecimal("0.0110"), new BigDecimal("300")));
        at(-10, 16, 0);
        regularisation.regularize(count.id(), null);

        at(-8, 15, 0);
        adjustments.adjust(new AdjustmentInput(lots.get("IVE-2402"), "adjustment_breakage", new BigDecimal("50"),
                "Flacon cassé à la manipulation"));
        at(-6, 10, 0);
        lotActions.recall(lots.get("FLU-2401"), "Rappel du fabricant (avis de démonstration n° 12)");
        at(-3, 17, 0);
        adjustments.adjust(new AdjustmentInput(lots.get("CHX-2405"), "adjustment_internal_use", new BigDecimal("100"),
                "Désinfection de la réserve"));
    }

    /**
     * Categories, then customers through the directory (a new customer is cash only, ADR-109), then their limits and
     * own prices through their own use cases, so the audit trail shows them.
     */
    private void createCustomers() {
        int ovins = customerCategories.create(new CategoryInput("Éleveurs ovins", new BigDecimal("5.00"))).id();
        int grands = customerCategories.create(new CategoryInput("Grands élevages", new BigDecimal("8.00"))).id();
        int cabinets = customerCategories.create(new CategoryInput("Cabinets vétérinaires", new BigDecimal("10.00")))
                .id();

        CustomerDetail vetKaci = customer(new CustomerInput("Dr Démo Kaci", "veterinarian", cabinets, null,
                "Tizi Ouzou", "Azazga", "Adresse fictive de démonstration", "0100 00 01 01", null, "DEMO-NIF-0001", 30,
                null), "200000.00");
        CustomerDetail vetPlateaux = customer(new CustomerInput("Cabinet vétérinaire Démo des Hauts-Plateaux",
                "veterinarian", cabinets, null, "Sétif", "El Eulma", null, "0100 00 01 02", null, "DEMO-NIF-0002", 45,
                null), null);
        CustomerDetail belkacem = customer(new CustomerInput("Élevage Démo Belkacem", "breeder", ovins, "Ovin",
                "Djelfa", "Hassi Bahbah", null, "0100 00 01 03", null, null, 30, vetPlateaux.id()), "50000.00");
        CustomerDetail merabet = customer(new CustomerInput("Ferme Démo Aït Mérabet", "breeder", ovins, "Ovin et caprin",
                "Tizi Ouzou", "Larbaâ Nath Irathen", null, "0100 00 01 04", null, null, 15, vetKaci.id()), "30000.00");
        CustomerDetail nakhil = customer(new CustomerInput("مزرعة النخيل التجريبية", "breeder", grands, "Bovin laitier",
                "Biskra", "Tolga", null, "+213 100 00 01 05", null, "DEMO-NIF-0005", 60, null), "150000.00");
        CustomerDetail avicole = customer(new CustomerInput("Grand élevage avicole Démo", "breeder", grands, "Avicole",
                "Bordj Bou Arréridj", "Ras El Oued", null, "0100 00 01 06", null, "DEMO-NIF-0006", 30, null),
                "300000.00");
        customer(new CustomerInput("Élevage bovin Démo de la Mitidja", "breeder", null, "Bovin laitier", "Blida",
                "Boufarik", null, "0100.00.01.07", null, null, 0, vetPlateaux.id()), "20000.00");
        customer(new CustomerInput("Particulier Démo", "other", null, null, "Alger", "Kouba", null, "0100 00 01 08",
                null, null, 0, null), "0.00");
        customer(new CustomerInput("Animalerie Démo du Centre", "other", null, null, "Alger", "Bab Ezzouar", null,
                "0100 00 01 09", null, null, 15, null), "10000.00");
        CustomerDetail caprin = customer(new CustomerInput("Élevage caprin Démo (cessé)", "breeder", ovins, "Caprin",
                "Médéa", "Berrouaghia", null, "0100 00 01 10", null, null, 30, null), "0.00");
        customer(new CustomerInput("Rucher Démo des Babors", "breeder", null, "Apiculture", "Béjaïa", "Kherrata", null,
                null, null, null, 0, null), "0.00");
        customer(new CustomerInput("Élevage Démo Ouled Naïl", "breeder", ovins, "Ovin", "Djelfa", "Messaad", null,
                "0100 00 01 12", null, null, 30, vetKaci.id()), "40000.00");

        ownPrice(belkacem, "amox", "0.1300");
        ownPrice(belkacem, "iver", "0.0580");
        ownPrice(merabet, "alben", "0.0250");
        ownPrice(nakhil, "oxy", "0.0800");
        ownPrice(nakhil, "tylo", "0.0950");
        ownPrice(avicole, "enro", "0.0520");
        ownPrice(avicole, "newcastle", "0.0042");
        ownPrice(vetKaci, "melox", "0.1550");
        customers.setActive(caprin.id(), false);
    }

    /** {@code limit}: {@code null} = no limit, "0.00" = cash only (kept from creation), else the amount. */
    private CustomerDetail customer(CustomerInput input, String limit) {
        CustomerDetail created = customers.create(input);
        if (limit != null && new BigDecimal(limit).signum() == 0) {
            return created;
        }
        return credit.change(created.id(), new CreditLimitInput(true, limit, created.version()));
    }

    private void ownPrice(CustomerDetail customer, String product, String unitPrice) {
        customerPrices.set(customer.id(), products.get(product), new BigDecimal(unitPrice), null);
    }

    /** Pins the clock to {@code days} from today at hour:minute, business time. */
    private void at(int days, int hour, int minute) {
        Instant instant = today.plusDays(days).atTime(hour, minute).atZone(BUSINESS).toInstant();
        clock.pin(instant);
    }

    private static List<Spec> specs() {
        Map<String, int[]> none = Map.of();
        return List.of(
                new Spec("amox", "DM-AMX500", "Amoxival 500 mg comprimés", "ANTIBIOTIC", "TABLET", 0, "comprimé",
                        false, "9", "20", "400", "50", "0.0800", "0.1500",
                        units("Plaquette de 10", "10", "Boîte de 100", "100"), List.of("CANINE", "FELINE"), none, true),
                new Spec("oxy", "DM-OXY20", "Oxytétra-Vet 20 % injectable", "ANTIBIOTIC", "INJECTABLE", 1, "ml", true,
                        "9", "0", "6000", "0", "0.0450", "0.0900", units("Flacon 100 ml", "100", "Flacon 250 ml", "250"),
                        List.of("BOVINE", "OVINE"), Map.of("BOVINE", new int[] { 28, 7, -1 }, "OVINE",
                                new int[] { 28, 7, -1 }), true),
                new Spec("enro", "DM-ENRO10", "Enrofloxa 10 % solution buvable", "ANTIBIOTIC", "SOLUTION", 2, "ml",
                        true, "9", "0", null, "0", "0.0300", "0.0600", units("Flacon 1 L", "1000"),
                        List.of("POULTRY"), none, true),
                new Spec("iver", "DM-IVER1", "Ivermectine 1 % injectable", "ANTIPARASITIC", "INJECTABLE", 0, "ml",
                        true, "9", "500", "8000", "1000", "0.0300", "0.0650", units("Flacon 50 ml", "50",
                                "Flacon 500 ml", "500"), List.of("BOVINE", "OVINE", "CAPRINE"),
                        Map.of("BOVINE", new int[] { 49, -1, -1 }), true),
                new Spec("alben", "DM-ALB25", "Albendazole 2,5 % suspension orale", "ANTIPARASITIC", "SOLUTION", 1,
                        "ml", true, "9", "0", null, "0", "0.0120", "0.0280", units("Flacon 1 L", "1000"),
                        List.of("OVINE", "CAPRINE"), none, true),
                new Spec("pipette", "DM-PIP-M", "Pipettes antipuces chien moyen", "ANTIPARASITIC", "POUR_ON", 3,
                        "pipette", false, "19", "0", null, "0", "1.9000", "3.5000", units("Boîte de 4", "4",
                                "Présentoir de 12", "12"), List.of("CANINE"), none, true),
                new Spec("melox", "DM-MELOX", "Méloxicam 1,5 mg/ml suspension orale", "ANTI_INFLAMMATORY", "SOLUTION",
                        2, "ml", true, "9", "0", null, "0", "0.0900", "0.1800", units("Flacon 32 ml", "32",
                                "Flacon 100 ml", "100"), List.of("CANINE"), none, true),
                new Spec("fluni", "DM-FLUNI", "Flunixine 50 mg/ml injectable", "ANTI_INFLAMMATORY", "INJECTABLE", 0,
                        "ml", true, "9", "0", null, "0", "0.0700", "0.1400", units("Flacon 100 ml", "100"),
                        List.of("BOVINE", "EQUINE"), none, true),
                new Spec("carpro", "DM-CARPRO", "Carprofène 50 mg comprimés", "ANTI_INFLAMMATORY", "TABLET", 3,
                        "comprimé", false, "9", "0", null, "0", "0.1100", "0.2500", units("Plaquette de 10", "10",
                                "Boîte de 100", "100"), List.of("CANINE"), none, true),
                new Spec("energivet", "DM-ENERG", "Énergivet Sélénium & Vitamine É", "VITAMIN_SUPPLEMENT", "SOLUTION",
                        1, "ml", true, "9", "0", null, "0", "0.0250", "0.0550", units("Flacon 100 ml", "100",
                                "Bidon 1 L", "1000"), List.of("BOVINE", "OVINE"), none, true),
                new Spec("mineral", "DM-MINER",
                        "Complément minéral bovins — poudre orale à libération progressive enrichie en oligo-éléments, "
                                + "sélénium et vitamines A, D3 et E pour vaches laitières en lactation",
                        "VITAMIN_SUPPLEMENT", "POWDER", 2, "g", true, "9", "0", null, "0", "0.0040", "0.0090",
                        units("Sachet 1 kg", "1000", "Seau 5 kg", "5000"), List.of("BOVINE"), none, true),
                new Spec("arabic", "DM-OXI-AR", "مضاد الطفيليات أوكسي", "ANTIPARASITIC", "POWDER", 3, "g", true, "9",
                        "0", null, "0", "0.0150", "0.0350", units("Sachet 100 g", "100", "Sachet 500 g", "500"),
                        List.of("OVINE", "POULTRY"), none, true),
                new Spec("vaccin-ovin", "DM-VAC-ENT", "Vaccin entérotoxémie ovins", "VACCINE", "INJECTABLE", 0,
                        "dose", false, "9", "0", null, "0", "0.3000", "0.6000", units("Flacon 50 doses", "50",
                                "Flacon 100 doses", "100"), List.of("OVINE"), none, true),
                new Spec("newcastle", "DM-VAC-NDV", "Vaccin Newcastle volailles", "VACCINE", "POWDER", 3, "dose",
                        false, "9", "0", null, "0", "0.0020", "0.0050", units("Flacon 1000 doses", "1000",
                                "Flacon 5000 doses", "5000"), List.of("POULTRY"), none, true),
                new Spec("rage", "DM-VAC-RAG", "Vaccin rage chien et chat", "VACCINE", "INJECTABLE", 3, "dose", false,
                        "9", "0", null, "0", "0.9000", "2.0000", units("Boîte de 10 doses", "10"),
                        List.of("CANINE", "FELINE"), none, true),
                new Spec("chlor", "DM-CHX2", "Chlorhexidine 2 % solution désinfectante", "DISINFECTANT", "SOLUTION",
                        1, "ml", true, "19", "500", "10000", "2000", "0.0080", "0.0200", units("Flacon 500 ml", "500",
                                "Bidon 5 L", "5000"), List.of(), none, true),
                new Spec("iode", "DM-IODE10", "Povidone iodée 10 %", "DISINFECTANT", "SOLUTION", 2, "ml", true, "19",
                        "0", null, "0", "0.0110", "0.0250", units("Flacon 125 ml", "125", "Bidon 1 L", "1000"),
                        List.of(), none, true),
                new Spec("batiment", "DM-DESBAT", "Désinfectant bâtiments d'élevage", "DISINFECTANT", "SOLUTION", 0,
                        "ml", true, "19", "0", null, "0", "0.0015", "0.0040", units("Bidon 5 L", "5000",
                                "Bidon 20 L", "20000"), List.of(), none, false),
                new Spec("tylo", "DM-TYLO20", "Tylosine 20 % injectable", "ANTIBIOTIC", "INJECTABLE", 2, "ml", true,
                        "9", "0", null, "0", "0.0500", "0.1100", units("Flacon 100 ml", "100", "Flacon 250 ml", "250"),
                        List.of("BOVINE", "CAPRINE"), Map.of("BOVINE", new int[] { 21, 4, -1 }, "CAPRINE",
                                new int[] { 21, 4, -1 }), true),
                new Spec("spray", "DM-SPRAY", "Spray cicatrisant à l'aluminium", "DISINFECTANT", "POUR_ON", 0,
                        "aérosol", false, "19", "0", null, "0", "2.1000", "4.2000", units("Carton de 12", "12"),
                        List.of("BOVINE", "OVINE", "EQUINE"), none, true));
    }

    private static Map<String, String> units(String... nameFactor) {
        Map<String, String> units = new LinkedHashMap<>();
        for (int i = 0; i < nameFactor.length; i += 2) {
            units.put(nameFactor[i], nameFactor[i + 1]);
        }
        return units;
    }
}
