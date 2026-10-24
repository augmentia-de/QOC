# AGENTS.md – Native Runtime

Diese Instanz läuft als native Quarkus-Binary.

## Wichtige Einschränkung

In dieser Umgebung gibt es **keine Build-Tools** und **kein Git**:

- kein JDK
- kein `javac`
- kein Maven
- kein Gradle
- kein Git (auch nicht optional)
- keine Compiler-Toolchain

## Regeln für Agenten

- Versuche keine Builds.
- Versuche keine Compilation.
- Versuche keine Installation von Build-Tools.
- Führe keine `mvn`, `gradle`, `javac` oder ähnlichen Befehle aus.
- Nutze diese Instanz nur für Analyse, Dateioperationen, Web-Recherche und reine Prompt-Ausführung.
- Wenn ein Build nötig ist, muss die JVM-Build-Variante mit JDK/Maven verwendet werden oder der Benutzer muss den Build extern ausführen.

## Git und Worktrees

Git ist in dieser Native-Instanz **nicht installiert**. Die Worktree-Tools
(`worktree_create`, `worktree_list`, `worktree_remove`) sind daher nicht
verfügbar. Versuche nicht, Git nachzuinstallieren oder Worktree-Operationen
auszuführen.

Git-Worktrees erfordern die JVM-Variante.

## Verfügbare Arbeitsweise

- Dateien lesen und analysieren
- Texte und Code generieren
- HTTP/Web-Recherche
- Session- und Agent-Verwaltung
- Datenbank- und Config-Operationen

## Nicht verfügbar

- Java-Compilation
- Maven-Builds
- Gradle-Builds
- Paketinstallation
- Toolchain-Setup
- Git-Operationen
- Git-Worktrees (Git ist nicht installiert)

## Diese Datei

Im Compose-Betrieb wird diese Datei read-only als
`/data/workspace/AGENTS.md` eingebunden. Änderungen daran werden nicht
persistiert. Passe stattdessen `deploy/AGENTS.native.md` auf dem Host an.
