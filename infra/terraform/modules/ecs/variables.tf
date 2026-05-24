variable "name_prefix" {
  description = "Resource name prefix (typically \"<app>-<env>\")."
  type        = string
}

variable "app_name" {
  description = "Application slug. The ECR repo name does NOT include the env suffix - the image registry is shared across envs."
  type        = string
}

variable "vpc_id" {
  description = "VPC ID for the ALB target group."
  type        = string
}

variable "private_subnet_ids" {
  description = "Private subnet IDs - both the ALB and the Fargate tasks live here."
  type        = list(string)
}

variable "app_security_group_id" {
  description = "Security group attached to the Fargate tasks."
  type        = string
}

variable "alb_security_group_id" {
  description = "Security group attached to the internal ALB."
  type        = string
}

variable "msk_cluster_arn" {
  description = "MSK cluster ARN, used to scope the task role's kafka-cluster:* permissions."
  type        = string
}

variable "kafka_bootstrap_brokers" {
  description = "Comma-separated SASL/IAM bootstrap brokers from the msk module. Set as KAFKA_BOOTSTRAP_SERVERS on the app container."
  type        = string
}

variable "aws_region" {
  description = "AWS region. Threaded into the task definition for the awslogs driver + the X-Ray daemon."
  type        = string
}

variable "image_tag" {
  description = "Container image tag pulled from ECR. The image push itself is out of scope for the Terraform."
  type        = string
  default     = "latest"
}

variable "desired_count" {
  description = "Number of Fargate tasks the service keeps running."
  type        = number
  default     = 2
}

variable "task_cpu" {
  description = "Task-level CPU units (1024 = 1 vCPU)."
  type        = number
  default     = 1024
}

variable "task_memory_mb" {
  description = "Task-level memory in MiB."
  type        = number
  default     = 2048
}

variable "log_retention_days" {
  description = "CloudWatch log retention for the app + sidecar log group."
  type        = number
  default     = 30
}

variable "adot_image" {
  description = "ADOT collector container image (pinned)."
  type        = string
  default     = "public.ecr.aws/aws-observability/aws-otel-collector:v0.40.0"
}

variable "tags" {
  description = "Tags to merge into every resource."
  type        = map(string)
  default     = {}
}
