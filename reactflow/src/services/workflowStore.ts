// Workflow persistence layer.
// Currently backed by localStorage; replace with API calls for DB-backed storage.

const KEY = 'reactflow-workflows';

function readAll() {
  try {
    return JSON.parse(localStorage.getItem(KEY) || '[]');
  } catch {
    return [];
  }
}

function writeAll(list) {
  localStorage.setItem(KEY, JSON.stringify(list));
}

export function listWorkflows() {
  return readAll().map((w) => ({ id: w.id, name: w.name, updatedAt: w.updatedAt }));
}

export function loadWorkflow(id) {
  return readAll().find((w) => w.id === id) || null;
}

export function saveWorkflow(name, nodes, edges) {
  const list = readAll();
  const now = new Date().toISOString();
  const existing = list.find((w) => w.name === name);
  if (existing) {
    existing.nodes = nodes;
    existing.edges = edges;
    existing.updatedAt = now;
    writeAll(list);
    return existing.id;
  }
  const id = 'wf-' + Date.now();
  list.push({ id, name, nodes, edges, createdAt: now, updatedAt: now });
  writeAll(list);
  return id;
}

export function deleteWorkflow(id) {
  const list = readAll().filter((w) => w.id !== id);
  writeAll(list);
}

export function exportWorkflow(name, nodes, edges) {
  return JSON.stringify({ name, nodes, edges }, null, 2);
}

export function importWorkflow(json) {
  const data = JSON.parse(json);
  return saveWorkflow(data.name || 'imported', data.nodes || [], data.edges || []);
}