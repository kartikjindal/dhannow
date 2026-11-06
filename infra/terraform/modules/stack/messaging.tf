# SES (sending domain + DKIM), a configuration set whose bounce/complaint/delivery events go
# SNS → SQS (raw delivery) → SesEventSqsListener (navix.ses.events.*). New SES accounts start in the
# sandbox: request production access before go-live (docs/GO_LIVE.md).

resource "aws_sesv2_email_identity" "domain" {
  count          = var.create_ses_identity ? 1 : 0
  email_identity = var.ses_domain
  dkim_signing_attributes {
    next_signing_key_length = "RSA_2048_BIT"
  }
}

resource "aws_route53_record" "ses_dkim" {
  count   = var.create_ses_identity && local.zone_given ? 3 : 0
  zone_id = var.route53_zone_id
  name    = "${aws_sesv2_email_identity.domain[0].dkim_signing_attributes[0].tokens[count.index]}._domainkey.${var.ses_domain}"
  type    = "CNAME"
  ttl     = 1800
  records = ["${aws_sesv2_email_identity.domain[0].dkim_signing_attributes[0].tokens[count.index]}.dkim.amazonses.com"]
}

resource "aws_sesv2_configuration_set" "this" {
  configuration_set_name = local.prefix
  delivery_options {
    tls_policy = "REQUIRE"
  }
  reputation_options {
    reputation_metrics_enabled = true
  }
  sending_options {
    sending_enabled = true
  }
}

resource "aws_sns_topic" "ses_events" {
  name = "${local.prefix}-ses-events"
}

data "aws_iam_policy_document" "ses_events_topic" {
  statement {
    sid       = "SesPublishes"
    actions   = ["sns:Publish"]
    resources = [aws_sns_topic.ses_events.arn]
    principals {
      type        = "Service"
      identifiers = ["ses.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "AWS:SourceAccount"
      values   = [local.account_id]
    }
  }
}

resource "aws_sns_topic_policy" "ses_events" {
  arn    = aws_sns_topic.ses_events.arn
  policy = data.aws_iam_policy_document.ses_events_topic.json
}

resource "aws_sesv2_configuration_set_event_destination" "sns" {
  configuration_set_name = aws_sesv2_configuration_set.this.configuration_set_name
  event_destination_name = "sns"
  event_destination {
    enabled              = true
    matching_event_types = ["BOUNCE", "COMPLAINT", "DELIVERY", "REJECT"]
    sns_destination {
      topic_arn = aws_sns_topic.ses_events.arn
    }
  }
}

resource "aws_sqs_queue" "ses_events_dlq" {
  name                      = "${local.prefix}-ses-events-dlq"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "ses_events" {
  name                       = "${local.prefix}-ses-events"
  visibility_timeout_seconds = 60
  message_retention_seconds  = 345600
  sqs_managed_sse_enabled    = true
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.ses_events_dlq.arn
    maxReceiveCount     = 5
  })
}

data "aws_iam_policy_document" "ses_events_queue" {
  statement {
    sid       = "FromTheSesTopic"
    actions   = ["sqs:SendMessage"]
    resources = [aws_sqs_queue.ses_events.arn]
    principals {
      type        = "Service"
      identifiers = ["sns.amazonaws.com"]
    }
    condition {
      test     = "ArnEquals"
      variable = "aws:SourceArn"
      values   = [aws_sns_topic.ses_events.arn]
    }
  }
}

resource "aws_sqs_queue_policy" "ses_events" {
  queue_url = aws_sqs_queue.ses_events.id
  policy    = data.aws_iam_policy_document.ses_events_queue.json
}

resource "aws_sns_topic_subscription" "ses_events" {
  topic_arn            = aws_sns_topic.ses_events.arn
  protocol             = "sqs"
  endpoint             = aws_sqs_queue.ses_events.arn
  raw_message_delivery = true
}

# ---- operations alerts ----
resource "aws_sns_topic" "alarms" {
  name = "${local.prefix}-alarms"
}

resource "aws_sns_topic_subscription" "alarm_email" {
  for_each  = toset(var.alarm_emails)
  topic_arn = aws_sns_topic.alarms.arn
  protocol  = "email"
  endpoint  = each.value
}
