#!/usr/bin/env bash
# GCP-Git-Setup für QOC (nur lokal, kein GitHub).
# Einmalig auf der VM als root ausführen:
#   sudo bash deploy/gcp-vm/gcp-git-setup.sh
#
# Umgebungsvariablen:
#   PROJECTS="project-a project-b"   (Leerzeichen-getrennt, Default: project-a project-b)
#   GIT_USER_NAME="QOC VM"            (Default)
#   GIT_USER_EMAIL="qoc@localhost"    (Default)
#
set -euo pipefail

PROJECTS="${PROJECTS:-project-a project-b}"
GIT_USER_NAME="${GIT_USER_NAME:-QOC VM}"
GIT_USER_EMAIL="${GIT_USER_EMAIL:-qoc@localhost}"
WORKSPACE_ROOT="${WORKSPACE_ROOT:-/data/workspace}"

echo "=== Git installieren ==="
apt-get update
apt-get install -y --no-install-recommends git

echo "=== Git global konfigurieren ==="
git config --global user.name "$GIT_USER_NAME"
git config --global user.email "$GIT_USER_EMAIL"
git config --global init.defaultBranch main
git config --global core.autocrlf false

echo "=== Projekt-Ordner anlegen ==="
for p in $PROJECTS; do
    dir="$WORKSPACE_ROOT/$p"
    mkdir -p "$dir"
    chown -R qoc:qoc "$dir"
    if [[ ! -d "$dir/.git" ]]; then
        sudo -u qoc git -C "$dir" init
        echo "Git-Repo initialisiert: $dir"
    else
        echo "Git-Repo existiert bereits: $dir"
    fi
    # .gitignore anlegen, falls nicht vorhanden
    if [[ ! -f "$dir/.gitignore" ]]; then
        cat > "$dir/.gitignore" <<'EOF'
# Build-Artefakte
target/
node_modules/
dist/
build/
*.jar
*.class

# Logs
*.log
log/

# Secrets / Umgebung
.env
.env.*
*.pem
*.key

# IDE / OS
.idea/
.vscode/
*.iml
.DS_Store
EOF
        chown qoc:qoc "$dir/.gitignore"
        echo ".gitignore angelegt: $dir"
    fi
done

echo "=== Git-Setup abgeschlossen ==="
echo "Projekte: $PROJECTS"
echo "Workspace: $WORKSPACE_ROOT"
echo ""
echo "Nächste Schritte:"
echo "  1. In einem Projekt-Ordner arbeiten: cd $WORKSPACE_ROOT/<projekt>"
echo "  2. Dateien hinzufügen: git add -A"
echo "  3. Committen: git commit -m '...'"
echo "  4. (Optional) Remote hinzufügen: git remote add origin <url>"
echo "  5. (Optional) Pushen: git push -u origin main"
