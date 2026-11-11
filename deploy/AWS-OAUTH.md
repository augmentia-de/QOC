# AWS + OAuth/OIDC (Auth0) – Plan und Betrieb

Stand: 2026-11-04  
Ziel: QOC als Native-Container auf AWS ECS Fargate betreiben und mit Auth0 als
OIDC-Provider absichern.

---

## 1. Aktueller AWS-Teststand (ECS Fargate)

### Infrastruktur

| Komponente | Wert |
|---|---|
| Account | `336129193871` |
| Region | `us-east-1` |
| Cluster | `qoc-test-cluster` |
| Service | `qoc-test-service` |
| ECR-Repository | `336129193871.dkr.ecr.us-east-1.amazonaws.com/qoc-test-native` |
| Secret | `arn:aws:secretsmanager:us-east-1:336129193871:secret:qoc-test-secrets-...` |
| EFS | `fs-007960e4d7fc061bf` |
| Access Point | `fsap-0223840081a0b6485` |
| Workspace im Container | `/mnt/workspace` |
| Task-Größe | `0.5 vCPU / 1 GB` |
| Zugriff | `http://<task-public-ip>:8080` (Basic Auth) |

Die Terraform-Definition liegt unter `deploy/aws-ecs-minimal/`. Die Datei
`terraform.tfvars` enthält Secrets und ist gitignored.

### Wichtige Betriebsbefehle

```bash
cd deploy/aws-ecs-minimal
eval "$(aws configure export-credentials --format env)"

# Aktuelle Task-IP ermitteln
TASK=$(aws ecs list-tasks --cluster qoc-test-cluster --service-name qoc-test-service --region us-east-1 --desired-status RUNNING --query 'taskArns[0]' --output text)
ENI=$(aws ecs describe-tasks --cluster qoc-test-cluster --tasks "$TASK" --region us-east-1 --query 'tasks[0].attachments[0].details[?name==`networkInterfaceId`].value | [0]' --output text)
aws ec2 describe-network-interfaces --network-interface-ids "$ENI" --region us-east-1 --query 'NetworkInterfaces[0].Association.PublicIp' --output text

# Health-Check
curl -sS -u "opencode:$PASS" http://<IP>:8080/api/health

# Kosten stoppen
terraform destroy -auto-approve
```

### Kosten (Minimal-Test, ca.)

| Komponente | Kosten |
|---|---|
| Fargate 0.5 vCPU / 1 GB | ~$0.025/h, ~$18/Monat |
| Public IPv4 | ~$0.005/h, ~$3.65/Monat |
| EFS 1 GB | ~$0.30/Monat |
| Secrets Manager | ~$0.40/Monat |
| CloudWatch Logs | < $0.10/Monat |
| **Gesamt** | **~$0.03/h, ~$0.75/Tag, ~$22–23/Monat** |

Hinweis: ALB, RDS, NAT Gateway und Datenübertragung sind in dieser
Minimalvariante nicht enthalten.

---

## 2. Security-Status des AWS-Tests

**Gut:**
- Task läuft als UID/GID `1000:1000`, nicht root
- Root-Dateisystem read-only; nur `/tmp` (tmpfs) und EFS (`/mnt/workspace`) schreibbar
- EFS verschlüsselt, `transit_encryption=ENABLED`
- Secrets in AWS Secrets Manager
- Security Group: Port 8080 nur von `77.0.248.8/32`
- EFS-SG: NFS (2049) nur vom ECS-SG
- Least-ish IAM: Execution Role für ECR/Logs/Secrets, Task Role nur EFS

**Restrisiken:**
- Kein TLS/HTTPS (HTTP + Basic Auth)
- Kein ALB/WAF/Rate Limiting
- Keine private Subnets / kein NAT (Task hat Public IP)
- Shell-Sandbox deaktiviert (`ENGINE_SHELL_SANDBOX_ENABLED=false`)
- Keine Backups/Versionierung für EFS
- Deployment mit Root-Account
- Outbound offen (`0.0.0.0/0`)

Für Produktion: ALB + ACM, private Subnets + NAT, WAF, OIDC statt Basic Auth,
Backups, Egress-Regeln, getrennte IAM-Rolle.

---

## 3. Auth0-OIDC-Plan (noch nicht umgesetzt)

### Ziel

QOC mit Auth0 als OIDC-Provider absichern. Gewählter Modus: Web-App mit
Session-Cookie (Authorization Code + PKCE), sodass die UI ohne manuelles
Token arbeitet.

