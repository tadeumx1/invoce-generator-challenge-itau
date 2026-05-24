output "api_endpoint" {
  description = "Public HTTPS base URL of the invoice-generator API. Smoke test: curl -X POST $(terraform output -raw api_endpoint)/api/orders/generate-invoice ..."
  value       = module.api_gateway.api_endpoint
}

output "ecr_repository_url" {
  description = "Push the application image here before scaling the ECS service up."
  value       = module.ecs.ecr_repository_url
}

output "ecs_cluster_name" {
  description = "ECS cluster name. Consumed by the GitHub Actions deploy workflow to force a new deployment."
  value       = module.ecs.cluster_name
}

output "ecs_service_name" {
  description = "ECS service name. Consumed by the GitHub Actions deploy workflow."
  value       = module.ecs.service_name
}

output "kafka_bootstrap_brokers_sasl_iam" {
  description = "Bootstrap brokers the application uses under the `aws` Spring profile (KAFKA_BOOTSTRAP_SERVERS)."
  value       = module.msk.bootstrap_brokers_sasl_iam
  sensitive   = true
}

output "cloudwatch_dashboard_name" {
  description = "Open in the CloudWatch console to see the four-SLI view."
  value       = module.observability.dashboard_name
}
