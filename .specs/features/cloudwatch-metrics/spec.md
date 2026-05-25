# F-CLOUDWATCH-METRICS — Micrometer CloudWatch Registry (AWS Production Path)

**Status:** Planned (2026-05-25) — not implemented.
**Milestone:** M7 — AWS observability backend wiring.
**Depends on:** F-OBSERVABILITY (complete), F-AWS (complete, proposal-grade).

## Problem Statement

F-OBSERVABILITY (M3) shipped four SLIs computed from raw Micrometer signals scraped
locally via `/actuator/prometheus`. F-AWS (M3) described — but did **not** wire — a
production path that publishes the same signals to Amazon CloudWatch under namespace
`InvoiceGenerator`, with metric math mirroring the Prometheus queries.

Today the AWS-side claim is documentation-only. The Spring Boot app has zero
`MeterRegistry` capable of pushing to CloudWatch; deploying the existing image to the
F-AWS Terraform topology would yield zero CloudWatch metrics, zero SLI widgets, zero
alarms. The dashboard and alarm modules under `infra/terraform/modules/observability/`
would render empty.

This feature closes the gap. It adds the `io.micrometer:micrometer-registry-cloudwatch2`
library, wires it as a profile-scoped `MeterRegistry` bean (active when Spring profile
`aws` is on), and lets the existing instrumentation feed CloudWatch with **no
changes** to the four SLI source meters, no changes to the cardinality budget, and no
changes to local development (Prometheus stays the default).

## Background — What Already Exists

- `pom.xml` already depends on `io.micrometer:micrometer-registry-prometheus`.
- `application.properties` already exposes:
  - `management.endpoints.web.exposure.include=health,info,prometheus,metrics`
  - `management.metrics.tags.application=invoice-generator`
  - `management.metrics.distribution.percentiles-histogram.http.server.requests=true`
  - `management.metrics.distribution.slo.http.server.requests=300ms,800ms,2s`
- `RateLimiterMeterFilter` (in `adapter/security/ratelimit/`) already enforces the
  AD-020 cardinality budget by denying high-cardinality `name` tag values.
- `CardinalityGuardTest` already asserts that `correlationId`, `invoiceId`, `orderId`,
  `traceId`, `spanId` are never used as metric tags.
- `infra/terraform/modules/observability/` already provisions the CloudWatch dashboard
  + 4 alarms referencing namespace `InvoiceGenerator`.
- `infra/terraform/modules/ecs/` already grants the ECS task role
  `cloudwatch:PutMetricData` (proposal-grade — verified clean by `terraform validate`).
- AD-018 in `docs/aws-architecture.md` records the dual-backend split: Prometheus local,
  CloudWatch AWS.

All of this means F-CLOUDWATCH-METRICS is a **wiring** feature, not a
re-instrumentation feature.

## Goals

- [ ] **REQ-1** — `pom.xml` adds `io.micrometer:micrometer-registry-cloudwatch2`
      (version managed by Spring Boot 3.5.14 BOM where possible; pinned otherwise).
- [ ] **REQ-2** — A `CloudWatchMetricsConfig` `@Configuration` class under
      `adapter/observability/` declares a `CloudWatchMeterRegistry` `@Bean` activated
      by `@Profile("aws")` (or by an `app.metrics.cloudwatch.enabled=true` property —
      pick one in design.md). No CloudWatch bean is created in `local`, `test`, or
      default profiles.
- [ ] **REQ-3** — Namespace defaults to `InvoiceGenerator`, configurable via
      `management.cloudwatch.metrics.export.namespace`, matching the namespace already
      referenced by `infra/terraform/modules/observability/`.
- [ ] **REQ-4** — Step interval (publish cadence) is configurable; default 1 minute
      (`management.cloudwatch.metrics.export.step=1m`) to match CloudWatch's standard
      resolution and minimise `PutMetricData` cost.
- [ ] **REQ-5** — AWS credentials resolve via the
      `DefaultCredentialsProvider` chain (env → SSO → ECS container credentials → EC2
      IMDS), so the ECS task role provisioned by `modules/ecs` is used in production
      with no explicit access-key configuration in code or properties.
- [ ] **REQ-6** — AWS region resolves via the `DefaultAwsRegionProviderChain` (env →
      profile → IMDS), so the registry binds to whatever region ECS runs in without
      hard-coding `sa-east-1`.
- [ ] **REQ-7** — The four SLI source meters (`http.server.requests`,
      `invoice.dispatch`, `invoice.sideeffect.duration`, `invoice.generated`,
      `invoice.rejected`) all reach CloudWatch under namespace `InvoiceGenerator` with
      the same tag set that goes to Prometheus locally. **No instrumentation code
      changes.**
- [ ] **REQ-8** — `RateLimiterMeterFilter` (the AD-020 cardinality guard) still
      applies in the `aws` profile — per-IP synthetic rate-limiter instances stay out
      of CloudWatch the same way they stay out of Prometheus. The guard registers on
      the composite `MeterRegistry`, not the Prometheus one specifically.
- [ ] **REQ-9** — `CardinalityGuardTest` continues to pass under the `aws` profile
      (or an equivalent test verifies the guard still applies when the CloudWatch
      registry is the only one present).
- [ ] **REQ-10** — A new lightweight integration test (`@ActiveProfiles("aws")` +
      property override pointing at a stub endpoint, or a unit test asserting bean
      presence + namespace property) proves the wiring boots without trying to
      authenticate against real AWS.
- [ ] **REQ-11** — Local Prometheus path is unaffected: `./mvnw test` and
      `./mvnw verify` stay green; `docker compose up` still serves
      `/actuator/prometheus`.
