# Quick Task 005 Summary — AWS icons in architecture diagrams

## Result

Rewrote two mermaid blocks in `docs/aws-architecture-diagram.md` so that
copy-pasting into draw.io (or any mermaid v10+ renderer) shows AWS's official
service iconography instead of generic colored rectangles:

- **Main Diagram** — `flowchart LR` with inline `<img>` for API Gateway,
  Fargate, MSK, CloudWatch.
- **Diagram 1 — Structural** — `flowchart LR` with inline `<img>` for API
  Gateway, VPC, ELB, Fargate, X-Ray (ADOT sidecar), MSK (×3 brokers), ECR,
  KMS, CloudWatch (×3 surfaces), X-Ray.
- Each rewritten block opens with `%%{init: {'securityLevel': 'loose'}}%%`
  so renderers that default to `strict` still process the HTML labels.
- Dropped the now-redundant `classDef` color buckets — the icons replace the
  role color was playing.
- Added a "Rendering notes" callout at the top of the doc explaining the
  approach and that no iconify pack registration is required.
- **Diagram 2 — Sequence** is byte-identical to before (icons would add noise
  without information in a sequence diagram).

All icons resolve from `icon.icepanel.io/AWS/svg/<category>/<service>.svg` —
a public CDN mirror of AWS's freely licensed architecture icon set.

## Why this approach (vs `architecture-beta`)

`architecture-beta` is mermaid's modern way to declare AWS topology, but it
falls back to `?` placeholders in the two renderers the team actually uses:

- **draw.io's bundled mermaid** does not parse `architecture-beta` at all
  (per draw.io's mermaid feature matrix, May 2026).
- **mermaid.live (free)** parses the syntax but does *not* pre-register the
  iconify `logos:` or `aws:` packs, so every AWS service icon renders as `?`
  — empirically confirmed by the team on 2026-05-25 by pasting a
  `logos:aws-api-gateway` test block into mermaid.live.

`<img>` tags inside flowchart node labels render in every mermaid v10+
renderer that honors `securityLevel: 'loose'`, including draw.io and
mermaid.live. No reader-side pack registration required.

## Verification

- All 10 referenced icon URLs return HTTP 200 (curl batch check).
- Mermaid source parses in mermaid.live with AWS icons visible.
- Sequence diagram preserved verbatim.
- No code paths affected; doc-only change.
