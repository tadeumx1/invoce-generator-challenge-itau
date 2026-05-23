# F-UPGRADE Tasks

**Spec:** `.specs/features/upgrade/spec.md`
**Design:** skipped as a separate file — this is a platform/tooling upgrade with standard Maven/Spring Boot changes. Key decisions are captured inline and in `.specs/project/STATE.md` AD-007 and AD-008.
**Status:** Done (2026-05-23)

---

## Key Design Decisions (Rolled In From Skipped Design Phase)

1. **Upgrade to Spring Boot 3.5.14, not Boot 4.x.** The requested scope is Spring Boot 3.x. Version 3.5.14 was selected as the current 3.x parent available at implementation time.
2. **Use Java 21 as the Maven release target.** The challenge asks for Java 21, and the active shell runs JDK 21.
3. **Let Spring Boot manage Lombok.** Removing old inherited dependency constraints avoids the JDK-internal Lombok failure seen on modern JDKs.
4. **Do not add fake `jakarta.*` churn.** The source scan found no `javax.*` imports, so there was nothing to migrate.
5. **Make `verify` the quality gate.** Tests, packaging, Spotless, Checkstyle, and JaCoCo all run from one command.
6. **Keep style gates low-noise.** Spotless handles formatting mechanically; Checkstyle only enforces import hygiene.
7. **Keep slow tests opt-in.** The default suite remains fast; `./mvnw test -Pslow` remains the explicit slow-characterization command.

---

## Execution Plan

### Phase 1: Baseline Discovery

```
start -> T1 -> T2
```

Confirm the target Spring Boot 3.x line and inspect source compatibility before changing build files.

### Phase 2: Maven Upgrade

```
T2 -> T3 -> T4 -> T5
```

Change the parent/JDK, clean dependency/plugin management, and verify compilation/tests.

### Phase 3: Build Quality Gates

```
T5 -> T6 -> T7 -> T8
```

Add formatting/import checks, run formatter, then make `verify` the gate.

### Phase 4: Documentation and State

```
T8 -> T9
```

Update docs and spec-driven project memory after the build is known-good.

---

## Task Breakdown

### T1: Confirm target Spring Boot 3.x version

**What:** Identify the Spring Boot 3.x parent version to use for the upgrade and record why that version was chosen.

**Where:**
- `pom.xml`
- `.specs/project/STATE.md`
- `.specs/project/ROADMAP.md`

**Depends on:** none
**Reuses:** current Maven parent structure.
**Requirements:** UPGRADE-05, UPGRADE-20, UPGRADE-23, UPGRADE-24.

**Done when:**
- [x] Spring Boot 3.5.14 is selected for the parent.
- [x] Decision is recorded in STATE as AD-007.
- [x] ROADMAP mentions the selected version.

**Tests:** none.
**Gate:** documentation/build-file review.

**Commit:** `chore(upgrade): select Spring Boot 3.5.14 baseline (T1)`

---

### T2: Inspect source for Java/Jakarta migration needs

**What:** Scan app and test source for `javax.*` imports before upgrading. If found, migrate to `jakarta.*`; if not found, document that no migration was required.

**Where:**
- `src/main/java/**`
- `src/test/java/**`
- `.specs/features/upgrade/spec.md`
- `.specs/project/ROADMAP.md`

**Depends on:** T1
**Reuses:** source tree from F-SAFETY-NET.
**Requirements:** UPGRADE-08.

**Done when:**
- [x] `grep -R "javax\\." -n src/main/java src/test/java` returns no matches.
- [x] No unnecessary Jakarta import edits are introduced.
- [x] Upgrade docs mention no `javax.*` migration was needed.

**Tests:** static scan.
**Gate:**

```bash
grep -R "javax\\." -n src/main/java src/test/java
```

**Commit:** covered by T3/T9 upgrade docs.

---

### T3: Upgrade Maven parent and Java version

**What:** Update `pom.xml` to Spring Boot 3.5.14 and Java 21. Remove old workaround assumptions from the build.

**Where:**
- `pom.xml`

**Depends on:** T1, T2
**Reuses:** existing Maven wrapper and dependency declarations.
**Requirements:** UPGRADE-01, UPGRADE-05, UPGRADE-06.

