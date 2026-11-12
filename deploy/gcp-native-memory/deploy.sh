#!/usr/bin/env bash
# Deployt QOC (native, Memory-Modus) auf eine GCP-VM.
# Voraussetzung: gcloud CLI konfiguriert, native Binary gebaut.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

PROJECT="${GCP_PROJECT:-gen-lang-client-0960862878}"
ZONE="${GCP_ZONE:-us-central1-a}"
VM="${GCP_VM:-quad-e2micro}"
ENV_FILE="${ENV_FILE:-$SCRIPT_DIR/env.memory.example}"

NATIVE_BINARY="$REPO_ROOT/quarkus/target/quarkus-1.0.0-SNAPSHOT-runner"
if [ ! -f "$NATIVE_BINARY" ]; then
    echo "Native binary nicht gefunden. Baue zuerst: $SCRIPT_DIR/build-memory.sh"
    exit 1
fi

echo "=== QOC Memory-Modus Deployment ==="
echo "Project: $PROJECT"
echo "Zone:    $ZONE"
echo "VM:      $VM"
echo ""

echo "Stoppe Service..."
gcloud compute ssh "$VM" --project "$PROJECT" --zone "$ZONE" --quiet \
  --command="sudo systemctl stop qoc || true"

echo "Kopiere Binary, Env, Prepare-Skript, Service-Unit, AGENTS.md..."
gcloud compute scp "$NATIVE_BINARY" "$VM:/tmp/qoc-runner-new" \
  --project "$PROJECT" --zone "$ZONE" --quiet
gcloud compute scp "$ENV_FILE" "$VM:/tmp/qoc-env-new" \
  --project "$PROJECT" --zone "$ZONE" --quiet
gcloud compute scp "$SCRIPT_DIR/qoc-prepare-memory.sh" "$VM:/tmp/qoc-prepare-memory.sh" \
  --project "$PROJECT" --zone "$ZONE" --quiet
gcloud compute scp "$SCRIPT_DIR/qoc-memory.service" "$VM:/tmp/qoc-memory.service" \
  --project "$PROJECT" --zone "$ZONE" --quiet
gcloud compute scp "$REPO_ROOT/deploy/AGENTS.native.md" "$VM:/tmp/AGENTS.native.md" \
  --project "$PROJECT" --zone "$ZONE" --quiet

gcloud compute ssh "$VM" --project "$PROJECT" --zone "$ZONE" --quiet --command='
set -e
sudo mkdir -p /etc/qoc /opt/qoc
sudo install -o qoc -g qoc -m 755 /tmp/qoc-runner-new /opt/qoc/qoc-runner
sudo install -o root -g root -m 644 /tmp/qoc-env-new /etc/qoc/env
sudo install -o root -g root -m 755 /tmp/qoc-prepare-memory.sh /usr/local/bin/qoc-prepare-memory.sh
sudo install -o root -g root -m 644 /tmp/qoc-memory.service /etc/systemd/system/qoc.service
sudo install -o qoc -g qoc -m 644 /tmp/AGENTS.native.md /data/workspace/AGENTS.md
sudo systemctl daemon-reload
sudo /usr/local/bin/qoc-prepare-memory.sh
'

echo "Starte Service..."
gcloud compute ssh "$VM" --project "$PROJECT" --zone "$ZONE" --quiet \
  --command="sudo systemctl start qoc && sudo systemctl enable qoc"

echo ""
echo "Warte auf Health..."
for i in $(seq 1 30); do
  if gcloud compute ssh "$VM" --project "$PROJECT" --zone "$ZONE" --quiet \
       --command="curl -fsS http://127.0.0.1:8081/api/health/live >/dev/null" 2>/dev/null; then
    echo "Health OK"
    break
  fi
  sleep 2
done

echo ""
echo "=== Deployment abgeschlossen ==="
echo "Logs: gcloud compute ssh $VM --zone=$ZONE -- 'sudo journalctl -u qoc -n 50 --no-pager'"
echo "Workflow-Dateien: /data/wf (auf der VM)"
