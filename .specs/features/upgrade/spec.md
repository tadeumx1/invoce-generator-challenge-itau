# F-UPGRADE — Java 21 + Spring Boot 3.x Specification

## Problem Statement

After F-SAFETY-NET, the project had enough tests to protect behavior, but the runtime/toolchain was still the old challenge baseline: Java 11, Spring Boot 2.6.2, an old Lombok version managed by that parent, and no enforceable formatting/style gate. On the active development shell, the build required workarounds because Lombok 1.18.22 is not compatible with modern JDK internals.

The challenge explicitly asks for Java 21 and an updated Spring version. Upgrading before F-CLEAN avoids doing the architecture refactor on an obsolete platform, and the safety net gives confidence that behavior and JSON payloads remain stable while dependency management changes underneath.

F-UPGRADE's job is a platform/tooling migration only: update Java/Spring/Maven build quality gates without changing the public API, business rules, known defects, endpoint behavior, or test semantics.

## Goals

- [x] Move the project from Java 11 to Java 21.
- [x] Move from Spring Boot 2.6.2 to a Spring Boot 3.x parent.
- [x] Rely on Spring Boot dependency management for a JDK-21-compatible Lombok version.
- [x] Remove the need for local `JAVA_HOME` overrides in normal commands.
- [x] Verify whether `javax.*` imports exist and migrate to `jakarta.*` only if needed.
- [x] Keep the F-SAFETY-NET behavior suite green after the upgrade.
- [x] Keep the slow characterization test runnable through the `slow` Maven profile.
- [x] Add Maven-enforced formatting with Spotless + google-java-format.
- [x] Add a low-noise Checkstyle import policy.
- [x] Make `./mvnw verify` the main local/CI-style gate: tests, package, format check, Checkstyle, and JaCoCo report.
- [x] Update README, CLAUDE.md, ROADMAP, STATE, and codebase docs to describe the Java 21 / Spring Boot 3 baseline.

## Out of Scope

| Feature | Reason |
| --- | --- |
| Spring Boot 4.x | The requested task is Spring Boot 3.x. Boot 4 is a separate major upgrade and outside scope. |
| Fixing functional defects C-1 through C-4 | Belongs in M2 / F-DEFECTS-FUNCTIONAL. F-UPGRADE must preserve behavior. |
| Clean Architecture restructuring | Belongs in F-CLEAN, which runs after the upgrade. |
| Resilience, async processing, observability, or AWS deployment | Later roadmap milestones. |
| Enforcing JaCoCo minimum coverage thresholds | Deferred; F-UPGRADE keeps JaCoCo report generation but does not fail builds on coverage percentage. |
| Broad style rewrites beyond mechanical formatting/import policy | Avoid mixing toolchain upgrade with unrelated refactors. |
| Renaming `payloads/` to `payloads/` | Cosmetic and deferred as C-7. Existing docs and tests rely on the current path. |
| Changing JSON payload keys or enum values | Explicit challenge constraint; payload contract remains frozen. |

---

## User Stories

### P1: Java 21 runtime baseline

**User Story**: As a developer running the project locally, I want Maven commands to work on the default JDK 21 shell, so that the codebase matches the challenge requirement and no longer needs JDK 11-specific workarounds.

**Why P1**: The old Lombok/Spring Boot baseline breaks on modern JDKs. This blocks normal development and makes every later task more fragile.

**Acceptance Criteria**:

1. **WHEN** `pom.xml` is inspected **THEN** `<java.version>` SHALL be `21`.
2. **WHEN** `./mvnw test` runs on the default shell **THEN** it SHALL compile and execute without setting `JAVA_HOME`.
3. **WHEN** `./mvnw verify` runs on the default shell **THEN** it SHALL complete successfully on Java 21.
4. **WHEN** docs mention the active JDK **THEN** they SHALL say Java/JDK 21, not JDK 11.

**Independent Test**:

```bash
./mvnw test
./mvnw verify
```

---

### P1: Spring Boot 3.x parent upgrade

**User Story**: As the maintainer, I want the Maven parent on Spring Boot 3.x, so that dependency management, plugin defaults, and the framework baseline are modern enough for Java 21.

