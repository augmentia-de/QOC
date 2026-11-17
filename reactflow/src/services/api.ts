const BASE = '/api';
const QOC_USER = (typeof localStorage !== 'undefined' && localStorage.getItem('qoc-user')) || 'dev';

async function request(path, options = {}) {
  const opts = options as { headers?: Record<string, string> };
  const res = await fetch(BASE + path, {
    ...opts,
    headers: { 'Content-Type': 'application/json', 'X-QOC-User': QOC_USER, ...(opts.headers || {}) },
  });
  if (!res.ok) throw new Error(res.statusText);
  return res.json();
}

export const Api = {
  workflows: {
    list: () => request('/workflows'),
    get: (id) => request('/workflows/' + encodeURIComponent(id)),
    save: (id, def) => request('/workflows/' + encodeURIComponent(id), { method: 'PUT', body: JSON.stringify(def) }),
    create: (def) => request('/workflows', { method: 'POST', body: JSON.stringify(def) }),
    delete: (id) => request('/workflows/' + encodeURIComponent(id), { method: 'DELETE' }),
  },
  agents: {
    list: () => request('/agents'),
    get: (id) => request('/agents/' + encodeURIComponent(id)),
    save: (id, agent) => request('/agents/' + encodeURIComponent(id), { method: 'PUT', body: JSON.stringify(agent) }),
    create: (agent) => request('/agents', { method: 'POST', body: JSON.stringify(agent) }),
    delete: (id) => request('/agents/' + encodeURIComponent(id), { method: 'DELETE' }),
  },
};

export async function backendAvailable() {
  try {
    const res = await fetch(BASE + '/workflows');
    return res.ok;
  } catch {
    return false;
  }
}