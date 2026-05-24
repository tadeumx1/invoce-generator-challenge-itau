# F-AWS Design

**Spec:** `.specs/features/aws/spec.md`
**Status:** Draft (2026-05-23)

---

## Architecture Overview

```mermaid
graph LR
  subgraph Client
    C[HTTP client]
  end

  subgraph "AWS — VPC 10.42.0.0/16"
    direction TB
    APIGW[API Gateway HTTP API<br/>ANY /api/{proxy+}]
    VPCL[VPC Link]
    ALB[Internal ALB<br/>private subnets × 3]

    subgraph "ECS Fargate cluster"
      ECS[invoice-generator service<br/>desired count 2]
      subgraph "Task (1 vCPU / 2 GiB)"
        APP[app container<br/>Spring Boot]
        OTEL[ADOT sidecar<br/>OTLP → X-Ray]
      end
      ECS --> APP
      ECS --> OTEL
    end

    subgraph "MSK cluster — 3 × kafka.t3.small"
      MSK1[broker 1<br/>private AZ-a]
      MSK2[broker 2<br/>private AZ-b]
      MSK3[broker 3<br/>private AZ-c]
    end

    subgraph "AWS managed observability"
      CWL[(CloudWatch Logs<br/>/aws/ecs, /aws/msk, /aws/apigateway)]
      CWM[(CloudWatch Metrics<br/>namespace InvoiceGenerator)]
      CWD[(CloudWatch Dashboard<br/>4 SLI widgets)]
      XRAY[(AWS X-Ray)]
    end

    subgraph "Supporting"
      ECR[(ECR repo<br/>invoice-generator)]
      KMS[KMS CMK<br/>at-rest MSK encryption]
    end

    C -->|HTTPS| APIGW
    APIGW -->|VPC Link| VPCL --> ALB
    ALB -->|:8080| APP
    APP -->|SASL/IAM 9098| MSK1
    APP -->|SASL/IAM 9098| MSK2
    APP -->|SASL/IAM 9098| MSK3
    APP -.->|awslogs JSON| CWL
    APP -.->|Micrometer CloudWatch registry| CWM
    APP -.->|OTLP HTTP localhost:4318| OTEL
    OTEL -.->|X-Ray| XRAY
    CWM --> CWD
    APP -. pull image .-> ECR
    KMS -. encrypts .-> MSK1
    KMS -. encrypts .-> MSK2
    KMS -. encrypts .-> MSK3
  end
```

The wire format on every interface above already exists in the local stack — the
Spring Kafka SASL/IAM authenticator, the OTLP HTTP exporter, and the JSON log encoder
are all in place. F-AWS is the *plane* the existing instrumentation lands on.

---

## Module Layout

```
infra/terraform/
├── README.md                # how to use, what's deferred, validate command
├── versions.tf              # terraform 1.9+, AWS provider 5.x
├── providers.tf             # aws provider w/ default_tags
├── variables.tf             # account_id, region, environment, app_name, vpc_cidr
├── locals.tf                # common tags, resource-name pattern
├── main.tf                  # wires the 5 modules
├── outputs.tf               # surfaces the public api endpoint
└── modules/
    ├── network/             # VPC + 3 public + 3 private + IGW + NAT + 3 SGs
    │   ├── main.tf
    │   ├── variables.tf
    │   └── outputs.tf
    ├── msk/                 # KMS + MSK cluster + configuration + log group
    │   ├── main.tf
    │   ├── variables.tf
    │   └── outputs.tf
    ├── ecs/                 # ECR + ECS cluster + task def + service + ALB + IAM
    │   ├── main.tf
    │   ├── variables.tf
    │   └── outputs.tf
    ├── api-gateway/         # HTTP API + VPC Link + integration + stage + log group
    │   ├── main.tf
    │   ├── variables.tf
    │   └── outputs.tf
    └── observability/       # dashboard + alarms + X-Ray group + sampling rule
        ├── main.tf
        ├── variables.tf
        └── outputs.tf
```

Five modules — exactly one per "concern" in the spec (network, messaging, compute,
edge, observability). Per the active feedback memory, this matches the user's
preference for vertical-slice cohesion over per-resource granularity.

---

## Dependency Matrix

```
network ──┬──→ msk           ──┐
          ├──→ ecs           ──┼──→ api-gateway
          └──→ ecs (alb sg)  ──┘
                                                                 
ecs + msk ──→ observability (dashboards reference both metric namespaces)
```

- `network` runs first and exports `vpc_id`, `private_subnet_ids`, `app_sg_id`,
  `msk_sg_id`, `alb_sg_id`.
