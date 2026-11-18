import { AgentNode, DecisionNode, StartNode, EndNode, ContextAgentExecutorNode, ForkNode, JoinNode, LoopNode, HitlNode, SubworkflowNode, CompactNode, McpCallNode, RestCallNode, ClassifyNode, GuardNode, InjectNode } from './nodeTypes.tsx';

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
  compact: CompactNode,
  guard: GuardNode,
  inject: InjectNode,
  mcp_call: McpCallNode,
  rest_call: RestCallNode,
  classify: ClassifyNode,
};

export default nodeTypes;