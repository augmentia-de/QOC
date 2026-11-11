import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ToastService } from '../core/toast.service';
import { autoLayout, emptyVm, toNodeJson, toNodeVm } from './graph-model';
import type { AgentRefOption, NodeVM, Point } from './graph-model';

export type { AgentRefOption } from './graph-model';

/**
 * Visual editor for {@code mainWorkflow.nodes}: layered layout, drag (positions per workflow in
 * localStorage), dependency editing via checkboxes and click-to-connect, add/rename/delete with
 * reference integrity, and target dropdowns for decisions/forks/loops. Emits the rebuilt,
 * schema-shaped node array.
 *
 * The pure view-model helpers live in `graph-model.ts`, the template in `graph-editor.html` and the
 * styles in `graph-editor.scss` (WP12 split).
 */
@Component({
  selector: 'af-graph-editor',
  imports: [FormsModule],
  templateUrl: './graph-editor.html',
  styleUrls: ['./graph-editor.scss'],
})
export class GraphEditorComponent {
  private readonly toast = inject(ToastService);

  readonly nodesJson = input<unknown[]>([]);
  readonly workflowId = input<string>('');
  /** Agents from the document's inline agents[] that an agent node may reference. */
  readonly agents = input<AgentRefOption[]>([]);
  readonly nodesChange = output<unknown[]>();

  readonly nodeTypes = ['agent', 'decision', 'loop', 'fork', 'join', 'hitl', 'subworkflow', 'end'];

  readonly nodes = signal<NodeVM[]>([]);
  readonly selectedId = signal<string | null>(null);
  /**
   * Draft of the id field.
   *
   * <p>The model id must not change while the user types: the id is the key of the stored node
   * position and of the selection, so a half-typed id would make the node jump to its
   * auto-layout position and lose its selection. The draft is applied as a whole (blur or
   * Enter) — see {@link #applyIdDraft}.
   */
  readonly idDraft = signal('');
  readonly positions = signal<Record<string, Point>>({});
  readonly linking = signal(false);
  readonly linkSource = signal<string | null>(null);

  private static readonly NODE_W = 200;
  private static readonly NODE_H = 96;

  readonly nodeIds = computed(() => this.nodes().map((n) => n.id));

  /** Bounding box of all nodes; sizes the SVG so edges are never clipped. */
  readonly content = computed(() => {
    let w = 600;
    let h = 460;
    for (const n of this.nodes()) {
      const p = this.point(n.id);
      w = Math.max(w, p.x + GraphEditorComponent.NODE_W + 60);
      h = Math.max(h, p.y + GraphEditorComponent.NODE_H + 60);
    }
    return { w, h };
  });

  newType = 'agent';
  private originalId = '';
  private drag: { id: string; dx: number; dy: number } | null = null;

  constructor() {
    effect(() => {
      const nodes = (this.nodesJson() as Record<string, any>[]).map((n) => toNodeVm(n));
      this.nodes.set(nodes);
      // The parent owns the nodes: an edit it did not accept must not leave a stale draft.
      untracked(() => {
        const id = this.selectedId();
        const selected = id ? nodes.find((n) => n.id === id) : undefined;
        if (selected) this.idDraft.set(selected.id);
      });
    });
    // Load stored positions only when the workflow changes — not on every node edit, so that
    // structural edits (which re-emit nodes) never clobber the in-memory layout.
    effect(() => {
      const id = this.workflowId();
      this.positions.set(id ? this.loadPositions(id) : {});
    });
    effect(() => {
      const p = this.positions();
      const id = this.workflowId();
      if (id) {
        localStorage.setItem('agentflow.graph.' + id, JSON.stringify(p));
      }
    });
  }

  selected(): NodeVM | undefined {
    const id = this.selectedId();
    return id ? this.nodes().find((n) => n.id === id) : undefined;
  }

  /** Whether an agent id is among the document's selectable agents. */
  hasAgent(id: string): boolean {
    return this.agents().some((a) => a.id === id);
  }

  point(id: string): Point {
    const layout = this.layout();
    return this.positions()[id] ?? layout[id] ?? { x: 40, y: 40 };
  }