- `msk` consumes `network.private_subnet_ids` + `network.msk_sg_id`; exports
  `bootstrap_brokers_sasl_iam` + `cluster_arn`.
- `ecs` consumes `network.private_subnet_ids` + `network.app_sg_id` +
  `network.alb_sg_id` + `msk.bootstrap_brokers_sasl_iam` + `msk.cluster_arn`; exports
  `alb_listener_arn` + `task_role_arn`.
- `api-gateway` consumes `network.private_subnet_ids` + `network.alb_sg_id` +
  `ecs.alb_listener_arn`; exports `api_endpoint`.
- `observability` is reference-only (it reads names of resources created elsewhere; no
  resource depends on it).

Modules with no edge between them in the graph above can be planned in parallel by
Terraform's graph engine.

---

## Tech Decisions (non-obvious only)

| Decision | Choice | Rationale |
| --- | --- | --- |
| Terraform version | 1.9.8 (pinned via `>= 1.6`) | Matches the binary we install locally; modern enough for `terraform plan -json` and the new `terraform fmt -recursive` behaviour. |
| AWS provider version | `hashicorp/aws ~> 5.60` | Stable v5; supports MSK SASL/IAM (`client_authentication.sasl.iam`), HTTP API VPC Link, ADOT-friendly ECS task definition format. |
| Default tags | Set on the AWS provider via `default_tags { tags = local.common_tags }` | Beats tagging every resource; the provider applies tags automatically. Saves ~40 LOC. |
| MSK auth | SASL/IAM only | No SASL/SCRAM Secrets Manager management; the Fargate task role is the principal. App code already supports SASL/IAM via `software.amazon.msk:aws-msk-iam-auth` (one Spring Kafka property to flip under the `aws` profile). |
| MSK encryption | At-rest with CMK; in-transit `TLS_PLAINTEXT` (TLS client ↔ broker, plaintext intra-broker) | App talks TLS; intra-broker plaintext mirrors the local KRaft simplicity and is the AWS default for the small-broker tier. Production-grade tightening (CLIENT_BROKER=TLS, IN_CLUSTER=TLS) is a one-line change captured in the doc. |
| Compute | ECS Fargate, no EC2 launch type | Spec decision. ADR-029. |
| ALB scheme | `internal` | API Gateway HTTP API + VPC Link is the only ingress; no need for a public ALB. Cuts the attack surface. |
| API Gateway type | HTTP API (`apigatewayv2`) over REST API | Lower cost, lower latency, native VPC Link to ALB, plenty for this workload. The REST API's WAF / request-validation features are not needed for the proposal. |
| Logs path | awslogs driver → CloudWatch Logs (no Firelens) | App already emits JSON lines; Firelens would add a Fluent Bit sidecar for zero gain at this scale. |
| Tracing path | ADOT sidecar (`aws-otel-collector`) → X-Ray | Same OTLP exporter the app uses locally; only the endpoint changes from `jaeger:4318` → `localhost:4318` (the sidecar in the same task). AD-021 reuse. |
| Metrics path | Micrometer CloudWatch registry (under `aws` Spring profile) | Direct push, no Prometheus container, no scrape interval to tune. AD-018 reuse. |
| Sampling | 10% via X-Ray sampling rule | Lower than the local 100% so the X-Ray bill doesn't dominate. Spec OBS-22 (defer). |
| Single NAT | One NAT Gateway in the first public subnet | Cost-vs-resilience trade-off for the proposal. Production should use one NAT per AZ; flagged in the architecture doc. |
| Auth | Documented, not provisioned | Spec scope decision. Two ADRs: Cognito user pool (built-in JWT, MFA, hosted UI) vs JWT verifier with an external IdP (Auth0, Keycloak). |

---

## Code Reuse Analysis

| Existing artifact | How it's reused in F-AWS |
| --- | --- |
| `docs/observability.md` (F-OBSERVABILITY T5) | The four Prometheus queries are translated **verbatim** into CloudWatch metric math in `modules/observability/main.tf`. The doc is the SSOT; if a query changes, both sides update. |
| `IntegrationEventPublisher` + `KafkaInvoiceSideEffectDispatcher` (F-DEFECTS-PERFORMANCE) | No changes. The Spring Kafka SASL/IAM authenticator only needs the `aws-msk-iam-auth` jar + 4 properties under the `aws` profile (documented in `infra/terraform/README.md`). |
| `CorrelationIdFilter` + the LogstashEncoder (F-OBSERVABILITY T2) | The API Gateway access-log format echoes `X-Correlation-Id` back into CloudWatch Logs, so a single correlation lookup works edge-to-app. |
| `application.properties` `management.otlp.tracing.endpoint=${OTLP_TRACING_ENDPOINT:http://localhost:4318/v1/traces}` | Already environment-variable-driven; the ECS task definition sets `OTLP_TRACING_ENDPOINT=http://localhost:4318/v1/traces` to point at the ADOT sidecar. |
| `app.messaging.kafka.enabled` gate + the `aws` profile that F-AWS introduces | Reuses AD-025. The `aws` profile only needs to set the SASL/IAM auth properties and the CloudWatch registry properties. |

