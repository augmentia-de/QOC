export interface Point { x: number; y: number; }

export interface NodeData {
  title?: string;
  agentRef?: string;
  requiresHumanApproval?: boolean;
  assignee?: string;
  maxIterations?: number;
  exitCondition?: string;
  bodySteps?: string[];
  joinType?: string;
  joinN?: number | null;
  branches?: Branch[];
  defaultTarget?: string;
  workflowRef?: string;
}

export interface Branch {
  branchId: string;
  condition: string;
  target: string;
}

export interface WorkflowNode {
  id: string;
  type: string;
  position: Point;
  data: NodeData;
}

export interface WorkflowEdge {
  id: string;
  source: string;
  target: string;
  type?: string;
}

export interface Agent {
  id: string;
  name: string;
  version?: string;
  category?: string;
  model: string;
  temperature: number;
  maxTokens: number;
  topP: number;
  tools: string[];
  guardrailsInput: string[];
  guardrailsOutput: string[];
  hooks: string[];
  jsonOutput: boolean;
  active: boolean;
  systemPrompt: string;
}

export interface WorkflowDefinition {
  id: string;
  name: string;
  version: string;
  status: string;
  nodes: WorkflowNode[];
  edges: WorkflowEdge[];
  agents: Agent[];
}