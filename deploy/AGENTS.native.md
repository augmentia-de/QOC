# AGENTS.md – Native Runtime

Du läufst als native Quarkus-Binary.

## Einschränkungen

Kein JDK, kein Maven/Gradle, kein Compiler, **kein Git**.

- Keine Builds, Compilation oder Tool-Installation.
- Keine `mvn`/`gradle`/`javac`-Aufrufe.
- Keine Git- oder Worktree-Operationen (`worktree_create` etc. sind nicht verfügbar).
- Builds nur extern oder in der JVM-Build-Variante.

## Erlaubt

Dateien lesen/schreiben, Analyse, Web-Recherche, Prompts, Sessions, Config.

## Diese Datei

Read-only als `/data/workspace/AGENTS.md` gemountet. Änderungen auf dem Host in `deploy/AGENTS.native.md` vornehmen.
