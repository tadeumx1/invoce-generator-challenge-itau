variable "name_prefix" {
  description = "Resource name prefix (typically \"<app>-<env>\")."
  type        = string
}

variable "aws_region" {
  description = "AWS region (passed into dashboard widget definitions)."
  type        = string
}

variable "ecs_cluster_name" {
  description = "ECS cluster name to filter ECS metrics widgets by."
  type        = string
}

variable "ecs_service_name" {
  description = "ECS service name to filter ECS metrics widgets by."
  type        = string
}

variable "api_id" {
  description = "HTTP API ID, used as the ApiId dimension on AWS/ApiGateway metrics."
  type        = string
}

variable "api_stage_name" {
  description = "HTTP API stage name (\"$default\"), used as the Stage dimension."
  type        = string
}

variable "msk_cluster_name" {
  description = "MSK cluster name, used to filter AWS/Kafka and broker metrics."
  type        = string
}

variable "metrics_namespace" {
  description = "Custom metrics namespace the app publishes through the Micrometer CloudWatch registry."
  type        = string
  default     = "InvoiceGenerator"
}

variable "xray_sampling_fixed_rate" {
  description = "Fixed sampling rate for X-Ray. Lower than the local 100%; see AD-021."
  type        = number
  default     = 0.1
}

variable "tags" {
  description = "Tags to merge into every resource."
  type        = map(string)
  default     = {}
}
