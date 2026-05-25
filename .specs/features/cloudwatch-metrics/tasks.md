# F-CLOUDWATCH-METRICS — Tasks

**Status:** Planned (2026-05-25). No code has been written.
**Granularity:** 6 vertical-slice tasks (per user preference for ~5–7 over fine-grained 12–16).
**Gate per task:** `./mvnw verify` green unless noted.
**Spec:** [spec.md](spec.md) · **Design:** [design.md](design.md)

---

## T1 — Add `micrometer-registry-cloudwatch2` dependency

- **What:** Add `io.micrometer:micrometer-registry-cloudwatch2` to `pom.xml`.
  Confirm the Spring Boot 3.5.14 BOM manages it; pin to a `<micrometer.version>`
  property only if the BOM does not.
- **Where:** `pom.xml` (`<dependencies>`).
- **Depends on:** none.
- **Reuses:** existing Spring Boot dependency-management + the
  `<resilience4j.version>` property idiom already in `pom.xml`.
- **Done when:**
  - `./mvnw dependency:tree -Dincludes=io.micrometer` lists `cloudwatch2`.
  - `./mvnw dependency:tree -Dincludes=software.amazon.awssdk` lists `cloudwatch`,
    `auth`, `regions`, `aws-core` from AWS SDK v2 (transitives).
  - `./mvnw verify` is green with **no** new beans declared yet (the dep is present
    but inert — no `@Configuration` activates the registry).
- **Tests:** none new.
- **Gate:** `./mvnw verify`.
- **Commit:** `feat(cloudwatch): add micrometer-registry-cloudwatch2 dependency (T1)`
- **Verifies:** REQ-1.

---

## T2 — Wire `CloudWatchMeterRegistry` bean under profile `aws`

- **What:**
  1. Create `adapter/observability/CloudWatchProperties.java`
     (`@ConfigurationProperties("management.cloudwatch.metrics.export")`) with fields
     `enabled`, `namespace`, `step`, `batchSize`.
  2. Create `adapter/observability/CloudWatchMetricsConfig.java`
     (`@Configuration @Profile("aws")`) declaring:
     - `CloudWatchAsyncClient` bean using `DefaultCredentialsProvider` +
       `DefaultAwsRegionProviderChain` (no explicit region/credentials).
     - `CloudWatchConfig` bean reading from `CloudWatchProperties`.
     - `CloudWatchMeterRegistry` bean wiring config + client + `Clock.SYSTEM`.
  3. Create `src/main/resources/application-aws.properties` with the four
     `management.cloudwatch.metrics.export.*` defaults from design.md.
- **Where:** `src/main/java/.../adapter/observability/`, `src/main/resources/`.
- **Depends on:** T1.
- **Reuses:** existing `ObservabilityConfig` package layout; Spring Boot
  auto-configuration of `MeterRegistry` composite (no manual composite construction
  required).
- **Done when:**
  - `./mvnw spring-boot:run -Dspring-boot.run.profiles=aws` boots far enough to fail
    *only* at credential lookup time if no AWS env is present — the bean is wired,
    the namespace property is read.
  - Default profile boot path is unchanged.
- **Tests:**
  - `CloudWatchMetricsConfigTest`:
    `@SpringBootTest(classes = {CloudWatchMetricsConfig.class,
    CloudWatchProperties.class}, properties = { "spring.profiles.active=aws",
    "management.cloudwatch.metrics.export.namespace=InvoiceGenerator" })` +
    `@TestConfiguration` stubbing `CloudWatchAsyncClient`.
    Asserts: bean present, `CloudWatchConfig.namespace()` returns `InvoiceGenerator`,
    `CloudWatchConfig.step()` returns `Duration.ofMinutes(1)`.
- **Gate:** `./mvnw verify`.
- **Commit:** `feat(cloudwatch): profile-scoped MeterRegistry bean + properties (T2)`
- **Verifies:** REQ-2, REQ-3, REQ-4, REQ-10.

---

## T3 — Credentials/region chain + IAM cross-check

