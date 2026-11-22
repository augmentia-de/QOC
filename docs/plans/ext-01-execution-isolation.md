# Erweiterung: Execution Isolation (Sandbox)

**Wofür:** QOC führt Shell, Dateien und Prozesse heute auf dem Host aus. Permissions und Shell-Scanner sind keine Isolation. Diese Erweiterung fügt eine **optionale, per Session/containerisierte Ausführungsumgebung** hinzu, ohne den Standardpfad (lokale Ausführung) zu verändern.

**Gewinn:** Untrusted Agenten-Code (Shell, Build, Tests, MCP-stdio-Server) läuft isoliert mit Resource-Limits, eingeschränktem Netzwerk und sauberem Cleanup. Trusted-Developer-Workflows bleiben unverändert schnell. QOC wird als sichere Runtime für fremden Code einsetzbar, ohne das bestehende Sicherheitsmodell zu ersetzen.

---

## 1. Prinzip: Optionalität

- **Default `local`** = heutiges Verhalten, keine Regression.
- Sandbox ist ein **Adapter hinter bestehenden Ports**, kein neuer Core-Pfad.
- Der Core kennt keine Sandbox; er erhält einfach einen `ProcessRunner`/`FileSystem`. Welcher Adapter gewählt wird, ist eine **Wiring-Entscheidung** (Quarkus-Startup/CLI), nicht eine Core-Entscheidung.
- Aktivierung per Konfiguration; deaktiviert = exakt heutiges Verhalten.
- Permissions, Shell-Scanner und OS-Hardening bleiben bestehen und wirken **zusätzlich** zur Sandbox (Defense in Depth).

## 2. Betroffene Ports (keine neuen Core-Ports nötig)

| Port | Sandbox-Adapter |
|---|---|
| `core/port/world/ProcessRunner` | `SandboxedProcessRunner` |
| `core/port/world/FileSystem` | `SandboxedFileSystem` |
| `core/port/world/HttpTransport` | `EgressProxy` (Allowlist/Proxy) |
| `core/port/world/RemoteShell` | `SandboxedRemoteShell` (optional) |

Optionaler neuer Port `ExecutionEnvironment` nur, wenn Lifecycle (create/destroy/checkpoint) nicht über die bestehenden Ports ausdrückbar ist. Bevorzugt: Lifecycle im Adapter kapseln, Core bleibt unberührt.

## 3. Architektur

### 3.1 Neues Adapter-Paket

`adapter/sandbox/` (neu, optional, eigenes Submodul möglich):

- `SandboxedProcessRunner` – implementiert `ProcessRunner`
- `SandboxedFileSystem` – implementiert `FileSystem`
- `EgressProxy` / `NetworkPolicy` – für `HttpTransport`
- `SandboxLifecycle` – create/destroy/checkpoint/reconcile
- `SandboxConfig` – Konfiguration

### 3.2 Modi

| Modus | Beschreibung |
|---|---|
| `local` (Default) | Heutige Adapter, keine Isolation. |
| `container` | Rootless Podman pro Session, Workspace-Mount, Resource-Limits. |
| `microvm` (später) | QEMU/KVM-Sandboxes für stärkere Isolation. |

### 3.3 Lebenszyklus

- Container pro Session oder pro Execution (konfigurierbar).
- Workspace als Bind-Mount (read/write) oder Kopie.
- Cleanup bei Session-Ende, Timeout oder Crash (best-effort + Reconciler).
- Checkpoints optional (Container-Snapshot oder Workspace-Snapshot).

### 3.4 Egress-Policy

- `outbound-only` (Default im Sandbox-Modus): nur ausgehende Verbindungen, Host-Allowlist.
- `none`: kein Netzwerk.
- `allowlist`: explizite Liste erlaubter Hosts.
- Umsetzung über Proxy oder Container-Netzwerk-Policy; `HttpTransport`-Adapter routet über den Proxy.

### 3.5 Tool-Kompatibilität

