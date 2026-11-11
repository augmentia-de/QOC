data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "public" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
  filter {
    name   = "map-public-ip-on-launch"
    values = ["true"]
  }
}

locals {
  subnet_id = tolist(data.aws_subnets.public.ids)[0]
}

resource "aws_security_group" "ecs" {
  name        = "${var.name}-ecs"
  description = "QOC Fargate tasks"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "HTTP from my IP"
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = [var.my_ip_cidr]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_security_group" "efs" {
  name        = "${var.name}-efs"
  description = "EFS for QOC workspace"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description     = "NFS from ECS tasks"
    from_port       = 2049
    to_port         = 2049
    protocol        = "tcp"
    security_groups = [aws_security_group.ecs.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_efs_file_system" "workspace" {
  creation_token = "${var.name}-workspace"
  encrypted      = true
  tags = {
    Name = "${var.name}-workspace"
  }
}

resource "aws_efs_mount_target" "workspace" {
  file_system_id  = aws_efs_file_system.workspace.id
  subnet_id       = local.subnet_id
  security_groups = [aws_security_group.efs.id]
}

resource "aws_efs_access_point" "workspace" {
  file_system_id = aws_efs_file_system.workspace.id

  posix_user {
    uid = 1000
    gid = 1000
  }

  root_directory {
    path = "/qoc"
    creation_info {
      owner_uid   = 1000
      owner_gid   = 1000
      permissions = "755"
    }
  }
}

resource "aws_ecr_repository" "qoc" {
  name                 = "${var.name}-native"
  image_tag_mutability = "MUTABLE"
  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_cloudwatch_log_group" "qoc" {
  name              = "/ecs/${var.name}"
  retention_in_days = 7
}

resource "aws_secretsmanager_secret" "qoc" {
  name = "${var.name}-secrets"
}

resource "aws_secretsmanager_secret_version" "qoc" {
  secret_id = aws_secretsmanager_secret.qoc.id
  secret_string = jsonencode({
    OPENAI_API_KEY              = var.openai_api_key
    QUARKUS_DATASOURCE_JDBC_URL = var.db_jdbc_url
    QUARKUS_DATASOURCE_USERNAME = var.db_username
    QUARKUS_DATASOURCE_PASSWORD = var.db_password
    OPENCODE_SERVER_PASSWORD    = var.server_password
  })
}

data "aws_iam_policy_document" "ecs_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "execution" {
  name               = "${var.name}-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

resource "aws_iam_role_policy_attachment" "execution_managed" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "execution_secrets_efs" {
  name = "${var.name}-execution-extras"
  role = aws_iam_role.execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["secretsmanager:GetSecretValue"]
        Resource = [aws_secretsmanager_secret.qoc.arn]
      },
      {
        Effect = "Allow"
        Action = [
          "elasticfilesystem:ClientMount",
          "elasticfilesystem:ClientWrite",
          "elasticfilesystem:DescribeMountTargets"
        ]
        Resource = [aws_efs_file_system.workspace.arn]
      }
    ]
  })
}

resource "aws_iam_role" "task" {
  name               = "${var.name}-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

resource "aws_iam_role_policy" "task_efs" {
  name = "${var.name}-task-efs"
  role = aws_iam_role.task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "elasticfilesystem:ClientMount",
          "elasticfilesystem:ClientWrite",
          "elasticfilesystem:DescribeMountTargets"
        ]
        Resource = [aws_efs_file_system.workspace.arn]
      }
    ]
  })
}

resource "aws_ecs_cluster" "qoc" {
  name = "${var.name}-cluster"
}