- **What:**
  - Confirm `CloudWatchAsyncClient` uses `DefaultCredentialsProvider` and
    `DefaultAwsRegionProviderChain` (verify via test assertion or code inspection —
    no explicit `.credentialsProvider(...)` / `.region(...)` calls in
    `CloudWatchMetricsConfig`).
  - Read `infra/terraform/modules/ecs/iam.tf` (or equivalent) and verify the task
    role grants `cloudwatch:PutMetricData` on `Resource: *` (CloudWatch
    `PutMetricData` does not support resource-level permissions).
  - If the grant is missing or under-scoped, add it (Terraform-side only; still
    proposal-grade — `terraform fmt + init -backend=false + validate` stays the
    gate).
- **Where:** `adapter/observability/CloudWatchMetricsConfig.java`,
  `infra/terraform/modules/ecs/iam.tf`.
- **Depends on:** T2.
- **Reuses:** F-AWS IAM role module; no new role created.
- **Done when:**
  - Code inspection / test confirms no hard-coded region or access key anywhere.
  - `terraform fmt -recursive -check && terraform init -backend=false && terraform
    validate` green from `infra/terraform/`.
- **Tests:**
  - Extend `CloudWatchMetricsConfigTest` with an assertion that no
    `AwsBasicCredentials` / `StaticCredentialsProvider` import appears in the config
    (grep-style sanity, or simply omit those imports from the file).
- **Gate:** `./mvnw verify` + Terraform validate.
- **Commit:** `feat(cloudwatch): credentials + region via default chains, IAM cross-check (T3)`
- **Verifies:** REQ-5, REQ-6.

---

## T4 — Cardinality guard + CloudWatch registry coverage

- **What:**
  - Register `RateLimiterMeterFilter` on the composite `MeterRegistry` (verify the
    existing bean wiring already covers both registries — it does, because Spring
    Boot wires `MeterFilter` beans onto the composite, not onto a specific child).
  - Add `CloudWatchCardinalityGuardTest` mirroring the existing
    `CardinalityGuardTest` but instantiating the CloudWatch registry instead of
    Prometheus (or both, if a composite test is cleaner). Asserts that
    `correlationId`, `invoiceId`, `orderId`, `traceId`, `spanId` never appear as
    tags.
  - Add a unit assertion that
    `resilience4j.ratelimiter.available.permissions{name="auth-login:127.0.0.1"}` is
    *not* published to the CloudWatch registry (the AD-020 budget holds across
    backends).
- **Where:** `src/test/java/.../adapter/observability/` (new test class),
  `adapter/security/ratelimit/RateLimiterMeterFilter.java` (no change expected —
  confirm).
- **Depends on:** T2.
- **Reuses:** the existing `CardinalityGuardTest` pattern + Micrometer's
  `MeterRegistry.find(...)` API.
- **Done when:**
  - New test fails the build when a forbidden tag is added (verify by temporarily
    introducing one, then reverting).
  - `RateLimiterMeterFilter` continues to deny `name="auth-login:<ip>"` style
    meters under the `aws` profile.
- **Tests:** `CloudWatchCardinalityGuardTest` (new, ~2 assertions).
- **Gate:** `./mvnw verify`.
- **Commit:** `test(cloudwatch): cardinality guard coverage for CloudWatch registry (T4)`
- **Verifies:** REQ-7, REQ-8, REQ-9, REQ-11.

---

## T5 — Documentation updates (cross-doc consistency)