- [ ] **REQ-12** — `docs/observability.md` (Backend split table + F-AWS reuse path
      section) updates from "planned" to "wired via `micrometer-registry-cloudwatch2`,
      activate with `SPRING_PROFILES_ACTIVE=aws`".
- [ ] **REQ-13** — `docs/aws-architecture.md` (services table row + ADR section)
      updates to reference the concrete library + profile activation and links back to
      the spec.
- [ ] **REQ-14** — A new AD entry in `STATE.md` records the four design decisions
      ratified at implementation time: library choice, profile-vs-property activation,
      credentials/region chain, and dimension-translation behaviour (Micrometer tags
      → CloudWatch dimensions — see [Cardinality rules](#cardinality-rules) below).

## Non-Goals / Out of Scope

| Out | Reason |
| --- | --- |
| Pushing OpenTelemetry traces to X-Ray from the app | Already in scope under F-AWS via ADOT sidecar — traces go OTLP → ADOT → X-Ray, not via Micrometer. |
| Provisioning a real AWS account / `terraform apply` | F-AWS stays proposal-grade. This feature ships the *app-side* wiring; the IaC path remains validate-clean only. |
| Migrating local from Prometheus to CloudWatch | Local stays Prometheus + Jaeger per AD-018. Two backends, one instrumentation. |
| Adding new SLIs or new meters | The four SLIs are frozen. This feature publishes existing signals to a new backend, nothing else. |
| Replacing `RateLimiterMeterFilter` | The AD-020 cardinality guard logic is reused as-is; it's already MeterRegistry-agnostic. |
| Custom dimensions per environment (`env=prod`, `env=staging`) | Could be added later via `management.metrics.tags.env=...`. Out of scope for this iteration. |
| Pricing optimisation beyond default step interval | Default `step=1m` is the cost-aware choice. High-resolution metrics (1s) explicitly rejected. |

## Cardinality Rules

CloudWatch charges per **metric** (each unique combination of namespace + metric name
+ dimensions). The AD-020 budget must hold in CloudWatch the same way it holds in
Prometheus:

| Tag/Dimension | Local Prometheus | AWS CloudWatch | Notes |
| --- | --- | --- | --- |
| `application=invoice-generator` | yes | yes | static — safe |
| `uri=/api/orders/generate-invoice` | yes | yes | enumerable, ~5 paths |
| `status=200/4xx/5xx` | yes | yes | enumerable |
| `topic=invoice.*` | yes | yes | 4 values |
| `outcome=success/failure` | yes | yes | 2 values |
| `tax_regime`, `region`, `person_type`, `large_order` | yes | yes | bounded by enum |
| `correlationId`, `invoiceId`, `orderId`, `traceId`, `spanId` | **never** | **never** | enforced by `CardinalityGuardTest`; same code, both backends |
| Per-IP rate-limiter `name="auth-login:1.2.3.4"` synthetic | **denied** by `RateLimiterMeterFilter` | **denied** by same filter | the filter is registry-agnostic; AD-020 holds |

Micrometer translates tags → CloudWatch dimensions automatically. Per CloudWatch
limits, max 30 dimensions per metric — current ceiling is well under that. Document
this in design.md.

## Acceptance Criteria

1. `./mvnw verify` is green with no profile flag (local path).
2. `./mvnw verify -Dspring-boot.run.profiles=aws` boots far enough to wire the
   `CloudWatchMeterRegistry` bean without contacting real AWS (test config stubs the
   `CloudWatchAsyncClient` or uses a no-op endpoint). The bean is present; the
   namespace property is `InvoiceGenerator`; the cardinality guard is registered.
3. `RateLimiterMeterFilter` is invoked against both the Prometheus and CloudWatch
   registries (or against the composite that contains both, depending on the
   bean-graph design).
4. `CardinalityGuardTest` (or its CloudWatch counterpart) fails the build if a
   forbidden tag appears on a meter under either backend.
5. `docs/observability.md` and `docs/aws-architecture.md` cross-reference each other
   and the spec; the "planned" hedge in observability.md §F-AWS-reuse-path is gone.
6. `infra/terraform/modules/observability/` requires no changes — the namespace
   `InvoiceGenerator` and the `cloudwatch:PutMetricData` IAM policy were already
   correct.
7. `STATE.md` carries a new `AD-CWM-*` entry block at implementation time.

## Open Questions (resolve during design.md)

- **Q1** — Activation seam: `@Profile("aws")` vs `@ConditionalOnProperty`. The
  F-DEFECTS-PERFORMANCE Kafka config uses `app.messaging.kafka.enabled` (property);
  F-AWS docs assume profile-based config. Pick one and document in AD-CWM-2.
- **Q2** — Step interval default: 1 minute (CloudWatch standard) vs 5 minutes (cost
  cutter). Recommend 1 minute to match CloudWatch alarm period; document in design.md.
- **Q3** — Should we drop the Prometheus registry when `aws` profile is active, or
  keep both registries (Prometheus for `/actuator/prometheus` + CloudWatch push)?
  **Recommended:** keep both. ECS task can still expose `/actuator/prometheus` for
  in-cluster scrape if a future ADOT collector wants Prom-format pull. Zero cost
  inside the JVM. Document in AD-CWM-3.
- **Q4** — Test strategy: full `@SpringBootTest(profiles="aws")` vs slice. Likely
  slice — `@SpringBootTest(classes = CloudWatchMetricsConfig.class, properties = ...)`.
  Decide in design.md.
