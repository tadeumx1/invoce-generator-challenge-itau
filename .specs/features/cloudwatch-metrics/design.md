# F-CLOUDWATCH-METRICS — Design

## Goal

Publish the four F-OBSERVABILITY SLI source meters to Amazon CloudWatch under
namespace `InvoiceGenerator` when the Spring profile `aws` is active, without
modifying any existing instrumentation, without breaking the local Prometheus path,
and without exceeding the AD-020 cardinality budget.

## Library Choice — AD-CWM-1 (proposed)

**Decision:** Use `io.micrometer:micrometer-registry-cloudwatch2`.

**Alternatives considered:**

| Option | Pros | Cons |
| --- | --- | --- |
| `micrometer-registry-cloudwatch` (v1, AWS SDK v1) | Older, more examples online | AWS SDK v1 is in maintenance mode; deprecated for new code |
| **`micrometer-registry-cloudwatch2` (AWS SDK v2)** | Active, async client, BOM-managed by Micrometer | Slightly less Stack Overflow coverage |
| ADOT Collector OTLP → CloudWatch EMF | Single export pipeline shared with traces | Adds a sidecar to the local dev story; pushes metric translation out of the JVM where Micrometer config lives; mismatched with the "Prometheus local, CloudWatch AWS, one instrumentation" model already in AD-018 |
| Manual `PutMetricData` SDK calls | Full control | Re-implements what Micrometer already does; bypasses the histogram/SLO bucket machinery |

**Reason:** The user explicitly asked for `io.micrometer:micrometer-registry-cloudwatch2`.
That aligns with current Micrometer + AWS SDK v2 idioms, plays nicely with the
existing Micrometer composite registry, and keeps instrumentation code unchanged.

## Activation Seam — AD-CWM-2 (proposed)

**Decision:** Activate via Spring **profile** (`@Profile("aws")`), not via property.

**Reason:** F-AWS docs (AD-018) describe `application-aws.yml` as the profile boundary
between local-Prometheus and prod-CloudWatch. F-DEFECTS-PERFORMANCE uses a
property-based gate (`app.messaging.kafka.enabled`), but that's a *binary feature
toggle*, not a deploy-environment switch. CloudWatch wiring is fundamentally a
deploy-environment concern (it requires AWS credentials + IAM permissions that only
exist in the ECS task role). Profile-based activation is the right idiom.

**Trade-off:** Slightly less granular than a property — you can't enable CloudWatch
without "being in AWS". That's fine: the test profile and local profile genuinely
have nowhere to push to.

**Test override:** `@TestPropertySource(properties = "management.cloudwatch.metrics.export.enabled=false")`
or a `@TestConfiguration` providing a stub `CloudWatchAsyncClient` keeps tests fast.

## Bean Graph

```
MeterRegistry (composite, autoconfigured by Spring Boot)
    ├── PrometheusMeterRegistry (always present — local /actuator/prometheus)
    └── CloudWatchMeterRegistry (added when profile=aws)
                  │
                  ├── CloudWatchConfig (Spring Boot property binding)
                  │     ├── namespace = "InvoiceGenerator"
                  │     ├── step      = Duration.ofMinutes(1)
                  │     └── batchSize = 20  (CloudWatch PutMetricData hard limit)
                  │
                  └── CloudWatchAsyncClient
                        ├── DefaultCredentialsProvider  (ECS task role at runtime)
                        └── DefaultAwsRegionProviderChain
```

