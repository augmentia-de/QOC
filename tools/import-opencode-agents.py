#!/usr/bin/env python3
"""Convert OpenCode agent markdown files into a QOC settings.json."""

import json
import re
from pathlib import Path

ROOT = Path("/home/torsten/dev/my-projects/AISDLC-design/AISDLC3/try2")
AGENTS_DIR = ROOT / ".opencode" / "agents"
OUTPUT = ROOT / "settings.json"

ACTION_MAP = {
    "read": "read",
    "edit": "edit",
    "bash": "shell",
    "glob": "glob",
    "grep": "grep",
    "task": "subagent",
    "skill": "skill",
    "webfetch": "webfetch",
    "question": "question",
    "todowrite": "todo",
    "write": "write",
}


def parse_frontmatter(text):
    if not text.startswith("---"):
        return {}, text
    parts = text.split("---", 2)
    if len(parts) < 3:
        return {}, text
    raw = parts[1]
    body = parts[2].strip()
    data = {}
    lines = raw.splitlines()
    i = 0
    while i < len(lines):
        line = lines[i].rstrip()
        if not line.strip():
            i += 1
            continue
        if line.startswith("permissions:"):
            perms = []
            i += 1
            current = None
            while i < len(lines) and (lines[i].startswith("  -") or lines[i].startswith("    ")):
                stripped = lines[i].strip()
                if stripped.startswith("- action:"):
                    if current is not None:
                        perms.append(current)
                    current = {"action": stripped.split(":", 1)[1].strip()}
                elif ":" in stripped and current is not None:
                    key, value = stripped.split(":", 1)
                    current[key.strip()] = value.strip().strip('"').strip("'")
                i += 1
            if current is not None:
                perms.append(current)
            data["permissions"] = perms
            continue
        if ":" in line:
            key, value = line.split(":", 1)
            data[key.strip()] = value.strip().strip('"').strip("'")
        i += 1
    return data, body


def to_rule(permission):
    action = ACTION_MAP.get(permission.get("action", "*"), permission.get("action", "*"))
    resource = permission.get("resource", "*")
    effect = permission.get("effect", "allow").lower()
    return {
        "action": action,
        "resource": resource,
        "effect": effect,
    }


BASELINE_PERMISSIONS = [
    {"action": "read", "resource": "*", "effect": "allow"},
    {"action": "glob", "resource": "*", "effect": "allow"},
    {"action": "grep", "resource": "*", "effect": "allow"},
    {"action": "skill", "resource": "*", "effect": "allow"},
    {"action": "subagent", "resource": "*", "effect": "allow"},
]

def convert(path):
    text = path.read_text(encoding="utf-8")
    data, body = parse_frontmatter(text)
    name = path.stem
    mode = data.get("mode", "all")
    hidden = mode == "subagent"
    steps = int(data.get("steps", "32"))
    permissions = [to_rule(p) for p in data.get("permissions", [])]

    # OpenCode treats unlisted read-only actions as allowed. QOC's rule chain
    # does not, so add an explicit baseline before the OpenCode rules.
    permissions = [dict(p) for p in BASELINE_PERMISSIONS] + permissions

    # Preserve OpenCode's implicit shell deny: if the agent does not contain a
    # catch-all shell deny, add one after all explicit shell allows.
    has_shell_deny = any(
        p.get("action") == "shell" and p.get("resource") == "*" and p.get("effect") == "deny"
        for p in permissions
    )
    if not has_shell_deny:
        permissions.append({"action": "shell", "resource": "*", "effect": "deny"})

    # QOC separates catalog visibility (tools.allow/deny) from authorization
    # (permissions). Keep the catalog broad and let the permission rules enforce
    # least privilege. OpenCode's permission list is an execution policy, not a
    # catalog filter.
    return {
        "name": name,
        "system": body,
        "hidden": hidden,
        "mode": mode,
        "maxSteps": steps,
        "tools": {
            "allow": ["*"],
            "deny": []
        },
        "permissions": permissions,
        "mcpServers": ["*"],
    }


def main():
    agents = []
    for path in sorted(AGENTS_DIR.glob("*.md")):
        agents.append(convert(path))
    settings = {"agents": agents}
    OUTPUT.write_text(json.dumps(settings, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Wrote {OUTPUT} with {len(agents)} agents")


if __name__ == "__main__":
    main()
