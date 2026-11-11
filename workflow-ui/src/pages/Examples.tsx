export default function Examples() {
  const examples = [
    { name: 'Einfacher Agent', desc: 'Ein einzelner Agent-Schritt' },
    { name: 'Sequentiell', desc: 'Mehrere Agenten hintereinander' },
    { name: 'Parallel (Fork/Join)', desc: 'Parallel ausführen und Ergebnisse zusammenführen' },
    { name: 'Bedingte Verzweigung', desc: 'Entscheidung basierend auf Ergebnis' },
    { name: 'HITL', desc: 'Human-in-the-Loop Genehmigung' },
    { name: 'Sub-Workflow', desc: 'Wiederverwendbare Sub-Workflows' },
  ];

  return (
    <div className="p-8">
      <h1 className="text-2xl font-bold mb-4">Examples</h1>
      <p className="text-gray-400 mb-4">Beispiel-Workflows</p>
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {examples.map((ex, i) => (
          <div key={i} className="p-4 bg-gray-800 rounded hover:bg-gray-700 cursor-pointer">
            <h3 className="font-bold">{ex.name}</h3>
            <p className="text-sm text-gray-400 mt-1">{ex.desc}</p>
          </div>
        ))}
      </div>
    </div>
  );
}
