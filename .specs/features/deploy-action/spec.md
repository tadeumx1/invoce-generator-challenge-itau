# F-DEPLOY-ACTION — GitHub Actions AWS Deploy Pipeline Specification

**Status:** COMPLETE (2026-05-23)
**Scope decision (user, 2026-05-23):** **Proposal-grade pipeline**, **triggers commented out**.
The workflow validates as runnable GitHub Actions YAML but never fires against a live AWS
account — same posture as F-AWS's Terraform (validate-clean, not apply-tested).

## Problem Statement

F-AWS shipped reviewable Terraform + an architecture write-up, but explicitly listed
"CI/CD pipeline (GitHub Actions, CodePipeline, etc.)" as out-of-scope, calling it a
"separate concern" (see F-AWS spec §Out of Scope). The repository therefore has no
artifact showing **how** the application would actually move from a commit on `main` to a
running ECS task — only **where** it would run.

F-DEPLOY-ACTION closes that gap with a single GitHub Actions workflow that a reviewer can read in
five minutes and understand the full path: Maven verify → Terraform apply → image push
to ECR → ECS rolling deployment → smoke test against the live API Gateway endpoint. The
workflow uses GitHub OIDC for AWS auth (no long-lived access keys committed as repo
secrets), which is the production-grade pattern any SRE team would expect.

This feature does not change application code or Terraform; it produces:

1. **`.github/workflows/deploy-aws.yml`** — three-job pipeline with `on:` triggers
   commented out and an inert `workflow_dispatch` placeholder so the YAML stays
   GitHub-parseable. Concurrency-guarded so two deploys cannot race on the same ref.
2. **Two new root Terraform outputs** (`ecs_cluster_name`, `ecs_service_name`) so the
   pipeline can read the live ECS resource names via `terraform output -raw` without
   re-deriving them.

## Scope Decision Matrix

| Choice                | Selected                                                 | Rationale                                                                                                                                                                                                                                            |
| --------------------- | -------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Pipeline realism      | **Proposal-grade** (commented triggers, never runs)      | Same posture as F-AWS Terraform. Running against a real account would require an AWS account, MSK spend, and the OIDC trust policy bootstrapped — out of the challenge contract. The workflow YAML must still parse cleanly (`yaml.safe_load` green). |
| Auth                  | **GitHub OIDC → IAM role**                               | Long-lived `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` in repo secrets is the legacy pattern. OIDC removes the rotation burden and the secret-leakage blast radius; AWS, GitHub, and Terraform docs all default to it in 2026. |
| CI/CD platform        | **GitHub Actions**                                       | Repo is on GitHub, no other CI is configured. CodePipeline / Jenkins / Buildkite would add a second control plane for no benefit on a challenge project.                                                                          |
| Deploy strategy       | **ECS rolling update via new task-def revision**         | The ECS service module already configures `deployment_minimum_healthy_percent`/`maximum_percent` for rolling deploys. Blue/green via CodeDeploy adds a second Terraform module and a second deploy-time IAM role; out of scope for the proposal.    |
| Terraform state       | **Remote S3 + DynamoDB lock**                            | The only safe pattern for pipeline-driven Terraform. State backend config is parameterised through repo variables (`TF_STATE_BUCKET`, `TF_STATE_LOCK_TABLE`); F-AWS already documents the same backend in `infra/terraform/README.md`.              |
| Smoke test            | **POST the existing `teste-pf.json` payload**            | Reuses the local-test fixture the README already documents (`curl -X POST .../api/orders/generate-invoice -d @payloads/teste-pf.json`). One curl, one assertion (`curl -fsS`), no new test framework.                                                |

## Goals

- [x] `.github/workflows/deploy-aws.yml` parses as valid GitHub Actions YAML
      (`ruby -ryaml -e 'YAML.load_file(...)'` exits 0).
- [x] Pipeline shape is three jobs with explicit dependencies:
      `build-and-test` → `terraform-apply` → `docker-deploy`.
- [x] AWS authentication uses GitHub OIDC (`permissions: id-token: write` +
      `aws-actions/configure-aws-credentials@v4` with `role-to-assume`). No
      `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` secret references anywhere in the
      workflow.
- [x] The `on:` trigger block is fully commented and a header comment explains why,
      pointing at the F-AWS scope decision. An inert `on: workflow_dispatch`
      placeholder keeps the YAML valid (GitHub rejects workflows with no `on:`).
- [x] `terraform apply` reads its backend config from repo variables
      (`TF_STATE_BUCKET`, `TF_STATE_LOCK_TABLE`, `AWS_REGION`), not hardcoded values.
- [x] Plan output is uploaded as an artifact so reviewers can audit what would have
      been applied before the apply step ran.
- [x] Image tag is the immutable git SHA (`${{ github.sha }}`); `:latest` is pushed as
      a convenience alias only.
- [x] New task definition is rendered by mutating the **live** task def
      (`aws ecs describe-task-definition` + `jq`), not a hand-rolled template — so any
      Terraform-managed fields (env vars, resource sizes, ADOT sidecar) are preserved
      across deploys.
