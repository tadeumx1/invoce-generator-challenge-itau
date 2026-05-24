# F-AWS Tasks

**Design:** `.specs/features/aws/design.md`
**Spec:** `.specs/features/aws/spec.md`
**Status:** Done (2026-05-23)
**Granularity policy:** consolidated vertical slices (5 tasks). Per user preference,
each task is one atomic commit covering one coherent AWS-plane slice rather than
per-resource sub-tasks.

---

## Execution Plan

```
T1 (foundation + network) ──┬──→ T2 (msk)            ─┐
                             ├──→ T3 (ecs platform)   ─┼──→ T4 (api-gateway + observability)
                             └──→ ────────────────────┘
                                                         │
                                                         ▼
                                                       T5 (docs + ROADMAP/STATE flip)
```

- **T1** is the dependency root. It ships `versions.tf` / `providers.tf` /
  `variables.tf` / `locals.tf` / `main.tf` plus the entire `network` module. After
  T1 the tree validates clean against the `aws` provider and a `terraform plan` of
  the network module is realistic.
- **T2, T3** can be reviewed independently after T1 — they share no files. We run
  them **sequentially** for diff hygiene; `T2 → T3` is the canonical order because
  T3 depends on T2's `bootstrap_brokers_sasl_iam` output.
- **T4** depends on T2 + T3 (api-gateway integrates the ALB from T3; observability
  references both MSK and ECS metric namespaces).
- **T5** wraps up `docs/aws-architecture.md`, the CLAUDE/README cross-links, and the
  ROADMAP/STATE flips.

---

## Task Breakdown

### T1: Foundation — versions/providers/variables/locals + `network` module

**What:** Bootstrap the Terraform tree so `terraform init -backend=false && terraform
validate` exits 0. Provision the VPC (`10.42.0.0/16`), three public subnets, three
private subnets, IGW, single NAT, route tables, and the three security groups (app,
MSK, ALB) with the rules described in spec AWS-12..15. After this task, no other
module exists — the root `main.tf` only wires `module "network"`.

**Where:**

- `infra/terraform/versions.tf` (new)
- `infra/terraform/providers.tf` (new — AWS provider w/ `default_tags`)
- `infra/terraform/variables.tf` (new — `account_id`, `region`, `environment`, `app_name`, `vpc_cidr`)
- `infra/terraform/locals.tf` (new — `common_tags`, `name_prefix`)
- `infra/terraform/main.tf` (new — only `module "network"`)
- `infra/terraform/outputs.tf` (new — empty for now, populated by T4)
- `infra/terraform/terraform.tfvars.example` (new — defaults a reviewer can copy)
- `infra/terraform/.gitignore` (new — `.terraform/`, `*.tfstate*`, `terraform.tfvars`)
- `infra/terraform/modules/network/{main,variables,outputs}.tf` (new)
- `infra/terraform/README.md` (new — initial skeleton, expanded by T5)

**Depends on:** none.

**Reuses:** AD-005 (Terraform as IaC), the spec's CIDR plan.

**Requirements covered:** AWS-6, AWS-7, AWS-8 (partial), AWS-9 (partial), AWS-10,
AWS-11, AWS-12, AWS-13, AWS-14, AWS-15.

**Done when:**

- [x] `~/.local/bin/terraform fmt -recursive -check infra/terraform` exits 0.
- [x] `~/.local/bin/terraform -chdir=infra/terraform init -backend=false` exits 0.
- [x] `~/.local/bin/terraform -chdir=infra/terraform validate` exits 0.
- [x] Root `main.tf` has exactly one `module` block (`network`).
- [x] `network` module exports `vpc_id`, `public_subnet_ids`, `private_subnet_ids`,
      `app_security_group_id`, `msk_security_group_id`, `alb_security_group_id`.

**Tests:** `terraform validate` is the gate.
**Gate:** `terraform fmt + init + validate`.

**Commit:** `feat(aws): bootstrap Terraform — providers, VPC, security groups`

---

### T2: `msk` module — MSK cluster + KMS + broker log group

