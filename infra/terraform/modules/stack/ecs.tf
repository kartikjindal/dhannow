# ECR + ECS Fargate: the backend service behind the ALB, and a one-off Flyway migration task that
# deploy-backend.yml runs before each rollout. Terraform registers the first task definitions with a
# bootstrap tag; the workflow registers new revisions with the commit SHA, so the service ignores
# task_definition changes here.

resource "aws_ecr_repository" "backend" {
  name                 = "${local.prefix}-backend"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration {
    scan_on_push = true
  }
  encryption_configuration {
    encryption_type = "KMS"
    kms_key         = aws_kms_key.app.arn
  }
}

resource "aws_ecr_lifecycle_policy" "backend" {
  repository = aws_ecr_repository.backend.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the last 60 images (app + migrate)"
      selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = 60 }
      action       = { type = "expire" }
    }]
  })
}

resource "aws_ecs_cluster" "this" {
  name = local.prefix
  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

resource "aws_cloudwatch_log_group" "backend" {
  name              = "/ecs/${local.prefix}/backend"
  retention_in_days = var.log_retention_days
}

resource "aws_cloudwatch_log_group" "migrate" {
  name              = "/ecs/${local.prefix}/migrate"
  retention_in_days = var.log_retention_days
}

# ---- IAM ----
data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:${local.partition}:ecs:${local.region}:${local.account_id}:*"]
    }
  }
}

resource "aws_iam_role" "execution" {
  name               = "${local.prefix}-ecs-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy_attachment" "execution_managed" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:${local.partition}:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

locals {
  google_sa_param_arn = var.google_service_account_ssm_name == "" ? "" : "arn:${local.partition}:ssm:${local.region}:${local.account_id}:parameter${local.ssm_prefix}${var.google_service_account_ssm_name}"
}

data "aws_iam_policy_document" "execution" {
  statement {
    sid       = "DbPassword"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [local.db_secret_arn]
  }
  statement {
    sid       = "DecryptInjectedSecrets"
    actions   = ["kms:Decrypt"]
    resources = [aws_kms_key.app.arn]
  }
  dynamic "statement" {
    for_each = local.google_sa_param_arn == "" ? [] : [1]
    content {
      sid       = "GoogleServiceAccount"
      actions   = ["ssm:GetParameters"]
      resources = [local.google_sa_param_arn]
    }
  }
}

resource "aws_iam_role_policy" "execution" {
  name   = "inject-secrets"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution.json
}

resource "aws_iam_role" "task" {
  name               = "${local.prefix}-backend-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

data "aws_iam_policy_document" "task" {
  statement {
    sid       = "ConfigFromSsm"
    actions   = ["ssm:GetParametersByPath", "ssm:GetParameters", "ssm:GetParameter"]
    resources = ["arn:${local.partition}:ssm:${local.region}:${local.account_id}:parameter${trimsuffix(local.ssm_prefix, "/")}", "arn:${local.partition}:ssm:${local.region}:${local.account_id}:parameter${local.ssm_prefix}*"]
  }
  statement {
    sid       = "Documents"
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:GetObjectVersion"]
    resources = ["${aws_s3_bucket.this["documents"].arn}/*"]
  }
  statement {
    sid       = "DocumentsList"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.this["documents"].arn]
  }
  statement {
    sid       = "AppKey"
    actions   = ["kms:Decrypt", "kms:Encrypt", "kms:GenerateDataKey", "kms:DescribeKey"]
    resources = [aws_kms_key.app.arn]
  }
  statement {
    sid       = "UnwrapPiiDataKey"
    actions   = ["kms:Decrypt"]
    resources = [aws_kms_key.pii.arn]
  }
  statement {
    sid       = "SendEmail"
    actions   = ["ses:SendEmail", "ses:SendRawEmail"]
    resources = ["arn:${local.partition}:ses:${local.region}:${local.account_id}:identity/${var.ses_domain}", aws_sesv2_configuration_set.this.arn]
  }
  statement {
    sid       = "SesEvents"
    actions   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes", "sqs:GetQueueUrl", "sqs:ChangeMessageVisibility"]
    resources = [aws_sqs_queue.ses_events.arn]
  }
}

resource "aws_iam_role_policy" "task" {
  name   = "backend"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.task.json
}

# ---- task definitions ----
locals {
  site_origin = "https://${var.site_domain}"

  backend_environment = merge({
    NAVIX_ENV                = var.environment
    NAVIX_SSM_IMPORT         = "aws-parameterstore:${local.ssm_prefix}"
    AWS_REGION               = local.region
    DB_URL                   = local.db_jdbc_url
    DB_USERNAME              = aws_db_instance.this.username
    NAVIX_S3_BUCKET          = aws_s3_bucket.this["documents"].id
    NAVIX_S3_KMS_KEY_ID      = aws_kms_key.app.arn
    NAVIX_PII_KMS_KEY_ID     = aws_kms_key.pii.arn
    NAVIX_STATIC_EGRESS_IP   = aws_eip.nat.public_ip
    NAVIX_EMAIL_PROVIDER     = "ses"
    NAVIX_SES_CONFIG_SET     = aws_sesv2_configuration_set.this.configuration_set_name
    NAVIX_SES_EVENTS_ENABLED = "true"
    NAVIX_SES_EVENTS_QUEUE   = aws_sqs_queue.ses_events.name
    NAVIX_CORS_ORIGINS       = "${local.site_origin},https://${var.staff_domain}"
    NAVIX_APP_BASE_URL       = local.site_origin
    NAVIX_SITE_BASE_URL      = local.site_origin
    NAVIX_REDIRECT_ALLOWLIST = "${local.site_origin}/app/cb/"
    JAVA_OPTS                = "-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
    }, local.google_sa_param_arn == "" ? {} : {
    NAVIX_GOOGLE_SERVICE_ACCOUNT_PATH = "/tmp/google-service-account.json"
  }, var.extra_environment)

  backend_secrets = concat(
    [{ name = "DB_PASSWORD", valueFrom = local.db_password_vf }],
    local.google_sa_param_arn == "" ? [] : [{ name = "GOOGLE_SERVICE_ACCOUNT_JSON", valueFrom = local.google_sa_param_arn }],
  )

  # The image runs `java -jar`; with a Google key the JSON is written to /tmp first (the app reads a path).
  backend_entrypoint = local.google_sa_param_arn == "" ? null : [
    "sh", "-c",
    "printf '%s' \"$GOOGLE_SERVICE_ACCOUNT_JSON\" > /tmp/google-service-account.json && unset GOOGLE_SERVICE_ACCOUNT_JSON && exec java $JAVA_OPTS -jar /app/app.jar",
  ]
}

resource "aws_ecs_task_definition" "backend" {
  family                   = "${local.prefix}-backend"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.api_cpu
  memory                   = var.api_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([{
    name        = "backend"
    image       = "${aws_ecr_repository.backend.repository_url}:${var.api_bootstrap_image_tag}"
    essential   = true
    entryPoint  = local.backend_entrypoint
    stopTimeout = 30
    portMappings = [{
      containerPort = 8080
      protocol      = "tcp"
    }]
    environment = [for k, v in local.backend_environment : { name = k, value = v }]
    secrets     = local.backend_secrets
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.backend.name
        awslogs-region        = local.region
        awslogs-stream-prefix = "backend"
      }
    }
  }])
}

