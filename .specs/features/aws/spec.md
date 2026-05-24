# F-AWS — AWS Deployment Proposal + Terraform IaC Specification

**Status:** Draft (2026-05-23)
**Scope decision (user, 2026-05-23):** **Proposal-grade Terraform**, **MSK provisioned**
(`kafka.t3.small × 3`), **Fargate only**. HCL must `terraform validate` clean; no real
`terraform apply` against an AWS account is in scope.

## Problem Statement

The invoice-generator runs end-to-end locally via `docker compose` (KRaft Kafka + Jaeger
+ app) but has no AWS deployment artifact. The roadmap commits F-AWS to deliver a
reviewable Terraform module + an architecture proposal that a reviewer can read in 15
minutes and understand: which AWS services run the application, how they map onto the
local stack (Kafka → MSK, OTLP → ADOT/X-Ray, Prometheus → CloudWatch), where the
F-OBSERVABILITY SLIs land, and what was deliberately deferred.

This feature is the **last node of M3 — Operations**. It does not change application
code; it produces:

1. **`infra/terraform/`** — modular HCL that provisions VPC, MSK, ECS Fargate, API
   Gateway, CloudWatch dashboards/alarms, and X-Ray. Validates clean
   (`terraform validate`). Idiomatic, but not deployed against a real AWS account.
2. **`docs/aws-architecture.md`** — architecture diagram (Mermaid), service-by-service
   walkthrough, ADRs for the non-obvious decisions, rough monthly cost, and the
   deployment runbook (the `terraform apply` story).

## Scope Decision Matrix

| Choice | Selected | Rationale |
| --- | --- | --- |
| Terraform realism | **Proposal-grade** (validate-clean, not apply-tested) | The roadmap calls F-AWS "deployment proposal + Terraform IaC" — applyable end-to-end requires a real AWS account + ~US$ 200/mo MSK spend. Proposal-grade is the reviewable artifact at zero cost. |
| Messaging plane | **Amazon MSK** (3 × `kafka.t3.small`) | Fidelity with the local Kafka topology (4 topics + retry + DLT); app code does not change. AD-014 already noted SQS as a simpler alt — captured here as Future Considerations, not in scope. |
| Compute | **ECS Fargate** only | The app holds long-lived Kafka consumer threads; Lambda's per-invocation model would split the consumers from the HTTP path and complicate the trace. Documented as ADR. |
| Logs ingestion | **awslogs driver → CloudWatch Logs** | Single hop, no Firelens sidecar. The app already writes single-line JSON via LogstashEncoder, so CloudWatch Insights queries work out of the box. |
| Traces ingestion | **ADOT sidecar → AWS X-Ray** | Same OTLP exporter the app already uses locally (AD-021); only the endpoint moves to `http://localhost:4318` (the sidecar). No code change. |
| Metrics ingestion | **Micrometer CloudWatch registry** (under `aws` Spring profile) | The local stack uses Prometheus scrape; the AWS stack pushes from the app via Micrometer's CloudWatch registry. Same instrumentation code (AD-018). |
| Auth | **Documented at the API Gateway boundary** (Cognito or JWT verifier) | Per roadmap: documented, not provisioned. ADR records the two paths. |

## Goals

- [ ] `terraform fmt -recursive` + `terraform validate` are green on every commit; an
      `infra/terraform/README.md` documents both as the F-AWS gate.
- [ ] A reviewer running `terraform plan` against a fake account context can read the
      plan output and see VPC, MSK, ECS, API Gateway, CloudWatch, X-Ray, IAM resources
      grouped by module.
- [ ] `docs/aws-architecture.md` includes a Mermaid diagram, the service table, the
      ADRs, an order-of-magnitude monthly cost estimate, and a one-page runbook.
- [ ] The four F-OBSERVABILITY SLIs from `docs/observability.md` are re-expressed
      verbatim as CloudWatch metric math in the dashboard module — no re-derivation.
- [ ] Auth is documented (one ADR + one paragraph in the architecture doc); no IaC
      resources are created for it.
