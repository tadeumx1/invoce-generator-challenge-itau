# Quick Task 005 — AWS icons for architecture diagrams in draw.io

**Feature:** F-AWS.
**Scope:** Visual fidelity of architecture diagrams when presented in draw.io.

## Why

The mermaid blocks in `docs/aws-architecture-diagram.md` render in draw.io as
generic colored rectangles. Presenters using draw.io as their canvas lose the
"at-a-glance" recognition the AWS icon set provides.

Two source-side workarounds were investigated and ruled out empirically on
2026-05-25:

- `architecture-beta` + iconify (`logos:aws-*` / `aws:*`) — mermaid.live (free)
  does not pre-register the icon packs, so every icon falls back to `?`.
  Confirmed by pasting a test block into mermaid.live. draw.io's mermaid also
  does not parse `architecture-beta`.
- `flowchart LR` + inline `<img>` tags pointing at AWS official icons on
  `icon.icepanel.io` — draw.io's mermaid importer sanitizes the HTML, so the
  `<img>` markup is shown as literal text. Confirmed by pasting into draw.io.

Conclusion: AWS iconography cannot be embedded in mermaid in a way that
survives draw.io's importer. A native `.drawio` file using the built-in AWS
shape stencils is the only path that puts real AWS icons on the draw.io
canvas.

## What ships

- New file `docs/aws-architecture-diagrams.drawio` with four pages, one per
  mermaid diagram in the project:
  - **Main** (from `aws-architecture-diagram.md`) — Client + AWS group with
    API Gateway, Fargate, MSK, CloudWatch nodes.
  - **Structural** (from `aws-architecture-diagram.md`) — Client + AWS
    region/VPC group with API Gateway, VPC Link, ALB, ECS Fargate sub-group
    (app + ADOT/X-Ray), MSK sub-group (3 brokers), ECR, KMS, and a Managed
    Observability sub-group (CloudWatch Logs/Metrics/Dashboard + X-Ray).
  - **Sequence** (from `aws-architecture-diagram.md`) — 6 participants
    (Client, API Gateway, Fargate app, MSK, KafkaListeners, CloudWatch/X-Ray)
    with AWS icons on each participant header, lifelines, 15 numbered
    messages, two notes, and a `par` block for the async consumption fan-out.
  - **Architecture (reviewer)** (from `aws-architecture.md`) — variant
    structural view matching the reviewer write-up's labels (no ADR-032
    callout on API Gateway; "3 private subnets" on ALB; explicit Client
    subgraph; per-broker `encrypts` edges from KMS).
- All shapes use `mxgraph.aws4.resourceIcon` with the verified canonical
  stencil names (`api_gateway`, `fargate`, `managed_streaming_for_kafka`,
  `cloudwatch`, `cloudwatch_logs`, `xray`, `ecr`, `key_management_service`,
  `virtual_private_cloud`, `application_load_balancer`).
- The mermaid blocks in `docs/aws-architecture-diagram.md` and
  `docs/aws-architecture.md` are reverted to the original `classDef`-colored
  versions so they render cleanly on GitHub.
- A short callout at the top of each affected doc points presenters at the
  `.drawio` file and explains why mermaid alone can't carry the AWS icons
  through draw.io.

## Done when

- `docs/aws-architecture-diagrams.drawio` opens in draw.io showing four pages
  with real AWS icons on the canvas.
- The mermaid blocks in `aws-architecture-diagram.md` and
  `aws-architecture.md` are byte-identical to the pre-task baseline.
- Presenter callouts added in both docs and links resolve.
- No iconify pack registration required by the reader.
