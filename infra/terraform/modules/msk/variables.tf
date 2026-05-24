variable "name_prefix" {
  description = "Resource name prefix (typically \"<app>-<env>\")."
  type        = string
}

variable "subnet_ids" {
  description = "Private subnet IDs for the broker ENIs. Must be one per AZ."
  type        = list(string)
}

variable "security_group_id" {
  description = "Security group attached to the broker ENIs (from the network module)."
  type        = string
}

variable "kafka_version" {
  description = "Apache Kafka version. 3.6.0 matches the local KRaft cp-kafka:7.7 protocol."
  type        = string
  default     = "3.6.0"
}

variable "broker_node_count" {
  description = "Number of broker nodes. Must be a multiple of the number of AZs (3)."
  type        = number
  default     = 3
}

variable "instance_type" {
  description = "Broker EC2 instance type. kafka.t3.small is the smallest available class."
  type        = string
  default     = "kafka.t3.small"
}

variable "ebs_volume_size_gb" {
  description = "EBS volume size per broker (GiB). 100 covers retry/DLT topic backlog generously for the proposal."
  type        = number
  default     = 100
}

variable "log_retention_days" {
  description = "CloudWatch log retention for the broker log group."
  type        = number
  default     = 30
}

variable "tags" {
  description = "Tags to merge into every resource."
  type        = map(string)
  default     = {}
}