resource "aws_ecs_task_definition" "migrate" {
  family                   = "${local.prefix}-migrate"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  execution_role_arn       = aws_iam_role.execution.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([{
    name      = "migrate"
    image     = "${aws_ecr_repository.backend.repository_url}:migrate-${var.api_bootstrap_image_tag}"
    essential = true
    command   = ["migrate"]
    environment = [
      { name = "FLYWAY_URL", value = local.db_jdbc_url },
      { name = "FLYWAY_USER", value = aws_db_instance.this.username },
      { name = "FLYWAY_LOCATIONS", value = "filesystem:/flyway/sql" },
      { name = "FLYWAY_CONNECT_RETRIES", value = "10" },
    ]
    secrets = [{ name = "FLYWAY_PASSWORD", valueFrom = local.db_password_vf }]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.migrate.name
        awslogs-region        = local.region
        awslogs-stream-prefix = "migrate"
      }
    }
  }])
}

# ---- service ----
resource "aws_ecs_service" "backend" {
  name                               = "${local.prefix}-backend"
  cluster                            = aws_ecs_cluster.this.id
  task_definition                    = aws_ecs_task_definition.backend.arn
  desired_count                      = var.api_min_count
  launch_type                        = "FARGATE"
  platform_version                   = "LATEST"
  health_check_grace_period_seconds  = 180
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  propagate_tags                     = "SERVICE"
  enable_execute_command             = false

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = aws_subnet.app[*].id
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.api.arn
    container_name   = "backend"
    container_port   = 8080
  }

  lifecycle {
    ignore_changes = [task_definition, desired_count]
  }

  depends_on = [aws_lb_listener.https]
}

resource "aws_appautoscaling_target" "backend" {
  service_namespace  = "ecs"
  resource_id        = "service/${aws_ecs_cluster.this.name}/${aws_ecs_service.backend.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  min_capacity       = var.api_min_count
  max_capacity       = var.api_max_count
}

resource "aws_appautoscaling_policy" "backend_cpu" {
  name               = "${local.prefix}-backend-cpu"
  service_namespace  = aws_appautoscaling_target.backend.service_namespace
  resource_id        = aws_appautoscaling_target.backend.resource_id
  scalable_dimension = aws_appautoscaling_target.backend.scalable_dimension
  policy_type        = "TargetTrackingScaling"
  target_tracking_scaling_policy_configuration {
    target_value       = 60
    scale_in_cooldown  = 300
    scale_out_cooldown = 60
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}