- **PTY:** Container mit TTY-Unterstützung.
- **Background-Prozesse:** Container lebt, bis die Session endet.
- **MCP stdio:** Server als Prozess im Container oder als Sidecar.
- **Worktrees/Git:** Workspace-Mount + Git im Container.
- **Secrets:** über Env/File-Mount injiziert, nie ins Image.

## 4. Wo in QOC

| Ort | Änderung |
|---|---|
| `adapter/sandbox/*` | **Neu.** Alle Sandbox-Adapter. |
| Quarkus-Startup / CLI | Adapter-Auswahl anhand von `qoc.sandbox.mode`; `ToolDependencies` erhält sandboxed Adapter. |
| `application.properties` / settings | `qoc.sandbox.mode`, `qoc.sandbox.image`, `qoc.sandbox.network`, `qoc.sandbox.cpu`, `qoc.sandbox.memory`, `qoc.sandbox.timeout`. |
| Tests | Contract-Tests gegen Port-Interfaces + Isolationstests (Escape, Egress, Secret-Leak, Cleanup). |
| `core`, `runtime`, `workflow-runner` | **Keine Änderung.** |

## 5. Phasen

1. **Phase 1:** Container-Modus (rootless Podman) + Workspace-Mount.
2. **Phase 2:** Egress-Policy (Allowlist/Proxy).
3. **Phase 3:** Checkpoints (Workspace-Snapshot; Container-Snapshot optional).
4. **Phase 4:** MicroVM (nur bei belegtem Bedarf).

## 6. Was genau vergleichen (Experiment E4)

Startup-Latenz, p95 Tool-Latenz, CPU/Speicher, Isolationstests (Escape, Egress, Secret-Leak), Cleanup-Zeit, Kompatibilität (PTY, Background, MCP stdio, Git).

## 7. Risiken

- PTY/Background/MCP-stdio-Kompatibilität.
- Resource-Overhead und Startup-Latenz.
- Cleanup bei Crash (Reconciler nötig).
- Betriebskomplexität (Podman-Installation, Image-Management).

## 8. Nicht-Ziele

- Keine Änderung am Permission-Modell (Permissions bleiben; Sandbox ist zusätzliche Schicht).
- Kein Ersatz für Shell-Scanner (komplementär).
- Kein Kubernetes-Operator (siehe kagent-Learnings).
- Keine MicroVM in Phase 1.

---

## Status der Implementierung (22.11.2026)

**Bereits implementiert (Engine-Modul):**
- `adapter/sandbox/SandboxConfig` (Record: mode, image, network, cpuLimit, memoryMb, timeoutSeconds, workspaceRoot, allowedHosts).
- `adapter/sandbox/SandboxedProcessRunner` (implementiert `ProcessRunner`; Path-Guard gegen Workspace-Escape; Container-Modus mit Podman-Wrapper + Fallback auf local wenn Podman fehlt).
- `adapter/sandbox/SandboxedProcessRunnerTest` (2 Tests: Path-Guard ablehnt Escape, local-Modus führt `echo` aus).
- `FileSystem`-Sandboxing ist bereits durch `NioFileSystem(root)` abgedeckt (root-basierte Normalisierung).

**Noch offen:** `SandboxedFileSystem` (expliziter Wrapper), Egress-Policy (`EgressProxy`), Checkpoints (`SandboxLifecycle`), Reconciler, MicroVM (Phase 4), Quarkus-Wiring + Config.

## Offene Todos (detailiert)

### Todo S1 – `SandboxedFileSystem` (optional, Wrapper)
- **Was:** Expliziter Adapter, der `FileSystem` implementiert und jeden Pfad gegen `workspaceRoot` prüft (Symlink-Traversal verhindern). Delegiert an `NioFileSystem`.
- **Wo:** `engine/src/main/java/de/augmentia/oc/engine/adapter/sandbox/SandboxedFileSystem.java`
- **Wie:** Konstruktor `(SandboxConfig, FileSystem delegate)`. Jede Methode: `if (!normalized.startsWith(root)) throw new IllegalArgumentException(...)`. Delegiere an `delegate`.
- **Warum optional:** `NioFileSystem(root)` macht das bereits implizit; expliziter Guard nur für Defense-in-Depth.

