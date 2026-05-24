# F-DEPLOY-ACTION Tasks

**Spec:** `.specs/features/deploy-action/spec.md`
**Status:** Done (2026-05-23)
**Granularity policy:** consolidated vertical slices (5 tasks). Matches the
F-OBSERVABILITY / F-AWS precedent — each task is one atomic commit covering one coherent
pipeline slice rather than per-step sub-tasks (per user preference, see
[[feedback_task-granularity]]).
**Retroactive:** the implementation landed in a single working session before this spec
was written. Tasks below reconstruct the logical slices and mark each one ✅ with the
verification evidence captured at the time.

---

## Execution Plan

```
T1 (workflow skeleton + OIDC + commented triggers)
      │
      ├──→ T2 (build-and-test job: ./mvnw verify + JaCoCo artifact)
      │
      ├──→ T3 (terraform-apply job: fmt/init/validate/plan-artifact/apply + outputs)
      │           │
      │           ├──→ root outputs.tf (ecs_cluster_name, ecs_service_name)
      │           │
      │           └──→ T4 (docker-deploy job: ECR push + task-def re-render + rolling deploy + smoke test)
      │
      └──→ T5 (docs: ROADMAP flip + STATE ADR)
```

- **T1** is the dependency root: it lays down the workflow file structure (name,
  triggers, concurrency, permissions, env, jobs scaffolding) so subsequent tasks just
  fill in each job's body.
- **T2, T3, T4** are sequential because they each touch the same `jobs:` block, and
  each later job's `needs:` points at the previous one — keeping diffs reviewable. `[P]`
  is NOT marked.
- **T3** also adds the two new root Terraform outputs the pipeline consumes.
- **T5** closes the loop with the roadmap entry and the STATE ADR.

---

## Task Breakdown

### T1: Workflow skeleton — file, triggers (commented), OIDC permissions, concurrency

**What:** Create `.github/workflows/deploy-aws.yml` with the workflow-level scaffolding:
header comment explaining the proposal-grade posture, the commented `on:` block + inert
`workflow_dispatch` placeholder, `concurrency` group, workflow-level `permissions:`
(`id-token: write` + `contents: read`), and the `env:` block with `AWS_REGION`,
`TF_VERSION`, `JAVA_VERSION`. Empty `jobs:` block to be filled by T2-T4. After this task
the YAML parses but no jobs run.

**Where:**

- `.github/workflows/deploy-aws.yml` (new — workflow-level keys + empty `jobs:`)

**Depends on:** none.

**Reuses:** F-AWS Terraform module names and outputs (referenced in the header comment
as the contract); the `payloads/teste-pf.json` sample payload (referenced as the
eventual smoke-test fixture).

**Requirements covered:** DEPLOY-01, DEPLOY-02, DEPLOY-04, DEPLOY-05, DEPLOY-07.

**Done when:**

- [x] File exists at `.github/workflows/deploy-aws.yml`.
- [x] Header comment (lines 1-22) describes the three-job pipeline, OIDC auth model,
      required repo secrets (`AWS_DEPLOY_ROLE_ARN`, `AWS_ACCOUNT_ID`) and variables
      (`AWS_REGION`, `TF_STATE_BUCKET`, `TF_STATE_LOCK_TABLE`, `ENVIRONMENT`).
- [x] `on:` block is fully commented; an inert `on: workflow_dispatch` line keeps the
      YAML valid (GitHub rejects workflows with no `on:`).
- [x] `concurrency: { group: deploy-aws-${{ github.ref }}, cancel-in-progress: false }`
      prevents two deploys racing on the same ref.
- [x] `permissions:` block declares `id-token: write` + `contents: read` (least
      privilege; everything else implicit-denied).
- [x] `env.AWS_REGION` reads from `vars.AWS_REGION` with `'us-east-1'` fallback.
- [x] `ruby -ryaml -e 'YAML.load_file(ARGV[0]); puts "ok"' .github/workflows/deploy-aws.yml`
      exits 0.

**Tests:** YAML-parse check (above). No unit tests applicable to a workflow file.
**Gate:** YAML parses cleanly.

---

### T2: build-and-test job — Maven verify gate + JaCoCo artifact

**What:** Add the first job. Checks out the repo, sets up JDK 21 with Maven cache, runs
`./mvnw -B -ntp verify` (Spotless + Checkstyle + tests + JaCoCo — the same gate
documented in `CLAUDE.md`), and uploads the JaCoCo HTML report as an `actions/upload-artifact@v4`
artifact for reviewer auditing.

**Where:**

- `.github/workflows/deploy-aws.yml` — adds `jobs.build-and-test`.

**Depends on:** T1.

