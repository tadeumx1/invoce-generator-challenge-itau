# Architecture

**Pattern:** Layered Spring MVC monolith with a partial hexagonal hint (`port/out/` package exists but only one port lives there).

## High-Level Structure

```
HTTP request
   │
   ▼
InvoiceController            (web/controller — Spring @RestController)
   │
   ▼
InvoiceGeneratorService      (service — interface)
InvoiceGeneratorServiceImpl  (service/impl — orchestrator; mixes all concerns)
   │
   ├──► ProductTaxRateCalculator   (service — per-item tax application; instantiated with `new`)
   │
   └──► Fire-and-return side-effect pipeline (sequential, blocking):
            new StockService().sendInvoiceForStockDeduction(invoice)
            new RegistrationService().registerInvoice(invoice)
            new DeliveryService().scheduleDelivery(invoice)
                 └──► new DeliveryIntegrationPort().createDeliverySchedule(invoice)
            new FinanceService().sendInvoiceToAccountsReceivable(invoice)
```

## Identified Patterns

### Spring MVC controller → service

**Location:** `web/controller/InvoiceController.java`
**Purpose:** HTTP entry point that delegates immediately to the service layer.
**Implementation:** `@Autowired InvoiceGeneratorService`, single `@PostMapping("/generate-invoice")`.
**Example:** `InvoiceController.java:18-27`.

### Interface + Impl split

**Location:** `service/InvoiceGeneratorService.java` (interface), `service/impl/InvoiceGeneratorServiceImpl.java`.
**Purpose:** Token gesture toward dependency-inversion; only one implementation exists today.
**Implementation:** Service interface annotated `@Service` on the impl only.
**Example:** `InvoiceGeneratorServiceImpl.java`.

### Lombok DTOs

**Location:** `model/`.
**Purpose:** Reduce boilerplate on data classes.
**Implementation:** `@Getter @Setter @Builder @AllArgsConstructor @NoArgsConstructor` on every record-like class.
**Example:** `Order.java`, `Invoice.java`, `Recipient.java`.

### Snake_case JSON ↔ camelCase Java

**Location:** All model classes.
**Purpose:** Preserve the Portuguese snake_case JSON payload while keeping English camelCase Java fields.
**Implementation:** `@JsonProperty` on every field.
**Example:** `Order.java:18-32`.

### Static-list "calculator" *(anti-pattern, kept verbatim)*

**Location:** `service/ProductTaxRateCalculator.java`.
**Purpose:** None intended — it's a bug.
**Implementation:** `private static List<InvoiceItem> invoiceItemList = new ArrayList<>();` shared across all requests.
**Example:** `ProductTaxRateCalculator.java:10`. See `CONCERNS.md` C-1.

### "Port" without inversion *(half-implemented hexagonal)*

**Location:** `port/out/DeliveryIntegrationPort.java`.
**Purpose:** Signals architectural intent toward hexagonal, but the "port" is a concrete class with no interface and is instantiated with `new` from the service that uses it. Not yet a real port.

## Data Flow

### Invoice generation (the only flow)

1. Client `POST`s an `Order` JSON.
2. Jackson deserializes via `@JsonProperty` annotations.
3. `InvoiceGeneratorServiceImpl.generateInvoice(order)`:
   a. Branch on `recipient.personType` (FISICA or JURIDICA).
   b. If JURIDICA, further branch on `taxRegime` (SIMPLES_NACIONAL / LUCRO_REAL / LUCRO_PRESUMIDO; `OUTROS` and `null` fall through → empty items list, bug).
   c. Look up `taxRate` from a hard-coded bracket table based on `totalItemsValue`.
   d. Call `ProductTaxRateCalculator.calculateTax(items, taxRate)` → returns `List<InvoiceItem>` (with bug: returns the static accumulated list, not per-request).
   e. Find the first `Address` with `purpose IN (ENTREGA, COBRANCA_ENTREGA)` and read its `region`.
   f. Multiply `freightValue` by region factor; if no matching address, `adjustedFreightValue = 0` (bug).
   g. Build `Invoice` (UUID id, `LocalDateTime.now()`, items, adjusted freight, recipient).
   h. Synchronously call the four downstream stubs in order (each sleeps; `DeliveryIntegrationPort` adds 5s if `items.size() > 5`).
   i. Return the `Invoice` to the controller, which wraps it in `200 OK`.

Full canonical rules: `docs/business-rules.md`.

## Code Organization

**Approach:** Layer-based, single bounded context.

**Structure (rooted at `br.com.itau.invoicegenerator`):**

```
.                                       — InvoiceGeneratorApplication
model/                                  — DTOs and enums (12 files)
service/                                — domain service interface + tax calculator
service/impl/                           — orchestrator + 4 side-effect stubs
port/out/                               — 1 partial outbound port
web/controller/                         — 1 REST controller
```

**Module boundaries:** none — single Maven module, single Java package tree.

## Open architectural directions

The target Clean Architecture (per the user's request, captured as F-CLEAN in `ROADMAP.md`) will introduce:

- A `domain/` layer with use cases (one per side effect + one for the invoice generation itself) and pure domain logic free of Spring.
- An `application/` layer (or "use case" interactors) coordinating the domain via ports.
- An `infrastructure/` (or `adapters/`) layer with Spring components: HTTP adapter, persistence adapter (if added), outbound integration adapters (stock/registration/delivery/finance/tax).
- Inversion of all current `new SomeService()` calls into ports + adapter implementations.

Until then, the codebase remains as documented above.
