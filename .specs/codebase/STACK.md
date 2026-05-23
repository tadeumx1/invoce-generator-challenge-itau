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
- Database: **none** — there is no persistence layer; downstream "services" are in-process stubs that `Thread.sleep` to simulate latency.
- Authentication: **none**

## Dependencies (`pom.xml`)

- `org.springframework.boot:spring-boot-starter-web` — REST + embedded Tomcat
- `org.springframework.boot:spring-boot-starter` — core
- `org.projectlombok:lombok` — model boilerplate (getters/setters/builders)
- `org.springframework.boot:spring-boot-starter-test` (scope: test) — JUnit 5 + Mockito + Spring Test

## Testing

- Unit: JUnit Jupiter (transitively from `spring-boot-starter-test`)
- Integration: `@SpringBootTest` for context-loads smoke test
- E2E: none
- Coverage tool: JaCoCo 0.8.11

## External Services

- *None real today.* The codebase has four in-process stubs (`StockService`, `RegistrationService`, `DeliveryService`, `FinanceService`) plus one port (`DeliveryIntegrationPort`) that pretend to call external systems via `Thread.sleep`. See `INTEGRATIONS.md`.

## Development Tools

- Build wrapper: `./mvnw` (POSIX) / `mvnw.cmd` (Windows)
- Formatter: Spotless + google-java-format
- Style checks: Maven Checkstyle plugin using `config/checkstyle/checkstyle.xml`
- IDE metadata: Eclipse `.classpath` / `.project` / `.settings`, IntelliJ `.idea/`
- Spring Boot Maven plugin configured with Paketo `paketobuildpacks/builder-jammy-base:latest` for OCI image builds (not yet exercised)