- **What:** Update every doc that referenced the planned CloudWatch wiring so it
  references the now-real wiring + the spec/design. Single commit so reviewers see
  the doc surface as a coherent diff.
  - **`docs/observability.md`** — "Observability Backend Decision" table row for
    AWS-Metrics + "F-AWS reuse path" section: replace "planned" / "translates to
    metric math with no code change" with "wired via `micrometer-registry-cloudwatch2`,
    profile `aws`; see `.specs/features/cloudwatch-metrics/`".
  - **`docs/aws-architecture.md`** — services-table row for "CloudWatch Metrics"
    gets a note "wired via Micrometer registry"; add `ADR-036`
    (next free number) cross-referencing `AD-CWM-1..4`; bump the cost table line
    for CloudWatch metrics from ~US$ 5 → ~US$ 15 with reasoning from design.md.
  - **`.specs/codebase/STACK.md`** — add `io.micrometer:micrometer-registry-cloudwatch2`
    to Runtime dependencies with "active only under profile `aws`".
  - **`.specs/codebase/INTEGRATIONS.md`** — new row for CloudWatch metrics
    (egress, async push, `monitoring.<region>.amazonaws.com`).
  - **`.specs/codebase/ARCHITECTURE.md`** — Companion Artifacts section gets a
    bullet for the new profile-scoped config + a one-line note in the layer-rules
    section ("`adapter/observability/CloudWatchMetricsConfig` is profile-gated and
    contributes no beans in local/test profiles").
  - **`CLAUDE.md`** — short note under Architecture/Observability that the AWS
    profile activates the CloudWatch registry; link to the spec.
- **Where:** `docs/`, `.specs/codebase/`, `CLAUDE.md`.
- **Depends on:** T2, T3, T4 (so docs describe what's actually true).
- **Reuses:** the existing doc structure; do not introduce new sections beyond the
  ones listed.
- **Done when:** every doc cross-references stay consistent; `grep -R "CloudWatch"
  docs/ .specs/` shows no orphan "planned" / "not wired" hedges.
- **Tests:** none.
- **Gate:** `./mvnw verify` (unchanged — docs only).
- **Commit:** `docs(cloudwatch): cross-doc consistency for F-CLOUDWATCH-METRICS (T5)`
- **Verifies:** REQ-12, REQ-13.

---

## T6 — STATE.md AD entries + ROADMAP closure

- **What:**
  - **`STATE.md`** — append a new `AD-CWM-1..4` block covering the four design
    decisions (library choice, profile activation, credentials/region chain,
    dimension translation behaviour); bump Current Work line to "M7 complete
    (F-CLOUDWATCH-METRICS)".
  - **`ROADMAP.md`** — flip F-CLOUDWATCH-METRICS from "planned" to "COMPLETE
    (YYYY-MM-DD)" with the closing notes referencing AD-CWM-1..4 and the test count
    delta.
  - Optional: add a short Postman regression line if a future iteration adds a
    `/actuator/metrics/{name}` probe to validate the CloudWatch meter presence.
- **Where:** `.specs/project/STATE.md`, `.specs/project/ROADMAP.md`.
- **Depends on:** T1–T5.
- **Reuses:** the existing AD block format + ROADMAP feature row format from
  previous closures (F-RATELIMIT, F-BULKHEAD, F-API-DOCS).
- **Done when:** STATE.md "Current Work" reflects M7 closed; ROADMAP F-CLOUDWATCH-METRICS
  row reads COMPLETE.
- **Tests:** none.
- **Gate:** `./mvnw verify` (unchanged).
- **Commit:** `docs(cloudwatch): close F-CLOUDWATCH-METRICS — STATE + ROADMAP (T6)`
- **Verifies:** REQ-14.

---

## Traceability Matrix

| REQ | Tasks |
| --- | --- |
| REQ-1  | T1 |
| REQ-2  | T2 |
| REQ-3  | T2 |
| REQ-4  | T2 |
| REQ-5  | T3 |
| REQ-6  | T3 |
| REQ-7  | T2, T4 |
| REQ-8  | T4 |
| REQ-9  | T4 |
| REQ-10 | T2 |
| REQ-11 | T1, T2, T4 |
| REQ-12 | T5 |
| REQ-13 | T5 |
| REQ-14 | T6 |

## Execution Order

```
T1 ──► T2 ──► T3 ──► T4 ──► T5 ──► T6
```

No `[P]` parallelism — each task layers on the previous one. Single-developer happy
path is sequential.

## Out-of-Scope Reminders (do not creep)

- No new SLIs, no new meters.
- No ADOT-collector-based metrics export (keep Micrometer-direct).
- No real `terraform apply` against an AWS account (proposal-grade).
- No removal of the Prometheus registry — both registries coexist under profile `aws`.
- No change to `RateLimiterMeterFilter` internals (it's already registry-agnostic).