- [x] ECS rolling deploy waits for service stability before the smoke test runs
      (`wait-for-service-stability: true`, 10-minute cap).
- [x] Smoke test uses the existing `src/main/resources/payloads/teste-pf.json` fixture
      via `curl -fsS --retry 5`.
- [x] `ROADMAP.md` adds F-DEPLOY-ACTION under M3 with status COMPLETE.

## Out of Scope

| Item                                                       | Reason                                                                                                                          |
| ---------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| Actually running the pipeline against a real AWS account   | Same constraint as F-AWS: no live account, no MSK spend, no OIDC trust policy bootstrapped. The YAML validates; nothing fires.   |
| PR-level CI (build + test on pull_request)                 | This feature is the **deploy** pipeline. A separate `ci.yml` for PR validation is a one-job follow-up; tracked under Future Considerations. |
| Blue/green via CodeDeploy                                  | Rolling deploys are sufficient for two-task Fargate services. Blue/green needs an ALB listener swap + a CodeDeploy app/group + IAM. |
| Multi-environment (dev/stg/prd) deploy matrix              | Single `ENVIRONMENT` repo variable parameterises the state key. A matrix strategy is mechanical to add once a second env exists. |
| Slack / Teams deploy notifications                         | The workflow run page already shows status. A notifications step is one-line to add later if needed.                            |
| Secret-scanning, SAST, container-image scan (Trivy, Snyk)  | Security tooling deserves its own feature spec and threshold conversation with the user. Captured under Future Considerations.   |
| Rollback workflow                                          | ECS rolling deploy auto-rolls back if `wait-for-service-stability` fails. A manual rollback workflow (re-tag previous SHA) is a follow-up. |
| Cache pruning, ECR lifecycle policy in the workflow        | ECR lifecycle policy belongs in the `ecs` Terraform module, not the pipeline. Captured as a Future Considerations follow-up.    |

---

## User Stories

### P1: Reviewable AWS deploy pipeline ⭐ MVP

**User Story:** As a reviewer of this technical challenge, I want one file that shows
the full deploy path from a `main`-branch commit to a running ECS task, so that I can
validate the CI/CD design without needing a live AWS account.

**Why P1:** F-AWS's reviewer-facing artifact answers "where does it run". F-DEPLOY-ACTION answers
"how does it get there". Together they close the deployment story.

**Acceptance Criteria:**

- [x] **DEPLOY-01:** A single workflow file at `.github/workflows/deploy-aws.yml` contains
      the entire pipeline. No external scripts in `scripts/`, no composite actions —
      everything a reviewer needs to understand is in the workflow.
- [x] **DEPLOY-02:** Workflow file opens with a header comment describing the three-job
      shape, the auth model (OIDC), the required repo secrets (`AWS_DEPLOY_ROLE_ARN`,
      `AWS_ACCOUNT_ID`) and variables (`AWS_REGION`, `TF_STATE_BUCKET`,
      `TF_STATE_LOCK_TABLE`, `ENVIRONMENT`), and the reason the triggers are commented.
- [x] **DEPLOY-03:** Jobs are explicitly named (`build-and-test`, `terraform-apply`,
      `docker-deploy`) and chained via `needs:` so failure short-circuits.
- [x] **DEPLOY-04:** YAML parses cleanly with a stdlib YAML loader.

### P1: Production-grade auth model

**User Story:** As an SRE reviewer, I want the pipeline to use OIDC federation rather
than committed access keys, so that the workflow demonstrates the auth pattern any
modern AWS deploy should use.

**Acceptance Criteria:**

- [x] **DEPLOY-05:** Workflow declares `permissions: id-token: write` + `contents: read`
      at the workflow level (least privilege — only the jobs that need OIDC get the
      token).
- [x] **DEPLOY-06:** Both AWS-touching jobs (`terraform-apply`, `docker-deploy`) call
      `aws-actions/configure-aws-credentials@v4` with `role-to-assume:
      ${{ secrets.AWS_DEPLOY_ROLE_ARN }}` and `aws-region: ${{ env.AWS_REGION }}`. No
      `aws-access-key-id` / `aws-secret-access-key` inputs anywhere.
- [x] **DEPLOY-07:** Header comment documents that the IAM role's trust policy must
      whitelist this repo's `main` branch on the GitHub OIDC provider.

### P2: Deterministic Terraform apply

**User Story:** As a reviewer, I want the Terraform step to be auditable — the plan
preserved as an artifact, the backend config injected from repo variables, and `fmt
-check` + `validate` as guard rails — so that what apply runs is exactly what the plan
showed.

**Acceptance Criteria:**

- [x] **DEPLOY-08:** `terraform fmt -recursive -check` runs before `init`. A formatting
      drift fails the pipeline (matches the local F-AWS gate).
- [x] **DEPLOY-09:** `terraform init` injects S3 backend bucket, DynamoDB lock table,
      region, and per-environment state key via `-backend-config=` flags reading from
      repo variables. No backend config committed to HCL.