**Why P1**: Spring Boot 2.6.x is old and brings outdated managed dependencies. The project should not refactor architecture on a legacy platform.

**Acceptance Criteria**:

5. **WHEN** `pom.xml` is inspected **THEN** `spring-boot-starter-parent` SHALL be a Spring Boot 3.x version.
6. **WHEN** dependencies are resolved **THEN** Lombok SHALL be managed at a version compatible with JDK 21.
7. **WHEN** the Spring context test runs **THEN** the application SHALL start under Spring Boot 3.x.
8. **WHEN** old imports are scanned **THEN** app/test source SHALL contain no `javax.*` imports requiring migration.

**Independent Test**:

```bash
grep -R "javax\\." -n src/main/java src/test/java
./mvnw test -Dtest=InvoiceGeneratorApplicationTests
```

---

### P1: Preserve behavior and JSON contract

**User Story**: As an API consumer, I want the Java/Spring upgrade to be invisible at the HTTP contract and business-rule level.

**Why P1**: F-UPGRADE is an infrastructure change. Any tax, freight, side-effect, or JSON change belongs in a separate feature with explicit business rationale.

**Acceptance Criteria**:

9. **WHEN** the fast safety-net suite runs **THEN** all F-SAFETY-NET tests SHALL remain green.
10. **WHEN** the slow characterization profile runs **THEN** the >5-items delivery delay characterization SHALL remain green.
11. **WHEN** `InvoiceControllerIntegrationTest` runs **THEN** the endpoint SHALL still accept the two `payloads/` fixtures and return Portuguese snake_case JSON.
12. **WHEN** known defect characterizations run **THEN** C-1, C-2, C-3, and C-6 SHALL still be characterized as before.

**Independent Test**:

```bash
./mvnw test
./mvnw test -Pslow
```

---

### P1: Maven verify as the build quality gate

**User Story**: As a developer preparing larger refactors, I want one reliable command that compiles, tests, formats, checks imports, packages, and produces coverage reports.

**Why P1**: F-CLEAN and M2 need a stable pre-commit gate. Formatting and import noise should be mechanical, not reviewed by hand.

**Acceptance Criteria**:

13. **WHEN** `./mvnw verify` runs **THEN** it SHALL execute the fast test suite.
14. **WHEN** `./mvnw verify` runs **THEN** it SHALL run Spotless check.
15. **WHEN** `./mvnw verify` runs **THEN** it SHALL run Checkstyle with the project import policy.
16. **WHEN** `./mvnw verify` runs **THEN** it SHALL produce the JaCoCo report.
17. **WHEN** Java formatting is needed **THEN** `./mvnw spotless:apply` SHALL format sources with google-java-format.

**Independent Test**:

```bash
./mvnw spotless:apply
./mvnw verify
```

---

### P2: Slow test profile remains usable

**User Story**: As the developer maintaining characterization tests, I want slow tests excluded from the default suite but still runnable on demand.

**Why P2**: The delivery slow path is intentionally slow. It must remain covered without making every local test run pay the +5s cost.

**Acceptance Criteria**:

18. **WHEN** `./mvnw test` runs **THEN** tests tagged `slow` SHALL be excluded.
19. **WHEN** `./mvnw test -Pslow` runs **THEN** slow characterization tests SHALL execute.
20. **WHEN** Surefire configuration is inspected **THEN** plugin management SHALL be compatible with the Spring Boot 3 parent.

**Independent Test**:

```bash
./mvnw test
./mvnw test -Pslow
```

---

### P2: Documentation and project memory update

**User Story**: As the next contributor, I want the README, agent guidance, roadmap, and state docs to reflect the upgraded baseline, so that no one follows stale JDK 11 instructions.

**Why P2**: Spec-driven work depends on docs being current. Stale toolchain instructions cause broken local loops.

**Acceptance Criteria**:

