# AWS Deployment — Architecture Proposal

This is the reviewer-facing architecture write-up for F-AWS. The matching Terraform
lives under [`infra/terraform/`](../infra/terraform/). The HCL is **proposal-grade** —
it validates clean (`terraform fmt + init + validate`) but is intentionally not wired
against a real AWS account; that would require ~US$ 200/mo of MSK spend and a real IAM
bootstrap, which is out of the F-AWS scope.

Spec / design / tasks: [`.specs/features/aws/`](../.specs/features/aws/).
Operator-facing SLI catalog (reused below): [`docs/observability.md`](observability.md).

---

## Architecture diagram

```mermaid
graph LR
  subgraph Client
    C[HTTP client]
  end

  subgraph "AWS — single VPC 10.42.0.0/16, 3 AZs"
    direction TB
    APIGW[API Gateway HTTP API<br/>ANY /api/{proxy+}]
    VPCL[VPC Link]
    ALB[Internal ALB<br/>3 private subnets]

    subgraph "ECS Fargate service (desired = 2)"
      direction TB
      subgraph "Task — 1 vCPU / 2 GiB"
        APP[app container<br/>Spring Boot 3.5 / Java 21]
        OTEL[ADOT sidecar<br/>OTLP collector]
      end
    end

    subgraph "MSK cluster (3 × kafka.t3.small)"
      MSK1[(broker 1<br/>private AZ-a)]
      MSK2[(broker 2<br/>private AZ-b)]
      MSK3[(broker 3<br/>private AZ-c)]
    end

    ECR[(ECR repo)]
    KMS[KMS CMK<br/>at-rest MSK]

    subgraph "Managed observability"
      CWL[(CloudWatch Logs<br/>/aws/ecs, /aws/msk,<br/>/aws/apigateway)]
      CWM[(CloudWatch Metrics<br/>namespace InvoiceGenerator)]
      CWD[CloudWatch Dashboard<br/>4 SLI widgets + business volume]
      XRAY[(AWS X-Ray<br/>10% sampling)]
    end

    C -->|HTTPS| APIGW
    APIGW -->|VPC Link| VPCL --> ALB
    ALB -->|:8080| APP
    APP -->|SASL/IAM :9098| MSK1
    APP -->|SASL/IAM :9098| MSK2
    APP -->|SASL/IAM :9098| MSK3
    APP -.->|awslogs JSON| CWL
    APP -.->|Micrometer<br/>CloudWatch registry| CWM
    APP -.->|OTLP HTTP localhost:4318| OTEL
    OTEL -.->|X-Ray segments| XRAY
    CWM --> CWD
    APP -. pull image .-> ECR
    KMS -. encrypts .-> MSK1
    KMS -. encrypts .-> MSK2
    KMS -. encrypts .-> MSK3
  end
```

Every interface in the diagram already exists in the local Docker Compose stack — Spring
Kafka's SASL/IAM authenticator, the OTLP HTTP exporter, and the LogstashEncoder are all
in place. F-AWS is the *plane* the existing instrumentation lands on; no application
code change is required beyond enabling the `aws` Spring profile and adding the
`aws-msk-iam-auth` jar to the runtime classpath.

---

## Services map

Every AWS service the Terraform provisions, what it's for, and what it replaces from
the local stack.

