# Implementation Plan: Mobile Version of workflow-ui

Status: Plan (2026-11-14)
Reference: `workflow-ui/src/App.tsx`, `workflow-ui/src/pages/GraphEditor.tsx`, `workflow-ui/src/components/PropertyPanel.tsx`

## 1. Challenges (verified in code)

- **GraphEditor** (`pages/GraphEditor.tsx`): ReactFlow canvas with drag/drop, zoom, pan – only partially usable on touch.
- **Navigation** (`App.tsx`): horizontal top nav with 11 links – overflows/breaks on mobile.
- **PropertyPanel** (`components/PropertyPanel.tsx`): wide sidebar, too wide for small screens.
- **Data tables** (Workflows, Runs, Agents, MCP): horizontal tables, not mobile-optimized.

## 2. Concept: "Mobile-First, Desktop-Enhanced"

### 2.1 Layout & Navigation

- **Bottom navigation bar** (5 main icons: Dashboard, Workflows, Editor, Runs, Settings) – thumb-reachable.
- **Hamburger menu** for secondary pages (Agents, MCP, Generate, HITL, Observe, Testbench).
- **Top bar**: title + language switcher (compact).
- **Safe-area insets** for notch/gesture bar.

### 2.2 GraphEditor – Mobile Mode

The canvas is not practical on mobile. Two options:

- **Option A (recommended):** Mobile shows a **list view** of the nodes (title, type, status) + tap opens the PropertyPanel as a bottom sheet. Edges are shown as a "Dependencies" list. Drag/drop only on desktop.
- **Option B:** Read-only viewer with pinch-zoom (no editing).

Implementation: `useMediaQuery('(max-width: 768px)')` → conditional rendering. ReactFlow is only loaded on desktop (lazy import, code splitting).

### 2.3 Page Adjustments

| Page | Desktop | Mobile |
|---|---|---|
| Dashboard | KPI cards + charts | Vertical card list, charts as mini-sparklines |
| Workflows | Table | Card list with swipe actions (Edit/Delete) |
| WorkflowDetail | Split view (Info + Graph) | Tabs: Info / Graph (read-only) / Runs |
| GraphEditor | Canvas + sidebar | List view + bottom-sheet properties |
| Runs | Table with filters | Card list, filters as bottom sheet |
| Agents | Table + form | Accordion list + modal form |
| McpServers | Table + form | Same as Agents |
| Generate | Textarea + button | Full-screen textarea + button |
| Settings | Form | Vertical sections, accordion |
| HITL/Observe/Testbench | adjust individually | adjust individually |

### 2.4 Technical Implementation

- **Tailwind breakpoints:** `sm:` (640px), `md:` (768px), `lg:` (1024px). Mobile-first: base styles for mobile, `md:`/`lg:` for desktop.
- **Bottom-sheet component:** new `components/BottomSheet.tsx` (slide-up, backdrop, swipe-to-close).
- **Responsive nav:** `components/BottomNav.tsx` (mobile) + `components/TopNav.tsx` (desktop).
- **Touch gestures:** `react-use-gesture` for swipe actions (optional).
- **Viewport meta:** check `index.html` (viewport width=device-width, initial-scale=1).
- **PWA (optional):** `manifest.json` + service worker for offline use.

## 3. New / Changed Files

```
workflow-ui/src/
├── components/
│   ├── BottomNav.tsx          # NEW: mobile bottom navigation
│   ├── TopNav.tsx             # NEW: desktop top navigation (extracted from App.tsx)
│   ├── BottomSheet.tsx        # NEW: slide-up panel
│   └── NodeListView.tsx       # NEW: mobile list view for GraphEditor
├── hooks/
│   └── useMediaQuery.ts       # NEW: breakpoint hook
├── App.tsx                    # CHANGED: responsive nav + layout
├── pages/GraphEditor.tsx      # CHANGED: conditional rendering (canvas vs. list)
└── index.html                 # CHANGED: check viewport meta
```

## 4. Implementation Order

### Phase 1 (2-3 days): Responsive Layout
1. Create `useMediaQuery` hook.
2. Extract `BottomNav.tsx` + `TopNav.tsx` from `App.tsx`.
3. Make `App.tsx` responsive: mobile → BottomNav, desktop → TopNav.
4. Check/set viewport meta in `index.html`.
5. Adapt all pages to Tailwind mobile-first (card layouts instead of tables).

### Phase 2 (2-3 days): GraphEditor Mobile Mode
1. `NodeListView.tsx`: list of nodes (title, type, status) with tap → PropertyPanel.
2. `GraphEditor.tsx`: `useMediaQuery` → mobile: NodeListView + BottomSheet(PropertyPanel), desktop: ReactFlow canvas.
3. Lazy import ReactFlow for desktop (code splitting).
4. Edges as "Dependencies" list in NodeListView.

### Phase 3 (1-2 days): Bottom Sheet & Swipe Actions
1. `BottomSheet.tsx`: slide-up, backdrop, swipe-to-close.
2. Swipe actions on card lists (Workflows, Runs, Agents).
3. Embed PropertyPanel in BottomSheet.

### Phase 4 (1 day): Tests & Polish
1. Playwright mobile viewport tests (375px, 768px).
2. PWA manifest (optional).
3. Performance: lazy loading, virtualization for many nodes.

## 5. Risks

- **ReactFlow on mobile:** performance (many nodes). Solution: virtualization + lazy loading.
- **PropertyPanel complexity:** too many fields for mobile. Solution: accordion sections in the bottom sheet.
- **i18n:** bottom nav labels must be short (icons + short labels).
- **Touch targets:** minimum size 44px for buttons/links.

## 6. Dependencies

- No new backend changes needed.
- `react-use-gesture` (optional) for swipe gestures.
- Tailwind already present.

## 7. Open Questions

1. Should the GraphEditor be completely read-only on mobile, or should simple edits (add/remove node) be possible?
2. Should a PWA (offline use) be implemented?
3. Which breakpoints are relevant (375px iPhone, 768px tablet)?