# Translation Changelog — Initial English Rename

This document records every change made when translating the codebase from Portuguese to English. It is the audit trail for the rename; it is **not** a record of business-logic changes — none were made. Behavior is preserved bit-for-bit, including the known defects listed in `docs/business-rules.md` §6.

If you want to verify any line of this log, the previous state lives in commit `0780ce3` ("Initial Commit").

---

## 1. Scope and intent

- Translate Java identifiers (packages, classes, methods, fields, variables, comments) from Portuguese to English.
- **Preserve the JSON request/response contract**: keys stay snake_case Portuguese, enum values stay Portuguese. The README requires the input payload to remain unchanged; this satisfies that constraint with `@JsonProperty` on every field.
- **Preserve all existing logic, including legacy defects.** The static accumulation list, the synchronous side-effect pipeline, the 5s sleep on >5 items, the missing `OUTROS` branch — all kept identical. Fixing them is the next phase, not this one.
- Add documentation (`docs/business-rules.md`) so the contract is captured before refactor work begins.

---

## 2. Package rename

| Before                                  | After                                    |
| --------------------------------------- | ---------------------------------------- |
| `br.com.itau.geradornotafiscal`         | `br.com.itau.invoicegenerator`           |
| `br.com.itau.calculadoratributos` (tests) | `br.com.itau.invoicegenerator` (tests) |

The Maven `<groupId>br.com.itau</groupId>` and `<artifactId>geradornotafiscal</artifactId>` are unchanged — they would affect the produced JAR name and any external build tooling, so they're out of scope for this rename.

---

## 3. Class / interface / enum renames

| Before                              | After                                |
| ----------------------------------- | ------------------------------------ |
| `GeradorNotaFiscalApplication`      | `InvoiceGeneratorApplication`        |
| `GeradorNotaFiscalService`          | `InvoiceGeneratorService`            |
| `GeradorNotaFiscalServiceImpl`      | `InvoiceGeneratorServiceImpl`        |
| `GeradorNFController`               | `InvoiceController`                  |
| `CalculadoraAliquotaProduto`        | `ProductTaxRateCalculator`           |
| `EstoqueService`                    | `StockService`                       |
| `RegistroService`                   | `RegistrationService`                |
| `EntregaService`                    | `DeliveryService`                    |
| `FinanceiroService`                 | `FinanceService`                     |
| `EntregaIntegrationPort`            | `DeliveryIntegrationPort`            |
| `Pedido`                            | `Order`                              |
| `NotaFiscal`                        | `Invoice`                            |
| `Destinatario`                      | `Recipient`                          |
| `Endereco`                          | `Address`                            |
| `Item`                              | `Item` *(unchanged — already English)* |
| `ItemNotaFiscal`                    | `InvoiceItem`                        |
| `Documento`                         | `Document`                           |
| `TipoPessoa`                        | `PersonType`                         |
| `TipoDocumento`                     | `DocumentType`                       |
| `RegimeTributacaoPJ`                | `CompanyTaxRegime`                   |
| `Regiao`                            | `Region`                             |
| `Finalidade`                        | `AddressPurpose`                     |

Test classes:

| Before                                       | After                                     |
| -------------------------------------------- | ----------------------------------------- |
| `CalculadoratributosApplicationTests`        | `InvoiceGeneratorApplicationTests`        |
| `GeradorNotaFiscalServiceImplTest`           | `InvoiceGeneratorServiceImplTest`         |

---

## 4. Enum **constants** — intentionally not translated

The enum *class names* moved to English; the *constant names* did not. They are kept in Portuguese because they match the JSON payload values that Jackson deserializes by name. Translating them would either break the input contract or require a `@JsonProperty` annotation per constant just to fake compatibility.

