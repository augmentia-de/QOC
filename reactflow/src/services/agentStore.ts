// Agent definition persistence (reference versions).
// Backed by localStorage; mirrors workflowStore pattern. A workflow node
// references an agent via data.agentRef and may override fields locally.

const KEY = 'reactflow-agents';

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

export function listAgents() {
  return readAll().map((a) => ({ id: a.id, name: a.name, model: a.model, version: a.version }));
}

export function getAgent(id) {
  return readAll().find((a) => a.id === id) || null;
}

export function saveAgent(agent) {
  const list = readAll();
  const idx = list.findIndex((a) => a.id === agent.id);
  if (idx >= 0) {
    list[idx] = { ...list[idx], ...agent, updatedAt: new Date().toISOString() };
  } else {
    list.push({ ...agent, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() });
  }
  writeAll(list);
  return agent.id;
}

export function deleteAgent(id) {
  writeAll(readAll().filter((a) => a.id !== id));
}

export function createAgent(agent) {
  const id = agent.id || 'agent-' + Date.now();
  const created = { ...agent, id };
  saveAgent(created);
  return created;
}

export function seedDummyAgents() {
  if (readAll().length > 0) return;
  const dummies = [
    {
      id: 'code-reviewer',
      name: 'Code Reviewer',
      version: '1.0.0',
      category: 'core',
      model: 'gpt-4o-mini',
      temperature: 0.2,
      maxTokens: 2048,
      topP: 1.0,
      tools: ['read_file', 'search_replace'],
      guardrailsInput: [],
      guardrailsOutput: [],
      hooks: [],
      jsonOutput: true,
      active: true,
      systemPrompt: 'You are a code reviewer. Analyze the provided code and return structured issues.',
      responseSchema: {
        type: 'object',
        properties: {
          summary: { type: 'string' },
          issues: { type: 'array', items: { type: 'object', properties: { file: { type: 'string' }, line: { type: 'number' }, message: { type: 'string' } } } },
          severity: { type: 'string', enum: ['low', 'medium', 'high', 'critical'] },
        },
        required: ['summary', 'issues'],
      },
      ownerId: '',
      rolesAllowed: ['*'],
      mode: 'all',
      skills: [],
    },
    {
      id: 'summarizer',
      name: 'Summarizer',
      version: '1.0.0',
      category: 'utility',
      model: 'gpt-4o',
      temperature: 0.3,
      maxTokens: 1024,
      topP: 1.0,
      tools: [],
      guardrailsInput: [],
      guardrailsOutput: [],
      hooks: [],
      jsonOutput: true,
      active: true,
      systemPrompt: 'Summarize the given text concisely.',
      responseSchema: {
        type: 'object',
        properties: {
          summary: { type: 'string' },
          keyPoints: { type: 'array', items: { type: 'string' } },
          wordCount: { type: 'number' },
        },
        required: ['summary'],
      },
      ownerId: '',
      rolesAllowed: ['*'],
      mode: 'all',
      skills: [],
    },
    {
      id: 'plain-chat',
      name: 'Plain Chat',
      version: '1.0.0',
      category: 'core',
      model: 'gpt-4o-mini',
      temperature: 0.7,
      maxTokens: 1024,
      topP: 1.0,
      tools: [],
      guardrailsInput: [],
      guardrailsOutput: [],
      hooks: [],
      jsonOutput: false,
      active: true,
      systemPrompt: 'You are a helpful assistant.',
      responseSchema: null,
      ownerId: '',
      rolesAllowed: ['*'],
      mode: 'all',
      skills: [],
    },
  ];
  dummies.forEach((d) => saveAgent(d));
}