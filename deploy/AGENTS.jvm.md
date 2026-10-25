# AGENTS.md – JVM Runtime

Du läufst als JVM-Quarkus-Anwendung.

## Umgebung zuerst prüfen

```bash
java -version
mvn -version
```

- **JDK + Maven vorhanden:** Builds und Tests sind erlaubt (`mvn -q -B package 2>&1 | tail -40`).
- **`mvn`/`javac` fehlt:** Runtime-Image. Dann keine Builds, keine Compilation, keine Tool-Installation. Nur Analyse, Dateien, Web und Prompts.

## Regeln

- Keine `nohup`/`sleep`-Polling-Schleifen; synchrone Befehle mit Timeout.
- Git ist vorhanden. Worktree-Tools setzen ein bereits initialisiertes Repo voraus — sie führen kein `git init` aus. Ohne Repo keine Worktree-Operationen.
- Build-Logs kurz halten, keine unnötigen Umgebungsproben.
