# Terraform — F-AWS proposal

Proposal-grade Terraform for the AWS deployment of `invoice-generator`. The HCL is
validated clean (`terraform fmt + validate`) and demonstrates the architecture, but is
**not** wired against a real AWS account — see the F-AWS spec for the explicit
scope decision.

Operator-facing architecture walkthrough:
[`../../docs/aws-architecture.md`](../../docs/aws-architecture.md) (added by T5).

## Layout

```
infra/terraform/
├── versions.tf              terraform / AWS provider pins
├── providers.tf             AWS provider w/ default_tags
├── variables.tf             account_id, region, environment, app_name, vpc_cidr
├── locals.tf                common tags + name prefix
├── main.tf                  wires the modules
├── outputs.tf               public surface (filled by T4)
├── terraform.tfvars.example copy → terraform.tfvars and edit
├── .gitignore
└── modules/
    ├── network/             VPC + subnets + IGW + NAT + SGs           (T1)
    ├── msk/                 MSK cluster + KMS + log group              (T2)
    ├── ecs/                 ECR + cluster + Fargate task + ALB + IAM   (T3)
    ├── api-gateway/         HTTP API + VPC Link + access logs          (T4)
    └── observability/       Dashboards + alarms + X-Ray                (T4)
```

## Gate

```bash
# from repo root
~/.local/bin/terraform -chdir=infra/terraform fmt -recursive -check
~/.local/bin/terraform -chdir=infra/terraform init -backend=false
~/.local/bin/terraform -chdir=infra/terraform validate
```

All three commands must exit 0. They are the F-AWS equivalent of `./mvnw verify`.

## Status

| Module | Task | Status |
| --- | --- | --- |
| `network` | T1 | shipped |
| `msk` | T2 | pending |
| `ecs` | T3 | pending |
| `api-gateway` | T4 | pending |
| `observability` | T4 | pending |

T5 (architecture doc + ROADMAP/STATE flip) lands when T1..T4 are green.
