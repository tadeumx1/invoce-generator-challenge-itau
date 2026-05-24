data "aws_caller_identity" "current" {}

# ECR repository - image is built and pushed out-of-band (CI/CD is out of F-AWS scope).

resource "aws_ecr_repository" "this" {
  name                 = var.app_name
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = merge(var.tags, { Name = var.app_name })
}

# CloudWatch log group for the app + sidecar containers.

resource "aws_cloudwatch_log_group" "app" {
  name              = "/aws/ecs/${var.name_prefix}"
  retention_in_days = var.log_retention_days

  tags = merge(var.tags, { Name = "${var.name_prefix}-app-logs" })
}

# ECS cluster (Fargate-only, no EC2 capacity providers).

resource "aws_ecs_cluster" "this" {
  name = var.name_prefix

  setting {
    name  = "containerInsights"
    value = "enabled"
  }

  tags = merge(var.tags, { Name = var.name_prefix })
}

resource "aws_ecs_cluster_capacity_providers" "this" {
  cluster_name       = aws_ecs_cluster.this.name
  capacity_providers = ["FARGATE"]

  default_capacity_provider_strategy {
    capacity_provider = "FARGATE"
    weight            = 1
  }
}

# IAM: execution role - Fargate's agent uses this to pull the image + ship logs.

data "aws_iam_policy_document" "task_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "task_execution" {
  name               = "${var.name_prefix}-task-execution"
  assume_role_policy = data.aws_iam_policy_document.task_assume.json

  tags = merge(var.tags, { Name = "${var.name_prefix}-task-execution" })
}

resource "aws_iam_role_policy_attachment" "task_execution_managed" {
  role       = aws_iam_role.task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# IAM: task role - the running app authenticates as this principal against MSK, X-Ray, CloudWatch.

resource "aws_iam_role" "task" {
  name               = "${var.name_prefix}-task"
  assume_role_policy = data.aws_iam_policy_document.task_assume.json

  tags = merge(var.tags, { Name = "${var.name_prefix}-task" })
}

data "aws_iam_policy_document" "task" {
  # MSK SASL/IAM: connect + describe + produce + consume on this cluster, all topics + groups.
  statement {
    sid    = "MskClusterAuth"
    effect = "Allow"
    actions = [
      "kafka-cluster:Connect",
      "kafka-cluster:DescribeCluster",
      "kafka-cluster:AlterCluster",
    ]
    resources = [var.msk_cluster_arn]
  }

  statement {
    sid    = "MskTopicAccess"
    effect = "Allow"
    actions = [
      "kafka-cluster:DescribeTopic",
      "kafka-cluster:CreateTopic",
      "kafka-cluster:WriteData",
      "kafka-cluster:ReadData",
      "kafka-cluster:AlterTopic",
      "kafka-cluster:DescribeTopicDynamicConfiguration",
    ]
    # MSK ARN format for topics: replace cluster:/foo/.../UUID-x with topic:/foo/.../UUID-x/<topic>
    resources = [replace(var.msk_cluster_arn, ":cluster/", ":topic/")]
  }

  statement {
    sid    = "MskGroupAccess"
    effect = "Allow"
    actions = [
      "kafka-cluster:DescribeGroup",
      "kafka-cluster:AlterGroup",
    ]
    resources = [replace(var.msk_cluster_arn, ":cluster/", ":group/")]
  }

  # CloudWatch metrics push - scoped to the application namespace via the cloudwatch:namespace
  # condition so a runaway PutMetricData cannot inflate every other namespace's billing.
  statement {
    sid       = "CloudWatchMetricsPush"
    effect    = "Allow"
    actions   = ["cloudwatch:PutMetricData"]
    resources = ["*"]

    condition {
      test     = "StringEquals"
      variable = "cloudwatch:namespace"
      values   = ["InvoiceGenerator"]
    }
  }

  # X-Ray segments. The X-Ray API does not support resource-level ARNs.
  statement {
    sid    = "XRayPut"
    effect = "Allow"
    actions = [
      "xray:PutTraceSegments",
      "xray:PutTelemetryRecords",
      "xray:GetSamplingRules",
      "xray:GetSamplingTargets",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "task" {
  name   = "${var.name_prefix}-task"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.task.json
}

# Task definition - two containers: app + ADOT sidecar.

locals {
  app_image = "${aws_ecr_repository.this.repository_url}:${var.image_tag}"

  app_container = {
    name              = "app"
    image             = local.app_image
    essential         = true
    cpu               = 0
    memoryReservation = 1536

    portMappings = [{
      containerPort = 8080
      hostPort      = 8080
      protocol      = "tcp"
    }]

    environment = [
      { name = "SPRING_PROFILES_ACTIVE", value = "aws" },
      { name = "KAFKA_BOOTSTRAP_SERVERS", value = var.kafka_bootstrap_brokers },
      { name = "OTLP_TRACING_ENDPOINT", value = "http://localhost:4318/v1/traces" },
      { name = "AWS_REGION", value = var.aws_region },
      { name = "APP_MESSAGING_KAFKA_ENABLED", value = "true" },
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.app.name
        awslogs-region        = var.aws_region
        awslogs-stream-prefix = "app"
      }
    }
  }

  adot_container = {
    name              = "aws-otel-collector"
    image             = var.adot_image
    essential         = false
    cpu               = 0
    memoryReservation = 256

    command = ["--config=/etc/ecs/ecs-default-config.yaml"]

    portMappings = [
      { containerPort = 4318, hostPort = 4318, protocol = "tcp" },
      { containerPort = 4317, hostPort = 4317, protocol = "tcp" },
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.app.name
        awslogs-region        = var.aws_region
        awslogs-stream-prefix = "adot"
      }
    }
  }
}

resource "aws_ecs_task_definition" "app" {
  family                   = var.name_prefix
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = var.task_cpu
  memory                   = var.task_memory_mb
  execution_role_arn       = aws_iam_role.task_execution.arn
  task_role_arn            = aws_iam_role.task.arn

  container_definitions = jsonencode([
    local.app_container,
    local.adot_container,
  ])

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  tags = merge(var.tags, { Name = var.name_prefix })
}

# Internal ALB - API Gateway VPC Link is the only public ingress.

resource "aws_lb" "internal" {
  name               = "${var.name_prefix}-alb"
  internal           = true
  load_balancer_type = "application"
  security_groups    = [var.alb_security_group_id]
  subnets            = var.private_subnet_ids

  tags = merge(var.tags, { Name = "${var.name_prefix}-alb" })
}

resource "aws_lb_target_group" "app" {
  name        = "${var.name_prefix}-tg"
  port        = 8080
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = var.vpc_id

  health_check {
    path                = "/actuator/health"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    interval            = 30
    timeout             = 5
    matcher             = "200"
  }

  deregistration_delay = 30

  tags = merge(var.tags, { Name = "${var.name_prefix}-tg" })
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.internal.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app.arn
  }

  tags = merge(var.tags, { Name = "${var.name_prefix}-listener" })
}

# Service - 2 tasks, rolling deploys via CodeDeploy-style minimums.

resource "aws_ecs_service" "app" {
  name            = var.name_prefix
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.app.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [var.app_security_group_id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.app.arn
    container_name   = "app"
    container_port   = 8080
  }

  deployment_minimum_healthy_percent = 50
  deployment_maximum_percent         = 200

  enable_execute_command = true # for debugging via ecs execute-command in dev

  tags = merge(var.tags, { Name = var.name_prefix })

  depends_on = [aws_lb_listener.http]
}
