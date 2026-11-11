import { useState } from 'react';
import { WorkflowApi } from '../services/api';

export default function Testbench() {
  const [workflowId, setWorkflowId] = useState('');
  const [input, setInput] = useState('');
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<any>(null);

  const run = async () => {
    if (!workflowId.trim()) return;
    setRunning(true);
    setResult(null);
    try {
      const wf = await WorkflowApi.get(workflowId.trim());
      const res = await WorkflowApi.run(wf.data, input);
      setResult(res.data);
    } catch (e) {
      console.error(e);
      setResult({ error: 'Ausführung fehlgeschlagen' });
    } finally {
      setRunning(false);
    }
  };

  return (
    <div className="p-8">
      <h1 className="text-2xl font-bold mb-4">Testbench</h1>
      <p className="text-gray-400 mb-4">Workflow testen und ausführen</p>
      <div className="space-y-4 max-w-2xl">
        <input
          value={workflowId}
          onChange={(e) => setWorkflowId(e.target.value)}
          placeholder="Workflow ID"
          className="w-full px-3 py-2 bg-gray-800 rounded border border-gray-700"
        />
        <textarea
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="Input für den Workflow"
          className="w-full h-24 bg-gray-800 text-white p-3 rounded border border-gray-700"
        />
        <button
          onClick={run}
          disabled={running || !workflowId.trim()}
          className="px-4 py-2 bg-green-600 rounded text-sm disabled:opacity-50"
        >
          {running ? 'Läuft...' : 'Ausführen'}
        </button>
        {result && (
          <pre className="bg-gray-800 p-4 rounded text-sm text-green-400 overflow-auto max-h-96">
            {JSON.stringify(result, null, 2)}
          </pre>
        )}
      </div>
    </div>
  );
}
