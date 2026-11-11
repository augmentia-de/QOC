import { ComponentFixture, TestBed } from '@angular/core/testing';
import { GraphEditorComponent } from './graph-editor';
import { emptyVm, toNodeJson, toNodeVm } from './graph-model';

/**
 * WP12 baseline (characterisation) tests for the graph editor: they pin the node mapping and the
 * key structural interactions the refactoring must preserve.
 */
describe('GraphEditorComponent (baseline)', () => {
  let fixture: ComponentFixture<GraphEditorComponent>;
  let component: GraphEditorComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [GraphEditorComponent] }).compileComponents();
    fixture = TestBed.createComponent(GraphEditorComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('renders nodes passed via nodesJson', () => {
    fixture.componentRef.setInput('nodesJson', [
      { id: 'a', type: 'agent', title: 'Alpha', agent: { ref: 'agent-1' } },
      { id: 'b', type: 'agent', title: 'Beta', dependsOn: ['a'] },
    ]);
    fixture.detectChanges();

    expect(component.nodes().length).toBe(2);
    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('Alpha');
    expect(text).toContain('Beta');
  });

  it('adds a node and emits the rebuilt node array', () => {
    const emitted: unknown[][] = [];
    component.nodesChange.subscribe((v) => emitted.push(v));

    component.newType = 'loop';
    component.addNode();
    fixture.detectChanges();

    expect(component.nodes().length).toBe(1);
    expect(component.nodes()[0].type).toBe('loop');
    expect(emitted.length).toBe(1);
  });

  it('connects two nodes via click-to-link', () => {
    const emitted: unknown[][] = [];
    component.nodesChange.subscribe((v) => emitted.push(v));
    fixture.componentRef.setInput('nodesJson', [
      { id: 'a', type: 'agent' },
      { id: 'b', type: 'agent' },
    ]);
    fixture.detectChanges();

    component.linking.set(true);
    component.onNodeClick('a');
    component.onNodeClick('b');
    fixture.detectChanges();

    expect(emitted.length).toBe(1);
    const out = emitted[0] as Array<{ id: string; dependsOn?: string[] }>;
    expect(out.find((n) => n.id === 'b')?.dependsOn).toEqual(['a']);
  });

  it('deletes a node and prunes dangling references', () => {
    fixture.componentRef.setInput('nodesJson', [
      { id: 'a', type: 'agent' },
      { id: 'b', type: 'agent', dependsOn: ['a'] },
    ]);
    fixture.detectChanges();

    component.onNodeClick('a');
    component.deleteSelected();
    fixture.detectChanges();

    expect(component.nodes().map((n) => n.id)).toEqual(['b']);
    expect(component.nodes()[0].dependsOn).toEqual([]);
  });

  it('editing the ID field rewrites the references of the other nodes', () => {
    // WP12 froze the dangling reference (rename() looked the node up by the *old* selectedId
    // after ngModel had already written the new id). This is the follow-up: the id can now
    // change without breaking the graph.
    fixture.componentRef.setInput('nodesJson', [
      { id: 'a', type: 'agent' },
      { id: 'b', type: 'agent', dependsOn: ['a'] },
    ]);
    fixture.detectChanges();

    const node = fixture.nativeElement.querySelectorAll('.gnode')[0] as HTMLElement;
    node.dispatchEvent(new MouseEvent('click'));
    fixture.detectChanges();

    const idInput = fixture.nativeElement.querySelector('.card .node-id') as HTMLInputElement;
    idInput.value = 'a2';
    idInput.dispatchEvent(new Event('input'));
    idInput.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(component.nodes().map((n) => n.id)).toEqual(['a2', 'b']);
    expect(component.nodes().find((n) => n.id === 'b')!.dependsOn).toEqual(['a2']);
  });

  it('keeps the node selected and in place while the id is being typed', () => {
    // Regression: the id was bound directly to the model, so the first keystroke changed it.
    // The id is the key of the selection and of the stored position - the node lost its
    // selection and jumped to the auto-layout position, and the id field disappeared with it.
    fixture.componentRef.setInput('nodesJson', [
      { id: 'a', type: 'decision' },
      { id: 'b', type: 'decision', dependsOn: ['a'] },
    ]);
    fixture.detectChanges();
    component.onNodeClick('a');
    fixture.detectChanges();

    const gnode = fixture.nativeElement.querySelectorAll('.gnode')[0] as HTMLElement;
    const before = { left: gnode.style.left, top: gnode.style.top };

    const idInput = fixture.nativeElement.querySelector('.card .node-id') as HTMLInputElement;
    idInput.value = 'a2';
    idInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    // Nothing applied yet: the model is untouched.
    expect(component.idDraft()).toBe('a2');
    expect(component.nodes().map((n) => n.id)).toEqual(['a', 'b']);
    expect(component.selected()?.id).toBe('a');
    expect(gnode.classList.contains('selected')).toBeTrue();
    expect({ left: gnode.style.left, top: gnode.style.top }).toEqual(before);
    // The field is still there, so the id can be typed to the end.
    expect(fixture.nativeElement.querySelector('.card .node-id')).toBeTruthy();
  });

  it('applies the typed id on Enter and rewrites the references', () => {
    const emitted: unknown[][] = [];
    component.nodesChange.subscribe((v) => emitted.push(v));
    fixture.componentRef.setInput('nodesJson', [
      { id: 'a', type: 'decision' },
      { id: 'b', type: 'decision', dependsOn: ['a'] },
    ]);
    fixture.detectChanges();
    component.onNodeClick('a');
    fixture.detectChanges();

    const idInput = fixture.nativeElement.querySelector('.card .node-id') as HTMLInputElement;
    idInput.value = 'a2';
    idInput.dispatchEvent(new Event('input'));
    idInput.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    fixture.detectChanges();

    expect(component.nodes().map((n) => n.id)).toEqual(['a2', 'b']);
    expect(component.nodes().find((n) => n.id === 'b')!.dependsOn).toEqual(['a2']);
    expect(emitted.length).toBe(1);
  });

  it('reports a duplicate id and keeps the old one', () => {    fixture.componentRef.setInput('nodesJson', [
      { id: 'a', type: 'agent' },
      { id: 'b', type: 'agent' },
    ]);
    fixture.detectChanges();

    component.onNodeClick('a');
    fixture.detectChanges();

    const idInput = fixture.nativeElement.querySelector('.card .node-id') as HTMLInputElement;
    idInput.value = 'b';
    idInput.dispatchEvent(new Event('input'));
    idInput.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(component.nodes().map((n) => n.id)).toEqual(['a', 'b']);
  });
});