**Done when:**
- [x] `spring-boot-starter-parent` is `3.5.14`.
- [x] `<java.version>` is `21`.
- [x] Lombok has no explicit stale version pin and is managed by Spring Boot.
- [x] Maven compiles under the default JDK 21 shell.

**Tests:** compile/test.
**Gate:** `./mvnw test-compile`

**Commit:** `chore(upgrade): move build to Java 21 and Spring Boot 3 (T3)`

---

### T4: Align Surefire slow-test profile with Boot 3 plugin management

**What:** Keep the default slow-test exclusion and `slow` profile behavior while letting the Spring Boot 3 parent manage Surefire versioning.

**Where:**
- `pom.xml`
- `.specs/project/STATE.md` AD-006 update

**Depends on:** T3
**Reuses:** F-SAFETY-NET slow test tagging.
**Requirements:** UPGRADE-18, UPGRADE-19, UPGRADE-20.

**Done when:**
- [x] Default `./mvnw test` excludes `@Tag("slow")`.
- [x] `./mvnw test -Pslow` runs the slow characterization test.
- [x] Explicit outdated Surefire version pin is not needed under Boot 3.

**Tests:** fast + slow suite commands.
**Gate:**

```bash
./mvnw test
./mvnw test -Pslow
```

**Commit:** `chore(upgrade): keep slow tests opt-in under Boot 3 (T4)`

---

### T5: Verify behavior after platform upgrade

**What:** Run the full behavior suite before adding style gates, proving the platform upgrade itself did not change business behavior.

**Where:** whole repository.

**Depends on:** T3, T4
**Reuses:** F-SAFETY-NET tests.
**Requirements:** UPGRADE-02, UPGRADE-03, UPGRADE-07, UPGRADE-09, UPGRADE-10, UPGRADE-11, UPGRADE-12.

**Done when:**
- [x] Fast suite passes.
- [x] Slow suite passes.
- [x] Spring context test passes under Boot 3.
- [x] HTTP integration tests preserve payload contract.

**Tests:** fast + slow suites.
**Gate:**

```bash
./mvnw test
./mvnw test -Pslow
```

**Commit:** covered by T3/T4 implementation commit.

---

### T6: Add Spotless with google-java-format

**What:** Add Spotless Maven plugin with google-java-format and bind `spotless:check` to `verify`. Use `spotless:apply` to format Java files mechanically.

**Where:**
- `pom.xml`
- Java source/test files touched by formatting

**Depends on:** T5
**Reuses:** Maven build plugin section.
**Requirements:** UPGRADE-14, UPGRADE-17.

**Done when:**
- [x] `pom.xml` declares Spotless plugin and format version properties.
- [x] `./mvnw spotless:apply` succeeds.
- [x] `./mvnw verify` runs `spotless:check`.

**Tests:** format gate.
**Gate:**

```bash
./mvnw spotless:apply
./mvnw verify
```

**Commit:** `chore(upgrade): add Spotless formatting gate (T6)`

---

### T7: Add Checkstyle import policy

**What:** Add Checkstyle Maven plugin and a small import-only policy to block wildcard, redundant, and unused imports.

**Where:**
- `pom.xml`
- `config/checkstyle/checkstyle.xml`

**Depends on:** T6
**Reuses:** Maven verify lifecycle.
**Requirements:** UPGRADE-15.

**Done when:**
- [x] `config/checkstyle/checkstyle.xml` exists.
- [x] Maven Checkstyle plugin is bound to `verify`.
- [x] `./mvnw verify` reports 0 Checkstyle violations.

**Tests:** style gate.
**Gate:** `./mvnw verify`

**Commit:** `chore(upgrade): add Checkstyle import policy (T7)`

---

### T8: Confirm JaCoCo and package lifecycle under `verify`

**What:** Ensure the pre-existing JaCoCo report still runs under Boot 3 and the package/repackage lifecycle completes.

**Where:**
- `pom.xml`
- `target/site/jacoco/index.html` generated output
- `target/geradornotafiscal-0.0.1-SNAPSHOT.jar` generated output

