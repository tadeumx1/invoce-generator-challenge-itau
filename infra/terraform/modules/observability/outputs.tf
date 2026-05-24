output "dashboard_name" {
  description = "CloudWatch dashboard name (open via console deep-link or `aws cloudwatch get-dashboard`)."
  value       = aws_cloudwatch_dashboard.slis.dashboard_name
}

output "alarm_arns" {
  description = "Per-SLI alarm ARNs, in spec order (SLI-1..SLI-4)."
  value = [
    aws_cloudwatch_metric_alarm.sli_1_success_rate.arn,
    aws_cloudwatch_metric_alarm.sli_2_latency.arn,
    aws_cloudwatch_metric_alarm.sli_3_dispatch.arn,
    aws_cloudwatch_metric_alarm.sli_4_sideeffect.arn,
  ]
}

output "xray_group_arn" {
  description = "ARN of the X-Ray group filter."
  value       = aws_xray_group.this.arn
}

output "xray_sampling_rule_arn" {
  description = "ARN of the X-Ray sampling rule (fixed 10% by default)."
  value       = aws_xray_sampling_rule.this.arn
}
