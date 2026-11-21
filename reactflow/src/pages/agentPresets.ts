// Permission presets: combine tool selection with a concrete permission JSON.
// Each preset materializes into { tools, permissions } stored on the agent.
// Least-privilege is the secure default; presets opt into more access.

export type PermRule = { action: string; resource: string; effect: 'ALLOW' | 'DENY' | 'ASK' };

export interface AgentPreset {
  id: string;
  label: string;
  description: string;
  // Optional generic system prompt applied to the agent. For context agents
  // this is intentionally NOT set: the system prompt is designed in the workflow
  // (workflow.systemPrompt). The generic instruction is a USER message (the
  // node title / task), not a system prompt.
  prompt?: string;
  // Optional default node contextMode hint (node-level, not stored on agent).
  contextMode?: 'focused' | 'full' | 'artifacts' | 'custom';
  // Optional comment shown in the UI explaining the preset's intent.
  comment?: string;
  tools: string[];
  permissions: PermRule[];
}

const ENV_PROTECTION: PermRule[] = [
  { action: 'read', resource: '*.env', effect: 'ASK' },
  { action: 'read', resource: '*.env.*', effect: 'ASK' },
  { action: 'read', resource: '*.env.example', effect: 'ALLOW' },
];

export const AGENT_PRESETS: AgentPreset[] = [
  {
    id: 'readonly',
    label: 'Readonly (least privilege)',
    description: 'Nur lesen/suchen. Keine Änderungen, kein Shell.',
    tools: ['read', 'glob', 'grep', 'webfetch'],
    permissions: [
      { action: '*', resource: '*', effect: 'DENY' },
      { action: 'read', resource: '*', effect: 'ALLOW' },
      { action: 'external_directory', resource: '*', effect: 'DENY' },
      { action: 'edit', resource: '*', effect: 'DENY' },
      { action: 'shell', resource: '*', effect: 'DENY' },
      ...ENV_PROTECTION,
    ],
  },
  {
    id: 'retrieval',
    label: 'Retrieval (read + web)',
    description: 'Lesen + Websuche/Firecrawl. Shell nur read-only Befehle.',
    tools: ['read', 'glob', 'grep', 'webfetch', 'firecrawl'],
    permissions: [
      { action: '*', resource: '*', effect: 'DENY' },
      { action: 'read', resource: '*', effect: 'ALLOW' },
      { action: 'external_directory', resource: '*', effect: 'DENY' },
      { action: 'webfetch', resource: '*', effect: 'ALLOW' },
      { action: 'firecrawl', resource: '*', effect: 'ALLOW' },
      { action: 'edit', resource: '*', effect: 'DENY' },
      { action: 'shell', resource: '*', effect: 'ASK' },
      { action: 'shell', resource: '*git log*', effect: 'ALLOW' },
      { action: 'shell', resource: '*git status*', effect: 'ALLOW' },
      { action: 'shell', resource: '*git diff*', effect: 'ALLOW' },
      { action: 'shell', resource: '*ls*', effect: 'ALLOW' },
      { action: 'shell', resource: '*cat*', effect: 'ALLOW' },
      ...ENV_PROTECTION,
    ],
  },
  {
    id: 'editor',
    label: 'Editor (read + write im Workspace)',
    description: 'Lesen + Schreiben im Workspace. Shell nach Rückfrage.',
    tools: ['read', 'glob', 'grep', 'webfetch', 'edit', 'write', 'patch', 'delete', 'undo_write'],
    permissions: [
      { action: '*', resource: '*', effect: 'DENY' },
      { action: 'read', resource: '*', effect: 'ALLOW' },
      { action: 'external_directory', resource: '*', effect: 'DENY' },
      { action: 'edit', resource: '*', effect: 'ALLOW' },
      { action: 'undo_write', resource: '*', effect: 'ALLOW' },
      { action: 'shell', resource: '*', effect: 'ASK' },
      { action: 'shell', resource: '*git log*', effect: 'ALLOW' },
      { action: 'shell', resource: '*git status*', effect: 'ALLOW' },
      { action: 'shell', resource: '*git diff*', effect: 'ALLOW' },
      { action: 'shell', resource: '*ls*', effect: 'ALLOW' },
      { action: 'shell', resource: '*cat*', effect: 'ALLOW' },
      ...ENV_PROTECTION,
    ],
  },
  {
    id: 'context-agent',
    label: 'Context Agent (generisch, full context)',
    description: 'Generischer Kontext-Agent: verarbeitet den automatisch injizierten Kontext (contextMode=full) und liefert ein Endergebnis. Gilt für alle Kontext-Agenten — Prompt bleibt generisch, Aufgabe kommt aus dem Node-Title.',
    // Applies to ALL context agents. The generic instruction is a USER message
    // (the node title / task), NOT a system prompt — the system prompt is
    // designed in the workflow. contextMode=full injects the entire context.
    comment: 'Generischer Kontext-Agent: System-Prompt wird im Workflow designed. Die Aufgabe (User-Message) kommt aus dem Node-Title. contextMode=full.',
    contextMode: 'full',
    tools: ['read', 'glob', 'grep', 'webfetch', 'firecrawl'],
    permissions: [
      { action: '*', resource: '*', effect: 'DENY' },
      { action: 'read', resource: '*', effect: 'ALLOW' },
      { action: 'external_directory', resource: '*', effect: 'DENY' },
      { action: 'webfetch', resource: '*', effect: 'ALLOW' },
      { action: 'firecrawl', resource: '*', effect: 'ALLOW' },
      { action: 'glob', resource: '*', effect: 'ALLOW' },
      { action: 'grep', resource: '*', effect: 'ALLOW' },
      { action: 'shell', resource: '*', effect: 'ASK' },
      { action: 'shell', resource: '*git log*', effect: 'ALLOW' },
      { action: 'shell', resource: '*git status*', effect: 'ALLOW' },
      { action: 'shell', resource: '*git diff*', effect: 'ALLOW' },
      { action: 'shell', resource: '*ls*', effect: 'ALLOW' },
      { action: 'shell', resource: '*cat*', effect: 'ALLOW' },
      ...ENV_PROTECTION,
    ],
  },
  {
    id: 'full',
    label: 'Full (allow-all, .env geschützt)',
    description: 'Alle Tools erlaubt. .env-Dateien lösen Rückfrage aus.',
    tools: ['*'],
    permissions: [
      { action: '*', resource: '*', effect: 'ALLOW' },
      ...ENV_PROTECTION,
    ],
  },
];