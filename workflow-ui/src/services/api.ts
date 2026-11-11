import axios from 'axios';

const api = axios.create({
  baseURL: '/api',
  headers: { 'Content-Type': 'application/json' },
});

export interface WorkflowSummary {
  id: string;
  name: string;
  version: string;
  status: string;
}

export const WorkflowApi = {
  list: () => api.get<{ workflows: string[] }>('/workflows'),
  get: (id: string) => api.get<import('../types/workflow').WorkflowDefinition>(`/workflows/${id}`),
  create: (name?: string) => api.post<import('../types/workflow').WorkflowDefinition>('/workflows', { name }),
  save: (id: string, def: import('../types/workflow').WorkflowDefinition) =>
    api.put<import('../types/workflow').WorkflowDefinition>(`/workflows/${id}`, def),
  validate: (def: import('../types/workflow').WorkflowDefinition) =>
    api.post<{ valid: boolean; errors: string[]; summary: string }>('/workflows/validate', def),
  versions: (id: string) => api.get<{ versions: string[] }>(`/workflows/${id}/versions`),
  version: (id: string, v: string) => api.get<import('../types/workflow').WorkflowDefinition>(`/workflows/${id}/versions/${v}`),
  run: (def: import('../types/workflow').WorkflowDefinition, input: string) =>
    api.post<{ result: string; order: string[]; valid: boolean }>('/workflows/run', { workflow: def, input }),
};

export const HealthApi = {
  check: () => api.get<{ status: string }>('/health'),
};

export const AgentApi = {
  list: () => api.get<{ agents: import('../types/workflow').Agent[] }>('/agents'),
  get: (id: string) => api.get<import('../types/workflow').Agent>(`/agents/${id}`),
  create: (agent: Partial<import('../types/workflow').Agent>) => api.post<import('../types/workflow').Agent>('/agents', agent),
  save: (id: string, agent: import('../types/workflow').Agent) => api.put<import('../types/workflow').Agent>(`/agents/${id}`, agent),
  delete: (id: string) => api.delete<{ deleted: boolean }>(`/agents/${id}`),
};

export default api;