| Enum                | Constants kept verbatim                                                        |
| ------------------- | ------------------------------------------------------------------------------ |
| `PersonType`        | `FISICA`, `JURIDICA`                                                           |
| `DocumentType`      | `CPF`, `CNPJ`                                                                  |
| `CompanyTaxRegime`  | `SIMPLES_NACIONAL`, `LUCRO_REAL`, `LUCRO_PRESUMIDO`, `OUTROS`                  |
| `Region`            | `NORTE`, `NORDESTE`, `CENTRO_OESTE`, `SUDESTE`, `SUL`                          |
| `AddressPurpose`    | `COBRANCA_ENTREGA`, `ENTREGA`, `COBRANCA`, `OUTROS`                            |

A glossary explaining each Brazilian-domain term lives in `docs/business-rules.md` §7.

---

## 5. Method renames (selected)

| Before                                        | After                                       |
| --------------------------------------------- | ------------------------------------------- |
| `gerarNotaFiscal`                             | `generateInvoice`                           |
| `calcularAliquota`                            | `calculateTax`                              |
| `enviarNotaFiscalParaBaixaEstoque`            | `sendInvoiceForStockDeduction`              |
| `registrarNotaFiscal`                         | `registerInvoice`                           |
| `agendarEntrega`                              | `scheduleDelivery`                          |
| `criarAgendamentoEntrega`                     | `createDeliverySchedule`                    |
| `enviarNotaFiscalParaContasReceber`           | `sendInvoiceToAccountsReceivable`           |

---

## 6. Field renames (Java side)

JSON keys are unchanged. Lombok getters/setters move with the field rename automatically.

| `@JsonProperty` key  | Java field before        | Java field after        |
| -------------------- | ------------------------ | ----------------------- |
| `id_pedido`          | `idPedido`               | `orderId`               |
| `data`               | `data`                   | `date`                  |
| `valor_total_itens`  | `valorTotalItens`        | `totalItemsValue`       |
| `valor_frete`        | `valorFrete`             | `freightValue`          |
| `itens`              | `itens`                  | `items`                 |
| `destinatario`       | `destinatario`           | `recipient`             |
| `id_nota_fiscal`     | `idNotaFiscal`           | `invoiceId`             |
| `valor_tributo_item` | `valorTributoItem`       | `itemTaxValue`          |
| `id_item`            | `idItem`                 | `itemId`                |
| `descricao`          | `descricao`              | `description`           |
| `valor_unitario`     | `valorUnitario`          | `unitPrice`             |
| `quantidade`         | `quantidade`             | `quantity`              |
| `nome`               | `nome`                   | `name`                  |
| `tipo_pessoa`        | `tipoPessoa`             | `personType`            |
| `regime_tributacao`  | `regimeTributacao`       | `taxRegime`             |
| `documentos`         | `documentos`             | `documents`             |
| `enderecos`          | `enderecos`              | `addresses`             |
| `tipo` (Document)    | `tipo`                   | `type`                  |
| `numero` (Document)  | `numero`                 | `number`                |
| `numero` (Address)   | `numero`                 | `number`                |
| `logradouro`         | `logradouro`             | `street`                |
| `estado`             | `estado`                 | `state`                 |
| `complemento`        | `complemento`            | `complement`            |
| `cep`                | `cep`                    | `zipCode`               |
| `finalidade`         | `finalidade`             | `purpose`               |
| `regiao`             | `regiao`                 | `region`                |

Internal variables (not exposed via JSON) also renamed: `aliquota` → `taxRate`, `aliquotaPercentual` → `taxRate` (parameter), `valorFreteComPercentual` → `adjustedFreightValue`, etc.

---

## 7. Endpoint URL change

| Before                            | After                                |
| --------------------------------- | ------------------------------------ |
| `POST /api/pedido/gerarNotaFiscal`| `POST /api/orders/generate-invoice`  |

The README only locks the **payload**, not the URL. The endpoint was translated for consistency. If external clients pin the old path, expose `/api/pedido/gerarNotaFiscal` as an alias on `InvoiceController`.

---

## 8. File-level inventory

### Created

