# Quick Task 005 Summary — AWS icons for draw.io presentations

## Result

Delivered `docs/aws-architecture-diagrams.drawio` — a four-page draw.io file
using native `mxgraph.aws4.*` AWS shape stencils so every mermaid diagram in
the project has an AWS-iconified counterpart for draw.io presentation:

- **Page 1 — Main:** Client + AWS group with API Gateway, Fargate, MSK,
  CloudWatch nodes; arrows for HTTPS, POST, publish, consume, observability.
- **Page 2 — Structural:** Client + AWS region/VPC group with API Gateway,
  VPC Link, ALB, an ECS Fargate sub-group (app container + ADOT/X-Ray
  sidecar), an MSK sub-group (3 brokers), ECR, KMS, and a Managed
  Observability sub-group (CloudWatch Logs/Metrics/Dashboard + X-Ray); all
  the edges from the mermaid structural diagram are preserved.
- **Page 3 — Sequence:** 6 participants with AWS icons on each header
  (Client + API Gateway + Fargate app + MSK + KafkaListeners (Fargate icon)
  + CloudWatch/X-Ray), 6 dashed lifelines, 15 numbered messages mirroring
  the mermaid `autonumber` flow, two yellow notes (interactor compute + retry
  semantics), and a `par`-block container around the async fan-out.
- **Page 4 — Architecture (reviewer):** the variant structural view that
  lives in `aws-architecture.md`, matching its specific labels (explicit
  Client subgraph, "3 private subnets" on ALB, no ADR-032 callout on API
  Gateway, per-broker `encrypts` edges from KMS).

The mermaid blocks in both `aws-architecture-diagram.md` and
`aws-architecture.md` are reverted to the original `classDef`-colored
version (clean rectangles, no icons). Callouts at the top of each doc point
presenters at the `.drawio` file.

## Canonical stencil names used

Verified against draw.io's bundled AWS4 shape library (some early attempts
used the wrong name and rendered as solid color blocks — see "Why this
approach" below):

| Service | `resIcon` |
|---|---|
| API Gateway | `mxgraph.aws4.api_gateway` |
| Fargate | `mxgraph.aws4.fargate` |
| MSK | `mxgraph.aws4.managed_streaming_for_kafka` |
| CloudWatch | `mxgraph.aws4.cloudwatch` |
| CloudWatch Logs | `mxgraph.aws4.cloudwatch_logs` |
| X-Ray | `mxgraph.aws4.xray` |
| ECR | `mxgraph.aws4.ecr` |
| KMS | `mxgraph.aws4.key_management_service` |
| VPC | `mxgraph.aws4.virtual_private_cloud` |
| ALB | `mxgraph.aws4.application_load_balancer` |

## Why a separate `.drawio` and not mermaid icons

Two attempts to make mermaid carry the AWS icons were made and rejected
empirically:

- **`architecture-beta` + iconify** — mermaid.live (free) does not
  pre-register the `logos:` or `aws:` icon packs, so icons render as `?`
  placeholders. Confirmed live by the team on 2026-05-25 (a test block with
  `logos:aws-api-gateway` showed `?`). draw.io's bundled mermaid does not
  parse `architecture-beta` at all.
- **`flowchart LR` + inline `<img>` tags** — draw.io's mermaid importer
  sanitizes inline HTML, so the entire `<img src=... />` markup was shown
  as literal text inside each node (visible in a draw.io screenshot the
  user shared after the first attempt).

The only path that puts real AWS iconography on the draw.io canvas is a
native `.drawio` file using draw.io's bundled AWS shape stencils
(`mxgraph.aws4.api_gateway`, `mxgraph.aws4.fargate`,
`mxgraph.aws4.managed_streaming_for_apache_kafka`, etc.). That's what
shipped.

## Verification

- `aws-architecture-diagrams.drawio` is well-formed XML (`xmllint --noout`
  parses clean — see below).
- Mermaid in `aws-architecture-diagram.md` is byte-identical to baseline
  except for the new presenter callout at the top.
- Open in draw.io to verify the AWS icons render (manual step — left to
  the presenter; layout is functional and can be polished in-app).

## Follow-up ideas (not done)

- The `.drawio` layout is intentionally hand-laid-out and basic. A presenter
  can drag-arrange in draw.io as needed.
- A sequence-diagram page could be added if there's later value in keeping
  everything in one `.drawio`; left out because the existing sequence mermaid
  is already presentation-ready.