**Reuses:** the existing `./mvnw verify` gate (F-UPGRADE / F-OBSERVABILITY / F-AWS all
use it locally); the `target/site/jacoco/` report path (set up under F-UPGRADE).

**Requirements covered:** DEPLOY-03 (job 1 of 3).

**Done when:**

- [x] Job named `build-and-test` runs on `ubuntu-latest`.
- [x] `actions/setup-java@v4` with `distribution: temurin`, `java-version: ${{ env.JAVA_VERSION }}`,
      `cache: maven`.
- [x] One step runs `./mvnw -B -ntp verify` (batch mode + no-transfer-progress for
      readable logs).
- [x] JaCoCo report uploaded under artifact name `jacoco-report`, with
      `if-no-files-found: ignore` so an unchanged module doesn't fail the upload.
- [x] Job has no AWS credential step (it doesn't need OIDC).

**Tests:** workflow YAML parses; logical correctness verified by reading the diff
against the F-UPGRADE / F-OBSERVABILITY verify-step pattern.
**Gate:** YAML still parses cleanly.

---

### T3: terraform-apply job — fmt/init/validate/plan-artifact/apply + new root outputs

**What:** Add the Terraform job. OIDC-auths into AWS, sets up Terraform with a pinned
version, runs the F-AWS gate (`fmt -recursive -check` → `init` with backend config from
repo variables → `validate` → `plan -out=tfplan`), uploads the binary plan as an
artifact, then `apply -auto-approve tfplan`. Captures `ecr_repository_url`,
`ecs_cluster_name`, `ecs_service_name`, `api_endpoint` as job-level outputs for the
downstream `docker-deploy` job. Adds the two new root Terraform outputs
(`ecs_cluster_name`, `ecs_service_name`) to `infra/terraform/outputs.tf` so the
pipeline can read them.

**Where:**

- `.github/workflows/deploy-aws.yml` — adds `jobs.terraform-apply` with `needs:
  build-and-test`, `defaults.run.working-directory: infra/terraform`, and
  `outputs:` mapping `ecr_repo`/`cluster`/`service`/`api_endpoint`.
- `infra/terraform/outputs.tf` — adds two new outputs.

**Depends on:** T1, T2 (via `needs:`).

**Reuses:** F-AWS Terraform tree (`infra/terraform/`), the `ecs` module's existing
`cluster_name` / `service_name` outputs, the F-AWS gate command shape documented in
`infra/terraform/README.md`.

**Requirements covered:** DEPLOY-03 (job 2 of 3), DEPLOY-05, DEPLOY-06, DEPLOY-08, DEPLOY-09,
DEPLOY-10, DEPLOY-11, DEPLOY-18, DEPLOY-19, DEPLOY-20.

**Done when:**

- [x] Job named `terraform-apply` with `needs: build-and-test`.
- [x] `aws-actions/configure-aws-credentials@v4` step with `role-to-assume:
      ${{ secrets.AWS_DEPLOY_ROLE_ARN }}` and `aws-region: ${{ env.AWS_REGION }}`. No
      access-key inputs anywhere.
- [x] `hashicorp/setup-terraform@v3` with `terraform_version: ${{ env.TF_VERSION }}`
      (pinned to `1.9.5`).
- [x] Step order: `fmt -recursive -check` → `init` (backend-config from repo
      variables: `TF_STATE_BUCKET`, `TF_STATE_LOCK_TABLE`, `AWS_REGION`,
      `ENVIRONMENT`) → `validate` → `plan -input=false -out=tfplan` (with
      `TF_VAR_account_id` / `TF_VAR_region` / `TF_VAR_environment` env vars) →
      `actions/upload-artifact@v4` for `tfplan` (7-day retention) → `apply
      -input=false -auto-approve tfplan`.
- [x] Final step captures four `terraform output -raw ...` values and writes them to
      `$GITHUB_OUTPUT` as `ecr_repo` / `cluster` / `service` / `api_endpoint`.
- [x] `outputs:` block at the job level re-exposes those for downstream jobs.
- [x] `infra/terraform/outputs.tf` declares `ecs_cluster_name` and `ecs_service_name`
      with descriptions naming the GitHub Actions deploy workflow as the consumer.
- [x] F-AWS gate stays green: `cd infra/terraform && terraform fmt -recursive -check
      && terraform init -backend=false -input=false && terraform validate`.

**Tests:** F-AWS gate command shows no regression. Workflow YAML parses.
**Gate:** F-AWS gate (`terraform fmt -recursive -check + init -backend=false + validate`).

---

### T4: docker-deploy job — ECR push + live task-def re-render + rolling deploy + smoke test

**What:** Add the third job. OIDC-auths into AWS, logs into ECR via
`aws-actions/amazon-ecr-login@v2`, sets up Docker Buildx with GHA layer cache, builds
and pushes a `linux/amd64` image tagged with `${{ github.sha }}` (and `:latest`).
Renders a new task definition by describing the **live** revision and swapping the `app`
container's image via `jq` (preserving every Terraform-managed field), strips the fields
AWS rejects on register, then deploys via
`aws-actions/amazon-ecs-deploy-task-definition@v2` with
`wait-for-service-stability: true, wait-for-minutes: 10`. Closes with a smoke test
running `curl -fsS --retry 5` against the API Gateway endpoint with the existing
`payloads/teste-pf.json` payload.

**Where:**

- `.github/workflows/deploy-aws.yml` — adds `jobs.docker-deploy` with `needs:
  terraform-apply`.

**Depends on:** T1, T3 (consumes T3's job outputs).

**Reuses:** the F-DEFECTS-PERFORMANCE production Dockerfile (`./Dockerfile`); the
F-SAFETY-NET / F-OBSERVABILITY fixture `src/main/resources/payloads/teste-pf.json`; the
F-AWS `ecs` module's existing ECR repository, ECS cluster, and ECS service.

**Requirements covered:** DEPLOY-03 (job 3 of 3), DEPLOY-12, DEPLOY-13, DEPLOY-14, DEPLOY-15,
DEPLOY-16, DEPLOY-17.

**Done when:**

- [x] Job named `docker-deploy` with `needs: terraform-apply`.
- [x] OIDC auth step (identical pattern to T3).
- [x] `aws-actions/amazon-ecr-login@v2` + `docker/setup-buildx-action@v3`.
- [x] `docker buildx build --platform linux/amd64 --tag $ECR_REPO:$IMAGE_TAG --tag
      $ECR_REPO:latest --cache-from type=gha --cache-to type=gha,mode=max --push .`
      with `IMAGE_TAG=${{ github.sha }}`.
- [x] Task-def re-render: `aws ecs describe-task-definition` reads the live revision
      (resolved via `aws ecs describe-services --query 'services[0].taskDefinition'`)
      → `jq` swaps `containerDefinitions[name=="app"].image` to the new SHA-tagged URI
      → deletes the six register-rejected fields
      (`taskDefinitionArn`, `revision`, `status`, `requiresAttributes`,
      `compatibilities`, `registeredAt`, `registeredBy`).
- [x] `aws-actions/amazon-ecs-deploy-task-definition@v2` with
      `wait-for-service-stability: true`, `wait-for-minutes: 10`.
- [x] Smoke test step reads `$API` from `needs.terraform-apply.outputs.api_endpoint`
      and runs `curl -fsS --retry 5 --retry-delay 6 --retry-connrefused -X POST
      "$API/api/orders/generate-invoice" -H 'Content-Type: application/json' -d
      @src/main/resources/payloads/teste-pf.json`.

**Tests:** Workflow YAML parses cleanly; logical correctness verified by reading the
ECS deploy step against `aws-actions/amazon-ecs-deploy-task-definition` v2 input schema.
**Gate:** YAML parses cleanly.

---

### T5: Docs — ROADMAP entry + STATE ADR

**What:** Add F-DEPLOY-ACTION to the M3 milestone in `ROADMAP.md` with status COMPLETE and a
one-paragraph summary mirroring the F-AWS / F-POSTMAN entries. Add a new ADR to
`STATE.md` (AD-031) documenting the F-DEPLOY-ACTION design decisions: OIDC over committed keys,
commented triggers for proposal-grade posture, rolling deploy over blue/green, live
task-def re-render over template injection.

**Where:**

- `.specs/project/ROADMAP.md` — new entry under M3 between F-AWS and Future Considerations.
- `.specs/project/STATE.md` — new ADR; updates `Last Updated` and `Current Work`.

**Depends on:** T1, T2, T3, T4 (closes the loop).

**Reuses:** the F-AWS / F-POSTMAN ROADMAP entry shape; the AD-NNN numbering convention
already used in STATE.md.

**Requirements covered:** spec.md §Success Criteria items 5 and 6.

**Done when:**

- [x] `ROADMAP.md` lists F-DEPLOY-ACTION under M3 with status COMPLETE (2026-05-23) and a
      bullet list summarising the three jobs, OIDC, commented triggers, and the two new
      Terraform outputs.
- [x] `STATE.md` `Recent Decisions` gets a new ADR (AD-031) titled "F-DEPLOY-ACTION scope —
      proposal-grade GitHub Actions deploy pipeline, OIDC, commented triggers" with
      the standard Decision / Reason / Trade-off / Impact fields.
- [x] `STATE.md` `Last Updated` and `Current Work` reflect F-DEPLOY-ACTION completion.

**Tests:** none (docs-only).
**Gate:** ROADMAP + STATE diff reviews cleanly.
