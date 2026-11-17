# AGENTS.md – Projekt- & Entwicklungsrichtlinien

Du bist ein erfahrener Frontend- und TypeScript-Entwickler. Halte dich bei allen Änderungen in diesem Repository strikt an die folgenden Vorgaben.

## 1. React Flow (`@xyflow/react` v12+) – Verbindliche Regeln

### Lokale Dokumentation & API-Referenz
- **Primäre Referenz:** Im Root-Verzeichnis liegt die Datei **`./llm.txt`** mit der aktuellen React-Flow-Dokumentation. Lies diese Datei bei Fragen zu APIs, Props, Hooks oder Patterns zuerst ein, bevor du React-Flow-Code schreibst oder änderst.
- **Wichtig (v12 Migration):** Importiere ausschließlich aus `@xyflow/react` und `@xyflow/react/dist/style.css`. Verwende **niemals** das veraltete Paket `reactflow` (v11) oder veraltete Prop-Namen.

### Architektur & Best Practices
- **Styles:** Stelle sicher, dass `@xyflow/react/dist/style.css` genau einmal global (z. B. im Root-Layout oder Einstiegspunkt) importiert wird.
- **Container-Dimensionen:** `<ReactFlow />` benötigt immer ein umschließendes HTML-Element mit expliziter Breite und Höhe (z. B. `w-full h-screen` oder `style={{ width: '100%', height: '100%' }}`).
- **Memoization (Performance):** Definiere `nodeTypes` und `edgeTypes` immer **außerhalb** der Komponente oder umschließe sie mit `useMemo`, um unnötige Re-Renders und Flackern zu verhindern.
- **Custom Nodes & Edges:**
  - Verwende `NodeProps<Node<MyDataType>>` bzw. `EdgeProps<Edge<MyEdgeData>>` für strikte TypeScript-Typisierung.
  - Nutze `Handle` aus `@xyflow/react` für Ein- und Ausgänge; vergib eindeutige `id`-Props, sobald ein Node mehrere Handles desselben Typs (`source` oder `target`) besitzt.
  - Aktualisiere Node-Daten über `useReactFlow().updateNodeData(id, newData)` statt manueller Array-Manipulationen, wo immer möglich.
- **State Management:**
  - Nutze für einfache Flows `useNodesState` und `useEdgesState`.
  - Bei komplexem globalen State (z. B. Zustand, Redux) implementiere `onNodesChange`, `onEdgesChange` und `onConnect` sauber über `applyNodeChanges`, `applyEdgeChanges` und `addEdge`.
- **Provider-Kontext:** Wenn Hooks wie `useReactFlow()`, `useNodes()` oder `useEdges()` außerhalb der `<ReactFlow />`-Komponente (z. B. in einer Sidebar oder Toolbar) genutzt werden, muss die übergeordnete Komponente in `<ReactFlowProvider>` gewrappt sein.

## 2. OpenCode Workflow & Code-Standards

- **TypeScript:** Kein `any`. Nutze strikte Typen und Interfaces für Node-Payloads, Edge-Daten und API-Responses.
- **Komponentenstruktur:** Halte Custom Nodes klein, modular und frei von schwerer Business-Logik (lagere Logik in Custom Hooks unter `src/hooks/` aus).
- **Änderungsumfang:** Nimm nur gezielte Änderungen vor, die zur Aufgabe gehören. Refaktoriere keine unbeteiligten Dateien ohne Rückfrage.
- **Verifikation:** Prüfe nach Änderungen an Typen oder Komponenten mit dem Projekt-Build/Typecheck (z. B. `npm run typecheck` oder `npm run build`), ob TypeScript-Fehler auftreten.