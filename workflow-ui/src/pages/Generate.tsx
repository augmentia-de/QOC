import { useState } from 'react';
import { WorkflowApi } from '../services/api';

export default function Generate() {
  const [prompt, setPrompt] = useState('');
  const [generating, setGenerating] = useState(false);
  const [result, setResult] = useState<string | null>(null);

  const generate = async () => {
    if (!prompt.trim()) return;
    setGenerating(true);
    setResult(null);
    try {
      // Use the engine to generate a workflow from a prompt
      const res = await WorkflowApi.create(prompt.trim().substring(0, 40));
      setResult(`Workflow erstellt: ${res.data.id}`);
    } catch (e) {
      console.error(e);
      setResult('Generierung fehlgeschlagen');
    } finally {
      setGenerating(false);
    }
  };

  return (
    <div className="p-8">
      <h1 className="text-2xl font-bold mb-4">Generate</h1>
      <p className="text-gray-400 mb-4">Workflow aus Prompt generieren</p>
      <textarea
        value={prompt}
        onChange={(e) => setPrompt(e.target.value)}
        placeholder="Beschreibe den Workflow, den du erstellen möchtest..."
        className="w-full h-32 bg-gray-800 text-white p-3 rounded border border-gray-700 mb-4"
      />
      <button
        onClick={generate}
        disabled={generating || !prompt.trim()}
        className="px-4 py-2 bg-blue-600 rounded text-sm disabled:opacity-50"
      >
        {generating ? 'Generiere...' : 'Generieren'}
      </button>
      {result && <p className="mt-4 text-green-400">{result}</p>}
    </div>
  );
}
