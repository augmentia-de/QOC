import { create } from 'zustand';
import { devtools, persist } from 'zustand/middleware';

export interface WfNode { id: string; type: string; position: { x: number; y: number }; data: Record<string, unknown>; }
export interface WfEdge { id: string; source: string; target: string; type?: string; }

interface State {
  nodes: WfNode[];
  edges: WfEdge[];
  selectedId: string | null;
  workflowName: string;
  setNodes: (v: WfNode[] | ((prev: WfNode[]) => WfNode[])) => void;
  setEdges: (v: WfEdge[] | ((prev: WfEdge[]) => WfEdge[])) => void;
  setSelectedId: (id: string | null) => void;
  setWorkflowName: (name: string) => void;
  pushHistory: () => void;
  undo: () => void;
  redo: () => void;
  clear: () => void;
}

const history: { nodes: WfNode[]; edges: WfEdge[] }[] = [];
let historyIndex = -1;

export const useWorkflowStore = create<State>()(devtools(persist((set, get) => ({
  nodes: [],
  edges: [],
  selectedId: null,
  workflowName: '',
  setNodes: (v) => set((s) => ({ nodes: typeof v === 'function' ? (v as any)(s.nodes) : v })),
  setEdges: (v) => set((s) => ({ edges: typeof v === 'function' ? (v as any)(s.edges) : v })),
  setSelectedId: (id) => set({ selectedId: id }),
  setWorkflowName: (name) => set({ workflowName: name }),
  pushHistory: () => {
    const { nodes, edges } = get();
    const next = history.slice(0, historyIndex + 1);
    next.push({ nodes: JSON.parse(JSON.stringify(nodes)), edges: JSON.parse(JSON.stringify(edges)) });
    history.splice(0, history.length, ...next);
    historyIndex = next.length - 1;
  },
  undo: () => {
    if (historyIndex <= 0) return;
    historyIndex -= 1;
    const prev = history[historyIndex];
    set({ nodes: prev.nodes, edges: prev.edges });
  },
  redo: () => {
    if (historyIndex >= history.length - 1) return;
    historyIndex += 1;
    const next = history[historyIndex];
    set({ nodes: next.nodes, edges: next.edges });
  },
  clear: () => {
    history.splice(0, history.length);
    historyIndex = -1;
    set({ nodes: [], edges: [], selectedId: null, workflowName: '' });
  },
}), { name: 'qoc-editor-state' })));