describe('GraphEditorComponent — ID aus Dropdown für Agent-Knoten', () => {
  let fixture: ComponentFixture<GraphEditorComponent>;
  let component: GraphEditorComponent;

  const agents = [
    { id: 'researcher', name: 'Researcher' },
    { id: 'writer', name: 'Writer' },
  ];

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [GraphEditorComponent] }).compileComponents();
    fixture = TestBed.createComponent(GraphEditorComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('agents', agents);
    fixture.detectChanges();
  });

  function idControl(): HTMLSelectElement | HTMLInputElement {
    return fixture.nativeElement.querySelector('.card .node-id');
  }

  function selectNode(id: string): void {
    component.onNodeClick(id);
    fixture.detectChanges();
  }

  it('zeigt die ID eines Agent-Knoten als Dropdown der Dokument-Agents', () => {
    fixture.componentRef.setInput('nodesJson', [{ id: 'a', type: 'agent' }]);
    fixture.detectChanges();
    selectNode('a');

    const control = idControl();
    expect(control.tagName).toBe('SELECT');
    const options = Array.from((control as HTMLSelectElement).options).map((o) => o.textContent);
    expect(options).toContain('Researcher (researcher)');
    expect(options).toContain('Writer (writer)');
  });

  it('übernimmt den gewählten Agenten als ID und als Agent-Ref', () => {
    const emitted: unknown[][] = [];
    component.nodesChange.subscribe((v) => emitted.push(v));
    fixture.componentRef.setInput('nodesJson', [{ id: 'node-1', type: 'agent' }]);
    fixture.detectChanges();
    selectNode('node-1');

    const select = idControl() as HTMLSelectElement;
    const options = Array.from(select.options);
    const index = options.findIndex((o) => o.textContent?.includes('researcher'));
    select.selectedIndex = index;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(component.nodes()[0].id).toBe('researcher');
    expect(component.nodes()[0].agentRef).toBe('researcher');
    expect(emitted.length).toBe(1);
    expect((emitted[0] as any[])[0]).toEqual({
      id: 'researcher',
      type: 'agent',
      agent: { ref: 'researcher' },
    });
  });

  it('behält einen bewusst abweichenden Agent-Ref', () => {
    fixture.componentRef.setInput('nodesJson', [
      { id: 'node-1', type: 'agent', agent: { ref: 'agents/extractor@1.0.0' } },
    ]);
    fixture.detectChanges();
    selectNode('node-1');

    const select = idControl() as HTMLSelectElement;
    const options = Array.from(select.options);
    const index = options.findIndex((o) => o.textContent?.includes('writer'));
    select.selectedIndex = index;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(component.nodes()[0].id).toBe('writer');
    expect(component.nodes()[0].agentRef).toBe('agents/extractor@1.0.0');
  });

  it('schreibt die Referenzen der anderen Knoten beim Auswählen um', () => {
    fixture.componentRef.setInput('nodesJson', [
      { id: 'a', type: 'agent' },
      { id: 'b', type: 'agent', dependsOn: ['a'] },
    ]);
    fixture.detectChanges();
    selectNode('a');

    const select = idControl() as HTMLSelectElement;
    const options = Array.from(select.options);
    const index = options.findIndex((o) => o.textContent?.includes('researcher'));
    select.selectedIndex = index;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(component.nodes().map((n) => n.id)).toEqual(['researcher', 'b']);
    expect(component.nodes().find((n) => n.id === 'b')!.dependsOn).toEqual(['researcher']);
  });

  it('verwirft den Agent-Ref, wenn die gewählte ID bereits vergeben ist', () => {
    fixture.componentRef.setInput('nodesJson', [
      { id: 'a', type: 'agent' },
      { id: 'researcher', type: 'agent' },
    ]);
    fixture.detectChanges();
    selectNode('a');

    const select = idControl() as HTMLSelectElement;
    const options = Array.from(select.options);
    const index = options.findIndex((o) => o.textContent?.includes('researcher'));
    select.selectedIndex = index;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    // The id is taken -> the rename is rejected, the node keeps id and ref.
    expect(component.nodes()[0].id).toBe('a');
    expect(component.nodes()[0].agentRef).toBe('');
  });

  it('lässt die ID eines Nicht-Agent-Knoten frei editierbar', () => {    fixture.componentRef.setInput('nodesJson', [{ id: 'd', type: 'decision' }]);
    fixture.detectChanges();
    selectNode('d');

    const control = idControl();
    expect(control.tagName).toBe('INPUT');
  });

  it('fällt ohne Agents im Dokument auf das Freitextfeld zurück', () => {
    fixture.componentRef.setInput('agents', []);
    fixture.componentRef.setInput('nodesJson', [{ id: 'a', type: 'agent' }]);
    fixture.detectChanges();
    selectNode('a');

    const control = idControl();
    expect(control.tagName).toBe('INPUT');
  });
});

