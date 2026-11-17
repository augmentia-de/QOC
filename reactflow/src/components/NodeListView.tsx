export default function NodeListView({ nodes, selectedId, onSelect, onAddNode, onDeleteNode }) {
  return (
    <div style={{ padding: 8, color: '#d4d4d4', fontSize: 12 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8 }}>
        <strong>Nodes</strong>
        <button onClick={onAddNode} style={{ background: '#5cb85c', color: '#fff', border: 'none', padding: '2px 8px', borderRadius: 4, fontSize: 11 }}>+ Add</button>
      </div>
      {nodes.map((n) => (
        <div key={n.id} onClick={() => onSelect(n.id)} style={{ padding: 6, marginBottom: 4, background: selectedId === n.id ? '#333' : '#2d2d2d', borderRadius: 4, cursor: 'pointer', display: 'flex', justifyContent: 'space-between' }}>
          <span>{n.data?.title || n.data?.label || n.id} ({n.type})</span>
          <button onClick={(e) => { e.stopPropagation(); onDeleteNode(n.id); }} style={{ background: '#d9534f', color: '#fff', border: 'none', padding: '0 6px', borderRadius: 3, fontSize: 10 }}>x</button>
        </div>
      ))}
    </div>
  );
}