**Depends on:** T7
**Reuses:** F-SAFETY-NET JaCoCo plugin.
**Requirements:** UPGRADE-13, UPGRADE-16.

**Done when:**
- [x] `./mvnw verify` runs tests.
- [x] Spring Boot repackage completes.
- [x] JaCoCo report generation completes.
- [x] Build ends with `BUILD SUCCESS`.

**Tests:** full build.
**Gate:** `./mvnw verify`

**Commit:** covered by T6/T7 gate implementation.

---

### T9: Update documentation and spec-driven state

**What:** Update human docs and project memory so the active baseline is Java 21 + Spring Boot 3.5.14 and `./mvnw verify` is the primary gate.

**Where:**
- `README.md`
- `CLAUDE.md`
- `.specs/project/ROADMAP.md`
- `.specs/project/STATE.md`
- `.specs/codebase/STACK.md`
- `.specs/codebase/TESTING.md`
- `.specs/codebase/CONVENTIONS.md`
- `.specs/features/upgrade/spec.md`
- `.specs/features/upgrade/tasks.md`

**Depends on:** T8
**Reuses:** verification output.
**Requirements:** UPGRADE-04, UPGRADE-21, UPGRADE-22, UPGRADE-23, UPGRADE-24, UPGRADE-25.

**Done when:**
- [x] README mentions Java 21, Spring Boot 3.5.x, and `./mvnw verify`.
- [x] CLAUDE.md says default JDK 21 shell is expected.
- [x] ROADMAP marks F-UPGRADE complete.
- [x] STATE records AD-007, AD-008, and resolved Lombok blocker B-001.
- [x] Codebase docs mention Spotless/Checkstyle/JDK 21 where relevant.

**Tests:** doc scan.
**Gate:**

```bash
grep -R "JDK 11\\|Java 11\\|Spring Boot 2.6" -n README.md CLAUDE.md .specs/codebase .specs/project
```

**Commit:** `docs(upgrade): record Java 21 Spring Boot 3 baseline (T9)`

---

## Requirement Traceability

| Requirement | Tasks | Verification |
| --- | --- | --- |
| UPGRADE-01 | T3 | `pom.xml` property |
| UPGRADE-02 | T5 | `./mvnw test` |
| UPGRADE-03 | T5, T8 | `./mvnw verify` |
| UPGRADE-04 | T9 | doc scan |
| UPGRADE-05 | T1, T3 | `pom.xml` parent |
| UPGRADE-06 | T3 | compile on JDK 21 |
| UPGRADE-07 | T5 | Spring context test |
| UPGRADE-08 | T2 | `javax.*` scan |
| UPGRADE-09 | T5 | fast suite |
| UPGRADE-10 | T5 | slow suite |
| UPGRADE-11 | T5 | MockMvc tests |
| UPGRADE-12 | T5 | characterization tests |
| UPGRADE-13 | T8 | verify lifecycle |
| UPGRADE-14 | T6 | Spotless check |
| UPGRADE-15 | T7 | Checkstyle check |
| UPGRADE-16 | T8 | JaCoCo report |
| UPGRADE-17 | T6 | `spotless:apply` |
| UPGRADE-18 | T4 | default test run |
| UPGRADE-19 | T4 | `-Pslow` run |
| UPGRADE-20 | T4 | Surefire under Boot parent |
| UPGRADE-21 | T9 | README |
| UPGRADE-22 | T9 | CLAUDE.md |
| UPGRADE-23 | T1, T9 | ROADMAP |
| UPGRADE-24 | T1, T4, T9 | STATE |
| UPGRADE-25 | T9 | codebase docs |

---

## Final Verification Log

- [x] `grep -R "javax\\." -n src/main/java src/test/java` — no matches.
- [x] `./mvnw spotless:apply` — success.
- [x] `./mvnw verify` — success; fast tests green, jar packaged, Spotless clean, Checkstyle 0 violations, JaCoCo report generated.
- [x] `./mvnw test -Pslow` — success; slow characterization green.
- [x] README / CLAUDE / ROADMAP / STATE updated for Java 21 + Spring Boot 3.5.14.
