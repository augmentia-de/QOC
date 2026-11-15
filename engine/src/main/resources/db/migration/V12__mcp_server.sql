-- MCP server persistence (UI-registered servers)
CREATE TABLE IF NOT EXISTS mcp_server (
    name        TEXT PRIMARY KEY,
    config      TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);