| AWS service | Purpose | Local-stack equivalent | Terraform module |
| --- | --- | --- | --- |
| Amazon VPC + subnets + NAT + IGW | Network isolation, 3 AZs, single NAT (cost trade-off) | Docker user-defined bridge network | [`modules/network`](../infra/terraform/modules/network) |
| Security Groups | Layer-4 firewall between ALB ↔ app ↔ MSK | docker-compose service-to-service trust | [`modules/network`](../infra/terraform/modules/network) |
| Amazon MSK (3 × `kafka.t3.small`, Kafka 3.6.0) | Durable Kafka plane, SASL/IAM auth, at-rest CMK | `confluentinc/cp-kafka:7.7` in KRaft mode | [`modules/msk`](../infra/terraform/modules/msk) |
| AWS KMS CMK | At-rest encryption for MSK broker storage | n/a (local Docker volumes unencrypted) | [`modules/msk`](../infra/terraform/modules/msk) |
| Amazon ECR | Container image registry the ECS service pulls from | local `invoice-generator:local` image built by `docker compose build` | [`modules/ecs`](../infra/terraform/modules/ecs) |
| Amazon ECS on Fargate (cluster + service + task def) | Compute for the Spring Boot app — 2 tasks for HA, no EC2 to manage | `invoice-generator` container in compose | [`modules/ecs`](../infra/terraform/modules/ecs) |
| ADOT sidecar (`aws-otel-collector`) | OTLP receiver in the task, forwards to X-Ray | Jaeger container in compose | [`modules/ecs`](../infra/terraform/modules/ecs) |
| IAM (task execution role + task role) | Fargate agent pulls images + ships logs; running task authenticates as Kafka principal + pushes metrics + emits traces | n/a (local container runs as root inside compose net) | [`modules/ecs`](../infra/terraform/modules/ecs) |
| Internal ALB | L7 in front of the Fargate service, health checks on `/actuator/health` | Direct docker-compose port mapping `8080:8080` | [`modules/ecs`](../infra/terraform/modules/ecs) |
| API Gateway HTTP API + VPC Link | Public HTTPS edge with throttling and access logs | `localhost:8080` (no edge in the local stack) | [`modules/api-gateway`](../infra/terraform/modules/api-gateway) |
| CloudWatch Logs (3 log groups) | JSON application logs, MSK broker logs, API Gateway access logs | `docker compose logs` | [`modules/ecs`](../infra/terraform/modules/ecs) + [`modules/msk`](../infra/terraform/modules/msk) + [`modules/api-gateway`](../infra/terraform/modules/api-gateway) |
| CloudWatch Metrics (custom namespace `InvoiceGenerator`) | Where the Micrometer CloudWatch registry publishes | `/actuator/prometheus` scraped by a local Prometheus container (planned, not running) | n/a — app-side publication |
| CloudWatch Dashboard | 4 SLI widgets + business-volume widget | Prometheus + Grafana (planned locally) | [`modules/observability`](../infra/terraform/modules/observability) |
| CloudWatch Alarms × 4 | One alarm per SLI, no action wired | Prometheus Alertmanager (out of local stack) | [`modules/observability`](../infra/terraform/modules/observability) |
| AWS X-Ray (group + sampling rule) | Trace storage + Service Map view, 10% sampling | Jaeger UI on `localhost:16686`, 100% sampling | [`modules/observability`](../infra/terraform/modules/observability) |

---

## SLI catalog → CloudWatch metric math

The four SLIs are defined in `docs/observability.md`. The CloudWatch dashboard widgets
and alarms reuse the same numerator/denominator semantics. Do not re-derive — these are
the same math, expressed in a different query language.

| # | SLI | Prometheus (local) | CloudWatch metric math (AWS) | SLO |
| --- | --- | --- | --- | --- |
| SLI-1 | API success rate | `sum(rate(http_server_requests_seconds_count{status!~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count[5m]))` | `100 * (m_total - m_5xx) / m_total` | 99.5 % / 30d |
| SLI-2 | API latency under 800 ms | `sum(rate(http_server_requests_seconds_bucket{le="0.8"}[5m])) / sum(rate(http_server_requests_seconds_count[5m]))` | `100 * m_under_800 / m_total` | 99 % / 30d |
| SLI-3 | Kafka dispatch success | `sum(rate(invoice_dispatch_total{outcome="success"}[5m])) / sum(rate(invoice_dispatch_total[5m]))` | `100 * m_success / m_total` | 99.9 % / 7d |
| SLI-4 | Side-effect latency under 30 s | `sum(rate(invoice_sideeffect_duration_seconds_bucket{le="30"}[5m])) / sum(rate(invoice_sideeffect_duration_seconds_count[5m]))` | `100 * m_under_30 / m_total` | 95 % / 7d |

Each alarm fires when the 5-minute rolling ratio drops below the SLO target. No SNS
topic is wired — the next iteration would attach PagerDuty / Opsgenie / Slack.

---

## Architectural decisions (ADRs)

### ADR-029: MSK over SQS (kept the F-DEFECTS-PERFORMANCE choice)

**Decision:** Provision Amazon MSK (3 × `kafka.t3.small`, SASL/IAM). Do not pivot to
SQS + Lambda consumers despite the cost advantage.

**Reason:** AD-014 already noted SQS as the simpler AWS production fit for these
*command-style* side effects. But pivoting would force a parallel adapter code path
(`SqsInvoiceSideEffectDispatcher` + 4 Lambda consumers), break the symmetry with the
local Kafka stack, and invalidate every existing integration test that uses
`@EmbeddedKafka`. For the proposal, fidelity to the local topology beats cost
optimisation. The SQS alternative is captured here as **Future Considerations** for an
SRE team that decides MSK is operationally too heavy.

**Trade-off:** ~US$ 200/mo at idle for the 3-broker cluster vs ~US$ 5/mo for SQS at
the same throughput. App-side code stays identical.

