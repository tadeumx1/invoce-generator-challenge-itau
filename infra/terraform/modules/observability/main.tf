# CloudWatch dashboard with one widget per SLI. The metric-math expressions mirror the
# Prometheus queries from docs/observability.md verbatim. If a query changes there, update
# the corresponding widget expression here in the same commit.

locals {
  # The Micrometer CloudWatch registry replaces "." with "_" by default. Counter names
  # like invoice.dispatch.total land on CloudWatch as `invoice_dispatch_total` in
  # namespace var.metrics_namespace.
  dashboard_body = jsonencode({
    widgets = [
      # SLI-1: API success rate = count(non-5xx) / count(all) on http.server.requests
      {
        type   = "metric"
        x      = 0
        y      = 0
        width  = 12
        height = 6
        properties = {
          title  = "SLI-1 — API success rate (target: 99.5%)"
          region = var.aws_region
          view   = "timeSeries"
          stat   = "Sum"
          period = 60
          metrics = [
            [{
              expression = "100 * (m_total - m_5xx) / m_total"
              label      = "Success rate (%)"
              id         = "e1"
            }],
            ["${var.metrics_namespace}", "http_server_requests_seconds_count", "uri", "/api/orders/generate-invoice", { id = "m_total", visible = false }],
            ["${var.metrics_namespace}", "http_server_requests_seconds_count", "uri", "/api/orders/generate-invoice", "status_class", "5xx", { id = "m_5xx", visible = false }],
          ]
          annotations = {
            horizontal = [{ value = 99.5, label = "SLO 99.5%" }]
          }
        }
      },

      # SLI-2: API latency = count(le=0.8) / count(all)
      {
        type   = "metric"
        x      = 12
        y      = 0
        width  = 12
        height = 6
        properties = {
          title  = "SLI-2 — API latency under 800 ms (target: 99%)"
          region = var.aws_region
          view   = "timeSeries"
          stat   = "Sum"
          period = 60
          metrics = [
            [{
              expression = "100 * m_under_800 / m_total"
              label      = "Requests < 800 ms (%)"
              id         = "e2"
            }],
            ["${var.metrics_namespace}", "http_server_requests_seconds_bucket", "uri", "/api/orders/generate-invoice", "le", "0.8", { id = "m_under_800", visible = false }],
            ["${var.metrics_namespace}", "http_server_requests_seconds_count", "uri", "/api/orders/generate-invoice", { id = "m_total", visible = false }],
          ]
          annotations = {
            horizontal = [{ value = 99, label = "SLO 99%" }]
          }
        }
      },

      # SLI-3: Kafka dispatch success = count(outcome=success) / count(all)
      {
        type   = "metric"
        x      = 0
        y      = 6
        width  = 12
        height = 6
        properties = {
          title  = "SLI-3 — Kafka dispatch success (target: 99.9%)"
          region = var.aws_region
          view   = "timeSeries"
          stat   = "Sum"
          period = 60
          metrics = [
            [{
              expression = "100 * m_success / m_total"
              label      = "Dispatch success (%)"
              id         = "e3"
            }],
            ["${var.metrics_namespace}", "invoice_dispatch_total", "outcome", "success", { id = "m_success", visible = false }],
            ["${var.metrics_namespace}", "invoice_dispatch_total", { id = "m_total", visible = false }],
          ]
          annotations = {
            horizontal = [{ value = 99.9, label = "SLO 99.9%" }]
          }
        }
      },

      # SLI-4: side-effect end-to-end latency under 30s (one line per topic)
      {
        type   = "metric"
        x      = 12
        y      = 6
        width  = 12
        height = 6
        properties = {
          title  = "SLI-4 — Side-effect latency under 30 s (target: 95% per topic)"
          region = var.aws_region
          view   = "timeSeries"
          stat   = "Sum"
          period = 60
          metrics = [
            [{
              expression = "100 * m_under_30 / m_total"
              label      = "Side-effects < 30 s (%)"
              id         = "e4"
            }],
            ["${var.metrics_namespace}", "invoice_sideeffect_duration_seconds_bucket", "le", "30", { id = "m_under_30", visible = false }],
            ["${var.metrics_namespace}", "invoice_sideeffect_duration_seconds_count", { id = "m_total", visible = false }],
          ]
          annotations = {
            horizontal = [{ value = 95, label = "SLO 95%" }]
          }
        }
      },

      # Supporting widget: business volume.
      {
        type   = "metric"
        x      = 0
        y      = 12
        width  = 24
        height = 6
        properties = {
          title  = "Business volume - invoices generated vs rejected"
          region = var.aws_region
          view   = "timeSeries"
          stat   = "Sum"
          period = 60
          metrics = [
            ["${var.metrics_namespace}", "invoice_generated_total"],
            ["${var.metrics_namespace}", "invoice_rejected_total"],
          ]
        }
      },
    ]
  })
}

resource "aws_cloudwatch_dashboard" "slis" {
  dashboard_name = "${var.name_prefix}-slis"
  dashboard_body = local.dashboard_body
}

# One alarm per SLI. No action wired (no SNS topic) - the next feature would attach one.

