package de.augmentia.qoc.workflowrunner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.augmentia.oc.engine.adapter.StorageFactory;
import de.augmentia.oc.engine.adapter.memory.RuntimeState;
import de.augmentia.oc.engine.adapter.memory.MemoryBudget;
import de.augmentia.oc.engine.adapter.time.SystemClock;
import de.augmentia.oc.engine.core.wiring.StorageConfig;
import de.augmentia.oc.engine.core.wiring.StorageKind;
import de.augmentia.oc.engine.core.wiring.Stores;
import de.augmentia.oc.engine.runtime.QocEngine;
import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;
import de.augmentia.qoc.workflowrunner.runner.WorkflowExecutor;
import de.augmentia.qoc.workflowrunner.store.CheckpointStore;
import de.augmentia.qoc.workflowrunner.store.AgentStore;
import de.augmentia.qoc.workflowrunner.store.WorkflowStore;
import de.augmentia.qoc.workflowrunner.validation.GraphCompiler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * Standalone HTTP server for workflow execution using ReactFlow format.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET  /health — health check</li>
 *   <li>GET  /workflows — list workflow IDs</li>
 *   <li>GET  /workflows/:id — get workflow definition</li>
 *   <li>POST /workflows — create new workflow</li>
 *   <li>PUT  /workflows/:id — save workflow definition</li>
 *   <li>DELETE /workflows/:id — delete workflow</li>
 *   <li>POST /workflows/validate — validate workflow definition</li>
 *   <li>GET  /workflows/:id/versions — list versions</li>
 *   <li>POST /workflows/run — run a workflow</li>
 * </ul>
 * </p>
 */
