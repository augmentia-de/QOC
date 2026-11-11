# 04 – OIDC mit Auth0 (spec-only)

> **Status:** spec-only
> **Zweck:** Auth0 als OIDC-Provider für QOC (Web-App-Modus mit Session-Cookie).

## 1. Auth0-Ressourcen

1. **Application** (Regular Web Application)
   - Callback URLs: lokale + produktive QOC-URLs + Callback-Pfad
   - Logout URLs: lokale + produktive QOC-URLs
   - Web Origins: lokale + produktive QOC-URLs
2. **API / Resource Server**
   - Identifier/Audience, z. B. `https://qoc-api` oder `qoc-engine`
   - Scopes: `openid`, `profile`, `email`, optional `qoc:read`, `qoc:edit`
3. **Rollen**: `admin`, `user`
4. **Action (Post-Login)**: Rollen in ID-/Access-Token einfügen

## 2. QOC-Konfiguration

```properties
ENGINE_SECURITY_MODE=oidc
QUARKUS_OIDC_ENABLED=true
quarkus.oidc.auth-server-url=https://<tenant>.auth0.com/
quarkus.oidc.client-id=<client-id>
quarkus.oidc.credentials.secret=<client-secret>
quarkus.oidc.token.issuer=https://<tenant>.auth0.com/
quarkus.oidc.token.audience=<api-identifier>
quarkus.oidc.application-type=web-app
```

Wichtig: `quarkus.oidc.enabled` ist eine Buildzeit-Eigenschaft.

## 3. Code-Anpassungen

- `OidcPrincipalProducer`: Claim-Mapping für Auth0 (z. B. `https://qoc/roles`, `permissions`)
- `SecurityConfig.mode()`: unterstützt bereits `ENGINE_SECURITY_MODE=oidc`
- Auth0-Rollen → QOC-Rollen/Agentenrechten
- `isOwner`/Autorisierung implementieren

## 4. Testplan

1. Auth0-Testuser mit `admin` und `user` anlegen
2. QOC mit OIDC-Env bauen und starten
3. Browser-Login
4. `/api/health`, `/api/config`, Dateioperationen prüfen
5. Token-Claims und Rollen-Mapping prüfen
6. Logout, Session-Ablauf, Re-Login

## 5. Risiken

- Quarkus-Callback-Pfad und Session-Cookie-Verhalten
- Auth0-Rollen-Claim-Namen müssen mit Code abgestimmt werden
- Native Image muss OIDC-Klassen initialisieren
- Basic Auth parallel: AuthMode-Auflösung testen