### ADR-030: ECS Fargate over Lambda for the HTTP path

**Decision:** Use ECS Fargate, no Lambda alternative.

**Reason:** The Spring Boot app holds long-lived Kafka consumer threads (one per
`@KafkaListener`). Lambda's per-invocation model splits the HTTP path from the
consumers, requires a separate compute target for the listeners, and complicates the
end-to-end trace (HTTP span → Kafka span no longer flows through a single process).
Fargate keeps app + consumers in the same task, the same JVM, the same trace.

**Trade-off:** Fargate has a constant per-task cost (~US$ 30/mo per task at the chosen
1 vCPU / 2 GiB size) vs Lambda's pay-per-request. For this workload (low QPS, long
consumers) Fargate is cheaper anyway.

### ADR-031: awslogs over Firelens

**Decision:** Use the built-in `awslogs` log driver on the Fargate task definition.
Do not deploy a Fluent Bit Firelens sidecar.

**Reason:** The application emits single-line JSON via LogstashEncoder. CloudWatch
Logs Insights queries (`fields @timestamp, correlationId, level, message | filter
correlationId = "probe-1"`) work natively on JSON without a parser sidecar. Firelens
adds a container (more memory, more failure modes) for zero observability gain at
this scale.

**Trade-off:** If a future requirement demands cross-account log fan-out or routing
some streams to OpenSearch, Firelens becomes the right answer. Out of scope today.

### ADR-032: Authentication deferred — Cognito vs JWT verifier comparison

**Decision:** No authorizer is configured on the API Gateway in this proposal. The
README is explicit that the public endpoint is open.

**Two paths, both viable** when the user decides to add auth:

| Path | What | Trade-off |
| --- | --- | --- |
| **Cognito User Pool + API Gateway JWT authorizer** | Cognito hosts the user pool, MFA, hosted UI, federated IdPs. API Gateway natively validates the Cognito-issued JWT (no Lambda authorizer needed). | One AWS service to operate; vendor-coupled. The JWT lands on the app as `Authorization: Bearer ...` and the Spring Security `oauth2ResourceServer().jwt()` line reads it. |
| **External IdP (Auth0, Keycloak, Okta) + API Gateway JWT authorizer** | API Gateway is still the JWT authorizer; the issuer is some external service. No Cognito provisioning. | Multiple vendors; more flexibility on user federation and pricing. Same `oauth2ResourceServer().jwt()` config — only the `issuer-uri` changes. |

**Reason for the deferral:** Per the roadmap, F-AWS ships *documented* AuthN/AuthZ,
not provisioned. The right choice depends on whether this is a single-tenant internal
service (Cognito wins on simplicity) or a multi-tenant external one (external IdP wins
on flexibility). That decision needs a stakeholder; it does not need IaC.

### ADR-033: Proposal-grade Terraform (not applyable end-to-end)

**Decision:** The HCL validates clean (`terraform validate`) but is not run against a
real AWS account. There is no S3 backend, no DynamoDB lock table, no remote state.

**Reason:** Captured during F-AWS clarification (2026-05-23). Applyable end-to-end
would require an AWS account, IAM bootstrap, MSK provisioning (~US$ 200/mo) and
manual cleanup — all of which are out of the F-AWS scope. Proposal-grade gives a
reviewable artifact at zero cost. When the team is ready to deploy: add a
`backend.tf`, run `aws sso login`, `terraform init`, `terraform plan`, `terraform
apply` — the modules themselves do not change.

**Trade-off:** No real ARNs, no live smoke test. The runbook below lists the
ordered commands.

---

## Cost estimate

Order-of-magnitude monthly cost in `us-east-1` at idle (no real traffic, just the
baseline infra running). Production sizing — and a heavy traffic month — would change
all of these meaningfully.

| Service | Monthly | Notes |
| --- | --- | --- |
| MSK (3 × `kafka.t3.small`, 100 GiB EBS each) | ~US$ 200 | Brokers + storage + data transfer at idle. Largest single line. |
| Fargate (2 tasks × 1 vCPU × 2 GiB, 24×7) | ~US$ 30 | Linear with task count + size. Scaling the service to 1 task halves it. |
| NAT Gateway (single AZ) | ~US$ 32 | Plus US$ 0.045/GiB processed; egress for ECR pulls + CloudWatch + X-Ray + MSK metadata. |
| Application Load Balancer | ~US$ 18 | Plus LCU; trivial at low QPS. |
| API Gateway HTTP API | <US$ 1 | US$ 1/million requests; negligible at proposal traffic. |
| CloudWatch Logs (3 log groups, 30-day retention) | ~US$ 5 | Ingestion + storage; volume depends on log verbosity. |
| CloudWatch Metrics (custom + standard) | ~US$ 5 | First million per region free; custom metrics dominate when SLOs are computed in-backend. |
| CloudWatch Dashboard | US$ 3 | Per dashboard. |
| AWS X-Ray | ~US$ 5 | 10% sampling at proposal traffic; growth is linear with request volume. |
| ECR storage | <US$ 1 | A few image tags. |
| KMS (CMK + key usage) | US$ 1 | Flat per-CMK fee + per-call. |
| **Total (idle)** | **~US$ 300** | Dominated by MSK (~67%). |

