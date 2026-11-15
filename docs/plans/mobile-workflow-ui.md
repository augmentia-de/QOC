# Implementierungsplan: Mobile Version des workflow-ui

Status: Plan (2026-11-14)
Bezug: `workflow-ui/src/App.tsx`, `workflow-ui/src/pages/GraphEditor.tsx`, `workflow-ui/src/components/PropertyPanel.tsx`

## 1. Herausforderungen (verifiziert im Code)

- **GraphEditor** (`pages/GraphEditor.tsx`): ReactFlow-Canvas mit Drag/Drop, Zoom, Pan – auf Touch nur bedingt nutzbar.
- **Navigation** (`App.tsx`): horizontale Top-Nav mit 11 Links – auf Mobile überläuft/bricht.
- **PropertyPanel** (`components/PropertyPanel.tsx`): breite Sidebar, für kleine Screens zu breit.
- **Daten-Tabellen** (Workflows, Runs, Agents, MCP): horizontale Tabellen, nicht mobile-optimiert.

## 2. Konzept: "Mobile-First, Desktop-Enhanced"

### 2.1 Layout & Navigation

- **Bottom-Navigation-Bar** (5 Haupt-Icons: Dashboard, Workflows, Editor, Runs, Settings) – thumb-reachable.
- **Hamburger-Menu** für sekundäre Seiten (Agents, MCP, Generate, HITL, Observe, Testbench).
- **Top-Bar**: Titel + Language-Switcher (kompakt).
- **Safe-Area-Insets** für Notch/Gesture-Bar.

### 2.2 GraphEditor – Mobile-Modus

Der Canvas ist auf Mobile nicht praktikabel. Zwei Optionen:

- **Option A (empfohlen):** Mobile zeigt eine **Listenansicht** der Nodes (Titel, Typ, Status) + Tap öffnet PropertyPanel als Bottom-Sheet. Edges werden als "Abhängigkeiten"-Liste angezeigt. Drag/Drop nur auf Desktop.
- **Option B:** Read-only-Viewer mit Pinch-Zoom (kein Editieren).

Implementierung: `useMediaQuery('(max-width: 768px)')` → bedingtes Rendering. ReactFlow wird nur auf Desktop geladen (lazy import, Code-Splitting).

### 2.3 Seiten-Anpassungen

| Seite | Desktop | Mobile |
|---|---|---|
| Dashboard | KPI-Cards + Charts | Vertikale Card-Liste, Charts als Mini-Sparklines |
| Workflows | Tabelle | Card-Liste mit Swipe-Actions (Edit/Delete) |
| WorkflowDetail | Split-View (Info + Graph) | Tabs: Info / Graph(Read-only) / Runs |
| GraphEditor | Canvas + Sidebar | Listenansicht + Bottom-Sheet Properties |
| Runs | Tabelle mit Filtern | Card-Liste, Filter als Bottom-Sheet |
| Agents | Tabelle + Form | Akkordeon-Liste + Modal-Form |
| McpServers | Tabelle + Form | Wie Agents |
| Generate | Textarea + Button | Vollbild-Textarea + Button |
| Settings | Formular | Vertikale Sections, Accordion |
| HITL/Observe/Testbench | jeweils anpassen | jeweils anpassen |

### 2.4 Technische Umsetzung

- **Tailwind Breakpoints:** `sm:` (640px), `md:` (768px), `lg:` (1024px). Mobile-First: Basis-Styles für Mobile, `md:`/`lg:` für Desktop.
- **Bottom-Sheet-Komponente:** neues `components/BottomSheet.tsx` (Slide-up, Backdrop, Swipe-to-close).
- **Responsive Nav:** `components/BottomNav.tsx` (Mobile) + `components/TopNav.tsx` (Desktop).
- **Touch-Gesten:** `react-use-gesture` für Swipe-Actions (optional).
- **Viewport-Meta:** `index.html` prüfen (viewport width=device-width, initial-scale=1).
- **PWA (optional):** `manifest.json` + Service Worker für Offline-Nutzung.

## 3. Neue / geänderte Dateien

```
workflow-ui/src/
├── components/
│   ├── BottomNav.tsx          # NEU: Mobile-Bottom-Navigation
│   ├── TopNav.tsx             # NEU: Desktop-Top-Navigation (aus App.tsx extrahiert)
│   ├── BottomSheet.tsx        # NEU: Slide-up Panel
│   └── NodeListView.tsx       # NEU: Mobile-Listenansicht für GraphEditor
├── hooks/
│   └── useMediaQuery.ts       # NEU: Breakpoint-Hook
├── App.tsx                    # GEÄNDERT: responsive Nav + Layout
├── pages/GraphEditor.tsx      # GEÄNDERT: bedingtes Rendering (Canvas vs. Liste)
└── index.html                 # GEÄNDERT: Viewport-Meta prüfen
```

## 4. Reihenfolge der Umsetzung

### Phase 1 (2-3 Tage): Responsive Layout
1. `useMediaQuery` Hook erstellen.
2. `BottomNav.tsx` + `TopNav.tsx` aus `App.tsx` extrahieren.
3. `App.tsx` responsive: Mobile → BottomNav, Desktop → TopNav.
4. Viewport-Meta in `index.html` prüfen/setzen.
5. Alle Seiten mit Tailwind Mobile-First anpassen (Card-Layouts statt Tabellen).

### Phase 2 (2-3 Tage): GraphEditor Mobile-Modus
1. `NodeListView.tsx`: Liste der Nodes (Titel, Typ, Status) mit Tap → PropertyPanel.
2. `GraphEditor.tsx`: `useMediaQuery` → Mobile: NodeListView + BottomSheet(PropertyPanel), Desktop: ReactFlow-Canvas.
3. ReactFlow lazy import für Desktop (Code-Splitting).
4. Edges als "Abhängigkeiten"-Liste in NodeListView.

### Phase 3 (1-2 Tage): Bottom-Sheet & Swipe-Actions
1. `BottomSheet.tsx`: Slide-up, Backdrop, Swipe-to-close.
2. Swipe-Actions auf Card-Listen (Workflows, Runs, Agents).
3. PropertyPanel in BottomSheet einbetten.

### Phase 4 (1 Tag): Tests & Polish
1. Playwright mobile viewport Tests (375px, 768px).
2. PWA-Manifest (optional).
3. Performance: Lazy-Loading, Virtualisierung bei vielen Nodes.

## 5. Risiken

- **ReactFlow auf Mobile:** Performance (viele Nodes). Lösung: Virtualisierung + Lazy-Loading.
- **PropertyPanel-Komplexität:** zu viele Felder für Mobile. Lösung: Accordion-Sections im Bottom-Sheet.
- **i18n:** Bottom-Nav-Labels müssen kurz sein (Icons + kurze Labels).
- **Touch-Targets:** Mindestgröße 44px für Buttons/Links.

## 6. Abhängigkeiten

- Keine neuen Backend-Änderungen nötig.
- `react-use-gesture` (optional) für Swipe-Gesten.
- Tailwind bereits vorhanden.

## 7. Offene Fragen

1. Soll der GraphEditor auf Mobile komplett read-only sein, oder sollen einfache Edits (Node hinzufügen/entfernen) möglich sein?
2. Soll eine PWA (Offline-Nutzung) implementiert werden?
3. Welche Breakpoints sind relevant (375px iPhone, 768px Tablet)?
