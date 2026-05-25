# Quick Task 005 — AWS icons in architecture mermaid diagrams

**Feature:** F-AWS.
**Scope:** Visual fidelity of `docs/aws-architecture-diagram.md` when rendered.

## Why

When the mermaid source from `docs/aws-architecture-diagram.md` is pasted into
draw.io (the user's actual presentation flow), the rendered diagrams show generic
colored rectangles instead of recognizable AWS service icons. The Main and Structural
diagrams therefore lose their primary affordance — letting a reviewer recognize each
component at a glance.

The "modern" mermaid answer (`architecture-beta` + `logos:aws-*` icons) does not solve
this: it requires the renderer to pre-register an iconify icon pack via JavaScript at
boot, and neither draw.io's bundled mermaid nor the free mermaid.live playground does
that. Confirmed empirically on 2026-05-25 — pasting an `architecture-beta` block with
`logos:aws-api-gateway` into mermaid.live renders a `?` placeholder.

## What ships

- Rewrite **Main Diagram** to `flowchart LR` with inline `<img src='...' />` tags
  pointing at AWS official architecture icons hosted on `icon.icepanel.io` (public CDN
  mirror of AWS's freely licensed architecture icons).
- Rewrite **Diagram 1 — Structural** the same way: every AWS service node gets its
  official icon (API Gateway, VPC, ELB, Fargate, MSK, ECR, KMS, CloudWatch, X-Ray).
- Add `%%{init: {'securityLevel': 'loose'}}%%` directive at the top of each rewritten
  block so renderers that default to strict mode still allow HTML labels.
- Leave **Diagram 2 — Sequence** untouched — sequence diagrams describe interaction
  order, not topology, and icons would add noise without information.
- Drop the now-redundant `classDef` color buckets from the two rewritten diagrams
  (icons replace the visual role color was playing).
- Add a short "Rendering" note in the doc explaining where the icons come from and that
  no iconify pack registration is needed.

## Done when

- The two rewritten mermaid blocks parse cleanly in mermaid.live with AWS icons visible.
- Pasting either block into draw.io renders the AWS icons inline (manual verification
  in draw.io).
- Sequence diagram is byte-identical to before.
- All 10 referenced icon URLs return HTTP 200.
- No reader-side iconify pack registration required.
