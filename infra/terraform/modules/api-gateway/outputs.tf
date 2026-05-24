output "api_id" {
  description = "HTTP API ID."
  value       = aws_apigatewayv2_api.this.id
}

output "api_endpoint" {
  description = "Public HTTPS base URL for the API. Smoke test: curl -X POST <endpoint>/api/orders/generate-invoice ..."
  value       = aws_apigatewayv2_api.this.api_endpoint
}

output "stage_name" {
  description = "Default stage name (\"$default\")."
  value       = aws_apigatewayv2_stage.default.name
}

output "access_log_group_name" {
  description = "Access log CloudWatch log group."
  value       = aws_cloudwatch_log_group.access_logs.name
}
