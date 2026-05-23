# Tech Stack

**Analyzed:** 2026-05-23

## Core

- Framework: Spring Boot 3.5.14
- Language: Java 21
- Runtime: JVM (HotSpot)
- Package manager: Maven (`./mvnw` wrapper committed)

## Backend

- API Style: REST (Spring `@RestController`)
- Single endpoint: `POST /api/orders/generate-invoice`
- Database: **none** — there is no persistence layer; downstream adapters are in-process stubs that `Thread.sleep` to simulate latency.
- Authentication: **none**

## Dependencies (`pom.xml`)

- `org.springframework.boot:spring-boot-starter-web` — REST + embedded Tomcat
- `org.springframework.boot:spring-boot-starter` — core
- `org.projectlombok:lombok` — model boilerplate (getters/setters/builders)
- `org.springframework.boot:spring-boot-starter-test` (scope: test) — JUnit 5 + Spring Test; Mockito artifacts are excluded because the current tests use explicit fakes instead.

## Testing

- Unit: JUnit Jupiter (transitively from `spring-boot-starter-test`)
- Integration: `@SpringBootTest` + MockMvc for context and HTTP contract tests
- E2E: none outside the JVM
- Coverage tool: JaCoCo 0.8.11

## External Services

- *None real today.* The codebase has four in-process outbound adapters (`StockIntegrationAdapter`, `InvoiceRegistrationAdapter`, `DeliveryIntegrationAdapter`, `AccountsReceivableAdapter`) plus `DeliverySchedulingClient`; they pretend to call external systems via `Thread.sleep`. There is no fire-and-forget implementation today. See `INTEGRATIONS.md`.

## Development Tools

- Build wrapper: `./mvnw` (POSIX) / `mvnw.cmd` (Windows)
- Formatter: Spotless + google-java-format
- Style checks: Maven Checkstyle plugin using `config/checkstyle/checkstyle.xml`
- IDE metadata: Eclipse `.classpath` / `.project` / `.settings`, IntelliJ `.idea/`
- Spring Boot Maven plugin configured with Paketo `paketobuildpacks/builder-jammy-base:latest` for OCI image builds (not yet exercised)