**What:** KMS customer-managed key, `aws_msk_configuration` baking the four-topic
defaults, `aws_msk_cluster` (3 × `kafka.t3.small`, 100 GiB EBS, Kafka 3.6.0, SASL/IAM,
in-transit TLS_PLAINTEXT, at-rest CMK), broker logs to a CloudWatch log group with
30-day retention. Wire the module into the root `main.tf`.

**Where:**

- `infra/terraform/modules/msk/main.tf` (new)
- `infra/terraform/modules/msk/variables.tf` (new — `cluster_name`, `subnet_ids`,
  `security_group_id`, `kafka_version`, `broker_node_count`, `instance_type`,
  `ebs_volume_size_gb`, `tags`)
- `infra/terraform/modules/msk/outputs.tf` (new — `cluster_arn`, `cluster_name`,
  `bootstrap_brokers_sasl_iam`, `kms_key_arn`, `log_group_name`)
- `infra/terraform/main.tf` (modify — add `module "msk"`)

**Depends on:** T1.

**Reuses:** `network.private_subnet_ids` + `network.msk_security_group_id`.

**Requirements covered:** AWS-16, AWS-17, AWS-18, AWS-19, AWS-20, AWS-21.

**Done when:**

- [x] `~/.local/bin/terraform fmt -recursive -check` exits 0.
- [x] `~/.local/bin/terraform validate` exits 0 with the new module.
- [x] `aws_msk_cluster` resource has `client_authentication.sasl.iam = true` and
      `client_authentication.unauthenticated = false`.
- [x] `encryption_info.encryption_in_transit.client_broker = "TLS_PLAINTEXT"`.
- [x] `encryption_info.encryption_at_rest_kms_key_arn` references the local KMS key.
- [x] `bootstrap_brokers_sasl_iam` is exposed as a module output.

**Tests:** `terraform validate`.
**Gate:** `terraform fmt + validate`.

**Commit:** `feat(aws): MSK cluster module (3 × kafka.t3.small, SASL/IAM, KMS-encrypted)`

---

### T3: `ecs` module — ECR + ECS cluster + Fargate service + ALB + IAM roles

**What:** ECR repo (`invoice-generator`), ECS cluster + Fargate task definition (app
container + ADOT sidecar), service (desired count 2), internal ALB + target group +
listener, two IAM roles (execution + task) with the minimum policies from spec
AWS-25..26.

**Where:**

- `infra/terraform/modules/ecs/main.tf` (new — bulk of the F-AWS HCL lands here)
- `infra/terraform/modules/ecs/variables.tf` (new — `cluster_name`, `vpc_id`,
  `private_subnet_ids`, `app_security_group_id`, `alb_security_group_id`,
  `msk_cluster_arn`, `kafka_bootstrap_brokers`, `aws_region`, `image_tag`,
  `desired_count`, `cpu`, `memory`, `tags`)
- `infra/terraform/modules/ecs/outputs.tf` (new — `cluster_name`, `service_name`,
  `task_role_arn`, `alb_listener_arn`, `target_group_arn`, `ecr_repository_url`,
  `log_group_name`)
- `infra/terraform/main.tf` (modify — add `module "ecs"` after `module "msk"`)

**Depends on:** T2.

**Reuses:** `network.*` outputs, `msk.bootstrap_brokers_sasl_iam`,
`msk.cluster_arn`.

**Requirements covered:** AWS-22, AWS-23, AWS-24, AWS-25, AWS-26, AWS-27.

**Done when:**

- [x] `~/.local/bin/terraform fmt -recursive -check` exits 0.
- [x] `~/.local/bin/terraform validate` exits 0 with the new module.
- [x] Task definition has exactly two containers: `app` (`essential = true`) and
      `aws-otel-collector` (`essential = false`).
- [x] App container env contains `KAFKA_BOOTSTRAP_SERVERS`, `OTLP_TRACING_ENDPOINT`,
      `SPRING_PROFILES_ACTIVE`, `AWS_REGION`.
- [x] Task role policy is scoped: `kafka-cluster:*` only on `msk_cluster_arn`,
      `cloudwatch:PutMetricData` only on `cloudwatch:namespace = InvoiceGenerator`,
      `xray:Put*` unscoped (per X-Ray docs).
- [x] `alb_listener_arn` is exposed as a module output.

