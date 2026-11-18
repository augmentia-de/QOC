import { useState, useCallback } from 'react';

const PANEL_IDS = ['files', 'viewer', 'tasks', 'questions', 'events', 'llm'] as const;
export type PanelId = typeof PANEL_IDS[number];

const DEFAULT_HEIGHTS: Record<PanelId, number> = {
  files: 180,
  viewer: 160,
  tasks: 140,
  questions: 140,
  events: 140,
  llm: 120,
};

function clamp(v: number, min: number, max: number): number {
  return Math.min(Math.max(v, min), max);
}

function loadCollapsed(): Record<PanelId, boolean> {
  const out = {} as Record<PanelId, boolean>;
  for (const id of PANEL_IDS) {
    try { out[id] = localStorage.getItem('qoc.chat.panel.' + id) === '1'; }
    catch { out[id] = false; }
  }
  // LLM panel collapsed by default
  if (localStorage.getItem('qoc.chat.panel.llm') === null) out.llm = true;
  return out;
}

export function usePanelLayout() {
  const [sideWidth, setSideWidth] = useState<number>(() => {
    try {
      const v = Number(localStorage.getItem('qoc.chat.sideWidth'));
      if (Number.isFinite(v) && v > 0) return clamp(v, 220, window.innerWidth * 0.6);
    } catch { /* ignore */ }
    return 380;
  });

  const [heights, setHeights] = useState<Record<PanelId, number>>(() => {
    const total = (typeof window !== 'undefined' ? window.innerHeight : 800) * 0.6;
    const base = { ...DEFAULT_HEIGHTS };
    const sum = Object.values(base).reduce((a, b) => a + b, 0);
    if (sum > 0 && total > 0) {
      const scale = total / sum;
      for (const k of PANEL_IDS) base[k] = Math.max(60, Math.round(base[k] * scale));
    }
    return base;
  });

  const [collapsed, setCollapsed] = useState<Record<PanelId, boolean>>(loadCollapsed);

  const setSideWidthPersisted = useCallback((w: number) => {
    const clamped = clamp(w, 220, (typeof window !== 'undefined' ? window.innerWidth : 1200) * 0.6);
    setSideWidth(clamped);
    try { localStorage.setItem('qoc.chat.sideWidth', String(clamped)); } catch { /* ignore */ }
  }, []);

  const togglePanel = useCallback((id: PanelId) => {
    setCollapsed((prev) => {
      const next = { ...prev, [id]: !prev[id] };
      try { localStorage.setItem('qoc.chat.panel.' + id, next[id] ? '1' : '0'); } catch { /* ignore */ }
      setHeights((h) => ({ ...h, [id]: next[id] ? 60 : DEFAULT_HEIGHTS[id] }));
      return next;
    });
  }, []);

  const resizePanels = useCallback((i: number, ids: PanelId[], delta: number) => {
    const topId = ids[i];
    const botId = ids[i + 1];
    if (!topId || !botId) return;
    setHeights((prev) => {
      if (collapsed[topId] || collapsed[botId]) return prev;
      const nt = Math.max(60, prev[topId] + delta);
      const nb = Math.max(60, prev[botId] - delta);
      return { ...prev, [topId]: nt, [botId]: nb };
    });
  }, [collapsed]);

  const resizeSide = useCallback((delta: number) => {
    setSideWidth((prev) => {
      const nw = clamp(prev - delta, 220, (typeof window !== 'undefined' ? window.innerWidth : 1200) * 0.6);
      try { localStorage.setItem('qoc.chat.sideWidth', String(nw)); } catch { /* ignore */ }
      return nw;
    });
  }, []);

  return {
    sideWidth,
    setSideWidth: setSideWidthPersisted,
    resizeSide,
    heights,
    setHeights,
    collapsed,
    togglePanel,
    resizePanels,
    PANEL_IDS,
  };
}