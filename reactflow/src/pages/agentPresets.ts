// Permission presets: combine tool selection with a concrete permission JSON.
// Each preset materializes into { tools, permissions } stored on the agent.
// Least-privilege is the secure default; presets opt into more access.

export type PermRule = { action: string; resource: string; effect: 'ALLOW' | 'DENY' | 'ASK' };

export interface AgentPreset {
  id: string;
  label: string;
  description: string;
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
      { action: 'write', resource: '*', effect: 'DENY' },
      { action: 'patch', resource: '*', effect: 'DENY' },
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
      { action: 'write', resource: '*', effect: 'DENY' },
      { action: 'patch', resource: '*', effect: 'DENY' },
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
    tools: ['read', 'glob', 'grep', 'webfetch', 'edit', 'write', 'patch'],
    permissions: [
      { action: '*', resource: '*', effect: 'DENY' },
      { action: 'read', resource: '*', effect: 'ALLOW' },
      { action: 'external_directory', resource: '*', effect: 'DENY' },
      { action: 'edit', resource: '*', effect: 'ALLOW' },
      { action: 'write', resource: '*', effect: 'ALLOW' },
      { action: 'patch', resource: '*', effect: 'ALLOW' },
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
    tools: ['read', 'glob', 'grep', 'webfetch', 'firecrawl', 'edit', 'write', 'patch', 'shell', 'todo', 'process', 'skill', 'worktree_create', 'worktree_list', 'worktree_remove', 'secure_rest'],
    permissions: [
      { action: '*', resource: '*', effect: 'ALLOW' },
      ...ENV_PROTECTION,
    ],
  },
];