output "cluster_arn" {
  description = "MSK cluster ARN. Used by the ECS task IAM policy to scope kafka-cluster:* actions."
  value       = aws_msk_cluster.this.arn
}

output "cluster_name" {
  description = "MSK cluster name. Used by the observability module to filter MSK metrics."
  value       = aws_msk_cluster.this.cluster_name
}

output "bootstrap_brokers_sasl_iam" {
  description = "Comma-separated SASL/IAM bootstrap broker endpoints. Set as KAFKA_BOOTSTRAP_SERVERS on the app task."
  value       = aws_msk_cluster.this.bootstrap_brokers_sasl_iam
}

output "kms_key_arn" {
  description = "ARN of the CMK encrypting broker storage."
  value       = aws_kms_key.msk.arn
}

output "log_group_name" {
  description = "CloudWatch log group receiving broker logs."
  value       = aws_cloudwatch_log_group.broker.name
}