resource "aws_ecs_task_definition" "qoc" {
  family                   = "${var.name}-task"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "512"
  memory                   = "1024"
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  volume {
    name = "workspace"
    efs_volume_configuration {
      file_system_id     = aws_efs_file_system.workspace.id
      transit_encryption = "ENABLED"
      authorization_config {
        access_point_id = aws_efs_access_point.workspace.id
      }
    }
  }

  container_definitions = jsonencode([
    {
      name      = "qoc"
      image     = "${aws_ecr_repository.qoc.repository_url}:latest"
      essential = true
      user      = "1000:1000"
      linuxParameters = {
        readonlyRootFilesystem = true
        tmpfs = [
          {
            containerPath = "/tmp"
            size          = 512
          }
        ]
      }
      portMappings = [
        {
          containerPort = 8080
          protocol      = "tcp"
        }
      ]
      mountPoints = [
        {
          sourceVolume  = "workspace"
          containerPath = "/mnt/workspace"
          readOnly      = false
        }
      ]
      environment = [
        { name = "QUARKUS_HTTP_HOST", value = "0.0.0.0" },
        { name = "QUARKUS_HTTP_PORT", value = "8080" },
        { name = "ENGINE_WORKSPACE", value = "/mnt/workspace" },
        { name = "HOME", value = "/mnt/workspace/.home" },
        { name = "LOG_DIR", value = "/tmp/log" },
        { name = "QUARKUS_LOG_FILE_PATH", value = "/tmp/qoc.log" },
        { name = "ENGINE_DB_KIND", value = "postgresql" },
        { name = "ENGINE_DB_DRIVER", value = "org.postgresql.Driver" },
        { name = "ENGINE_SHELL_SANDBOX_ENABLED", value = "false" },
        { name = "ENGINE_REVERT_SNAPSHOT", value = "false" },
        { name = "ENGINE_MAX_CONCURRENT_PROMPTS", value = "4" },
        { name = "ENGINE_TURN_MAX_WALL_MINUTES", value = "10" },
        { name = "ENGINE_PROVIDER_SCRIPTED", value = "false" },
        { name = "OPENAI_BASE_URL", value = "https://opencode.ai/zen/go/v1" },
        { name = "ENGINE_PROVIDER_URL", value = "https://opencode.ai/zen/go/v1/chat/completions" },
        { name = "OPENAI_MODEL", value = "deepseek-v4.1-flash" },
        { name = "ENGINE_MODEL_ID", value = "deepseek-v4.1-flash" },
        { name = "QUARKUS_OTEL_ENABLED", value = "false" },
        { name = "ENGINE_SECURITY_MODE", value = var.auth_mode },
        { name = "QUARKUS_OIDC_AUTH_SERVER_URL", value = var.auth0_issuer },
        { name = "QUARKUS_OIDC_TOKEN_ISSUER", value = var.auth0_issuer },
        { name = "QUARKUS_OIDC_TOKEN_AUDIENCE", value = var.auth0_audience }
      ]
      secrets = [
        { name = "OPENAI_API_KEY", valueFrom = "${aws_secretsmanager_secret.qoc.arn}:OPENAI_API_KEY::" },
        { name = "QUARKUS_DATASOURCE_JDBC_URL", valueFrom = "${aws_secretsmanager_secret.qoc.arn}:QUARKUS_DATASOURCE_JDBC_URL::" },
        { name = "QUARKUS_DATASOURCE_USERNAME", valueFrom = "${aws_secretsmanager_secret.qoc.arn}:QUARKUS_DATASOURCE_USERNAME::" },
        { name = "QUARKUS_DATASOURCE_PASSWORD", valueFrom = "${aws_secretsmanager_secret.qoc.arn}:QUARKUS_DATASOURCE_PASSWORD::" },
        { name = "OPENCODE_SERVER_PASSWORD", valueFrom = "${aws_secretsmanager_secret.qoc.arn}:OPENCODE_SERVER_PASSWORD::" }
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.qoc.name
          "awslogs-region"        = var.region
          "awslogs-stream-prefix" = "qoc"
        }
      }
    }
  ])
}

resource "aws_ecs_service" "qoc" {
  name            = "${var.name}-service"
  cluster         = aws_ecs_cluster.qoc.id
  task_definition = aws_ecs_task_definition.qoc.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = [local.subnet_id]
    security_groups  = [aws_security_group.ecs.id]
    assign_public_ip = true
  }

  depends_on = [aws_efs_mount_target.workspace]
}