public final class WorkflowRunnerMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int PORT = 8097;

    public static void main(String[] args) throws Exception {
        String storageDir = args.length > 0 ? args[0] : "./wf-data";
        Path dataDir = Paths.get(storageDir).toAbsolutePath().normalize();

        System.out.println("Workflow storage: " + dataDir);

        // Create QocEngine (in-memory)
        StorageConfig config = new StorageConfig(StorageKind.MEMORY);
        RuntimeState memory = new RuntimeState(new MemoryBudget(1L << 30));
        Stores stores = StorageFactory.create(config, memory, null,
                new SystemClock(), new de.augmentia.oc.engine.adapter.fs.NioFileSystem(Path.of(".")));
        QocEngine engine = QocEngine.create(stores, config, new SystemClock(), false);
        engine.setWorkflowMaxSteps(100);
        engine.setPreflightContext(new de.augmentia.oc.engine.runtime.resilience.PreflightChecker.PreflightContext(
                null,
                System.getenv().getOrDefault("OPENAI_API_KEY", "demo-key"),
                () -> true,
                Path.of(".").toAbsolutePath().normalize()));

        WorkflowStore store = new WorkflowStore(storageDir);
        AgentStore agentStore = new AgentStore(Paths.get(storageDir));
        CheckpointStore checkpoints = new CheckpointStore(Paths.get(storageDir));

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        // Health
        server.createContext("/health", ex -> {
            sendJson(ex, 200, Map.of("status", "ok"));
        });

        // Agents
        server.createContext("/agents", ex -> {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();

            if ("GET".equals(method) && "/agents".equals(path)) {
                sendJson(ex, 200, Map.of("agents", agentStore.listAll()));
            } else if ("POST".equals(method) && "/agents".equals(path)) {
                String body = readBody(ex);
                WorkflowDefinition.Agent agent = MAPPER.readValue(body, WorkflowDefinition.Agent.class);
                String id = agent.id();
                if (id == null || id.isBlank()) id = agent.name().toLowerCase().replaceAll("[^a-z0-9-]", "-");
                if (id.isBlank()) id = "agent-" + System.currentTimeMillis();
                agentStore.save(id, agent);
                sendJson(ex, 201, agentStore.get(id));
            } else if (path.startsWith("/agents/")) {
                String id = path.substring("/agents/".length()).split("/")[0];
                if ("GET".equals(method)) {
                    WorkflowDefinition.Agent a = agentStore.get(id);
                    if (a == null) sendJson(ex, 404, Map.of("error", "not found"));
                    else sendJson(ex, 200, a);
                } else if ("PUT".equals(method)) {
                    String body = readBody(ex);
                    WorkflowDefinition.Agent agent = MAPPER.readValue(body, WorkflowDefinition.Agent.class);
                    agentStore.save(id, agent);
                    sendJson(ex, 200, agentStore.get(id));
                } else if ("DELETE".equals(method)) {
                    boolean deleted = agentStore.delete(id);
                    sendJson(ex, deleted ? 200 : 404, Map.of("deleted", deleted));
                } else {
                    sendJson(ex, 405, Map.of("error", "method not allowed"));
                }
            } else {
                sendJson(ex, 404, Map.of("error", "not found"));
            }
        });

        // List workflows
        server.createContext("/workflows", ex -> {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();

            if ("GET".equals(method) && "/workflows".equals(path)) {
                sendJson(ex, 200, Map.of("workflows", store.list()));
            } else if ("POST".equals(method) && "/workflows".equals(path)) {
                // Create
                String body = readBody(ex);
                Map<String, Object> req = MAPPER.readValue(body, Map.class);
                String name = (String) req.getOrDefault("name", "Untitled");
                WorkflowDefinition def = store.create(name);
                sendJson(ex, 201, def);
            } else if (path.startsWith("/workflows/")) {
                String id = path.substring("/workflows/".length()).split("/")[0];
                if ("GET".equals(method)) {
                    WorkflowDefinition def = store.get(id);
                    if (def == null) sendJson(ex, 404, Map.of("error", "not found"));
                    else sendJson(ex, 200, def);
                } else if ("PUT".equals(method)) {
                    String body = readBody(ex);
                    WorkflowDefinition def = MAPPER.readValue(body, WorkflowDefinition.class);
                    store.save(id, def);
                    sendJson(ex, 200, def);
                } else if ("DELETE".equals(method)) {
                    boolean deleted = store.delete(id);
                    sendJson(ex, deleted ? 200 : 404, Map.of("deleted", deleted));
                } else {
                    sendJson(ex, 405, Map.of("error", "method not allowed"));
                }
            } else {
                sendJson(ex, 404, Map.of("error", "not found"));
            }
        });

        // Validate
        server.createContext("/workflows/validate", ex -> {
            String body = readBody(ex);
            WorkflowDefinition def = MAPPER.readValue(body, WorkflowDefinition.class);
            var result = GraphCompiler.validate(def);
            sendJson(ex, 200, Map.of("valid", result.valid(), "errors", result.errors(), "summary", result.summary()));
        });

        // Versions
        server.createContext("/workflows/versions", ex -> {
            sendJson(ex, 404, Map.of("error", "use /workflows/{id}/versions"));
        });

        // Run
        server.createContext("/workflows/run", ex -> {
            String body = readBody(ex);
            Map<String, Object> req = MAPPER.readValue(body, Map.class);
            WorkflowDefinition def = MAPPER.convertValue(req.get("workflow"), WorkflowDefinition.class);
            String input = (String) req.getOrDefault("input", "");

            var validation = GraphCompiler.validate(def);
            if (!validation.valid()) {
                sendJson(ex, 400, Map.of("valid", false, "errors", validation.errors()));
                return;
            }

            WorkflowExecutor executor = new WorkflowExecutor(engine, store);
            String result = executor.execute(def, input);
            sendJson(ex, 200, Map.of("result", result, "valid", true));
        });

        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.start();

        System.out.println("Workflow Runner started on port " + PORT);

        // Keep running
        Thread.currentThread().join();
    }

    private static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void sendJson(HttpExchange ex, int status, Object data) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(data);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
