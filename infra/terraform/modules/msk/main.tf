# Customer-managed KMS key for at-rest broker storage encryption. A CMK (vs the AWS-owned
# default) is what makes the dashboard auditable: who used the key, when, from which task.

data "aws_caller_identity" "current" {}

resource "aws_kms_key" "msk" {
  description             = "At-rest encryption for ${var.name_prefix} MSK broker storage."
  enable_key_rotation     = true
  deletion_window_in_days = 7

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "RootAccountAdmin"
        Effect    = "Allow"
        Principal = { AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root" }
        Action    = "kms:*"
        Resource  = "*"
      },
      {
        Sid       = "AllowMSKUse"
        Effect    = "Allow"
        Principal = { Service = "kafka.amazonaws.com" }
        Action = [
          "kms:Decrypt",
          "kms:DescribeKey",
          "kms:GenerateDataKey",
        ]
        Resource = "*"
      },
    ]
  })

  tags = merge(var.tags, { Name = "${var.name_prefix}-msk-kms" })
}

resource "aws_kms_alias" "msk" {
  name          = "alias/${var.name_prefix}-msk"
  target_key_id = aws_kms_key.msk.id
}

# Cluster-level configuration. Bakes the four invoice topics' partition + retry defaults
# so Spring Kafka's startup topic creation lands the same shape as the local KRaft stack.

resource "aws_msk_configuration" "this" {
  name              = "${var.name_prefix}-msk-config"
  kafka_versions    = [var.kafka_version]
  description       = "Defaults for invoice-generator topics: 3 partitions, auto-create on, RF 3, min.insync.replicas 2."
  server_properties = <<-EOT
    auto.create.topics.enable=true
    default.replication.factor=3
    min.insync.replicas=2
    num.partitions=3
    log.retention.hours=168
  EOT
}

# Broker logs → CloudWatch (server.log, controller.log). 30-day retention matches the
# observability spec's defaults.

resource "aws_cloudwatch_log_group" "broker" {
  name              = "/aws/msk/${var.name_prefix}"
  retention_in_days = var.log_retention_days

  tags = merge(var.tags, { Name = "${var.name_prefix}-msk-broker-logs" })
}

# The cluster itself.

resource "aws_msk_cluster" "this" {
  cluster_name           = "${var.name_prefix}-msk"
  kafka_version          = var.kafka_version
  number_of_broker_nodes = var.broker_node_count

  broker_node_group_info {
    instance_type   = var.instance_type
    client_subnets  = var.subnet_ids
    security_groups = [var.security_group_id]

    storage_info {
      ebs_storage_info {
        volume_size = var.ebs_volume_size_gb
      }
    }
  }

  configuration_info {
    arn      = aws_msk_configuration.this.arn
    revision = aws_msk_configuration.this.latest_revision
  }

  encryption_info {
    encryption_at_rest_kms_key_arn = aws_kms_key.msk.arn

    encryption_in_transit {
      client_broker = "TLS_PLAINTEXT" # TLS to clients, plaintext intra-broker (see design.md ADR)
      in_cluster    = true
    }
  }

  client_authentication {
    sasl {
      iam = true
    }
    unauthenticated = false
  }

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.broker.name
      }
    }
  }

  tags = merge(var.tags, { Name = "${var.name_prefix}-msk" })
}
