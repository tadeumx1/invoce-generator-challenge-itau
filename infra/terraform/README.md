# Terraform — F-AWS proposal

Proposal-grade Terraform for the AWS deployment of `invoice-generator`. The HCL is
validated clean (`terraform fmt + init + validate`) and demonstrates the architecture,
but is **not** wired against a real AWS account. See
[`../../docs/aws-architecture.md`](../../docs/aws-architecture.md) for the full
walkthrough (diagram, services table, ADRs, cost, runbook).

## Layout

```
infra/terraform/
├── versions.tf              terraform >= 1.6 / aws ~> 5.60
├── providers.tf             AWS provider w/ default_tags
├── variables.tf             account_id, region, environment, app_name, vpc_cidr
├── locals.tf                common tags + name prefix
├── main.tf                  wires the five modules
├── outputs.tf               api_endpoint, ecr_repository_url, brokers (sensitive),
│                            dashboard name
├── terraform.tfvars.example copy → terraform.tfvars and edit
├── .gitignore
└── modules/
    ├── network/             VPC + subnets + IGW + NAT + 3 SGs
    ├── msk/                 KMS + MSK cluster + configuration + broker log group
    ├── ecs/                 ECR + cluster + Fargate task (app + ADOT) + ALB + IAM
    ├── api-gateway/         HTTP API + VPC Link + integration + stage + access logs
    └── observability/       Dashboard (4 SLIs) + alarms + X-Ray group + sampling
```

## Gate

```bash
# from repo root
~/.local/bin/terraform -chdir=infra/terraform fmt -recursive -check
~/.local/bin/terraform -chdir=infra/terraform init -backend=false
~/.local/bin/terraform -chdir=infra/terraform validate
```

All three commands must exit 0. This is the F-AWS equivalent of `./mvnw verify`.

## Going from proposal-grade to applyable

1. **Add a backend.** Create `backend.tf` with an S3 + DynamoDB lock backend pointing
   at a bucket you control. Remove `-backend=false` from `init`.
2. **Fill in `terraform.tfvars`.** Copy from `terraform.tfvars.example`. Set
   `account_id` to your AWS account.
3. **Bootstrap AWS auth.** `aws sso login` (or `aws configure`) so the AWS provider
   can read credentials.
4. **Plan, then apply.** `terraform plan -out=tfplan && terraform apply tfplan`.
5. **Push the image.** See the runbook in `docs/aws-architecture.md` for the
   ECR push + `ecs update-service --force-new-deployment` sequence.

## App-side changes for the `aws` Spring profile

The Terraform alone does not change application code. To run the app **against** the
provisioned MSK + CloudWatch + X-Ray, the application needs an `aws` profile
(`application-aws.yml` or equivalent). Captured under Future Considerations on the
roadmap as the F-AWS-CLIENT follow-up:

- Add `software.amazon.msk:aws-msk-iam-auth:2.x` to `pom.xml`.
- Add `application-aws.yml`:
  ```yaml
  spring:
    kafka:
      properties:
        security.protocol: SASL_SSL
        sasl.mechanism: AWS_MSK_IAM
        sasl.jaas.config: software.amazon.msk.auth.iam.IAMLoginModule required;
        sasl.client.callback.handler.class: software.amazon.msk.auth.iam.IAMClientCallbackHandler

  management:
    cloudwatch:
      metrics:
        export:
          enabled: true
          namespace: InvoiceGenerator
          step: 1m
    prometheus:
      metrics:
        export:
          enabled: false
  ```
- Add `io.micrometer:micrometer-registry-cloudwatch2` to `pom.xml` and gate it on the
  `aws` profile.

These changes are not in F-AWS scope — the spec called for documented + IaC.

## Deferred follow-ups

- SNS topic + PagerDuty/Slack action on the four SLI alarms.
- Cognito user pool or external JWT-verifier auth on the API Gateway (see ADR-032).
- VPC interface endpoints for ECR / CloudWatch / X-Ray to retire the NAT Gateway.
- Migrate MSK to MSK Serverless to roughly halve the idle bill.
- Encrypted intra-broker traffic (`encryption_in_transit.client_broker = "TLS"`).
