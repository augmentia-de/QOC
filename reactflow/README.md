# ReactFlow Example

Minimal ReactFlow example with Vite + React + mock backend.

## Run

```bash
# Frontend + Mock backend together
./start.sh

# Or separately:
./start.sh frontend   # Vite dev server (port 5173)
./start.sh mock       # json-server mock backend (port 3001)
```

## Mock Backend

`mock-server/db.json` provides `/nodes` and `/edges` endpoints.

## Build

```bash
npm run build
```