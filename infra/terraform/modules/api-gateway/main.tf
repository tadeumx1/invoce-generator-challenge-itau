# HTTP API (apigatewayv2) - cheaper + lower latency than REST API; native VPC Link.

resource "aws_apigatewayv2_api" "this" {
  name          = "${var.name_prefix}-api"
  protocol_type = "HTTP"
  description   = "Public edge for invoice-generator. No authorizer is configured; auth is the deferred follow-up (see docs/aws-architecture.md ADR-031)."

  tags = merge(var.tags, { Name = "${var.name_prefix}-api" })
}

# VPC Link - the API Gateway uses these ENIs to reach the internal ALB.

resource "aws_apigatewayv2_vpc_link" "this" {
  name               = "${var.name_prefix}-vpc-link"
  subnet_ids         = var.private_subnet_ids
  security_group_ids = [var.alb_security_group_id]

  tags = merge(var.tags, { Name = "${var.name_prefix}-vpc-link" })
}

# Integration - HTTP_PROXY to the ALB listener via the VPC Link.

resource "aws_apigatewayv2_integration" "alb" {
  api_id                 = aws_apigatewayv2_api.this.id
  integration_type       = "HTTP_PROXY"
  integration_method     = "ANY"
  integration_uri        = var.alb_listener_arn
  connection_type        = "VPC_LINK"
  connection_id          = aws_apigatewayv2_vpc_link.this.id
  payload_format_version = "1.0"
}

# Route - any HTTP verb under /api/* forwards to the integration. Covers both
# /api/orders/generate-invoice and the legacy /api/pedido/gerarNotaFiscal alias.

resource "aws_apigatewayv2_route" "api_proxy" {
  api_id    = aws_apigatewayv2_api.this.id
  route_key = "ANY /api/{proxy+}"
  target    = "integrations/${aws_apigatewayv2_integration.alb.id}"
}

# Access log group + JSON format.

resource "aws_cloudwatch_log_group" "access_logs" {
  name              = "/aws/apigateway/${var.name_prefix}"
  retention_in_days = var.log_retention_days

  tags = merge(var.tags, { Name = "${var.name_prefix}-api-access-logs" })
}

resource "aws_apigatewayv2_stage" "default" {
  api_id      = aws_apigatewayv2_api.this.id
  name        = "$default"
  auto_deploy = true

  access_log_settings {
    destination_arn = aws_cloudwatch_log_group.access_logs.arn
    format = jsonencode({
      requestId          = "$context.requestId"
      ip                 = "$context.identity.sourceIp"
      requestTime        = "$context.requestTime"
      httpMethod         = "$context.httpMethod"
      routeKey           = "$context.routeKey"
      status             = "$context.status"
      protocol           = "$context.protocol"
      responseLength     = "$context.responseLength"
      integrationLatency = "$context.integration.latency"
      responseLatency    = "$context.responseLatency"
      correlationId      = "$context.requestOverride.header.X-Correlation-Id"
    })
  }

  default_route_settings {
    throttling_burst_limit = 100
    throttling_rate_limit  = 50
  }

  tags = merge(var.tags, { Name = "${var.name_prefix}-api-stage" })
}
