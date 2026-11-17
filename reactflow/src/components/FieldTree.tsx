import { useState } from 'react';

export function FieldTree({ basePath, properties, selected, onToggle, depth = 0 }) {
  const keys = Object.keys(properties || {});
  if (keys.length === 0) return null;
  return (
    <div style={{ marginLeft: depth > 0 ? 12 : 0 }}>
      {keys.map((key) => {
        const path = basePath + '.' + key;
        const checked = (selected || []).includes(path);
        const nested = properties[key]?.properties;
        return (
          <div key={path}>
            <label style={{ fontSize: Math.max(9, 11 - depth), color: '#d4d4d4' }}>
              <input type="checkbox" checked={checked} onChange={() => onToggle(path)} />
              {' .' + key}
            </label>
            {nested && <FieldTree basePath={path} properties={nested} selected={selected} onToggle={onToggle} depth={depth + 1} />}
          </div>
        );
      })}
    </div>
  );
}