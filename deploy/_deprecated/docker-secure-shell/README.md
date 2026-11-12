# Shell-isoliertes Deployment (Port 8076)

Gehärtete Variante, in der das Shell-Tool **ausschließlich `/data/home`**
erreichen kann. Andere Datenpfade (`/data/workspace`, `/data/qoc.db`,
`../log`, Quellcode, Maven-Cache, Docker-Socket) existieren im Container
nicht – auch nicht über Skripte, `cd ..`, Variablen oder Symlinks.

Die Definition liegt in `deploy/docker-compose.yml` (Profil `secure-shell`).

## Start

```bash
cd deploy
docker compose up -d qoc-secure-shell
```

- UI: http://localhost:8076/
- Health: http://localhost:8076/api/health/ready
- Agent-Workspace: `/data/home/work` (im Container)
- HOME: `/data/home`

## Wie die Grenze funktioniert

Es wird genau **ein** Host-Verzeichnis gemountet:

```yaml
volumes:
  - ./docker-secure-shell/home:/data/home:ro
  - ./docker-secure-shell/home/work:/data/home/work
```

`/data/home` ist read-only; nur der Agent-Workspace `/data/home/work` ist
beschreibbar. Damit kann ein Shell-Skript auch keine globalen Agenten oder
`$HOME/.config/opencode/settings.json` verändern, um sich selbst mehr Rechte
zu geben.

Damit ist der Mount-Namespace des Containers die Grenze:

| Pfad im Container | Existiert? |
|---|---|
| `/data/home` | ja (Bind-Mount, read-only) |
| `/data/home/work` | ja (Bind-Mount, beschreibbar) |
| `/data/workspace` | nein |
| `/data/qoc.db` | nein |
| `/data/log` | nein |
| Host-Repository | nein |
| `/var/run/docker.sock` | nein |

Ein Shell-Aufruf läuft als normaler Prozess im selben Namespace. Er kann
deshalb nur Pfade sehen, die dort gemountet sind. `cd /data && ls` zeigt
ausschließlich `home`.

### Zusätzliche Schichten

- `ENGINE_PERMISSION_DEFAULT=least-privilege` und `ENGINE_PERMISSION_ASK_MODE=deny`:
  Pfade außerhalb des Workspace werden schon vom Pfad-Scanner des Shell-Tools
  als `external_directory` abgelehnt (Defense in Depth, kein Ersatz für die
  Mount-Grenze).
- `ENGINE_AGENT_AUTHORING_ENABLED=false`: Agenten können keine eigenen
  Agenten mit weiteren Rechten anlegen.
- `ENGINE_SETTINGS_WORKSPACE_ENABLED=false`: eine `settings.json` im Workspace
  kann die Berechtigungen nicht hochsetzen.
- `ENGINE_EMAIL_ENABLED=false`: kein Mail-Versand aus dem Workspace.
- read-only Container-Root, `cap_drop: ALL`, `no-new-privileges`,
  `pids_limit: 256`.
- Seccomp-Deny-Liste (`deploy/seccomp-qoc-shell.json`) blockiert u. a.
  `ptrace`, `process_vm_readv/writev`, `mount`, `pivot_root`, `chroot`,
  `setns`, `unshare`, `bpf`, `perf_event_open`, `keyctl`. Damit kann ein
  Shell-Skript den Engine-Prozess nicht debuggen oder fremden Speicher lesen.

## Verifizieren

```bash
docker exec qoc-secure-shell sh -c '
  echo "--- /data";  ls -A /data
  echo "--- /";      ls -A /
  test -e /data/workspace && echo WORKSPACE_LEAK || echo WORKSPACE_BLOCKED
  test -e /data/qoc.db    && echo DB_LEAK        || echo DB_BLOCKED
  test -e /app           && echo APP_PRESENT     || echo APP_ABSENT
'
```

Erwartet: `/data` enthält nur `home`; `WORKSPACE_BLOCKED`, `DB_BLOCKED`.
`/app` ist das Image (nur die Anwendung, kein Quellcode).

## Grenzen (bewusst offen)

- Der Container sieht sein eigenes Betriebssystem (`/usr`, `/etc`, `/proc`).
  Das sind Image-Dateien, keine Host-Daten.
- `/proc/1/environ` ist für denselben UID lesbar. Secrets, die per
  `env_file` gesetzt werden (API-Key, DB-Passwort), sind damit für einen
  Shell-Prozess lesbar. Für vollständige Geheimnis-Isolation müsste der
  Engine-Prozess unter einer anderen UID laufen oder der Host unprivilegierte
  Namespaces erlauben (bwrap).
- Netzwerkzugriffe des Shell-Tools sind nicht pro Prozess begrenzt; der
  Container braucht LLM- und DB-Zugriff.
- bwrap ist deaktiviert, weil dieser Host keine unprivilegierten Namespaces
  zulässt (`kernel.apparmor_restrict_unprivileged_userns=1`). Die Grenze
  kommt deshalb von den Mounts, nicht vom Sandbox-Runner.

## Daten

- `deploy/docker-secure-shell/home` ist der einzige persistente Pfad.
- `home/` ist read-only, `home/work/` ist der beschreibbare Workspace.
- Inhalt wird nicht versioniert (siehe `.gitignore`), nur `.gitkeep`.
- Der Workspace muss dem Container-UID gehören (`1000:1000`), sonst kann der
  Prozess nicht schreiben. Bei abweichender UID/GID:

  ```bash
  chown -R "$(id -u):$(id -g)" docker-secure-shell/home
  ```