- [x] **DEPLOY-10:** `terraform plan -out=tfplan` runs, the binary plan is uploaded as a
      `tfplan` artifact (7-day retention), and `terraform apply tfplan` runs against the
      saved plan so apply cannot diverge from plan.
- [x] **DEPLOY-11:** Plan injects `account_id`, `region`, `environment` via `TF_VAR_*`
      env vars sourced from secrets/variables; no `terraform.tfvars` is committed by the
      pipeline.

### P2: Safe ECS rolling deploy

**User Story:** As a release engineer, I want the new image rolled out without
short-circuiting whatever the live task definition already declares (env vars, resource
sizes, ADOT sidecar), and I want the deploy to fail if the new tasks never reach
healthy.

**Acceptance Criteria:**

- [x] **DEPLOY-12:** Image is tagged with the immutable `${{ github.sha }}` and pushed to
      the ECR URL read from `terraform output -raw ecr_repository_url`. A `:latest`
      alias is also pushed for convenience but is **not** what the task definition
      references.
- [x] **DEPLOY-13:** New task definition is rendered by `aws ecs describe-task-definition`
      on the **live** revision + `jq` swap of the `app` container's `image`, with
      `taskDefinitionArn`/`revision`/`status`/`requiresAttributes`/`compatibilities`/
      `registeredAt`/`registeredBy` stripped before `register-task-definition`.
- [x] **DEPLOY-14:** Deploy uses `aws-actions/amazon-ecs-deploy-task-definition@v2` with
      `wait-for-service-stability: true`, `wait-for-minutes: 10`. Pipeline fails if the
      service does not stabilise.
- [x] **DEPLOY-15:** Buildx pushes a `linux/amd64` image (Fargate platform per F-AWS) and
      uses `type=gha` cache mode `max` for layer reuse across runs.

### P3: Post-deploy smoke test

**User Story:** As an operator, I want the pipeline to prove the new revision actually
serves requests, not just that ECS marked it healthy, by hitting the API Gateway with
the sample payload.

**Acceptance Criteria:**

- [x] **DEPLOY-16:** A `Smoke test` step runs `curl -fsS --retry 5 --retry-delay 6
      --retry-connrefused -X POST $API/api/orders/generate-invoice` against the
      `terraform output -raw api_endpoint` value, using the existing
      `src/main/resources/payloads/teste-pf.json` fixture as the body.
- [x] **DEPLOY-17:** `curl -f` makes the step fail on any 4xx/5xx response. Retries
      tolerate API Gateway → ALB → ECS warm-up jitter without hiding real failures.

### P3: Plumbing — new Terraform root outputs

**User Story:** As the pipeline, I need to read the live ECS cluster name and service
name back from Terraform without re-deriving the naming convention.

**Acceptance Criteria:**

- [x] **DEPLOY-18:** `infra/terraform/outputs.tf` exposes `ecs_cluster_name` and
      `ecs_service_name` (passthrough of the existing `module.ecs.cluster_name` /
      `module.ecs.service_name`), with descriptions that mention the F-DEPLOY-ACTION pipeline as
      the consumer.
- [x] **DEPLOY-19:** Both outputs are non-sensitive (cluster/service names are visible in
      the AWS console) so `terraform output -raw` works without `-no-color` /
      sensitive-handling tricks.
- [x] **DEPLOY-20:** `terraform fmt -recursive -check + init -backend=false + validate`
      stays green (same F-AWS gate, no regression).

---

## Success Criteria

The feature is COMPLETE when:

1. ✅ `.github/workflows/deploy-aws.yml` exists with the three-job pipeline, OIDC
   auth, and commented triggers.
2. ✅ YAML parses cleanly (`ruby -ryaml -e 'YAML.load_file(ARGV[0])' .github/workflows/deploy-aws.yml`).
3. ✅ `infra/terraform/outputs.tf` declares `ecs_cluster_name` and `ecs_service_name`.
4. ✅ F-AWS gate (`terraform fmt -recursive -check + init -backend=false + validate`)
   stays green.
5. ✅ `ROADMAP.md` lists F-DEPLOY-ACTION under M3 with status COMPLETE.
6. ✅ `STATE.md` records the F-DEPLOY-ACTION design decisions (OIDC over keys, commented triggers,
   rolling deploy over blue/green).

---

## Future Considerations

- Separate `.github/workflows/ci.yml` for PR-level build/test validation (one job: `./mvnw verify`).
- Container image scanning (Trivy or Snyk) on every push to ECR.
- ECR lifecycle policy in the `ecs` Terraform module (retain N most-recent tags,
  expire untagged after M days).
- Multi-environment deploy matrix (`dev` / `stg` / `prd`) gated by environment-protection rules.
- Slack notification step on deploy success/failure.
- Manual rollback workflow (`workflow_dispatch` with a SHA input, re-renders the
  task def with `:<sha>` and force-deploys).
- Blue/green via CodeDeploy if zero-downtime invariants ever tighten beyond what
  rolling deploys give.