  readonly edges = computed(() => {
    const byId = new Set(this.nodes().map((n) => n.id));
    const list: { id: string; x1: number; y1: number; x2: number; y2: number }[] = [];
    for (const n of this.nodes()) {
      const to = this.point(n.id);
      for (const dep of n.dependsOn) {
        if (!byId.has(dep)) continue;
        const from = this.point(dep);
        list.push({ id: `${dep}->${n.id}`, x1: from.x + 100, y1: from.y + 20, x2: to.x + 100, y2: to.y + 20 });
      }
    }
    return list;
  });

  private layout = computed(() => autoLayout(this.nodes()));

  private loadPositions(id: string): Record<string, Point> {
    try {
      return JSON.parse(localStorage.getItem('agentflow.graph.' + id) ?? '{}');
    } catch {
      return {};
    }
  }

  // ── selection / linking ───────────────────────────────────────────────────

  onNodeClick(id: string): void {
    if (this.linking()) {
      const source = this.linkSource();
      if (!source) {
        this.linkSource.set(id);
      } else if (source !== id) {
        const target = this.nodes().find((n) => n.id === id);
        if (target && !target.dependsOn.includes(source)) {
          target.dependsOn = [...target.dependsOn, source];
        }
        this.linkSource.set(null);
        this.update();
      }
      return;
    }
    this.selectedId.set(id);
    this.originalId = id;
    this.idDraft.set(id);
  }

  // ── drag ──────────────────────────────────────────────────────────────────

  startDrag(id: string, event: PointerEvent): void {
    const p = this.point(id);
    const local = this.localPoint(event);
    this.drag = { id, dx: local.x - p.x, dy: local.y - p.y };
    event.stopPropagation();
  }

  onMove(event: PointerEvent): void {
    if (!this.drag) return;
    const local = this.localPoint(event);
    const x = Math.max(0, local.x - this.drag.dx);
    const y = Math.max(0, local.y - this.drag.dy);
    this.positions.update((m) => ({ ...m, [this.drag!.id]: { x, y } }));
  }

  /** Pointer position in canvas-local coordinates (accounting for border and scroll). */
  private localPoint(event: PointerEvent): Point {
    const el = event.currentTarget as HTMLElement;
    const canvas = (el.closest('.graph-canvas') as HTMLElement) ?? el;
    const rect = canvas.getBoundingClientRect();
    return {
      x: event.clientX - rect.left - canvas.clientLeft + canvas.scrollLeft,
      y: event.clientY - rect.top - canvas.clientTop + canvas.scrollTop,
    };
  }

  endDrag(): void {
    this.drag = null;
  }

  /** Discards the stored positions and falls back to the automatic layered layout. */
  resetLayout(): void {
    const id = this.workflowId();
    if (id) {
      localStorage.removeItem('agentflow.graph.' + id);
    }
    this.positions.set({});
  }

  // ── add / delete / rename ─────────────────────────────────────────────────

  private nextId(): string {
    const base = 'node';
    let i = this.nodes().length + 1;
    while (this.nodes().some((n) => n.id === `${base}-${i}`)) i++;
    return `${base}-${i}`;
  }

  addNode(): void {
    const vm = emptyVm(this.nextId(), this.newType);
    this.nodes.update((list) => [...list, vm]);
    this.selectedId.set(vm.id);
    this.originalId = vm.id;
    this.idDraft.set(vm.id);
    this.update();
  }

  addConnected(): void {
    const parent = this.selectedId();
    const vm = emptyVm(this.nextId(), this.newType);
    if (parent) vm.dependsOn = [parent];
    this.nodes.update((list) => [...list, vm]);
    this.selectedId.set(vm.id);
    this.originalId = vm.id;
    this.idDraft.set(vm.id);
    this.update();
  }

  deleteSelected(): void {
    const s = this.selected();
    if (!s) return;
    const id = s.id;
    this.nodes.update((list) =>
      list
        .filter((n) => n.id !== id)
        .map((n) => ({
          ...n,
          dependsOn: n.dependsOn.filter((d) => d !== id),
          bodySteps: n.bodySteps.filter((b) => b !== id),
          branches: n.branches.filter((b) => b.target !== id),
          defaultTarget: n.defaultTarget === id ? '' : n.defaultTarget,
        })),
    );
    this.selectedId.set(null);
    this.update();
  }

