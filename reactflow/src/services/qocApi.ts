import { Api } from './api.js';

const QOC_USER = (typeof localStorage !== 'undefined' && localStorage.getItem('qoc-user')) || 'dev';

function withAuth(options = {}) {
  const opts = options as { headers?: Record<string, string> };
  return {
    ...opts,
    headers: { 'Content-Type': 'application/json', ...(opts.headers || {}), 'X-QOC-User': QOC_USER },
  };
}

async function req(path: string, options: any = {}) {
  const res = await fetch('/api' + path, withAuth(options));
  if (!res.ok) throw new Error(res.statusText);
  return res.json();
}

export const QocApi = {
  ...Api,
  workflows: {
    ...Api.workflows,
    validate: (def: any) => req('/workflows/validate', { method: 'POST', body: JSON.stringify(def) }),
    run: (def: any, input: string) => req('/workflows/run', { method: 'POST', body: JSON.stringify({ workflow: def, input }) }),
    generate: (prompt: string) => req('/workflows/generate', { method: 'POST', body: JSON.stringify({ prompt }) }),
    versions: (id: string) => req(`/workflows/${encodeURIComponent(id)}/versions`),
    version: (id: string, v: string) => req(`/workflows/${encodeURIComponent(id)}/versions/${encodeURIComponent(v)}`),
  },
  agents: {
    ...Api.agents,
  },
  mcpClient: {
    listServers: () => req('/mcp-client/servers'),
    getServerTools: (name: string) => req(`/mcp-client/servers/${encodeURIComponent(name)}/tools`),
    reloadServer: (name: string) => req(`/mcp-client/servers/${encodeURIComponent(name)}/reload`, { method: 'POST' }),
    addServer: (config: any) => req('/mcp-client/servers', { method: 'POST', body: JSON.stringify(config) }),
    removeServer: (name: string) => req(`/mcp-client/servers/${encodeURIComponent(name)}`, { method: 'DELETE' }),
    listTools: () => req('/mcp-client/tools'),
  },
  health: {
    check: () => req('/health'),
  },
  schema: {
    fromJava: (source: string) => req('/schema/from-java', { method: 'POST', body: JSON.stringify({ source }) }),
    fromOpenApi: (spec: string, schemaName?: string) => req('/schema/from-openapi', { method: 'POST', body: JSON.stringify({ spec, schemaName }) }),
    fromYaml: (yaml: string, schemaName?: string) => req('/schema/from-yaml', { method: 'POST', body: JSON.stringify({ yaml, schemaName }) }),
    validate: (schema: unknown) => req('/schema/validate', { method: 'POST', body: JSON.stringify({ schema }) }),
  },
};

export default QocApi;