Tightening levers (not in scope, captured for the operator):

- Switch MSK to **MSK Serverless** — pay per partition + throughput instead of broker-hours.
  Roughly halves the bill for low-throughput workloads.
- Replace the single NAT Gateway with **VPC interface endpoints** for the AWS APIs the
  app talks to (S3, ECR, CloudWatch, X-Ray, MSK control plane). Trade-off: more
  endpoints to provision, no NAT bill.
- Drop the second Fargate task during off-hours via a scheduled Application Auto
  Scaling rule.

---

## Deployment runbook

Once the team is ready to deploy, the modules can be applied against a real account.
Two prerequisites first:

1. **IAM bootstrap.** Create or sign into an AWS account with permissions to create
   VPC, MSK, IAM, ECS, ECR, API Gateway, CloudWatch, X-Ray. `aws sso login` (or
   `aws configure`) so Terraform can pick up credentials.
2. **Backend.** Decide where state lives. For the proposal there is **no backend** —
   add an `infra/terraform/backend.tf` pointing at an S3 bucket + DynamoDB lock table
   before the first `apply`.

Then:

```bash
cd infra/terraform

# 0. fill in the variables for your account
cp terraform.tfvars.example terraform.tfvars
$EDITOR terraform.tfvars           # account_id, region, environment

# 1. init + plan + apply
~/.local/bin/terraform init
~/.local/bin/terraform plan -out=tfplan
~/.local/bin/terraform apply tfplan

# 2. push the application image
APP_TAG=v1
docker build -t invoice-generator:${APP_TAG} .
aws ecr get-login-password --region $(terraform output -raw region) \
  | docker login --username AWS --password-stdin \
    $(terraform output -raw ecr_repository_url | cut -d/ -f1)
docker tag invoice-generator:${APP_TAG} $(terraform output -raw ecr_repository_url):${APP_TAG}
docker push $(terraform output -raw ecr_repository_url):${APP_TAG}

# 3. force the ECS service to pick up the new image
aws ecs update-service \
  --cluster   $(terraform output -raw cloudwatch_dashboard_name | sed 's/-slis$//') \
  --service   $(terraform output -raw cloudwatch_dashboard_name | sed 's/-slis$//') \
  --force-new-deployment

# 4. smoke test
curl -i -X POST "$(terraform output -raw api_endpoint)/api/orders/generate-invoice" \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-Id: smoketest-1' \
  -d @../../src/main/resources/paylods/teste-pf.json

# 5. observe
open "https://console.aws.amazon.com/cloudwatch/home?region=$(terraform output -raw region)#dashboards:name=$(terraform output -raw cloudwatch_dashboard_name)"
open "https://console.aws.amazon.com/xray/home?region=$(terraform output -raw region)#/service-map"
```

---

## What's deliberately deferred

| Topic | Where it should land |
| --- | --- |
| Cognito user pool / external JWT-verifier wiring | ADR-032 above; a follow-up feature when the auth boundary is decided. |
| CI/CD pipeline (image build + `terraform plan/apply` on push) | A separate concern; F-AWS ships the IaC, not the pipeline. |
| Multi-region or multi-account | Single region (`us-east-1` default), single account. |
| Durable IdempotencyStore (DynamoDB or ElastiCache for Redis) | Flagged in AD-024. Future Considerations entry on the roadmap. |
| EKS / Lambda alternatives | Fargate-only per scope decision (ADR-030). |
| Encrypted intra-broker MSK traffic (`TLS` instead of `TLS_PLAINTEXT`) | One-line change in `modules/msk/main.tf` (`encryption_in_transit.client_broker = "TLS"`). Documented but not enabled. |
| WAF, Shield Advanced, GuardDuty, Security Hub | Production-readiness add-ons; not part of an architecture proposal. |
| MSK Serverless migration | Cost tightening lever above. |
| SNS / PagerDuty wiring on the SLI alarms | The alarms exist with no action; attaching a topic is one extra resource. |