resource "aws_cloudwatch_metric_alarm" "sli_1_success_rate" {
  alarm_name          = "${var.name_prefix}-sli1-success-rate"
  alarm_description   = "SLI-1 burn: HTTP success rate dropped below 99.5% over a 5-minute window."
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = 5
  threshold           = 99.5
  treat_missing_data  = "notBreaching"

  metric_query {
    id          = "e1"
    expression  = "100 * (m_total - m_5xx) / m_total"
    label       = "Success rate (%)"
    return_data = true
  }
  metric_query {
    id = "m_total"
    metric {
      namespace   = var.metrics_namespace
      metric_name = "http_server_requests_seconds_count"
      dimensions  = { uri = "/api/orders/generate-invoice" }
      period      = 60
      stat        = "Sum"
    }
  }
  metric_query {
    id = "m_5xx"
    metric {
      namespace   = var.metrics_namespace
      metric_name = "http_server_requests_seconds_count"
      dimensions = {
        uri          = "/api/orders/generate-invoice"
        status_class = "5xx"
      }
      period = 60
      stat   = "Sum"
    }
  }

  tags = merge(var.tags, { Name = "${var.name_prefix}-sli1-alarm" })
}

resource "aws_cloudwatch_metric_alarm" "sli_2_latency" {
  alarm_name          = "${var.name_prefix}-sli2-latency"
  alarm_description   = "SLI-2 burn: <99% of requests completed under 800 ms over a 5-minute window."
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = 5
  threshold           = 99
  treat_missing_data  = "notBreaching"

  metric_query {
    id          = "e2"
    expression  = "100 * m_under_800 / m_total"
    label       = "Requests < 800 ms (%)"
    return_data = true
  }
  metric_query {
    id = "m_under_800"
    metric {
      namespace   = var.metrics_namespace
      metric_name = "http_server_requests_seconds_bucket"
      dimensions = {
        uri = "/api/orders/generate-invoice"
        le  = "0.8"
      }
      period = 60
      stat   = "Sum"
    }
  }
  metric_query {
    id = "m_total"
    metric {
      namespace   = var.metrics_namespace
      metric_name = "http_server_requests_seconds_count"
      dimensions  = { uri = "/api/orders/generate-invoice" }
      period      = 60
      stat        = "Sum"
    }
  }

  tags = merge(var.tags, { Name = "${var.name_prefix}-sli2-alarm" })
}

resource "aws_cloudwatch_metric_alarm" "sli_3_dispatch" {
  alarm_name          = "${var.name_prefix}-sli3-dispatch"
  alarm_description   = "SLI-3 burn: Kafka dispatch success rate dropped below 99.9% over a 5-minute window."
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = 5
  threshold           = 99.9
  treat_missing_data  = "notBreaching"

  metric_query {
    id          = "e3"
    expression  = "100 * m_success / m_total"
    label       = "Dispatch success (%)"
    return_data = true
  }
  metric_query {
    id = "m_success"
    metric {
      namespace   = var.metrics_namespace
      metric_name = "invoice_dispatch_total"
      dimensions  = { outcome = "success" }
      period      = 60
      stat        = "Sum"
    }
  }
  metric_query {
    id = "m_total"
    metric {
      namespace   = var.metrics_namespace
      metric_name = "invoice_dispatch_total"
      period      = 60
      stat        = "Sum"
    }
  }

  tags = merge(var.tags, { Name = "${var.name_prefix}-sli3-alarm" })
}

resource "aws_cloudwatch_metric_alarm" "sli_4_sideeffect" {
  alarm_name          = "${var.name_prefix}-sli4-sideeffect"
  alarm_description   = "SLI-4 burn: <95% of side-effects completed under 30 s over a 5-minute window."
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = 5
  threshold           = 95
  treat_missing_data  = "notBreaching"

  metric_query {
    id          = "e4"
    expression  = "100 * m_under_30 / m_total"
    label       = "Side-effects < 30 s (%)"
    return_data = true
  }
  metric_query {
    id = "m_under_30"
    metric {
      namespace   = var.metrics_namespace
      metric_name = "invoice_sideeffect_duration_seconds_bucket"
      dimensions  = { le = "30" }
      period      = 60
      stat        = "Sum"
    }
  }
  metric_query {
    id = "m_total"
    metric {
      namespace   = var.metrics_namespace
      metric_name = "invoice_sideeffect_duration_seconds_count"
      period      = 60
      stat        = "Sum"
    }
  }

  tags = merge(var.tags, { Name = "${var.name_prefix}-sli4-alarm" })
}

# X-Ray group + sampling rule. The local stack samples 100%; in AWS the bill dictates ~10%.

resource "aws_xray_group" "this" {
  group_name        = var.name_prefix
  filter_expression = "service(\"${var.name_prefix}\")"

  insights_configuration {
    insights_enabled      = true
    notifications_enabled = false
  }

  tags = merge(var.tags, { Name = "${var.name_prefix}-xray-group" })
}

resource "aws_xray_sampling_rule" "this" {
  rule_name      = "${var.name_prefix}-default"
  priority       = 9000
  reservoir_size = 1
  fixed_rate     = var.xray_sampling_fixed_rate
  service_name   = var.name_prefix
  service_type   = "*"
  host           = "*"
  http_method    = "*"
  url_path       = "*"
  resource_arn   = "*"
  version        = 1

  tags = merge(var.tags, { Name = "${var.name_prefix}-xray-sampling" })
}
