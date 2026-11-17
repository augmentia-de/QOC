export type JsonValue = string | number | boolean | null | JsonObject | JsonValue[]

export interface JsonObject {
  [key: string]: JsonValue
}

export interface SessionInfo {
  id: string
  agent?: string
  mode?: string
  title?: string
  parentSessionId?: string
}

export interface WorkflowRun {
  runId: string
  sessionId: string
  workflowId: string
  status: string
  currentNodeId?: string
  startedAt: string
  updatedAt: string
  error?: string
  active?: boolean
}