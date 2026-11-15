import { useIsMobile } from '../hooks/useMediaQuery';

interface NodeItem {
  id: string;
  type: string;
  data?: { title?: string; agentRef?: string };
}

interface NodeListViewProps {
  nodes: NodeItem[];
  selectedId: string | null;
  onSelect: (id: string) => void;
  onAddNode?: () => void;
  onDeleteNode?: (id: string) => void;
}

export default function NodeListView({ nodes, selectedId, onSelect, onAddNode, onDeleteNode }: NodeListViewProps) {
  return (
    <div className="p-4">
      <div className="flex items-center justify-between mb-3">
        <h2 className="text-lg font-semibold">Nodes</h2>
        {onAddNode && (
          <button onClick={onAddNode} className="px-3 py-1 bg-blue-600 rounded text-sm" style={{ minHeight: 44 }}>
            + Node
          </button>
        )}
      </div>
      <ul className="space-y-2">
        {nodes.map((n) => (
          <li
            key={n.id}
            onClick={() => onSelect(n.id)}
            className={`p-3 rounded border cursor-pointer ${selectedId === n.id ? 'border-blue-500 bg-blue-950/40' : 'border-gray-700 bg-gray-800'}`}
            style={{ minHeight: 44 }}
          >
            <div className="flex items-center justify-between">
              <div>
                <div className="text-sm font-medium">{n.data?.title || n.id}</div>
                <div className="text-xs text-gray-400">{n.type}{n.data?.agentRef ? ` · ${n.data.agentRef}` : ''}</div>
              </div>
              {onDeleteNode && (
                <button
                  onClick={(e) => { e.stopPropagation(); onDeleteNode(n.id); }}
                  className="text-red-400 text-xs px-2"
                  style={{ minWidth: 44, minHeight: 44 }}
                >
                  ✕
                </button>
              )}
            </div>
          </li>
        ))}
        {nodes.length === 0 && <li className="text-gray-500 text-sm">Keine Nodes</li>}
      </ul>
    </div>
  );
}