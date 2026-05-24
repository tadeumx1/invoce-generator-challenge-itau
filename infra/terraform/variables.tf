variable "account_id" {
  description = "AWS account ID this stack is deployed into. Used by resource policies."
  type        = string
}

variable "region" {
  description = "AWS region for every resource in the stack."
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "Logical environment slug (dev / stg / prd). Threaded into every resource name and the common tag set."
  type        = string
  default     = "dev"
}

variable "app_name" {
  description = "Short application slug used in resource names. Keep lowercase + dashes."
  type        = string
  default     = "invoice-generator"
}

variable "vpc_cidr" {
  description = "Primary CIDR block for the VPC. Subnets are carved as /24 slices off the first three octets."
  type        = string
  default     = "10.42.0.0/16"
}
