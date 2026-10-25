#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${OPENAI_API_KEY:-}" ]]; then
  echo "OPENAI_API_KEY is required for a live benchmark." >&2
  exit 1
fi

echo "Create a session for each agent, send benchmark/tasks.txt prompts,"
echo "and record latency/tokens/steps in benchmark/results/."
echo "Use the QOC HTTP API or your preferred client."
