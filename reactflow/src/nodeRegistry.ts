import { AgentNode, DecisionNode, StartNode, EndNode, ContextAgentExecutorNode, ForkNode, JoinNode, LoopNode, HitlNode, SubworkflowNode, LlmEvalNode, LlmSummarizeNode, CompactNode, McpCallNode, RestCallNode } from './nodeTypes.tsx';

export const nodeTypes = {
  agent: AgentNode,
  context_agent_executor: ContextAgentExecutorNode,
  decision: DecisionNode,
  start: StartNode,
  end: EndNode,
  fork: ForkNode,
  join: JoinNode,
  loop: LoopNode,
  hitl: HitlNode,
  subworkflow: SubworkflowNode,
  llm_eval: LlmEvalNode,
  llm_summarize: LlmSummarizeNode,
  compact: CompactNode,
  mcp_call: McpCallNode,
  rest_call: RestCallNode,
};

export default nodeTypes;