---

## Data Models — Resource Naming

All resources follow `${var.app_name}-${var.environment}-${suffix}`. With the
defaults (`app_name = "invoice-generator"`, `environment = "dev"`) this yields:

| Resource | Name |
| --- | --- |
| VPC | `invoice-generator-dev-vpc` |
| Public subnets | `invoice-generator-dev-public-{a,b,c}` |
| Private subnets | `invoice-generator-dev-private-{a,b,c}` |
| App SG | `invoice-generator-dev-app-sg` |
| MSK SG | `invoice-generator-dev-msk-sg` |
| ALB SG | `invoice-generator-dev-alb-sg` |
| MSK cluster | `invoice-generator-dev-msk` |
| MSK config | `invoice-generator-dev-msk-config` |
| MSK KMS key alias | `alias/invoice-generator-dev-msk` |
| ECR repo | `invoice-generator` (no env suffix — image registry is cross-env) |
| ECS cluster | `invoice-generator-dev` |
| Task definition family | `invoice-generator-dev` |
| Task execution role | `invoice-generator-dev-task-execution` |
| Task role | `invoice-generator-dev-task` |
| ALB | `invoice-generator-dev-alb` |
| Target group | `invoice-generator-dev-tg` |
| API Gateway | `invoice-generator-dev-api` |
| API stage | `default` |
| CloudWatch dashboard | `invoice-generator-dev-slis` |
| X-Ray group | `invoice-generator-dev` |

---

## Error Handling Strategy

| Scenario | Handling |
| --- | --- |
| `terraform validate` fails on a module | Gate fails. Fix the HCL; no module ships broken. |
| `terraform fmt -recursive -check` fails | Run `terraform fmt -recursive`. Always idempotent. |
| Reviewer runs `terraform plan` without AWS creds | Provider errors out at plan time with a clear "No valid credential sources found". The architecture doc tells them how to wire AWS SSO or a profile for plan-only context. |
| ADOT sidecar fails to start in the task | App container starts anyway (the sidecar is `essential = false`). Traces are lost; logs and metrics still flow. Documented in the runbook. |
| MSK broker AZ outage | Two surviving brokers honour `min.insync.replicas = 2` for the `RF=3` topics the proposal creates. Producer continues without re-election; consumer rebalances. |

---

## Verification Plan

Per task, after editing:

```bash
cd infra/terraform
~/.local/bin/terraform fmt -recursive
~/.local/bin/terraform init -backend=false
~/.local/bin/terraform validate
```

End-to-end after T5:

```bash
~/.local/bin/terraform fmt -recursive -check       # exit 0 (idempotent)
~/.local/bin/terraform validate                    # exit 0 across the whole tree
grep -rE '^module "' main.tf | wc -l               # 5
ls modules/ | wc -l                                # 5
```

Then a manual read of `docs/aws-architecture.md`: diagram renders, services table is
complete, ADRs cover MSK / Fargate / awslogs / auth-deferral / proposal-grade, cost
estimate is a single paragraph with an order-of-magnitude number, runbook fits on one
screen.

---

## Open Questions (none blocking)

- Should the architecture doc include a CloudWatch dashboard PNG screenshot? **Default:**
  no — the dashboard JSON in the Terraform plus the spec § ARCHITECTURE.md diagram is
  enough. A screenshot would imply a real apply.
- Should we ship a `terraform.tfvars.example`? **Default:** yes, in `infra/terraform/`.
  Three lines (account_id, region, environment).

---

## Sources

- [Amazon MSK SASL/IAM authentication](https://docs.aws.amazon.com/msk/latest/developerguide/iam-access-control.html)
- [Spring for Apache Kafka — SASL/IAM with MSK (aws-msk-iam-auth)](https://github.com/aws/aws-msk-iam-auth)
- [AWS Distro for OpenTelemetry — ECS task definition example](https://aws-otel.github.io/docs/setup/ecs)
- [API Gateway HTTP API + VPC Link](https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-private-integration.html)
- [Micrometer CloudWatch registry](https://docs.micrometer.io/micrometer/reference/implementations/cloudwatch.html)
