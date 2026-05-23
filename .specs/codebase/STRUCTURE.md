# Project Structure

**Root:** `/Users/matheustadeu/geradornotafiscal`

## Directory Tree

```
.
├── .claude/              # Claude Code project settings + the tlc-spec-driven skill
│   └── skills/tlc-spec-driven/
├── .mvn/                 # Maven wrapper config
├── .specs/               # ← This skill's output (the file you're reading lives here)
│   ├── codebase/         # 7 brownfield docs
│   ├── project/          # PROJECT.md, ROADMAP.md, STATE.md
│   ├── features/         # spec.md / design.md / tasks.md per feature
│   └── quick/            # ad-hoc tasks
├── docs/                 # Human-facing documentation
│   ├── business-rules.md       # Frozen contract: tax brackets, freight, side-effects, defects
│   └── translation-changelog.md# Audit trail of the Portuguese→English rename
├── src/main/java/br/com/itau/invoicegenerator/
│   ├── InvoiceGeneratorApplication.java
│   ├── model/            # DTOs + enums (12 files)
│   ├── port/out/         # 1 outbound integration "port"
│   ├── service/          # Domain service interface + tax calculator
│   ├── service/impl/     # Orchestrator + 4 side-effect stubs
│   └── web/controller/   # 1 REST controller
├── src/main/resources/
│   ├── application.properties  # empty
│   └── paylods/                # ← misspelled; contains sample request payloads
├── src/test/java/br/com/itau/invoicegenerator/
│   ├── InvoiceGeneratorApplicationTests.java     # @SpringBootTest contextLoads smoke
│   └── InvoiceGeneratorServiceImplTest.java      # 2 cases; flaky due to static-list bug
├── CLAUDE.md             # Guidance for Claude Code; points at business-rules.md
├── HELP.md               # Spring Initializr boilerplate
├── README.md             # Challenge brief (Portuguese — intentionally not translated)
├── pom.xml
└── mvnw, mvnw.cmd
```

## Module Organization

### model/

**Purpose:** Plain data carriers for HTTP I/O and internal aggregation.
**Location:** `src/main/java/br/com/itau/invoicegenerator/model/`
**Key files:** `Order.java`, `Invoice.java`, `Recipient.java`, `Address.java`, `Item.java`, `InvoiceItem.java`, `Document.java`, and 5 enums (`PersonType`, `CompanyTaxRegime`, `Region`, `AddressPurpose`, `DocumentType`).

### service/ and service/impl/

**Purpose:** The application's only meaningful business logic — the tax calculation and side-effect orchestration.
**Location:** `src/main/java/br/com/itau/invoicegenerator/service/`
**Key files:** `InvoiceGeneratorService.java` (interface), `InvoiceGeneratorServiceImpl.java` (the giant if/else orchestrator), `ProductTaxRateCalculator.java` (the static-list calculator). Stubs in `service/impl/`: `StockService`, `RegistrationService`, `DeliveryService`, `FinanceService`.

### port/out/

**Purpose:** Half-step toward hexagonal architecture — today only `DeliveryIntegrationPort` lives here, and it's a concrete class, not an interface.
**Location:** `src/main/java/br/com/itau/invoicegenerator/port/out/`

### web/controller/

**Purpose:** Single HTTP entry.
**Location:** `src/main/java/br/com/itau/invoicegenerator/web/controller/`
**Key file:** `InvoiceController.java` — `POST /api/orders/generate-invoice`.

### .specs/ (this folder)

**Purpose:** Spec-driven artifacts produced by the `tlc-spec-driven` skill. Brownfield mapping + project vision + roadmap + per-feature specs.

### docs/

**Purpose:** Human-facing documentation rooted in code state.
**Key files:** `business-rules.md` (the contract), `translation-changelog.md` (rename audit).

## Where Things Live

**Invoice generation (the only feature):**

- HTTP: `web/controller/InvoiceController.java`
- Business logic: `service/impl/InvoiceGeneratorServiceImpl.java`
- Per-item math: `service/ProductTaxRateCalculator.java`
- Side effects (stubs): `service/impl/{Stock,Registration,Delivery,Finance}Service.java`
- Outbound integration: `port/out/DeliveryIntegrationPort.java`
- DTOs / contract: `model/*.java`
- Sample payloads: `src/main/resources/paylods/teste-pf.json`, `teste-pj-simples.json`

## Special Directories

**`src/main/resources/paylods/`**
**Purpose:** Sample request bodies for manual `curl` testing.
**Examples:** `teste-pf.json` (FISICA individual), `teste-pj-simples.json` (JURIDICA / SIMPLES_NACIONAL).
**Note:** directory name is misspelled (should be `payloads/`). Left as-is to avoid scope creep — listed in `CONCERNS.md` C-7.