**Tests:** `terraform validate`.
**Gate:** `terraform fmt + validate`.

**Commit:** `feat(aws): ECS Fargate platform — ECR, cluster, task def with ADOT sidecar, ALB`

---

### T4: `api-gateway` + `observability` modules

**What:** API Gateway HTTP API + VPC Link + integration + access logging stage; and
the observability module (4-SLI CloudWatch dashboard, one alarm per SLI, X-Ray group
+ sampling rule). These are two physically separate modules; they ship together
because they both depend on T3 and they're each small.

**Where:**

- `infra/terraform/modules/api-gateway/main.tf` (new)
- `infra/terraform/modules/api-gateway/variables.tf` (new — `api_name`,
  `private_subnet_ids`, `alb_security_group_id`, `alb_listener_arn`, `tags`)
- `infra/terraform/modules/api-gateway/outputs.tf` (new — `api_endpoint`, `api_id`,
  `stage_name`, `access_log_group_name`)
- `infra/terraform/modules/observability/main.tf` (new — dashboard widgets +
  alarms + X-Ray)
- `infra/terraform/modules/observability/variables.tf` (new — `dashboard_name`,
  `ecs_cluster_name`, `ecs_service_name`, `api_id`, `api_stage_name`,
  `msk_cluster_name`, `aws_region`, `tags`)
- `infra/terraform/modules/observability/outputs.tf` (new — `dashboard_arn`,
  `xray_group_arn`)
- `infra/terraform/main.tf` (modify — add `module "api_gateway"` + `module
  "observability"`)
- `infra/terraform/outputs.tf` (modify — surface `api_endpoint`)

**Depends on:** T3 (and transitively T2 + T1).

**Reuses:** `ecs.alb_listener_arn`, `ecs.cluster_name`, `ecs.service_name`,
`msk.cluster_name`, `api_gateway.api_id`/`stage_name`. Reuses `docs/observability.md`
Prometheus queries — translated to CloudWatch metric math, verbatim.

**Requirements covered:** AWS-28, AWS-29, AWS-30, AWS-31, AWS-32, AWS-33, AWS-34,
AWS-35, AWS-36.

**Done when:**

- [x] `~/.local/bin/terraform fmt -recursive -check` exits 0.
- [x] `~/.local/bin/terraform validate` exits 0 with both new modules.
- [x] Root `outputs.tf` exposes `api_endpoint`.
- [x] Dashboard JSON contains exactly four widgets named `SLI-1` / `SLI-2` /
      `SLI-3` / `SLI-4`.
- [x] `aws_cloudwatch_metric_alarm` × 4 exists; each has
      `comparison_operator = "LessThanThreshold"` and a `threshold` matching the SLO
      from the spec (0.995 / 0.99 / 0.999 / 0.95).
- [x] `aws_xray_sampling_rule` has `fixed_rate = 0.1` and `priority = 9000`.

**Tests:** `terraform validate`.
**Gate:** `terraform fmt + validate`.

**Commit:** `feat(aws): API Gateway HTTP API + CloudWatch dashboards/alarms + X-Ray`

---

### T5: `docs/aws-architecture.md` + ROADMAP/STATE flip

**What:** Write the reviewer-facing architecture doc. Cross-link from `CLAUDE.md` and
`README.md`. Flip the ROADMAP entry for F-AWS to COMPLETE. Update STATE.md Current
Work and add the AD-030 entry documenting the proposal-grade decision.

**Where:**

- `docs/aws-architecture.md` (new)
- `infra/terraform/README.md` (modify — replace T1's skeleton with the full ops
  walkthrough: how to plan, how to apply once an account is wired, the deferred
  follow-ups)
- `CLAUDE.md` (modify — add a one-line link to `docs/aws-architecture.md` and a
  note that F-AWS is complete)
- `README.md` (modify — F-AWS entry under M3 → COMPLETE; add a top-level
  "AWS deployment" subsection pointing at the doc + the Terraform tree)