  /**
   * Renames a node and rewrites every reference to it.
   *
   * <p>The node is passed in because the id is already written when the change event arrives:
   * a lookup via {@link #selected()} finds the node by the <em>old</em> selectedId and therefore
   * no longer finds it — the reference rewrite never ran and the other nodes kept dangling
   * references (WP12 froze that behaviour; this is the follow-up).
   *
   * @return false if the id was rejected (blank or already taken), true otherwise
   */
  rename(node: NodeVM | undefined = this.selected()): boolean {
    if (!node) return false;
    const oldId = this.originalId;
    const newId = node.id;
    if (!newId.trim()) {
      node.id = oldId;
      this.idDraft.set(oldId);
      this.toast.error('Die ID darf nicht leer sein.');
      return false;
    }
    if (this.nodes().some((n) => n !== node && n.id === newId)) {
      node.id = oldId;
      this.idDraft.set(oldId);
      this.toast.error(`Die ID "${newId}" ist bereits vergeben.`);
      return false;
    }
    if (newId === oldId) {
      this.idDraft.set(newId);
      this.update();
      return true;
    }
    for (const n of this.nodes()) {
      n.dependsOn = n.dependsOn.map((d) => (d === oldId ? newId : d));
      n.bodySteps = n.bodySteps.map((b) => (b === oldId ? newId : b));
      n.branches = n.branches.map((b) => ({ ...b, target: b.target === oldId ? newId : b.target }));
      if (n.defaultTarget === oldId) n.defaultTarget = newId;
    }
    // An auto-generated title (the id at creation time) follows the id.
    if (node.title === oldId) node.title = newId;
    this.originalId = newId;
    this.selectedId.set(newId);
    this.idDraft.set(newId);
    // move stored position
    const pos = this.positions();
    if (pos[oldId]) {
      const { [oldId]: moved, ...rest } = pos;
      this.positions.set({ ...rest, [newId]: moved });
    }
    this.update();
    return true;
  }

  /**
   * Applies the id draft to the node.
   *
   * <p>Called when the id field is left (blur) or Enter is pressed. The id of the model changes
   * in one step — with the references, the stored position and the selection — so the node
   * neither jumps nor loses its selection while the id is being typed.
   */
  applyIdDraft(node: NodeVM): void {
    const draft = this.idDraft().trim();
    if (!draft) {
      this.idDraft.set(node.id);
      return;
    }
    if (draft === node.id) return;
    node.id = draft;
    this.rename(node);
  }

  /**
   * Sets the id of an agent node from the agent dropdown.
   *
   * <p>The id of an agent node is the agent: it must be one of the document's agents, otherwise
   * the step has no counterpart in the document. The agent ref follows the id as long as it was
   * in sync (empty or equal to the previous id); a deliberately different ref is kept.
   */
  pickAgentId(node: NodeVM, agentId: string): void {
    if (!agentId) return;
    const oldId = this.originalId;
    const oldRef = node.agentRef;
    node.id = agentId;
    if (!node.agentRef || node.agentRef === oldId) {
      node.agentRef = agentId;
    }
    if (!this.rename(node)) {
      // The id was rejected (already taken): the ref goes back to what it was.
      node.agentRef = oldRef;
    }
  }

  // ── dependencies / branches ───────────────────────────────────────────────

  toggleDep(id: string, event: Event): void {
    const s = this.selected();
    if (!s) return;
    const checked = (event.target as HTMLInputElement).checked;
    s.dependsOn = checked ? [...new Set([...s.dependsOn, id])] : s.dependsOn.filter((d) => d !== id);
    this.update();
  }

  toggleBody(id: string, event: Event): void {
    const s = this.selected();
    if (!s) return;
    const checked = (event.target as HTMLInputElement).checked;
    s.bodySteps = checked ? [...new Set([...s.bodySteps, id])] : s.bodySteps.filter((b) => b !== id);
    this.update();
  }

  addBranch(): void {
    const s = this.selected();
    if (!s) return;
    s.branches = [...s.branches, { branchId: 'b' + (s.branches.length + 1), condition: '', target: '' }];
    this.update();
  }

  removeBranch(index: number): void {
    const s = this.selected();
    if (!s) return;
    s.branches = s.branches.filter((_, i) => i !== index);
    this.update();
  }

  update(): void {
    this.nodesChange.emit(this.nodes().map((n) => toNodeJson(n)));
  }
}
