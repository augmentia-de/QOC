import { useEffect, useState } from 'react'
import { A2A } from '../services/qocApi'

interface A2AAgent {
  name?: string
  id?: string
  url?: string
  status?: string
  cached?: boolean
  version?: string
  protocolVersion?: string
  description?: string
  skills?: Array<{ name?: string; id?: string }>
  error?: string
  tool?: string
}

export default function A2APage() {
  const [agents, setAgents] = useState<A2AAgent[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  const load = async (refresh = false) => {
    setLoading(true)
    setError('')
    try {
      const data = await A2A.listAgents(refresh)
      setAgents(Array.isArray(data?.data) ? data.data : [])
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
      setAgents([])
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
  }, [])

  const statusColor = (status?: string) => status === 'ok' ? '#3fb950' : status === 'error' ? '#f85149' : '#9aa0a6'

  return (
    <div style={{ padding: 24, color: '#d4d4d4' }}>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 12 }}>
        <h1 style={{ fontSize: 22, color: '#5bc0de', margin: 0 }}>A2A-Agents</h1>
        <button onClick={() => void load(true)} style={{ padding: '6px 12px', background: '#555', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 12 }}>Aktualisieren</button>
      </div>
      {loading ? <p style={{ color: '#999' }}>Lade…</p> : error ? <p style={{ color: '#f85149' }}>Error: {error}</p> : agents.length === 0 ? (
        <p style={{ color: '#999' }}>Keine A2A-Agents konfiguriert (engine.a2a.urls / engine.a2a.endpoints).</p>
      ) : (
        <div style={{ display: 'grid', gap: 8 }}>
          {agents.map((agent, index) => (
            <div key={agent.id || agent.url || index} style={{ border: '1px solid #333', borderRadius: 8, padding: 12, background: '#1e1e1e' }}>
              <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
                <strong>{agent.name || agent.id || agent.url}</strong>
                <span style={{ fontSize: 11, padding: '1px 6px', borderRadius: 999, border: '1px solid #333', color: statusColor(agent.status) }}>{agent.status || 'unknown'}</span>
                {agent.cached ? <span style={{ fontSize: 11, color: '#999' }}>cache</span> : null}
              </div>
              <div style={{ fontSize: 12, color: '#999', marginTop: 4, wordBreak: 'break-all' }}>{[agent.url, agent.tool ? '→ ' + agent.tool : null].filter(Boolean).join(' ')}</div>
              {(agent.version || agent.protocolVersion) ? <div style={{ fontSize: 12, color: '#999', marginTop: 2 }}>{[agent.version ? 'v' + agent.version : null, agent.protocolVersion ? 'protocol ' + agent.protocolVersion : null].filter(Boolean).join(' · ')}</div> : null}
              {agent.description ? <div style={{ fontSize: 13, marginTop: 6 }}>{agent.description}</div> : null}
              {agent.skills && agent.skills.length > 0 ? (
                <ul style={{ margin: '6px 0 0', paddingLeft: 18, fontSize: 12 }}>
                  {agent.skills.map((skill, skillIndex) => <li key={skill.id || skill.name || skillIndex}>{skill.name || skill.id}</li>)}
                </ul>
              ) : null}
              {agent.error ? <div style={{ fontSize: 12, color: '#f85149', marginTop: 6 }}>{agent.error}</div> : null}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}