variable "name_prefix" {
  description = "Resource name prefix (typically \"<app>-<env>\")."
  type        = string
}

variable "private_subnet_ids" {
  description = "Private subnet IDs for the VPC Link ENIs."
  type        = list(string)
}

variable "alb_security_group_id" {
  description = "Security group of the internal ALB. The VPC Link is attached to it."
  type        = string
}

variable "alb_listener_arn" {
  description = "ALB listener ARN to forward to."
  type        = string
}

variable "log_retention_days" {
  description = "Retention for the access-log group."
  type        = number
  default     = 30
}

variable "tags" {
  description = "Tags to merge into every resource."
  type        = map(string)
  default     = {}
}