- [ ] `ROADMAP.md` flips F-AWS from PLANNED to COMPLETE.

## Out of Scope

| Item | Reason |
| --- | --- |
| `terraform apply` against a real account | Requires AWS account + ~US$ 200/mo spend; not the F-AWS contract. |
| CI/CD pipeline (GitHub Actions, CodePipeline, etc.) | A separate concern; F-AWS ships IaC, not the deploy pipeline. |
| Multi-region or multi-account | Single region (`us-east-1` default, parameterised). |
| Cognito user pool / JWT verifier resources | Documented per scope decision. |
| Durable IdempotencyStore on Redis/ElastiCache | Flagged in AD-024 as a follow-up; Future Considerations entry. |
| EKS / Lambda alternatives | Fargate-only per scope decision. ADR captures the trade-off. |
| KMS-encrypted Kafka client traffic (mTLS) | MSK Public Access + SASL/IAM is the proposal; mTLS at app→broker is a Future Considerations item. |
| WAF, Shield, GuardDuty, Security Hub | Out of the F-AWS scope; mentioned in the architecture doc as production-readiness add-ons. |

---

## User Stories

### P1: Reviewable AWS architecture ⭐ MVP

**User Story:** As a reviewer of this technical challenge, I want a single document that
explains how the local stack maps onto AWS, so that I can validate the architecture
choices without reading hundreds of HCL lines.

**Why P1:** The deliverable is fundamentally a *proposal*. If the architecture doc
doesn't tell the story, the Terraform doesn't matter.

**Acceptance Criteria:**

1. **WHEN** the reviewer opens `docs/aws-architecture.md` **THEN** the file SHALL begin
   with a Mermaid diagram showing the request path (Client → API Gateway → VPC Link →
   ALB → ECS Fargate → MSK) and the observability path (App → CloudWatch / X-Ray).
2. **WHEN** the reviewer reads the services table **THEN** every AWS service used by
   the Terraform SHALL appear with its purpose, the local-stack equivalent, and a link
   to the Terraform module that provisions it.
3. **WHEN** the reviewer reaches the ADR section **THEN** the document SHALL include
   ADRs for: MSK over SQS (links to AD-014), Fargate over Lambda, awslogs over
   Firelens, Cognito vs JWT-verifier (documented only), proposal-grade vs applyable.
