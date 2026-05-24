variable "name_prefix" {
  description = "Resource name prefix (typically \"<app>-<env>\")."
  type        = string
}

variable "vpc_cidr" {
  description = "Primary VPC CIDR. Subnets are computed off this block."
  type        = string
  default     = "10.42.0.0/16"
}

variable "az_count" {
  description = "Number of Availability Zones to span. Subnets are created in pairs (public + private) per AZ."
  type        = number
  default     = 3
}

variable "tags" {
  description = "Tags to merge into every resource. The provider default_tags also apply."
  type        = map(string)
  default     = {}
}
