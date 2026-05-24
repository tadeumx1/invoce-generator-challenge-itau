output "cluster_name" {
  description = "ECS cluster name. Used by the observability dashboard widgets."
  value       = aws_ecs_cluster.this.name
}

output "cluster_arn" {
  description = "ECS cluster ARN."
  value       = aws_ecs_cluster.this.arn
}

output "service_name" {
  description = "ECS service name."
  value       = aws_ecs_service.app.name
}

output "task_role_arn" {
  description = "ARN of the task IAM role (the principal the running app authenticates as)."
  value       = aws_iam_role.task.arn
}

output "task_execution_role_arn" {
  description = "ARN of the task execution role (Fargate agent's pull-and-log role)."
  value       = aws_iam_role.task_execution.arn
}

output "ecr_repository_url" {
  description = "ECR repository URL. Push images here."
  value       = aws_ecr_repository.this.repository_url
}

output "alb_arn" {
  description = "Internal ALB ARN."
  value       = aws_lb.internal.arn
}

output "alb_listener_arn" {
  description = "ALB listener ARN. Consumed by the api-gateway module's VPC Link integration."
  value       = aws_lb_listener.http.arn
}

output "target_group_arn" {
  description = "Target group ARN attached to the ECS service."
  value       = aws_lb_target_group.app.arn
}

output "log_group_name" {
  description = "CloudWatch log group containing app + sidecar logs."
  value       = aws_cloudwatch_log_group.app.name
}