### Todo S2 – Egress-Policy (`EgressProxy`)
- **Was:** `HttpTransport`-Adapter, der Requests über eine Allowlist/Proxy routet. Modi: `outbound-only` (Default), `none`, `allowlist`.
- **Wo:** `engine/src/main/java/de/augmentia/oc/engine/adapter/sandbox/EgressProxy.java` (implementiert `HttpTransport`).
- **Wie:** Delegate an `JdkHttpTransport`; vor jedem Request Host gegen `allowedHosts` prüfen. Bei `none`: sofort Fehler. Bei `allowlist`: nur gelistete Hosts.
- **Config:** `qoc.sandbox.network`, `qoc.sandbox.allowedHosts`.

### Todo S3 – Checkpoints (`SandboxLifecycle`)
- **Was:** Container/Workspace-Snapshot erstellen und wiederherstellen.
- **Wo:** `adapter/sandbox/SandboxLifecycle.java`.
- **Wie:** Workspace-Snapshot via bestehendem `SnapshotStore` (Datei-Map); Container-Snapshot via `podman commit` (optional, Phase 3). Restore: Workspace zurückschreiben + Container neu starten.
- **Abhängigkeit:** `SnapshotStore`-Port (bereits vorhanden).

### Todo S4 – Reconciler bei Crash
- **Was:** Orphaned Container/Workspace nach Crash aufräumen.
- **Wo:** `adapter/sandbox/SandboxReconciler.java` + Quarkus-Startup-Hook.
- **Wie:** Beim Start `podman ps --filter label=qoc.session` auflisten und Container ohne aktive Session entfernen; Workspace-Verzeichnisse prüfen.

### Todo S5 – MicroVM (Phase 4)
- **Was:** QEMU/KVM-basierte Sandboxes.
- **Wo:** `adapter/sandbox/MicroVmProcessRunner.java`.
- **Wie:** Nur wenn Bedarf belegt (Experiment E4); VM-Image, SSH-Zugriff, Snapshot/Restore. Hoher Aufwand, später.

### Todo S6 – Quarkus-Wiring
- **Was:** `SandboxConfig` aus Properties bauen, `SandboxedProcessRunner` als `ProcessRunner` in `ToolDependencies` setzen, wenn `qoc.sandbox.mode != local`.
- **Wo:** `quarkus/src/main/java/de/augmentia/oc/engine/http/Engine.java` (Startup, dort wo `ToolDependencies` gebaut wird).
- **Wie:** `SandboxConfig cfg = new SandboxConfig(properties...); ProcessRunner pr = cfg.isContainerMode() ? new SandboxedProcessRunner(cfg) : new JdkProcessRunner();` dann in `ToolDependencies` übergeben.

## Erkenntnisse für unabhängige Implementierung

- **NioFileSystem(root) reicht für Dateisystem-Sandboxing.** Kein eigener Adapter nötig; der Root-Guard ist bereits im `normalize()`-Pfad. Expliziter Wrapper (S1) nur für Defense-in-Depth.
- **Podman ist optional.** `SandboxedProcessRunner` prüft `podman --version`; bei Fehlen Fallback auf local. Betreiber müssen Podman installieren, sonst kein Container-Modus.
- **Path-Guard muss vor Delegation greifen**, nicht nach (verhindert TOCTOU). `resolved.startsWith(root)` mit normalisierten absoluten Pfaden.
- **Handle/Exit-Interface** muss 1:1 delegiert werden; keine Semantik-Änderung in local-Modus.
- **Egress-Policy** kann nicht im Container allein durchgesetzt werden; benötigt Proxy oder Host-Firewall. Empfehlung: outbound-only als Default, Allowlist über Proxy.
- **Secrets nie ins Image** – nur über Env/File-Mount zur Laufzeit.
- **Tests:** Isolationstests (Escape, Egress, Secret-Leak, Cleanup) sind Pflicht vor Produktivnutzung. Experiment E4 liefert die Messbasis.