### Auth0-Ressourcen (Plan)

1. **Application**
   - Typ: Regular Web Application
   - Callback URLs: lokale + produktive QOC-URLs + Callback-Pfad
   - Logout URLs: lokale + produktive QOC-URLs
   - Web Origins: lokale + produktive QOC-URLs

2. **API / Resource Server**
   - Identifier/Audience, z. B. `https://qoc-api`
   - Scopes: `openid`, `profile`, `email`, optional `qoc:read`, `qoc:edit`

3. **Rollen**
   - `admin`
   - `user`

4. **Action (Post-Login)**
   - Rollen in ID-/Access-Token einfügen, z. B. unter
     `https://qoc/roles` oder `permissions`

### QOC-Konfiguration (Plan)

```properties
ENGINE_SECURITY_MODE=oidc
QUARKUS_OIDC_ENABLED=true
quarkus.oidc.auth-server-url=https://<tenant>.auth0.com/
quarkus.oidc.client-id=<client-id>
quarkus.oidc.credentials.secret=<client-secret>
quarkus.oidc.token.issuer=https://<tenant>.auth0.com/
quarkus.oidc.token.audience=<api-identifier>
quarkus.oidc.application-type=web-app
quarkus.oidc.authentication.redirect-path=<callback-path>
QUARKUS_HTTP_AUTH_PERMISSION_AUTHENTICATED_PATHS=/*
QUARKUS_HTTP_AUTH_PERMISSION_AUTHENTICATED_POLICY=authenticated
```

Wichtig: `quarkus.oidc.enabled` ist eine Buildzeit-Eigenschaft. Das Image muss
mit `QUARKUS_OIDC_ENABLED=true` gebaut werden.

### Code-Anpassungen (Plan)

- `OidcPrincipalProducer` liest derzeit Keycloak-Claims (`realm_access.roles`,
  `groups`). Für Auth0 muss das Claim-Mapping ergänzt werden, z. B. für
  `https://qoc/roles`, `permissions` oder `roles`.
- `SecurityConfig.mode()` unterstützt bereits `ENGINE_SECURITY_MODE=oidc`.
- Auth0-Rollen müssen QOC-Rollen/Agentenrechten zugeordnet werden.
- `isOwner`/Autorisierung ist derzeit ein Stub und muss für echte
  Rollendurchsetzung implementiert werden.
- UI nutzt Session-Cookie im Web-App-Modus; Logout und Session-Ablauf testen.

### Testplan (Plan)

1. Auth0-Testuser mit `admin` und `user` anlegen.
2. QOC mit OIDC-Env bauen und starten.
3. Browser-Login durchführen.
4. `/api/health`, `/api/config` und Dateioperationen mit OIDC-Principal prüfen.
5. Token-Claims und Rollen-Mapping prüfen.
6. Agent-Permissions mit Auth0-Rollen prüfen.
7. Logout, Session-Ablauf und Re-Login testen.
8. API/MCP-Clients mit Bearer-Token testen, falls gewünscht.

### Risiken / offene Punkte

- Quarkus-Callback-Pfad und Session-Cookie-Verhalten müssen gegen echte
  Auth0-URLs getestet werden.
- Auth0-Rollen-Claim-Namen müssen exakt mit dem Code abgestimmt werden.
- Native Image muss OIDC-Klassen korrekt initialisieren.
- Wenn Basic Auth parallel bleibt, muss die AuthMode-Auflösung sauber getestet
  werden.

---

## 4. Nächste Schritte

1. Auth0-Ressourcen über das verbundene MCP oder manuell anlegen.
2. Callback-/Logout-URLs für lokal, GCP und AWS eintragen.
3. Claim-Mapping in `OidcPrincipalProducer` implementieren.
4. Image mit `QUARKUS_OIDC_ENABLED=true` bauen.
5. Lokalen Test mit Browser-Login durchführen.
6. Danach GCP/AWS-Deployment mit OIDC-Env aktualisieren.

---

## 5. Referenzen

- `deploy/keycloak/README.md` – lokales Keycloak-Setup (superseded, als Referenz)
- `docs/security.md` – Sicherheitsmodell und OAuth-Zielbild
- `quarkus/src/main/java/de/augmentia/oc/engine/http/security/OidcPrincipalProducer.java`
- `quarkus/src/main/java/de/augmentia/oc/engine/http/security/SecurityConfig.java`
- `deploy/aws-ecs-minimal/` – AWS-ECS-Terraform
EOF
echo "written"