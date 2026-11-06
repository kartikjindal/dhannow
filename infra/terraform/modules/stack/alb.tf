# api.dhannow.com: ACM certificate, ALB (HTTP → HTTPS), target group on the tasks' port 8080.
# Idle timeout 120 s: application.yml sizes the provider timeouts under it.

resource "aws_acm_certificate" "api" {
  domain_name       = var.api_domain
  validation_method = "DNS"
  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_route53_record" "api_validation" {
  for_each = local.zone_given ? {
    for o in aws_acm_certificate.api.domain_validation_options : o.domain_name => o
  } : {}
  zone_id         = var.route53_zone_id
  name            = each.value.resource_record_name
  type            = each.value.resource_record_type
  records         = [each.value.resource_record_value]
  ttl             = 300
  allow_overwrite = true
}

# Without a zone, this waits until the CNAMEs in dns_records_to_create are added by hand.
resource "aws_acm_certificate_validation" "api" {
  certificate_arn         = aws_acm_certificate.api.arn
  validation_record_fqdns = local.zone_given ? [for r in aws_route53_record.api_validation : r.fqdn] : null
  timeouts {
    create = "2h"
  }
}

resource "aws_lb" "api" {
  name                       = "${local.prefix}-api"
  load_balancer_type         = "application"
  internal                   = false
  security_groups            = [aws_security_group.alb.id]
  subnets                    = aws_subnet.public[*].id
  idle_timeout               = 120
  drop_invalid_header_fields = true
  enable_deletion_protection = local.is_prod

  access_logs {
    bucket  = aws_s3_bucket.this["logs"].id
    prefix  = "alb"
    enabled = true
  }

  depends_on = [aws_s3_bucket_policy.logs]
}

resource "aws_lb_target_group" "api" {
  name                 = "${local.prefix}-api"
  port                 = 8080
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = aws_vpc.this.id
  deregistration_delay = 30

  health_check {
    path                = "/actuator/health"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.api.arn
  port              = 80
  protocol          = "HTTP"
  default_action {
    type = "redirect"
    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.api.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = aws_acm_certificate_validation.api.certificate_arn
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api.arn
  }
}

# Actuator beyond health/info is not exposed (application.yml); keep the ALB from forwarding it anyway.
resource "aws_lb_listener_rule" "block_actuator" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 10
  condition {
    path_pattern {
      values = ["/actuator/*"]
    }
  }
  action {
    type = "fixed-response"
    fixed_response {
      content_type = "text/plain"
      message_body = "Not found"
      status_code  = "404"
    }
  }
}

# The OpenAPI document and Swagger UI stay reachable on staging (contract review, ZAP) only.
resource "aws_lb_listener_rule" "block_api_docs" {
  count        = local.is_prod ? 1 : 0
  listener_arn = aws_lb_listener.https.arn
  priority     = 11
  condition {
    path_pattern {
      values = ["/v3/api-docs*", "/swagger-ui*"]
    }
  }
  action {
    type = "fixed-response"
    fixed_response {
      content_type = "text/plain"
      message_body = "Not found"
      status_code  = "404"
    }
  }
}

resource "aws_lb_listener_rule" "allow_health" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 5
  condition {
    path_pattern {
      values = ["/actuator/health"]
    }
  }
  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api.arn
  }
}

resource "aws_route53_record" "api" {
  count   = local.zone_given ? 1 : 0
  zone_id = var.route53_zone_id
  name    = var.api_domain
  type    = "A"
  alias {
    name                   = aws_lb.api.dns_name
    zone_id                = aws_lb.api.zone_id
    evaluate_target_health = true
  }
}
