import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { WorkflowApi } from '../services/api';
import type { WorkflowDefinition } from '../types/workflow';
import GraphEditor from './GraphEditor';

export default function WorkflowDetail() {
  const { id } = useParams<{ id: string }>();
  const [workflow, setWorkflow] = useState<WorkflowDefinition | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [validating, setValidating] = useState(false);
  const [validation, setValidation] = useState<{ valid: boolean; errors: string[]; summary: string } | null>(null);
  const [tab, setTab] = useState<'graph' | 'json'>('graph');
  const [jsonText, setJsonText] = useState('');

  const load = async () => {
    if (!id) return;
    setLoading(true);
    try {
      const res = await WorkflowApi.get(id);
      setWorkflow(res.data);
      setJsonText(JSON.stringify(res.data, null, 2));
    } catch (e) {
      console.error(e);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, [id]);

  const validate = async () => {
    if (!workflow) return;
    setValidating(true);
    try {
      const res = await WorkflowApi.validate(workflow);
      setValidation(res.data);
    } catch (e) {
      console.error(e);
    } finally {
      setValidating(false);
    }
  };

  const save = async () => {
    if (!id || !workflow) return;
    setSaving(true);
    try {
      let def = workflow;
      if (tab === 'json') {
        try {
          def = JSON.parse(jsonText);
        } catch {
          alert('JSON fehlerhaft');
          setSaving(false);
          return;
        }
      }
      await WorkflowApi.save(id, def);
      setWorkflow(def);
      setJsonText(JSON.stringify(def, null, 2));
      alert('Gespeichert');
    } catch (e) {
      console.error(e);
      alert('Speichern fehlgeschlagen');
    } finally {
      setSaving(false);
    }
  };

  const run = async () => {
    if (!workflow) return;
    try {
      const res = await WorkflowApi.run(workflow, 'Hello');
      alert('Ergebnis: ' + res.data.result);
    } catch (e) {
      console.error(e);
      alert('Ausführung fehlgeschlagen');
    }
  };

  if (loading) return <div className="p-8 text-gray-400">Lade...</div>;
  if (!workflow) return <div className="p-8 text-red-400">Workflow nicht gefunden.</div>;

  return (
    <div className="p-8">
      <div className="flex justify-between items-center mb-4">
        <div>
          <h1 className="text-2xl font-bold">{workflow.name}</h1>
          <p className="text-gray-400 text-sm font-mono">{workflow.id}</p>
        </div>
        <div className="flex gap-2">
          <button onClick={validate} disabled={validating} className="px-4 py-2 bg-gray-700 rounded text-sm">
            {validating ? 'Validiere...' : 'Validieren'}
          </button>
          <button onClick={save} disabled={saving} className="px-4 py-2 bg-blue-600 rounded text-sm">
            {saving ? 'Speichere...' : 'Speichern'}
          </button>
          <button onClick={run} className="px-4 py-2 bg-green-600 rounded text-sm">
            Ausführen
          </button>
        </div>
      </div>

      {validation && (
        <div className={`mb-4 p-3 rounded ${validation.valid ? 'bg-green-900/50' : 'bg-red-900/50'}`}>
          {validation.valid ? (
            <p className="text-green-400">✓ gültig — {validation.summary}</p>
          ) : (
            <div>
              <p className="text-red-400 font-bold">Ungültig:</p>
              <ul className="list-disc list-inside text-red-300 text-sm">
                {validation.errors.map((e, i) => <li key={i}>{e}</li>)}
              </ul>
            </div>
          )}
        </div>
      )}

      <div className="flex gap-2 mb-4">
        <button
          onClick={() => setTab('graph')}
          className={`px-4 py-2 rounded text-sm ${tab === 'graph' ? 'bg-blue-600' : 'bg-gray-700'}`}
        >
          Graph
        </button>
        <button
          onClick={() => setTab('json')}
          className={`px-4 py-2 rounded text-sm ${tab === 'json' ? 'bg-blue-600' : 'bg-gray-700'}`}
        >
          JSON
        </button>
      </div>

      {tab === 'graph' ? (
        <div className="h-[600px] border border-gray-700 rounded">
          <GraphEditor
            nodesJson={workflow.nodes}
            workflowId={workflow.id}
            agents={workflow.agents || []}
            onGraphChange={(data: { nodes: any[]; edges: any[] }) => {
              // Ensure referenced agents exist in agents[]
              const agentRefs = new Set(data.nodes.map((n: any) => n.data?.agentRef).filter(Boolean));
              const existingIds = new Set((workflow.agents || []).map((a: any) => a.id));
              const agents = [...(workflow.agents || [])];
              for (const ref of agentRefs) {
                if (!existingIds.has(ref) && ref === 'build') {
                  agents.push({ id: 'build', name: 'Build Agent', version: '1.0.0', category: 'core', model: 'deepseek-v4.1-flash', temperature: 0.3, maxTokens: 4096, topP: 1.0, tools: [], guardrailsInput: [], guardrailsOutput: [], hooks: [], jsonOutput: false, active: true, systemPrompt: 'You are a helpful build agent.' });
                  existingIds.add(ref);
                }
              }
              const updated = { ...workflow, nodes: data.nodes, edges: data.edges, agents };
              setWorkflow(updated as any);
              setJsonText(JSON.stringify(updated, null, 2));
            }}
          />
        </div>
      ) : (
        <textarea
          value={jsonText}
          onChange={(e) => setJsonText(e.target.value)}
          className="w-full h-[600px] bg-gray-900 text-green-400 font-mono text-sm p-4 rounded border border-gray-700"
          spellCheck={false}
        />
      )}
    </div>
  );
}