21. **WHEN** README is read **THEN** it SHALL mention Java 21 / Spring Boot 3.5.x and `./mvnw verify`.
22. **WHEN** CLAUDE.md is read **THEN** it SHALL instruct agents to use the default JDK 21 shell.
23. **WHEN** ROADMAP is read **THEN** F-UPGRADE SHALL be marked complete with version and verification notes.
24. **WHEN** STATE is read **THEN** it SHALL record the Spring Boot 3.5.14 + Java 21 decision and resolved Lombok blocker.
25. **WHEN** codebase docs are read **THEN** STACK/TESTING/CONVENTIONS SHALL match the active Maven build.

**Independent Test**:

```bash
grep -R "JDK 11\\|Java 11\\|Spring Boot 2.6" -n README.md CLAUDE.md .specs/codebase .specs/project
```

---

## Edge Cases

- **WHEN** a future developer runs `./mvnw test` without the slow profile **THEN** the slow delivery test should not make the default suite slow.
- **WHEN** the project is run on Java 21 **THEN** Lombok annotation processing should not throw JDK-internal `NoSuchFieldError` failures.
- **WHEN** the source scan finds no `javax.*` imports **THEN** no no-op `jakarta.*` churn should be introduced.
- **WHEN** Spotless formats Java files **THEN** behavior must remain unchanged; format churn should be isolated to this feature.
- **WHEN** Checkstyle is added **THEN** the first policy should be intentionally low-noise: wildcard, redundant, and unused imports only.
- **WHEN** Maven Central eventually has newer Spring Boot 3.x versions **THEN** this spec remains historical: 3.5.14 was the selected version at implementation time.

## Requirement Traceability

| Requirement ID | Story | Status |
| --- | --- | --- |
| UPGRADE-01 | P1 — `java.version=21` | Verified |
| UPGRADE-02 | P1 — `./mvnw test` runs on default shell | Verified |
| UPGRADE-03 | P1 — `./mvnw verify` runs on Java 21 | Verified |
| UPGRADE-04 | P1 — docs no longer require JDK 11 | Verified |
| UPGRADE-05 | P1 — Spring Boot parent is 3.x | Verified |
| UPGRADE-06 | P1 — Lombok compatible through dependency management | Verified |
| UPGRADE-07 | P1 — Spring context starts on Boot 3.x | Verified |
| UPGRADE-08 | P1 — no `javax.*` migration needed | Verified |
| UPGRADE-09 | P1 — fast safety-net suite green | Verified |
| UPGRADE-10 | P1 — slow characterization green | Verified |
| UPGRADE-11 | P1 — HTTP JSON contract preserved | Verified |
| UPGRADE-12 | P1 — defect characterizations preserved | Verified |
| UPGRADE-13 | P1 — `verify` runs tests | Verified |
| UPGRADE-14 | P1 — `verify` runs Spotless check | Verified |
| UPGRADE-15 | P1 — `verify` runs Checkstyle | Verified |
| UPGRADE-16 | P1 — `verify` produces JaCoCo report | Verified |
| UPGRADE-17 | P1 — `spotless:apply` formats sources | Verified |
| UPGRADE-18 | P2 — default suite excludes slow tests | Verified |
| UPGRADE-19 | P2 — `-Pslow` runs slow tests | Verified |
| UPGRADE-20 | P2 — Surefire compatible with Boot 3 parent | Verified |
| UPGRADE-21 | P2 — README updated | Verified |
| UPGRADE-22 | P2 — CLAUDE.md updated | Verified |
| UPGRADE-23 | P2 — ROADMAP updated | Verified |
| UPGRADE-24 | P2 — STATE updated | Verified |
| UPGRADE-25 | P2 — codebase docs updated | Verified |

**ID format:** `UPGRADE-NN`

**Status values:** Pending -> In Design -> In Tasks -> Implementing -> Verified

## Success Criteria

- [x] `./mvnw verify` passes on Java 21.
- [x] `./mvnw test -Pslow` passes.
- [x] Spring Boot parent is `3.5.14`.
- [x] Java version property is `21`.
- [x] No `javax.*` imports exist in app/test source.
- [x] Spotless and Checkstyle are enforced by `verify`.
- [x] JaCoCo report still runs.
- [x] Docs describe Java 21 / Spring Boot 3.x as the active baseline.
