import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { WorkflowApi, AgentApi } from '../services/api';
import type { WorkflowDefinition } from '../types/workflow';
import GraphEditor from './GraphEditor';

export default function WorkflowDetail() {
  const { id } = useParams<{ id: string }>();
  const [workflow, setWorkflow] = useState<WorkflowDefinition | null>(null);
  const [agents, setAgents] = useState<any[]>([]);
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
      // Load agents from store (Option B: agents persisted separately)
      try {
        const agentRes = await AgentApi.list();
        setAgents(agentRes.data.agents || []);
      } catch {
        setAgents(res.data.agents || []);
      }
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
    <div className="flex flex-col h-[calc(100vh-64px)] p-4 gap-3">
      <div className="flex items-center gap-4">
        <div className="shrink-0">
          <h1 className="text-xl font-bold leading-tight">{workflow.name}</h1>
          <p className="text-gray-400 text-xs font-mono">{workflow.id}</p>
        </div>

        <div className="flex gap-1 shrink-0">
          <button
            onClick={() => setTab('graph')}
            className={`px-3 py-1.5 rounded text-sm ${tab === 'graph' ? 'bg-blue-600' : 'bg-gray-700'}`}
          >
            Graph
          </button>
          <button
            onClick={() => setTab('json')}
            className={`px-3 py-1.5 rounded text-sm ${tab === 'json' ? 'bg-blue-600' : 'bg-gray-700'}`}
          >
            JSON
          </button>
        </div>

        <div className="flex-1 min-w-0 flex justify-center">
          {validation && (validation.valid ? (
            <div className="px-3 py-1.5 rounded bg-green-900/50 text-green-400 text-xs truncate">
              ✓ gültig — {validation.summary}
            </div>
          ) : (
            <div className="px-3 py-1.5 rounded bg-red-900/50 text-red-300 text-xs max-w-2xl max-h-16 overflow-auto">
              <span className="font-bold text-red-400">Ungültig: </span>
              {validation.errors.join(' · ')}
            </div>
          ))}
        </div>

        <div className="flex gap-2 shrink-0">
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

      {tab === 'graph' ? (
        <div className="flex-1 min-h-0 border border-gray-700 rounded overflow-hidden">
          <GraphEditor
            nodesJson={workflow.nodes}
            workflowId={workflow.id}
            agents={agents}
            onGraphChange={(data: { nodes: any[]; edges: any[] }) => {
              // Option B: agents are in the store, not inline in workflow
              const updated = { ...workflow, nodes: data.nodes, edges: data.edges };
              setWorkflow(updated as any);
              setJsonText(JSON.stringify(updated, null, 2));
            }}
          />
        </div>
      ) : (
        <textarea
          value={jsonText}
          onChange={(e) => setJsonText(e.target.value)}
          className="flex-1 min-h-0 w-full bg-gray-900 text-green-400 font-mono text-sm p-4 rounded border border-gray-700"
          spellCheck={false}
        />
      )}
    </div>
  );
}