- `.specs/features/aws/tasks.md` (modify — flip Status: Draft → Done; tick boxes)
- `.specs/project/ROADMAP.md` (modify — F-AWS PLANNED → COMPLETE with completion date)
- `.specs/project/STATE.md` (modify — update Current Work, add AD-030 capturing the
  proposal-grade Terraform decision + the MSK / Fargate choices made interactively)

**Depends on:** T1, T2, T3, T4 (must reference real module structure).

**Reuses:** the Mermaid diagram from `design.md`, the 4 Prometheus queries from
`docs/observability.md`, the existing ROADMAP/STATE/CLAUDE conventions used by F-OBSERVABILITY.

**Requirements covered:** AWS-1 (architecture doc), AWS-2 (services table), AWS-3
(ADRs), AWS-4 (cost), AWS-5 (runbook), AWS-30 (auth deferral note).

**Done when:**

- [x] `docs/aws-architecture.md` opens with a Mermaid diagram.
- [x] Services table lists every AWS service used by the Terraform with: purpose,
      local-stack equivalent, link to the Terraform module.
- [x] ADRs section has ≥ 4 entries (MSK over SQS, Fargate over Lambda, awslogs over
      Firelens, auth-deferred Cognito vs JWT-verifier).
- [x] Cost section gives an order-of-magnitude monthly number with a one-line
      breakdown.
- [x] Runbook section fits on one screen: `terraform init/plan/apply` + smoke test.
- [x] `infra/terraform/README.md` has the gate command (`terraform fmt + validate`)
      and a deferred-follow-ups list.
- [x] `ROADMAP.md` F-AWS PLANNED → COMPLETE (2026-05-23).
- [x] `STATE.md` Current Work + AD-030 added.
- [x] `.specs/features/aws/tasks.md` Status Draft → Done; every Done-When box ticked.

**Tests:** none (documentation + status flips).
**Gate:** human read-through.

**Commit:** `docs(aws): architecture proposal, runbook; F-AWS complete`

---

## Parallel Execution Map

```
T1 (Sequential, foundational)
  │
  ▼
T2 ──→ T3 ──→ T4   (sequential — each depends on the previous module's outputs)
                │
                ▼
              T5   (sequential — depends on all)
```

`[P]` is not marked on any task; they each modify the root `main.tf` and would
conflict if run concurrently. The total volume per task is small enough that
sequential execution stays comfortably under one commit per task.

---

## Task Granularity Check

Per the active feedback memory ([[feedback-task-granularity]]), granularity is judged
by **vertical-slice cohesion**, not per-resource.

| Task | Scope | Vertical-slice cohesion | Status |
| --- | --- | --- | --- |
| T1 | Tree bootstrap + VPC + SGs | Single "Terraform validates clean and a VPC exists" slice. | ✅ Cohesive |
| T2 | MSK cluster + KMS + log group | Single "messaging plane is provisioned" slice. | ✅ Cohesive |
| T3 | ECR + ECS cluster + task def + service + ALB + IAM | Single "compute plane runs the app" slice. Large but cohesive. | ✅ Cohesive |
| T4 | API Gateway + observability modules | Two small modules, both depending on T3, both required for "the AWS plane is observable from the public edge". | ✅ Cohesive |
| T5 | Architecture doc + ROADMAP/STATE flip | Single "proposal is reviewable + closed" slice. | ✅ Cohesive |

---

## Diagram-Definition Cross-Check

| Task | Depends on (body) | Diagram shows | Status |
| --- | --- | --- | --- |
| T1 | none | (root) | ✅ Match |
| T2 | T1 | T1 → T2 | ✅ Match |
| T3 | T2 | T2 → T3 | ✅ Match (T1 flows transitively) |
| T4 | T3 | T3 → T4 | ✅ Match (T1+T2 flow transitively) |
| T5 | T1, T2, T3, T4 | T4 → T5 | ✅ Match |

---

## Pre-execution Asks

Already resolved (2026-05-23) — captured in spec § Scope Decision Matrix:

1. **Terraform realism:** Proposal-grade (validate-clean, no apply).
2. **Messaging plane:** Amazon MSK (3 × `kafka.t3.small`).
3. **Compute:** ECS Fargate only.
4. **Validation tooling:** terraform 1.9.8 installed locally at `~/.local/bin/terraform`.

No further blockers before T1.
