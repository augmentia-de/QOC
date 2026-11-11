# AWS ECS Fargate – Minimaler Test-Stack

> **Status:** implemented (Test)
> **Quelle:** `deploy/aws-ecs-minimal/*.tf`, `deploy/AWS-OAUTH.md`

## Infrastruktur

| Komponente | Wert |
|---|---|
| Account | `336129193871` |
| Region | `us-east-1` |
| Cluster | `qoc-test-cluster` |
| Service | `qoc-test-service` |
| ECR | `qoc-test-native` |
| Secret | `qoc-test-secrets` |
| EFS | `fs-007960e4d7fc061bf` |
| Access Point | `fsap-0223840081a0b6485` |
| Workspace | `/mnt/workspace` |
| Task-Größe | 0.5 vCPU / 1 GB |

## Betrieb

```bash
cd deploy/aws-ecs-minimal
eval "$(aws configure export-credentials --format env)"

# Task-IP ermitteln
TASK=$(aws ecs list-tasks --cluster qoc-test-cluster --service-name qoc-test-service --region us-east-1 --desired-status RUNNING --query 'taskArns[0]' --output text)
ENI=$(aws ecs describe-tasks --cluster qoc-test-cluster --tasks "$TASK" --region us-east-1 --query 'tasks[0].attachments[0].details[?name==`networkInterfaceId`].value | [0]' --output text)
aws ec2 describe-network-interfaces --network-interface-ids "$ENI" --region us-east-1 --query 'NetworkInterfaces[0].Association.PublicIp' --output text

# Health-Check
curl -sS -u "opencode:$PASS" http://<IP>:8080/api/health

# Kosten stoppen
terraform destroy -auto-approve
```

## Kosten (ca.)

~$0.03/h, ~$0.75/Tag, ~$22-23/Monat (Fargate + Public IP + EFS + Secrets).

## Sicherheit

Siehe `06-security-hardening.md`. Zusätzlich:
- Kein TLS/HTTPS (HTTP + Basic Auth)
- Kein ALB/WAF
- Task hat Public IP (keine private Subnets)
- Shell-Sandbox deaktiviert

## Auth0-OIDC-Plan

Siehe `spec/betrieb/04-oidc-auth0.md` und `deploy/AWS-OAUTH.md`.

## Neu: Neon + Auth0 + JVM (Minimal-Stack)

- **Datenbank**: Neon Free-Tier-Postgres (`db_jdbc_url` auf Neon-JDBC-URL setzen).
- **Auth**: Auth0-OIDC (`ENGINE_SECURITY_MODE=oidc`, `QUARKUS_OIDC_*`).
- **Image**: JVM-Image (`Dockerfile.jvm`) für schnelle Iteration; Native für Produktion.
- **Lokale Emulation**: `deploy/aws-ecs-minimal/docker-compose.yml` + `.env.example`.
- **Rollen**: `build`/`plan` = `*`, alle anderen = `admin` (Default) oder explizite Rollen.

Betriebsbefehle siehe `deploy/aws-ecs-minimal/README.md`.
