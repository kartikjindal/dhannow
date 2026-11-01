# RDS Postgres 16 (matches Testcontainers), private, encrypted, TLS forced, automated backups.
# The master password is generated and kept by RDS in Secrets Manager (never in Terraform state);
# the ECS task reads it as DB_PASSWORD. A least-privilege application role is a go-live item.

resource "aws_db_subnet_group" "this" {
  name       = local.prefix
  subnet_ids = aws_subnet.data[*].id
}

resource "aws_db_parameter_group" "this" {
  name   = "${local.prefix}-pg16"
  family = "postgres16"

  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  parameter {
    name  = "log_min_duration_statement"
    value = "1000"
  }

  parameter {
    name  = "timezone"
    value = "UTC"
  }
}

resource "aws_db_instance" "this" {
  identifier     = "${local.prefix}-postgres"
  engine         = "postgres"
  engine_version = var.postgres_engine_version
  instance_class = var.db_instance_class

  db_name  = "navix"
  username = "navix_admin"
  port     = 5432

  manage_master_user_password   = true
  master_user_secret_kms_key_id = aws_kms_key.app.arn

  allocated_storage     = var.db_allocated_storage_gb
  max_allocated_storage = var.db_max_allocated_storage_gb
  storage_type          = "gp3"
  storage_encrypted     = true
  kms_key_id            = aws_kms_key.app.arn

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.db.id]
  parameter_group_name   = aws_db_parameter_group.this.name
  publicly_accessible    = false
  multi_az               = var.db_multi_az
  ca_cert_identifier     = "rds-ca-rsa2048-g1"

  backup_retention_period  = var.db_backup_retention_days
  backup_window            = "20:00-21:00"         # 01:30-02:30 IST
  maintenance_window       = "sun:21:30-sun:22:30" # Monday 03:00-04:00 IST
  copy_tags_to_snapshot    = true
  delete_automated_backups = false

  deletion_protection       = var.db_deletion_protection
  skip_final_snapshot       = false
  final_snapshot_identifier = "${local.prefix}-postgres-final"

  auto_minor_version_upgrade      = true
  allow_major_version_upgrade     = false
  apply_immediately               = false
  enabled_cloudwatch_logs_exports = ["postgresql"]

  performance_insights_enabled    = true
  performance_insights_kms_key_id = aws_kms_key.app.arn

  tags = { Name = "${local.prefix}-postgres" }
}

locals {
  db_jdbc_url    = "jdbc:postgresql://${aws_db_instance.this.address}:${aws_db_instance.this.port}/${aws_db_instance.this.db_name}?sslmode=require"
  db_secret_arn  = aws_db_instance.this.master_user_secret[0].secret_arn
  db_password_vf = "${local.db_secret_arn}:password::"
}
