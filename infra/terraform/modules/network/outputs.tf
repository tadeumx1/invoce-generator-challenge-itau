output "vpc_id" {
  description = "VPC ID — consumed by every other module."
  value       = aws_vpc.this.id
}

output "vpc_cidr" {
  description = "Primary VPC CIDR."
  value       = aws_vpc.this.cidr_block
}

output "public_subnet_ids" {
  description = "Public subnet IDs (one per AZ, in AZ order)."
  value       = aws_subnet.public[*].id
}

output "private_subnet_ids" {
  description = "Private subnet IDs (one per AZ, in AZ order). Consumed by msk and ecs."
  value       = aws_subnet.private[*].id
}

output "app_security_group_id" {
  description = "Security group attached to the Fargate tasks."
  value       = aws_security_group.app.id
}

output "msk_security_group_id" {
  description = "Security group attached to the MSK broker ENIs."
  value       = aws_security_group.msk.id
}

output "alb_security_group_id" {
  description = "Security group attached to the internal ALB."
  value       = aws_security_group.alb.id
}
