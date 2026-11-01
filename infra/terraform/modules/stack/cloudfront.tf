# dhannow.com: static site/ from S3 through CloudFront (OAC), the APK under /downloads/*.
# Phase 10a hosting rules:
#  - extensionless paths serve the .html page (/app/cb/digilocker → /app/cb/digilocker.html,
#    /app/open?to=repay → /app/open.html, the query string kept for the page's script);
#  - /.well-known/assetlinks.json is served as application/json with no redirect;
#  - www is redirected to the apex (never the assetlinks path, which is fetched on the apex).
# The certificate must be in us-east-1 for CloudFront.

resource "aws_acm_certificate" "site" {
  provider                  = aws.us_east_1
  domain_name               = var.site_domain
  subject_alternative_names = var.site_aliases
  validation_method         = "DNS"
  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_route53_record" "site_validation" {
  for_each = local.zone_given ? {
    for o in aws_acm_certificate.site.domain_validation_options : o.domain_name => o
  } : {}
  zone_id         = var.route53_zone_id
  name            = each.value.resource_record_name
  type            = each.value.resource_record_type
  records         = [each.value.resource_record_value]
  ttl             = 300
  allow_overwrite = true
}

resource "aws_acm_certificate_validation" "site" {
  provider                = aws.us_east_1
  certificate_arn         = aws_acm_certificate.site.arn
  validation_record_fqdns = local.zone_given ? [for r in aws_route53_record.site_validation : r.fqdn] : null
  timeouts {
    create = "2h"
  }
}

resource "aws_cloudfront_origin_access_control" "s3" {
  name                              = "${local.prefix}-s3"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

resource "aws_cloudfront_function" "rewrite" {
  name    = "${local.prefix}-site-rewrite"
  runtime = "cloudfront-js-2.0"
  comment = "www -> apex; extensionless paths -> .html; directories -> index.html"
  publish = true
  code    = <<-JS
    function handler(event) {
      var req = event.request;
      var host = req.headers.host ? req.headers.host.value : '';
      var apex = '${var.site_domain}';
      if (host && host !== apex && req.uri.indexOf('/.well-known/') !== 0) {
        var qs = [];
        for (var k in req.querystring) {
          var v = req.querystring[k];
          qs.push(encodeURIComponent(k) + (v.value ? '=' + encodeURIComponent(v.value) : ''));
        }
        return {
          statusCode: 301,
          statusDescription: 'Moved Permanently',
          headers: { location: { value: 'https://' + apex + req.uri + (qs.length ? '?' + qs.join('&') : '') } }
        };
      }
      var uri = req.uri;
      if (uri.endsWith('/')) {
        req.uri = uri + 'index.html';
      } else if (uri.split('/').pop().indexOf('.') === -1) {
        req.uri = uri + '.html';
      }
      return req;
    }
  JS
}

resource "aws_cloudfront_function" "headers" {
  name    = "${local.prefix}-site-headers"
  runtime = "cloudfront-js-2.0"
  comment = "assetlinks.json as application/json"
  publish = true
  code    = <<-JS
    function handler(event) {
      var res = event.response;
      if (event.request.uri === '/.well-known/assetlinks.json') {
        res.headers['content-type'] = { value: 'application/json' };
      }
      return res;
    }
  JS
}

data "aws_cloudfront_cache_policy" "optimized" {
  name = "Managed-CachingOptimized"
}

resource "aws_cloudfront_response_headers_policy" "site" {
  name = "${local.prefix}-site"
  security_headers_config {
    strict_transport_security {
      access_control_max_age_sec = 31536000
      include_subdomains         = true
      preload                    = false
      override                   = true
    }
    content_type_options {
      override = true
    }
    frame_options {
      frame_option = "DENY"
      override     = true
    }
    referrer_policy {
      referrer_policy = "strict-origin-when-cross-origin"
      override        = false
    }
  }
}

resource "aws_cloudfront_distribution" "site" {
  enabled             = true
  is_ipv6_enabled     = true
  http_version        = "http2and3"
  comment             = "${local.prefix} public site"
  default_root_object = "index.html"
  aliases             = concat([var.site_domain], var.site_aliases)
  price_class         = "PriceClass_200" # includes India edge locations

  origin {
    origin_id                = "site"
    domain_name              = aws_s3_bucket.this["site"].bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.s3.id
  }

  origin {
    origin_id                = "downloads"
    domain_name              = aws_s3_bucket.this["downloads"].bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.s3.id
  }

  default_cache_behavior {
    target_origin_id           = "site"
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD", "OPTIONS"]
    cached_methods             = ["GET", "HEAD"]
    compress                   = true
    cache_policy_id            = data.aws_cloudfront_cache_policy.optimized.id
    response_headers_policy_id = aws_cloudfront_response_headers_policy.site.id

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.rewrite.arn
    }
    function_association {
      event_type   = "viewer-response"
      function_arn = aws_cloudfront_function.headers.arn
    }
  }

  # APKs: /downloads/dhannow-<version>-<abi>.apk, served by name (no rewrite).
  ordered_cache_behavior {
    path_pattern               = "/downloads/*"
    target_origin_id           = "downloads"
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD"]
    cached_methods             = ["GET", "HEAD"]
    compress                   = false
    cache_policy_id            = data.aws_cloudfront_cache_policy.optimized.id
    response_headers_policy_id = aws_cloudfront_response_headers_policy.site.id
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    acm_certificate_arn      = aws_acm_certificate_validation.site.certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }
}

resource "aws_route53_record" "site" {
  for_each = local.zone_given ? toset(concat([var.site_domain], var.site_aliases)) : toset([])
  zone_id  = var.route53_zone_id
  name     = each.value
  type     = "A"
  alias {
    name                   = aws_cloudfront_distribution.site.domain_name
    zone_id                = aws_cloudfront_distribution.site.hosted_zone_id
    evaluate_target_health = false
  }
}

# Staff console on Vercel (PLAN Phase 13: "Vercel or CloudFront"). It is a Next.js server app (edge
# middleware, BFF routes), so it cannot be a static S3 site; only its DNS record lives here.
resource "aws_route53_record" "staff" {
  count   = local.zone_given && var.staff_cname_target != "" ? 1 : 0
  zone_id = var.route53_zone_id
  name    = var.staff_domain
  type    = "CNAME"
  ttl     = 300
  records = [var.staff_cname_target]
}