describe('graph-model', () => {
  it('toNodeVm maps agent refs and branches', () => {
    const vm = toNodeVm({
      id: 'd',
      type: 'decision',
      title: 'Decide',
      agent: { ref: 'a1' },
      dependsOn: ['start'],
      branches: [{ branchId: 'b1', condition: 'x>1', firstStep: 'next' }],
    });
    expect(vm.agentRef).toBe('a1');
    expect(vm.dependsOn).toEqual(['start']);
    expect(vm.branches).toEqual([{ branchId: 'b1', condition: 'x>1', target: 'next' }]);
  });

  it('toNodeJson emits the schema-shaped node per type', () => {
    const agent = emptyVm('a', 'agent');
    expect(toNodeJson(agent)).toEqual({ id: 'a', type: 'agent', title: 'a', agent: { ref: '' } });

    const decision = emptyVm('d', 'decision');
    expect(toNodeJson(decision)).toEqual({
      id: 'd',
      type: 'decision',
      title: 'd',
      branches: [{ branchId: 'b1', condition: '', target: '' }],
    });

    const loop = toNodeJson({ ...emptyVm('l', 'loop'), exitCondition: '' });
    expect(loop).toEqual({ id: 'l', type: 'loop', title: 'l', maxIterations: 3, exitCondition: 'falsy', bodySteps: [] });
  });
});