```
docs/
  business-rules.md
  translation-changelog.md          (this file)

src/main/java/br/com/itau/invoicegenerator/
  InvoiceGeneratorApplication.java
  model/
    Address.java
    AddressPurpose.java
    CompanyTaxRegime.java
    Document.java
    DocumentType.java
    Invoice.java
    InvoiceItem.java
    Item.java
    Order.java
    PersonType.java
    Recipient.java
    Region.java
  port/out/
    DeliveryIntegrationPort.java
  service/
    InvoiceGeneratorService.java
    ProductTaxRateCalculator.java
  service/impl/
    DeliveryService.java
    FinanceService.java
    InvoiceGeneratorServiceImpl.java
    RegistrationService.java
    StockService.java
  web/controller/
    InvoiceController.java

src/test/java/br/com/itau/invoicegenerator/
  InvoiceGeneratorApplicationTests.java
  InvoiceGeneratorServiceImplTest.java
```

### Deleted

```
src/main/java/br/com/itau/geradornotafiscal/        (entire tree)
src/test/java/br/com/itau/calculadoratributos/      (entire tree)
target/                                             (stale build output; regenerated on next build)
```

### Modified

```
CLAUDE.md       — architecture section + named bugs updated to new English identifiers;
                  now points at docs/business-rules.md for the contract.
pom.xml         — <name>calculadoratributos</name> → <name>invoicegenerator</name>
                  <description>Aplicacao para calculo de tributos</description>
                  → <description>Invoice generation service</description>
```

---

## 9. Intentionally NOT changed

These were considered and deliberately left alone:

- **`README.md`** — it's the challenge brief from the challenge author; translating it would misrepresent its provenance.
- **`HELP.md`** — Spring-Initializr boilerplate, all-English already.
- **`pom.xml` `<groupId>` / `<artifactId>`** — would change the JAR name and could break external tooling; out of scope for a rename.
- **`src/main/resources/paylods/*.json`** — sample payloads; their values are part of the payload contract. The misspelled directory name (`paylods` vs `payloads`) is left as-is to avoid touching anything outside the rename scope.
- **`.classpath` / `.project` / `.settings/`** — Eclipse project metadata, not load-bearing for the Maven build.
- **JSON keys and enum values** — bound by the README's "payload must not change" rule.
- **All known business defects** — see `docs/business-rules.md` §6. Static accumulation list, missing `OUTROS` branch, freight fallthrough to 0, sequential side-effects, `double` money math, misleading `@Mock`/`@InjectMocks` in tests. All preserved verbatim.

---

## 10. Incidental change

- **`mvnw` executable bit** — was not set on the local checkout (`./mvnw` failed with "permission denied"); ran `chmod +x mvnw` to make it executable. This is a local-filesystem permission flip, not a content change; depending on filesystem and git config it may or may not show up in `git status`.

---

## 11. Build/test status after the rename

- **Compilation** — passes under JDK 11 (the version the pom is wired for). Fails under JDK 16+ because Spring Boot 2.6.2 pulls Lombok 1.18.22, which is incompatible with newer `javac` internals. This is a pre-existing toolchain issue and is one of the things the README's "upgrade to Java 21 + recent Spring Boot" item will fix. Run with `JAVA_HOME=/Users/matheustadeu/Library/Java/JavaVirtualMachines/temurin-11.0.20/Contents/Home ./mvnw test` until the upgrade.
- **Tests** —
  - `InvoiceGeneratorApplicationTests.contextLoads` ✅
  - `InvoiceGeneratorServiceImplTest.shouldGenerateInvoiceForPersonTypeJuridicaWithLucroPresumidoAndTotalItemsValueGreaterThan5000` ✅
  - `InvoiceGeneratorServiceImplTest.shouldGenerateInvoiceForPersonTypeFisicaWithTotalItemsValueLessThan500` ⚠️ passes in isolation, fails when run after the JURIDICA test in the same JVM. Cause: `ProductTaxRateCalculator.invoiceItemList` is `static`; the second test sees the first test's item still in the list. Identical behavior to the legacy `CalculadoraAliquotaProduto.itemNotaFiscalList`. This is documented in `docs/business-rules.md` §6.1 and is the first defect to fix during the refactor.
