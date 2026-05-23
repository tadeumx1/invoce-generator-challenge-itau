# Tech Stack

**Analyzed:** 2026-05-22

## Core

- Framework: Spring Boot 2.6.2 *(target: a recent 3.x — README requirement)*
- Language: Java 11 *(target: Java 21 — README requirement)*
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

> **Toolchain hazard:** Lombok 1.18.22 (transitive via Spring Boot 2.6.2) is incompatible with JDK 16+. The build only succeeds under JDK 11. Resolves with the Java 21 / Spring Boot 3.x upgrade.

## Testing

- Unit: JUnit Jupiter (transitively from `spring-boot-starter-test`)
- Integration: `@SpringBootTest` for context-loads smoke test
- E2E: none
- Coverage tool: none configured

## External Services

- *None real today.* The codebase has four in-process stubs (`StockService`, `RegistrationService`, `DeliveryService`, `FinanceService`) plus one port (`DeliveryIntegrationPort`) that pretend to call external systems via `Thread.sleep`. See `INTEGRATIONS.md`.

## Development Tools

- Build wrapper: `./mvnw` (POSIX) / `mvnw.cmd` (Windows)
- IDE metadata: Eclipse `.classpath` / `.project` / `.settings`, IntelliJ `.idea/`
- Spring Boot Maven plugin configured with Paketo `paketobuildpacks/builder-jammy-base:latest` for OCI image builds (not yet exercised)
