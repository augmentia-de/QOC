import { Routes, Route, Link } from 'react-router-dom';
import Dashboard from './pages/Dashboard';
import Workflows from './pages/Workflows';
import WorkflowDetail from './pages/WorkflowDetail';
import GraphEditor from './pages/GraphEditor';
import Generate from './pages/Generate';
import Testbench from './pages/Testbench';
import HITL from './pages/HITL';
import Observe from './pages/Observe';
import Settings from './pages/Settings';
import Runs from './pages/Runs';
import Agents from './pages/Agents';
import McpServers from './pages/McpServers';

export default function App() {
  return (
    <div className="h-screen bg-gray-900 text-white flex flex-col">
      <nav className="flex gap-4 p-4 bg-gray-800 border-b border-gray-700 shrink-0">
        <Link to="/" className="hover:text-blue-400 font-bold">Workflow UI</Link>
        <Link to="/workflows" className="hover:text-blue-400">Workflows</Link>
        <Link to="/editor" className="hover:text-blue-400">Graph Editor</Link>
        <Link to="/runs" className="hover:text-blue-400">Runs</Link>
        <Link to="/agents" className="hover:text-blue-400">Agents</Link>
        <Link to="/mcp-servers" className="hover:text-blue-400">MCP Servers</Link>
        <Link to="/generate" className="hover:text-blue-400">Generate</Link>
        <Link to="/testbench" className="hover:text-blue-400">Testbench</Link>
        <Link to="/hitl" className="hover:text-blue-400">HITL</Link>
        <Link to="/observe" className="hover:text-blue-400">Observe</Link>
        <Link to="/settings" className="hover:text-blue-400">Settings</Link>
      </nav>
      <div className="flex-1 min-h-0 overflow-auto">
      <Routes>
        <Route path="/" element={<Dashboard />} />
        <Route path="/workflows" element={<Workflows />} />
        <Route path="/workflows/:id" element={<WorkflowDetail />} />
        <Route path="/editor" element={<GraphEditor />} />
        <Route path="/runs" element={<Runs />} />
        <Route path="/agents" element={<Agents />} />
        <Route path="/mcp-servers" element={<McpServers />} />
        <Route path="/generate" element={<Generate />} />
        <Route path="/testbench" element={<Testbench />} />
        <Route path="/hitl" element={<HITL />} />
        <Route path="/observe" element={<Observe />} />
        <Route path="/settings" element={<Settings />} />
      </Routes>
      </div>
    </div>
  );
}