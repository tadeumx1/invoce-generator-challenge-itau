# Testing Infrastructure

## Test Frameworks

- **Unit / Integration:** JUnit Jupiter 5 (transitive via `spring-boot-starter-test` 2.6.2)
- **Mocking:** Mockito (annotation-driven: `@Mock`, `@InjectMocks`, `MockitoAnnotations.openMocks`)
- **Spring Test:** `@SpringBootTest` for context-loads smoke
- **E2E:** none
- **Coverage tool:** none configured

## Test Organization

**Location:** `src/test/java/br/com/itau/invoicegenerator/` (post-rename; previously sat in the dangling `br.com.itau.calculadoratributos` package).

**Naming:** `<ClassUnderTest>Test.java` for unit-style tests; `<App>Tests.java` for the SpringBoot smoke. Method naming uses descriptive `should<Behavior>For<Context>With<Condition>`.

**Structure:** one test class per production class. Today only 2 files exist; both ship with the challenge starter.

## Testing Patterns

### "Unit" tests (sort of)

**Approach:** Annotation-based Mockito setup; mocks declared but not actually applied (because the SUT instantiates collaborators with `new` rather than via injection).
**Location:** `src/test/java/.../InvoiceGeneratorServiceImplTest.java`.
**Pattern observed:**
- `@InjectMocks` on the service under test.
- `@Mock` on a collaborator (`ProductTaxRateCalculator`).
- `MockitoAnnotations.openMocks(this)` in `@BeforeEach`.
- The mock is never actually exercised by the SUT (the SUT does `new ProductTaxRateCalculator()` internally). So the test in practice exercises the real calculator end-to-end.

This is documented as a defect in `CONCERNS.md` C-5.

### Integration tests

**Approach:** Spring context smoke test only.
**Location:** `InvoiceGeneratorApplicationTests.java`.
**Pattern:** `@SpringBootTest` + empty `contextLoads()`. Verifies wiring, nothing else.

### E2E tests

None.

## Test Execution

**Commands:**

```bash
./mvnw test                                                   # all tests
./mvnw test -Dtest=InvoiceGeneratorServiceImplTest            # one class
./mvnw test -Dtest='InvoiceGeneratorServiceImplTest#shouldGenerateInvoiceForPersonTypeFisicaWithTotalItemsValueLessThan500'
                                                              # one method
```

**JDK requirement:** must run under JDK 11 (or earlier) until the Java 21 upgrade. Newer JDKs trip the Lombok 1.18.22 / `JCTree.qualid` incompatibility. Use:

```bash
JAVA_HOME=/Users/matheustadeu/Library/Java/JavaVirtualMachines/temurin-11.0.20/Contents/Home ./mvnw test
```

**Configuration:** all defaults. No surefire customization.

## Coverage Targets

**Current:** unmeasured.
**Goals:** none documented in repo. Plausible targets for the post-refactor state: ≥80 % on domain/use-case layer; ≥60 % on adapters. Final numbers TBD as part of F-SAFETY-NET feature.
**Enforcement:** none.

## Test Coverage Matrix

| Code Layer            | Required Test Type            | Location Pattern                                                | Run Command                                |
| --------------------- | ----------------------------- | --------------------------------------------------------------- | ------------------------------------------ |
| Domain / use cases (target post-Clean-Arch) | Unit (no Spring)             | `src/test/java/.../domain/**Test.java`, `.../application/**Test.java` | `./mvnw test`                              |
| Adapters (HTTP, integrations) (post-refactor)  | Integration                  | `src/test/java/.../adapter/**Test.java`                          | `./mvnw test`                              |
| Service layer (current)   | Unit (today: misconfigured)  | `src/test/java/.../*ServiceImplTest.java`                       | `./mvnw test`                              |
| Spring wiring         | Integration (`@SpringBootTest`) | `src/test/java/.../*ApplicationTests.java`                     | `./mvnw test`                              |
| Web layer             | none today                    | (no tests)                                                      | —                                          |
| External integrations | none today                    | (no tests)                                                      | —                                          |

## Parallelism Assessment

| Test Type     | Parallel-Safe? | Isolation Model            | Evidence                                                            |
| ------------- | -------------- | -------------------------- | ------------------------------------------------------------------- |
| Unit (today)  | **No**         | Shared JVM static state    | `ProductTaxRateCalculator.invoiceItemList` is `static` — tests in the same JVM see each other's items (`CONCERNS.md` C-1). |
| Unit (target) | Yes (after C-1 fix) | Per-test instance state    | After making the calculator stateless / per-request, no shared state remains. |
| Integration   | No (JVM-wide Spring context) | Single `@SpringBootTest` instance | One context per process; safe to parallelize across classes only with separate JVM forks. |

## Gate Check Commands

| Gate Level | When to Use                                       | Command                                                    |
| ---------- | ------------------------------------------------- | ---------------------------------------------------------- |
| Quick      | After tasks with unit tests only                  | `./mvnw test`                                              |
| Full       | After tasks involving Spring wiring or integration | `./mvnw test` *(today, all tests live in one suite)*       |
| Build      | After phase completion                            | `./mvnw clean package`                                     |

> No lint or formatter is configured in the project. Add `spotless` + `checkstyle` (or `google-java-format`) during F-UPGRADE.
