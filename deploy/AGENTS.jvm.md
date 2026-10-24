# AGENTS.md – JVM Runtime

Diese Instanz läuft als JVM-Quarkus-Anwendung.

## Build-Umgebung zuerst prüfen

Die JVM-Variante gibt es in zwei Ausprägungen:

1. **Runtime-Image** (`deploy/Dockerfile`): nur ein JRE, kein Maven, kein `javac`.
2. **Build-Image**: JDK, Maven und ein persistenter Maven-Cache.

Prüfe vor Build-Versuchen die Umgebung:

```bash
java -version
mvn -version
```

Wenn `mvn` oder `javac` fehlt, ist dies die reine Runtime-Variante. Dann gelten
die Einschränkungen der Native-Instanz: keine Builds, keine Compilation, keine
Installation von Build-Tools.

## Regeln für die Build-Variante

- Builds und Tests sind hier möglich.
- Nutze `mvn -q -B package` statt Hintergrundprozessen mit `sleep`.
- `JAVA_HOME` und `MAVEN_HOME` sind gesetzt.
- Nutze den Maven-Cache, um wiederholte Downloads zu vermeiden.
- Führe bei Fehlern nur gezielte Diagnosebefehle aus, nicht endlose Polling-Schleifen.

## Regeln für die Runtime-Variante

- Versuche keine Builds.
- Versuche keine Compilation.
- Versuche keine Installation von Build-Tools.
- Nutze die Instanz nur für Analyse, Dateioperationen, Web-Recherche und reine Prompt-Ausführung.
- Wenn ein Build nötig ist, verwende die JVM-Build-Variante oder führe den Build extern aus.

## Git und Worktrees

Git ist in der JVM-Variante vorhanden und unterstützt die Worktree-Tools
(`worktree_create`, `worktree_list`, `worktree_remove`).

Git kann mit `git init` ein Repository anlegen. Die Worktree-Tools führen aber
**kein** `git init` aus; sie setzen ein bereits initialisiertes Repository im
Workspace voraus. Ist der Workspace noch kein Git-Repository, muss er zuerst
initialisiert werden, z. B. mit `git init` und einem ersten Commit.

Ist der Workspace kein Git-Repository, dürfen keine Worktree-Operationen
versucht werden.

## Arbeitsweise

- Projekte im Workspace erstellen oder ändern
- Maven-Builds und Tests ausführen, sofern JDK/Maven vorhanden sind
- Fehler analysieren und beheben
- Dateien lesen, schreiben, patchen
- Git-Worktrees, sofern der Workspace ein Git-Repository ist

## Empfehlungen

- Verwende kurze, gezielte Shell-Befehle.
- Nutze `mvn -q -B package 2>&1 | tail -40`.
- Verwende keine `nohup`/`sleep`-Polling-Schleifen, wenn ein synchroner Befehl mit Timeout ausreicht.
- Nutze `tools.allow` passend, z. B. `read`, `write`, `edit`, `patch`, `glob`, `grep`, `shell`.

## Nicht empfohlen

- Unnötige Umgebungsprobes
- Wiederholte Downloads
- Sehr lange Build-Logs in den Kontext schreiben