4. **WHEN** the reviewer reaches the cost section **THEN** the document SHALL include
   an order-of-magnitude monthly estimate (e.g., "MSK ≈ US$ 200, Fargate ≈ US$ 30,
   NAT ≈ US$ 30, total ≈ US$ 300/mo at idle").
5. **WHEN** the reviewer reaches the runbook **THEN** the document SHALL list the
   ordered `terraform apply` invocation, the IAM bootstrap requirement, and the
   smoke-test (`curl` against the API Gateway URL).

**Independent Test:** Manual review of `docs/aws-architecture.md`.

---

### P1: Validatable Terraform modules ⭐ MVP

**User Story:** As a platform engineer about to fork this repo to deploy the service,
I want the Terraform to validate clean and to be organised by module, so that I can
read each module independently and replace any of them with my org's own pattern.

**Why P1:** Proposal-grade does not mean unstructured. The HCL must read as code that
an AWS engineer would respect.

**Acceptance Criteria:**

6. **WHEN** `terraform fmt -recursive infra/terraform` runs **THEN** the command SHALL
   exit 0 with no files reformatted (idempotent).
7. **WHEN** `terraform init -backend=false && terraform validate` runs in
   `infra/terraform/` **THEN** the command SHALL exit 0.
8. **WHEN** the reviewer lists `infra/terraform/modules/` **THEN** there SHALL be
   exactly five modules: `network`, `msk`, `ecs`, `api-gateway`, `observability`.
   Each module has `main.tf` + `variables.tf` + `outputs.tf`.
9. **WHEN** the root `main.tf` runs **THEN** modules SHALL be wired in the dependency
   order: network → (msk, ecs) in parallel → api-gateway → observability.
10. **WHEN** any resource in any module is created **THEN** it SHALL carry the common
    tags `{Application = "invoice-generator", Environment = var.environment,
    ManagedBy = "terraform", Feature = "F-AWS"}`.
11. **WHEN** the reviewer reads any module's `outputs.tf` **THEN** the outputs SHALL
    cover what downstream modules need (e.g., `network.private_subnet_ids`,
    `network.app_security_group_id`, `msk.bootstrap_brokers_sasl_iam`).

**Independent Test:**

```bash
cd infra/terraform
~/.local/bin/terraform fmt -recursive -check
~/.local/bin/terraform init -backend=false
~/.local/bin/terraform validate
```

---

### P1: VPC topology that meets MSK + Fargate requirements ⭐ MVP

**User Story:** As an SRE, I want a VPC layout that places Fargate tasks and MSK
brokers in private subnets across three AZs with controlled internet egress, so that
the deployment matches AWS best practices and the production runbook is unsurprising.

**Why P1:** Every other module depends on the VPC; getting the topology wrong forces
re-architecture downstream.

**Acceptance Criteria:**

12. **WHEN** the `network` module is applied **THEN** it SHALL create a VPC with CIDR
    `10.42.0.0/16` (parameterised), three public subnets (`10.42.{0,1,2}.0/24`), three
    private subnets (`10.42.{10,11,12}.0/24`), an Internet Gateway, a NAT Gateway in
    the first public subnet (single NAT — cost trade-off for the proposal), and
    matching route tables.
13. **WHEN** the `network` module emits outputs **THEN** it SHALL expose
    `vpc_id`, `private_subnet_ids` (list), `public_subnet_ids` (list),
    `app_security_group_id`, `msk_security_group_id`, `alb_security_group_id`.
14. **WHEN** the `app_security_group_id` is inspected **THEN** it SHALL allow inbound
    8080 from the ALB SG only, and full egress (so Fargate can reach MSK + CloudWatch
    + ECR + X-Ray).
15. **WHEN** the `msk_security_group_id` is inspected **THEN** it SHALL allow inbound
    `9092` (PLAINTEXT, intra-VPC) and `9098` (SASL/IAM) **from the app SG only**, and
    no egress.

**Independent Test:** `terraform plan` output for the `network` module shows the
correct CIDRs, subnet counts, and SG rules.

---

### P1: MSK cluster with the same topology as the local stack ⭐ MVP

**User Story:** As a developer who already understands the local Kafka topology, I want
the AWS cluster to behave the same way (3 brokers, same 4 topics with their retry/DLT
suffixes), so that the only thing that changes when promoting code is the bootstrap
servers string.

**Why P1:** App code under `app.messaging.kafka.enabled=true` already publishes to the
four topics; the AWS plane has to honour that contract.

**Acceptance Criteria:**

16. **WHEN** the `msk` module is applied **THEN** it SHALL create an `aws_msk_cluster`
    with 3 brokers of type `kafka.t3.small`, Kafka version `3.6.0`, EBS storage
    `100 GiB` per broker, distributed across the three private subnets from `network`.
17. **WHEN** the MSK cluster is created **THEN** it SHALL have **at-rest encryption
    enabled** with a customer-managed KMS key (`aws_kms_key.msk`) and **in-transit
    encryption** set to `TLS_PLAINTEXT` (TLS to clients, plaintext intra-broker —
    matches the local plaintext-listener simplicity while keeping the wire encrypted).
18. **WHEN** the MSK cluster is created **THEN** it SHALL have **SASL/IAM authentication
    enabled** so the Fargate task role can authenticate as a Kafka principal without
    SASL/SCRAM secrets management. Unauthenticated access SHALL be disabled.
19. **WHEN** the MSK cluster is created **THEN** broker logs SHALL be shipped to a
    CloudWatch log group `/aws/msk/invoice-generator` with a 30-day retention.
20. **WHEN** the `msk` module emits outputs **THEN** it SHALL expose
    `bootstrap_brokers_sasl_iam` (the string the app sets as
    `KAFKA_BOOTSTRAP_SERVERS`) and `cluster_arn` (used by IAM policy in the `ecs`
    module).
21. **WHEN** the reviewer reads the module's `main.tf` **THEN** there SHALL be a
    single `aws_msk_configuration` resource that bakes the four invoice topics'
    `num.partitions=3`, `auto.create.topics.enable=true`, and the retry/DLT topic
    auto-creation policy. (Topic creation itself is done by Spring Kafka on app
    startup; the configuration just enables it.)

**Independent Test:** `terraform plan` for the `msk` module shows the cluster with
3 brokers, SASL/IAM enabled, KMS key referenced.

---

### P1: ECS Fargate platform for the app ⭐ MVP

**User Story:** As a developer who pushes a new image, I want the ECS service to pull
the latest tag from ECR, run the task with the ADOT sidecar for tracing, and stream
logs to CloudWatch, so that the AWS deployment behaves indistinguishably from the
local `docker compose up`.

**Why P1:** This is the compute plane — without it nothing runs.

**Acceptance Criteria:**

22. **WHEN** the `ecs` module is applied **THEN** it SHALL create:
    an `aws_ecr_repository` (`invoice-generator`, mutable tags), an `aws_ecs_cluster`
    (`invoice-generator`), an `aws_ecs_task_definition` (Fargate, `1024` CPU / `2048`
    MiB memory, two containers: the app + the ADOT sidecar), an `aws_ecs_service`
    (desired count `2`), an `aws_lb` (internal ALB across the three private subnets),
    and `aws_lb_listener` on port `80` to the app target group.
23. **WHEN** the task definition is inspected **THEN** the app container SHALL set
    `KAFKA_BOOTSTRAP_SERVERS` from the `msk` module output, `OTLP_TRACING_ENDPOINT` to
    `http://localhost:4318/v1/traces` (the sidecar), `SPRING_PROFILES_ACTIVE=aws`,
    `AWS_REGION` from variable, and have the awslogs driver configured against
    `/aws/ecs/invoice-generator`.
24. **WHEN** the ADOT sidecar is inspected **THEN** it SHALL run
    `public.ecr.aws/aws-observability/aws-otel-collector:v0.40.0` (pin) with a config
    file mounted via a small inline command that enables the OTLP receiver + X-Ray
    exporter.
25. **WHEN** the `aws_iam_role.task_execution` is created **THEN** it SHALL allow
    `ecr:GetAuthorizationToken`, `ecr:BatchGetImage`, `logs:CreateLogStream`,
    `logs:PutLogEvents` — the minimum for Fargate to pull the image and ship the
    logs.
26. **WHEN** the `aws_iam_role.task` is created **THEN** it SHALL grant the running
    app the right to: produce/consume on the MSK cluster
    (`kafka-cluster:Connect`, `kafka-cluster:*Topic*`, `kafka-cluster:WriteData`,
    `kafka-cluster:ReadData`), push CloudWatch metrics (`cloudwatch:PutMetricData`
    scoped to the `InvoiceGenerator` namespace), and emit X-Ray segments
    (`xray:PutTraceSegments`, `xray:PutTelemetryRecords`). No `*:*` policies.
27. **WHEN** the `ecs` module emits outputs **THEN** it SHALL expose
    `alb_listener_arn` (consumed by the `api-gateway` module's VPC Link integration)
    and `task_role_arn`.

**Independent Test:** `terraform plan` for the `ecs` module shows ECR + cluster +
task def + service + ALB + two IAM roles with the expected policies.

---

### P1: API Gateway HTTP API as the public edge ⭐ MVP

**User Story:** As an API consumer, I want a stable HTTPS URL that routes
`POST /api/orders/generate-invoice` to the service, so that I don't have to know
about the internal ALB DNS name.

**Why P1:** The public edge is the contract surface; the runbook smoke test hits it.

**Acceptance Criteria:**

28. **WHEN** the `api-gateway` module is applied **THEN** it SHALL create an
    `aws_apigatewayv2_api` (HTTP protocol), an `aws_apigatewayv2_vpc_link` bound to
    the three private subnets + the ALB SG, an `aws_apigatewayv2_integration`
    pointing at the ALB listener via the VPC Link, and an `aws_apigatewayv2_route`
    for `ANY /api/{proxy+}` (forwards both the modern and legacy paths).
29. **WHEN** the API Gateway stage is created **THEN** it SHALL have access logging
    enabled to a CloudWatch log group `/aws/apigateway/invoice-generator` with a
    JSON format that includes `requestId`, `routeKey`, `status`, `integrationLatency`,
    `responseLatency`, `X-Correlation-Id` echoed back.
30. **WHEN** the API Gateway is created **THEN** the architecture doc and the module
    README SHALL state that **no authorizer is configured** — auth is the deferred
    follow-up (ADR-031).
31. **WHEN** the module emits outputs **THEN** it SHALL expose `api_endpoint` so the
    runbook smoke-test command can target it directly.

**Independent Test:** `terraform plan` for the `api-gateway` module shows the API,
the VPC Link, the integration, and the stage with access logs.

---

### P1: CloudWatch dashboards reusing the four F-OBSERVABILITY SLIs ⭐ MVP

**User Story:** As an on-call SRE, I want the CloudWatch dashboard to show the same
four SLIs we agreed on for the local Prometheus stack, so that I read the same
numbers in both environments without re-deriving the math.

**Why P1:** F-OBSERVABILITY's spec required AWS to reuse the SLI definitions verbatim.

**Acceptance Criteria:**

32. **WHEN** the `observability` module is applied **THEN** it SHALL create an
    `aws_cloudwatch_dashboard` with four widgets, one per SLI:
    SLI-1 (API success rate), SLI-2 (API latency, 800 ms target), SLI-3 (Kafka
    dispatch success), SLI-4 (side-effect end-to-end latency, 30 s target).
33. **WHEN** each widget's metric math expression is inspected **THEN** it SHALL
    mirror the Prometheus query published in `docs/observability.md` § per-SLI
    sections — same numerator/denominator semantics, same SLO bucket.
34. **WHEN** the `observability` module creates alarms **THEN** there SHALL be at
    least one `aws_cloudwatch_metric_alarm` per SLI configured at the SLO target
    breach (e.g., SLI-2 alarm fires when the 5-minute rolling ratio drops below
    0.99). Alarms have no action wired (no SNS topic) — the next feature would add
    PagerDuty.
35. **WHEN** the `observability` module creates X-Ray bits **THEN** there SHALL be an
    `aws_xray_group` filter targeting the service and an `aws_xray_sampling_rule`
    sampling 10% of requests at priority `9000` (the local 100% sampling is documented
    as a `local` profile choice in AD-021).
36. **WHEN** the module's `main.tf` is read **THEN** every CloudWatch log group SHALL
    have a 30-day retention and a KMS key reference parameter (default `null`, since
    the proposal does not provision its own log-encryption KMS key).

**Independent Test:** `terraform plan` for the `observability` module lists the
dashboard, alarms, log groups, X-Ray group, and sampling rule.

---

## Cardinality / Tagging Rules

- Every resource in every module carries the common tags from `locals.tf`:
  `{Application, Environment, ManagedBy, Feature}`.
- Resource names follow the pattern `${var.app_name}-${var.environment}-${suffix}`
  (e.g., `invoice-generator-dev-msk`).
- No hard-coded `us-east-1` strings in `main.tf` — region comes from the provider.

## Success Criteria

- [ ] All `Done when` checkboxes across T1..T5 are ticked.
- [ ] `terraform fmt -recursive -check` exits 0.
- [ ] `terraform init -backend=false && terraform validate` exits 0.
- [ ] `docs/aws-architecture.md` exists with the diagram, the services table, ≥ 4
      ADRs, the cost estimate, and the runbook.
- [ ] `ROADMAP.md` flips F-AWS PLANNED → COMPLETE with completion date.
- [ ] `STATE.md` Current Work line updated; one or more ADRs added for the
      proposal-grade decision and any other non-obvious calls made during execution.
