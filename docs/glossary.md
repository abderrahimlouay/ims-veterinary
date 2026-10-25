# Glossary — IMS Vétérinaire

> **Status:** Documentation / Architecture Phase — Implementation Not Started
> **Purpose:** ubiquitous language. Code, API and UI use the **English** term; French is the Spec/user-facing equivalent. Where a term is ambiguous, the disambiguation is stated.
> **Last updated:** 2026-10-18

| Term (EN) | Français | Definition |
|---|---|---|
| Active ingredient (DCI / INN) | DCI – Dénomination Commune Internationale | The pharmacologically active substance; used for search and substitutes (FR-PROD-004). |
| Adjustment | Ajustement | Manual stock correction with a mandatory reason (breakage, expiry, theft, internal use). |
| Advance | Acompte | Payment made to a supplier before invoicing/delivery. |
| Alert level | Niveau d'alerte | Expiry classification (expired, critical, warning, notice) from configurable thresholds. |
| Allocation (payment) | Imputation / affectation | Assignment of part of a payment to a specific invoice. |
| Audit log | Journal d'audit | Immutable record of sensitive actions with user, time and before/after values. |
| Balance (customer) | Solde client | Amount a customer owes; exact definition is OD-003. |
| Balance (lot) | Quantité en stock du lot | Maintained quantity on hand of a lot; must equal the sum of its movements. |
| Barcode | Code-barres | Scannable code (EAN, QR…) identifying a product or packaging unit. |
| Base unit | Unité de base | Smallest unit in which a product's quantities are stored (e.g. tablet, ml). |
| Blocked (lot) | Lot bloqué | Lot temporarily not sellable. |
| Cash session | Session de caisse | Period during which a cashier handles the till, from opening to counted closing. |
| Cash variance | Écart de caisse | Counted amount minus expected amount at closing. |
| Credit limit | Plafond de crédit | Maximum customer debt allowed; empty = no limit, 0 = cash only (ADR-108). |
| Credit note (customer) | Avoir | Document reducing what a customer owes, created by a return. |
| Credit note (supplier) | Avoir fournisseur | Document received from a supplier reducing a payable. |
| Credit sale | Vente à crédit | Sale paid later; requires an identified customer. |
| Customer category | Catégorie de clientèle | Segment carrying default discount/price conditions. |
| Delivery note | Bon de livraison (BL) | Document accompanying goods delivered; stock effect per OD-002. |
| Depleted (lot) | Lot épuisé | Lot with zero quantity. |
| Disposition (return) | Devenir du retour | Outcome of returned goods: restock or discard (OD-005). |
| Document number | Numéro de document | Unique human-readable number of an order, invoice, etc. (BR-DOC-001). |
| Dosage form | Forme galénique | Form of the medicine (injectable, powder, tablet…). |
| Expiry date | Date de péremption (DLC) | Last date the lot may be used/sold: sellable on this date, expired from the next day (ADR-055); `MM/YYYY` = last day of the month. |
| FEFO | Premier expiré, premier sorti | First-Expired-First-Out lot selection rule. |
| Goods receipt | Réception / bon de réception | Recording of goods received from a supplier, creating stock with lots. |
| Idempotency key | Clé d'idempotence | Client token ensuring a repeated request creates only one result. |
| Inventory (stock) | Stock | Quantities of goods held. **In this project "inventory" alone means stock**, and the activity of counting is always "inventory count" (ADR-022). |
| Inventory count | Inventaire physique | Counting stock physically and comparing with theory; full or cycle. |
| Invoice | Facture | Legal sales document; confirmed invoices are immutable. |
| Ledger (stock) | Historique / journal des mouvements | Immutable signed list of stock movements. |
| Lot | Lot | Manufacturer batch of a product, identified by lot number and expiry. |
| Lot number | Numéro de lot | Manufacturer batch identifier; mandatory at every entry. |
| Manufacturer | Fabricant / laboratoire | Producer of the medicine. |
| Margin (gross) | Marge brute | Revenue minus cost of goods sold, net of credit notes. |
| Minimum / maximum / reorder point | Stock mini / maxi / seuil de réapprovisionnement | Per-product thresholds driving alerts and proposals. |
| Movement (stock) | Mouvement de stock | One signed quantity change of one lot, with type, reason, user and time. |
| Opening stock | Stock initial | Initial load of existing stock into the system. |
| Packaging unit | Unité de conditionnement | Sale/purchase unit larger than the base unit (box, bottle) with a conversion factor. |
| Payment | Règlement / encaissement | Money received or paid; never edited, only reversed. |
| Permission | Permission / droit | Fine-grained right (e.g. `sale.create`) granted via roles. |
| Purchase order | Bon de commande | Order to a supplier; states draft, validated, sent, partially received, received, cancelled. |
| Quote | Devis | Offer to a customer with no stock or financial effect. |
| Quarantine | Quarantaine | Not a separate state: non-conforming goods enter a blocked lot (ADR-095). |
| Receipt (till) | Ticket de caisse | Short sales ticket for counter sales, printable on thermal paper. |
| Recall | Rappel de lot | Procedure blocking a lot and listing everyone who received it. |
| Reorder proposal | Proposition de commande | System-generated suggestion to buy when stock falls under the reorder point. |
| Return (customer) | Retour client | Goods brought back by a customer, linked to the original sale. |
| Return (supplier) | Retour fournisseur | Goods sent back to a supplier. |
| Role | Rôle | Named bundle of permissions assigned to users. |
| Sellable stock | Stock vendable | Quantity in lots that are active, unexpired and not blocked/recalled. |
| SKU | Référence article | Unique product code internal to the pharmacy. |
| Species | Espèce animale | Animal species used for withdrawal periods and search. |
| Statement | Relevé de compte | Chronological list of a customer's sales, credit notes and payments. |
| Supplier | Fournisseur | Laboratory or wholesaler. |
| Therapeutic category | Catégorie thérapeutique | Classification of products by therapeutic use. |
| Unit cost | Coût unitaire | Purchase cost per base unit of a lot; weighted average on each entry (ADR-098). |
| Variance (count) | Écart d'inventaire | Counted quantity minus theoretical quantity. |
| VAT | TVA | Value-added tax; computed per line, HALF_UP (ADR-093). |
| Veterinarian (customer) | Vétérinaire | A customer type or a link from a breeder to their vet. |
| Wilaya / Commune | Wilaya / Commune | Algerian administrative divisions used for customer location and statistics. |
| Withdrawal period | Délai d'attente | Time after treatment before meat, milk or eggs may enter the food chain; stored per species. |
| Write-off | Mise au rebut / casse | Removal of unusable stock through an adjustment. |

## Documentation tags

| Tag | Meaning |
|---|---|
| `[SRC]` | requirement stated in the functional Spec |
| `[SCH]` | taken from the source PostgreSQL schema |
| `[REC]` | architect recommendation, not in Spec |
| OD-nnn | OPEN DECISION awaiting the user |
| ADR-nnn | architecture decision record |
| SD-nnn | schema delta (migration register) |
| DR-nnn | documentation review issue |
| BR-xxx-nnn | business rule |
| FR/NFR-xxx-nnn | functional / non-functional requirement |