Both registries receive every meter and every `MeterFilter` (including
`RateLimiterMeterFilter` and `CardinalityGuardTest`'s registered filters). This is
Micrometer's default composite behaviour — no special wiring required.

## Code Layout (planned, not implemented)

```
src/main/java/br/com/itau/invoicegenerator/adapter/observability/
├── CloudWatchMetricsConfig.java       (NEW)  @Configuration @Profile("aws")
├── CloudWatchProperties.java          (NEW)  @ConfigurationProperties("management.cloudwatch.metrics.export")
└── ObservabilityConfig.java           (existing — unchanged)

src/test/java/br/com/itau/invoicegenerator/adapter/observability/
└── CloudWatchMetricsConfigTest.java   (NEW)  bean-presence + namespace + cardinality guard
```

`CloudWatchMetricsConfig` declares two beans:

```java
@Bean
CloudWatchAsyncClient cloudWatchAsyncClient() { ... }

@Bean
CloudWatchMeterRegistry cloudWatchMeterRegistry(
        CloudWatchConfig config,
        Clock clock,
        CloudWatchAsyncClient client) { ... }
```

`CloudWatchConfig` reads from `management.cloudwatch.metrics.export.*` exactly the way
the Prometheus side reads from `management.prometheus.metrics.export.*` — Spring Boot
auto-configuration provides the binding for free since Boot 3.

## application-aws.properties (planned)

A new profile-specific properties file, loaded only when `SPRING_PROFILES_ACTIVE=aws`:

```properties
# F-CLOUDWATCH-METRICS — Micrometer CloudWatch registry (M7, profile=aws only).
# Credentials and region resolve via the DefaultCredentialsProvider /
# DefaultAwsRegionProviderChain so the ECS task role configured in
# infra/terraform/modules/ecs/iam.tf is used automatically.

management.cloudwatch.metrics.export.enabled=true
management.cloudwatch.metrics.export.namespace=InvoiceGenerator
management.cloudwatch.metrics.export.step=1m
management.cloudwatch.metrics.export.batch-size=20
```

The local default profile continues to publish only to Prometheus. The test profile
explicitly disables CloudWatch.

## Failure Modes & Mitigations

| Failure | Symptom | Mitigation |
| --- | --- | --- |
| ECS task missing `cloudwatch:PutMetricData` IAM permission | Repeated `AccessDeniedException` from `CloudWatchAsyncClient` | F-AWS Terraform `modules/ecs/iam.tf` already grants this — sanity check during task review. |
| Network egress to CloudWatch blocked (private subnet, no NAT) | `IOException` / connection timeouts in the metrics-publish thread | F-AWS provisions a single NAT gateway (`modules/network`); confirm in design review. Alternative: VPC interface endpoint for `monitoring.<region>.amazonaws.com` — captured as a Future Consideration. |
| CloudWatch `PutMetricData` throttling (TPS limit) | Some publish cycles dropped | Default step=1m + batch=20 keeps TPS well under the per-account default. Micrometer logs but does not crash the app. |
| AWS SDK transient retry storm during a regional incident | CPU spike + thread saturation in the metrics-publish thread | Micrometer publish thread is a dedicated scheduled executor — won't starve request threads. Documented in design.md; no extra circuit breaker needed. |
| Profile mis-activation (running `aws` profile in local dev) | App boots, registry tries to authenticate, eventually errors out at first publish | `DefaultCredentialsProvider` fails fast with a clear `SdkClientException`; developer sees the error in logs and switches profiles. |

## Dimension Translation — AD-CWM-4 (proposed)

Micrometer maps tags → CloudWatch dimensions 1:1. Per CloudWatch:

- Max 30 dimensions per metric (current ceiling on `http.server.requests` is ~5
  dimensions: `application`, `method`, `uri`, `status`, `exception` — safe).
- Dimension values are part of the unique-metric key — same cardinality math as
  Prometheus labels. AD-020 budget holds because `RateLimiterMeterFilter` already
  denies high-cardinality `name` values *before* the meter ever reaches a registry.
- `application=invoice-generator` (set by `management.metrics.tags.application` in
  `application.properties`) is global and adds a single static dimension — useful for
  cross-namespace queries if a second service ever joins the namespace.

**No bespoke dimension config required** — Micrometer + Spring Boot do this for free.

## Cost Awareness

CloudWatch pricing at `step=1m`, ~50 unique metrics post-translation (rough estimate
from the existing meter set):

- Custom metrics: ~50 × US$ 0.30/metric/month = ~US$ 15/month.
- `PutMetricData` API calls: 50 metrics ÷ 20 batch = 3 calls per minute = ~130 k/month
  → first 1 M free, no cost at this volume.
- Total CloudWatch metrics line item: ~US$ 15/month (vs the ~US$ 5/month claimed in
  `docs/aws-architecture.md` cost table — update during T5).

## Testing Strategy

Two new test classes:

1. **`CloudWatchMetricsConfigTest`** — `@SpringBootTest(classes =
   {CloudWatchMetricsConfig.class, ObservabilityConfig.class})` with
   `@ActiveProfiles("aws")` and a `@TestConfiguration` providing a stub
   `CloudWatchAsyncClient` (returns `CompletableFuture.completedFuture(...)` for
   `putMetricData`). Asserts: bean exists, namespace property is `InvoiceGenerator`,
   step is 1 minute, the composite `MeterRegistry` has both Prometheus and CloudWatch
   instances.
2. **`CloudWatchCardinalityGuardTest`** — twin of the existing `CardinalityGuardTest`
   but run with the CloudWatch registry installed. Asserts every forbidden tag is
   absent.

`./mvnw verify` stays green without the `aws` profile (default tests run on the
default profile; the `aws` profile tests run inside their own slice).

## Documentation Updates (planned for T5)

| File | Change |
| --- | --- |
| `docs/observability.md` — "Observability Backend Decision" table | "CloudWatch (or ADOT collector → CloudWatch)" → "CloudWatch via `micrometer-registry-cloudwatch2`, profile `aws`" |
| `docs/observability.md` — F-AWS reuse path section | Replace "translates to CloudWatch metric math with no application code change" with "active when profile `aws` is on; same metric names, same dimensions, same SLI math" |
| `docs/aws-architecture.md` — services table row "CloudWatch Metrics" | Add column note: "wired via Micrometer registry, see F-CLOUDWATCH-METRICS" |
| `docs/aws-architecture.md` — ADR section | Add ADR-036 (or next free number) cross-referencing AD-CWM-1..4 |
| `.specs/codebase/STACK.md` — Dependencies > Runtime list | Add `io.micrometer:micrometer-registry-cloudwatch2` with "active under profile `aws` only" annotation |
| `.specs/codebase/INTEGRATIONS.md` | Add row for CloudWatch metrics (push, async, `monitoring.<region>.amazonaws.com`) |
| `.specs/project/ROADMAP.md` | Move F-CLOUDWATCH-METRICS from "planned" to "complete" at end of execution (T6) |
| `.specs/project/STATE.md` | Add `AD-CWM-1..4` block + bump Current Work line |
| `CLAUDE.md` | Optional: short "AWS observability" subsection under Architecture if reviewers will look for it. Skip if STATE.md + observability.md cover it. |

## Risks & Mitigations

| Risk | Mitigation |
| --- | --- |
| Dependency version conflict with existing AWS SDK transitives (Spring Cloud AWS, etc.) | Project has no Spring Cloud AWS today — clean slate. Verify `./mvnw dependency:tree` during T1. |
| `terraform validate` regression because Terraform never needed updating | None — the namespace and IAM policy were already correct. T5 only touches docs. |
| Memory/CPU overhead of the publish thread in test profile | Profile-gated — never instantiated in default/test profiles. |
| Drift between Prometheus meter names (`http_server_requests_seconds_count`) and CloudWatch meter names (`http.server.requests`) | Micrometer translates dot-separated names per backend automatically. The Prometheus naming used in `docs/observability.md` and the CloudWatch naming used in `docs/aws-architecture.md` are the **same meter** — Micrometer's job, not ours. T5 doc updates make this explicit. |

## Knowledge Verification

Per the spec-driven workflow's Knowledge Verification Chain:

- **Step 1 (codebase):** confirmed `pom.xml`, `application.properties`,
  `ObservabilityConfig`, `RateLimiterMeterFilter`, `CardinalityGuardTest`,
  `infra/terraform/modules/observability/` all already exist and align with the plan.
- **Step 2 (project docs):** AD-018 in `docs/aws-architecture.md` and the F-AWS reuse
  path in `docs/observability.md` describe the intended pattern at a high level.
- **Step 3 (Context7 / Micrometer docs):** the `CloudWatchConfig` interface, the
  `step`/`namespace`/`batchSize` properties, and the `CloudWatchAsyncClient` injection
  pattern are the documented Micrometer 1.13+ + AWS SDK v2 idiom (verify during T2
  implementation against the version Spring Boot 3.5.14 brings in).
- **Step 4 (web/AWS docs):** CloudWatch `PutMetricData` batch limit (20 metrics per
  call), 30-dimension-per-metric ceiling, and standard 1-minute resolution are
  AWS-documented defaults.
- **Step 5 (uncertain):** the exact Micrometer version Spring Boot 3.5.14 brings in
  via its BOM — flagged for verification in T1. If the BOM doesn't manage
  `micrometer-registry-cloudwatch2`, pin to the same `<micrometer.version>` already
  managed by the parent BOM.
