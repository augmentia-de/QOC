import { useEffect, useState } from 'react'
import { LlmTrace } from '../services/qocApi'

export default function LlmTracePage() {
  const [limit, setLimit] = useState(50000)
  const [text, setText] = useState('')
  const [total, setTotal] = useState(0)
  const [hasMore, setHasMore] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const load = async (nextLimit = limit, reset = false) => {
    setLoading(true)
    setError('')
    try {
      const data = await LlmTrace.get(nextLimit, nextLimit)
      const raw = typeof data?.data === 'string' ? data.data : ''
      setTotal(typeof data?.total === 'number' ? data.total : total)
      setHasMore(Boolean(data?.hasMore))
      setText(raw)
      setLimit(nextLimit)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load(50000, true)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <div style={{ padding: 24, color: '#d4d4d4', height: '100vh', boxSizing: 'border-box', display: 'flex', flexDirection: 'column' }}>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 12 }}>
        <h1 style={{ fontSize: 22, color: '#5bc0de', margin: 0 }}>LLM-Trace</h1>
        <button onClick={() => void load(50000, true)} disabled={loading} style={{ padding: '6px 12px', background: '#555', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 12 }}>Neu laden</button>
        {hasMore ? <button onClick={() => void load(Math.min(500000, limit + 50000), false)} disabled={loading} style={{ padding: '6px 12px', background: '#337ab7', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 12 }}>Ältere laden</button> : null}
        <span style={{ fontSize: 12, color: '#999' }}>total: {total}</span>
      </div>
      {error ? <p style={{ color: '#f85149' }}>Error: {error}</p> : null}
      <pre style={{ flex: 1, minHeight: 0, margin: 0, padding: 12, overflow: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-word', background: '#0b0d12', color: '#d4d4d4', border: '1px solid #333', borderRadius: 6, fontSize: 12 }}>
        {loading ? 'Lade…' : text || '(leer)'}
      </pre>
    </div>
  )
}