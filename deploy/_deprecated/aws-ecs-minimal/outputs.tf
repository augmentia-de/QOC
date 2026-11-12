output "ecr_repository_url" {
  value = aws_ecr_repository.qoc.repository_url
}

output "cluster_name" {
  value = aws_ecs_cluster.qoc.name
}

output "service_name" {
  value = aws_ecs_service.qoc.name
}

output "secret_arn" {
  value = aws_secretsmanager_secret.qoc.arn
}

output "efs_id" {
  value = aws_efs_file_system.workspace.id
}

output "access_point_id" {
  value = aws_efs_access_point.workspace.id
}
