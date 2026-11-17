import { BaseEdge, EdgeLabelRenderer, getBezierPath } from '@xyflow/react';
import { useNodes } from '@xyflow/react';

function truncateJson(value, maxDepth = 2, maxLines = 5) {
  function walk(v, depth) {
    if (depth > maxDepth) return '…';
    if (v === null) return 'null';
    if (typeof v !== 'object') return JSON.stringify(v);
    if (Array.isArray(v)) {
      if (v.length === 0) return '[]';
      return '[\n' + v.slice(0, 3).map((x) => '  ' + walk(x, depth + 1)).join(',\n') + (v.length > 3 ? ',\n  …' : '') + '\n]';
    }
    const keys = Object.keys(v);
    if (keys.length === 0) return '{}';
    return '{\n' + keys.slice(0, 5).map((k) => '  ' + JSON.stringify(k) + ': ' + walk(v[k], depth + 1)).join(',\n') + (keys.length > 5 ? ',\n  …' : '') + '\n}';
  }
  const text = walk(value, 1);
  const lines = text.split('\n');
  if (lines.length <= maxLines) return text;
  return lines.slice(0, maxLines).join('\n') + '\n…';
}

export function LabeledEdge({ id, source, target, sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, selected = false, markerEnd }: any) {
  const nodes = useNodes();
  const src = nodes.find((n) => n.id === source);
  const tgt = nodes.find((n) => n.id === target);
  const srcLabel = (src && (src.data?.title || src.data?.label || src.id)) || source;
  const tgtLabel = (tgt && (tgt.data?.title || tgt.data?.label || tgt.id)) || target;
  const srcOutput = src && (src.data?.responseSchema || src.data?.outputContract || (src.data?.jsonOutput ? { type: 'object' } : null));
  const srcOutputText = srcOutput ? truncateJson(srcOutput) : 'text';
  const inputs = (tgt && Array.isArray(tgt.data?.inputs)) ? tgt.data.inputs : [];
  const inputConfig = inputs.length
    ? inputs.map((i) => i.path || i.nodeId).join(',\n')
    : 'output: "text",\ninput: "lastMessage"';
  const [edgePath, labelX, labelY] = getBezierPath({ sourceX, sourceY, sourcePosition, targetX, targetY, targetPosition });
  return (
    <>
      <BaseEdge id={id} path={edgePath} markerEnd={markerEnd} />
      {selected && (
        <EdgeLabelRenderer>
          <div
            style={{
              position: 'absolute',
              transform: `translate(-50%, -50%) translate(${(sourceX + labelX) / 2}px,${(sourceY + labelY) / 2}px)`,
              fontSize: 8,
              background: 'transparent',
              color: '#333',
              zIndex: 1000,
              padding: '2px 4px',
              borderRadius: 4,
              pointerEvents: 'none',
              whiteSpace: 'pre-wrap',
              maxWidth: 180,
              lineHeight: 1.2,
            }}
          >
            out: {srcOutputText}
          </div>
          {/* node labels not needed on edges */}
          <div
            style={{
              position: 'absolute',
              transform: `translate(-50%, -50%) translate(${(labelX + targetX) / 2}px,${(labelY + targetY) / 2}px)`,
              fontSize: 8,
              background: 'transparent',
              color: '#333',
              zIndex: 1000,
              padding: '2px 4px',
              borderRadius: 4,
              pointerEvents: 'none',
              whiteSpace: 'pre-wrap',
              maxWidth: 180,
              lineHeight: 1.2,
            }}
          >
            in: {inputConfig}
          </div>
        </EdgeLabelRenderer>
      )}
    </>
  );
}