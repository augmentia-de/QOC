-- Finds assistant messages that contain provider-native tool-call markup
-- instead of a structured tool card. These are the turns that the engine
-- should have rejected or salvaged.
--
-- Usage:
--   docker exec -i postgres-pgvector psql -U qoc -d qoc -f - \
--     < scripts/find-tool-call-leaks.sql
--
-- Optional window (edit the WHERE clause):
--   AND m.created_at >= '2026-11-16 00:00:00+00'

WITH assistant_messages AS (
    SELECT
        m.session_id,
        m.seq,
        m.created_at,
        (
            SELECT string_agg(part ->> 'text', E'\n')
            FROM jsonb_array_elements(m.data -> 'content') AS part
            WHERE part ->> '_type' = 'TextContent'
        ) AS text,
        EXISTS (
            SELECT 1
            FROM jsonb_array_elements(m.data -> 'content') AS part
            WHERE part ->> '_type' = 'ToolCardContent'
        ) AS has_tool_card
    FROM session_message m
    WHERE m.data ->> '_type' = 'AssistantMessage'
)
SELECT
    session_id,
    seq,
    created_at,
    length(text) AS text_len,
    left(text, 300) AS preview
FROM assistant_messages
WHERE NOT has_tool_card
  AND text IS NOT NULL
  -- Tolerate full-width bars (｜), repeated bars and the ▁ separator.
  AND (
        text ~* '<[^>]*[|｜]+[[:space:]]*DSML'
     OR text ~* '<[|｜]+[[:space:]]*tool'
     OR text ~* '\[[[:space:]]*TOOL_CALLS[[:space:]]*\]'
  )
ORDER BY created_at DESC;
