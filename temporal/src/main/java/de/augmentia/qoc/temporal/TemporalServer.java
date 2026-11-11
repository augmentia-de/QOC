package de.augmentia.qoc.temporal;

import de.augmentia.oc.engine.adapter.memory.MemoryBudget;
import de.augmentia.oc.engine.adapter.memory.RuntimeState;
import de.augmentia.oc.engine.adapter.StorageFactory;
import de.augmentia.oc.engine.core.wiring.StorageConfig;
import de.augmentia.oc.engine.core.wiring.StorageKind;
import de.augmentia.oc.engine.core.wiring.Stores;
import de.augmentia.oc.engine.core.port.runtime.Clock;
import de.augmentia.oc.engine.adapter.time.SystemClock;
import de.augmentia.oc.engine.runtime.QocEngine;
import de.augmentia.qoc.temporal.activity.HitlActivityImpl;
import de.augmentia.qoc.temporal.activity.MemoryActivityImpl;
import de.augmentia.qoc.temporal.activity.MetricsActivityImpl;
import de.augmentia.qoc.temporal.activity.QocActivityImpl;
import de.augmentia.qoc.temporal.activity.SagaActivityImpl;
import de.augmentia.qoc.temporal.workflow.HitlWorkflow;
import de.augmentia.qoc.temporal.workflow.HitlWorkflowImpl;
import de.augmentia.qoc.temporal.workflow.MemoryWorkflow;
import de.augmentia.qoc.temporal.workflow.MemoryWorkflowImpl;
import de.augmentia.qoc.temporal.workflow.MetricsWorkflow;
import de.augmentia.qoc.temporal.workflow.MetricsWorkflowImpl;
import de.augmentia.qoc.temporal.workflow.QocWorkflow;
import de.augmentia.qoc.temporal.workflow.QocWorkflowImpl;
import de.augmentia.qoc.temporal.workflow.SagaWorkflow;
import de.augmentia.qoc.temporal.workflow.SagaWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;

import java.nio.file.Path;

/**
 * Temporal server that uses QOC Engine as agent runner.
 *
 * <p>Runs a Temporal worker with QOC-based activities. QOC Engine is used
 * in-memory (no DB) — Temporal provides durability via workflow replay.</p>
 *
 * <p>Start: {@code mvn -pl temporal exec:java} or {@code java -jar temporal/target/temporal-*.jar}</p>
 */
public final class TemporalServer {

    public static final String TASK_QUEUE = "qoc-agent-queue";

    private final WorkflowServiceStubs service;
    private final WorkerFactory factory;
    private final QocEngine engine;

    public TemporalServer(String temporalAddress) {
        // QOC Engine (in-memory)
        StorageConfig config = new StorageConfig(StorageKind.MEMORY);
        RuntimeState memory = new RuntimeState(new MemoryBudget(1L << 30));
        Stores stores = StorageFactory.create(config, memory, null,
                new SystemClock(), new de.augmentia.oc.engine.adapter.fs.NioFileSystem(Path.of(".")));
        engine = QocEngine.create(stores, config, new SystemClock(), false, null,
                de.augmentia.oc.engine.runtime.QocEngine.EngineConfig.defaults(),
                de.augmentia.oc.engine.runtime.callback.EventPublisher.noop(),
                de.augmentia.oc.engine.runtime.callback.AgentResolver.noop(),
                de.augmentia.oc.engine.runtime.callback.TracerProvider.noop(),
                de.augmentia.oc.engine.adapter.ToolFactory.create(java.nio.file.Path.of(".")));

        // Temporal
        // Register PickFirst load balancer (needed for shaded jar)
        try {
            Class<?> pickFirst = Class.forName("io.grpc.internal.PickFirstLoadBalancerProvider");
            Object provider = pickFirst.getDeclaredConstructor().newInstance();
            io.grpc.LoadBalancerRegistry.getDefaultRegistry().register(
                    (io.grpc.LoadBalancerProvider) provider);
        } catch (Exception e) {
            System.err.println("Could not register PickFirst: " + e.getMessage());
        }

        io.grpc.ManagedChannel channel = io.grpc.ManagedChannelBuilder
                .forAddress("localhost", 7233)
                .usePlaintext()
                .build();
        service = WorkflowServiceStubs.newServiceStubs(
                io.temporal.serviceclient.WorkflowServiceStubsOptions.newBuilder()
                        .setChannel(channel)
                        .build());
        WorkflowClient client = WorkflowClient.newInstance(service);
        factory = WorkerFactory.newInstance(client);

        Worker worker = factory.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(QocWorkflowImpl.class);
        worker.registerWorkflowImplementationTypes(HitlWorkflowImpl.class);
        worker.registerWorkflowImplementationTypes(SagaWorkflowImpl.class);
        worker.registerWorkflowImplementationTypes(MemoryWorkflowImpl.class);
        worker.registerWorkflowImplementationTypes(MetricsWorkflowImpl.class);
        worker.registerActivitiesImplementations(new QocActivityImpl(engine));
        worker.registerActivitiesImplementations(new SagaActivityImpl());
        worker.registerActivitiesImplementations(new MemoryActivityImpl());
        worker.registerActivitiesImplementations(new MetricsActivityImpl(engine));
        worker.registerActivitiesImplementations(new HitlActivityImpl(engine));

        factory.start();
        System.out.println("Temporal worker started on task queue: " + TASK_QUEUE);

        // Simple HTTP endpoint to start workflows
        try {
            com.sun.net.httpserver.HttpServer httpServer = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress(8096), 0);
            httpServer.createContext("/run", exchange -> {
                String query = exchange.getRequestURI().getQuery();
                String agent = "build";
                String prompt = "Hello from Temporal";
                if (query != null) {
                    for (String param : query.split("&")) {
                        String[] kv = param.split("=", 2);
                        if (kv.length == 2) {
                            if ("agent".equals(kv[0])) agent = java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
                            if ("prompt".equals(kv[0])) prompt = java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
                        }
                    }
                }
                String workflowId = "qoc-" + java.util.UUID.randomUUID();
                WorkflowClient wfClient = WorkflowClient.newInstance(service);
                QocWorkflow wf = wfClient.newWorkflowStub(QocWorkflow.class,
                        io.temporal.client.WorkflowOptions.newBuilder()
                                .setTaskQueue(TASK_QUEUE)
                                .setWorkflowId(workflowId)
                                .build());
                io.temporal.client.WorkflowClient.start(wf::run, agent, prompt);
                byte[] response = ("{\"workflowId\":\"" + workflowId + "\",\"started\":true}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.getResponseBody().close();
            });
            httpServer.createContext("/health", exchange -> {
                byte[] response = "{\"status\":\"ok\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.getResponseBody().close();
            });

            // Saga endpoint
            httpServer.createContext("/saga", exchange -> {
                String query = exchange.getRequestURI().getQuery();
                String orderId = "order-1";
                double amount = 50.0;
                if (query != null) {
                    for (String param : query.split("&")) {
                        String[] kv = param.split("=", 2);
                        if (kv.length == 2) {
                            if ("orderId".equals(kv[0])) orderId = java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
                            if ("amount".equals(kv[0])) amount = Double.parseDouble(java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8));
                        }
                    }
                }
                WorkflowClient wfClient = WorkflowClient.newInstance(service);
                String workflowId = "saga-" + java.util.UUID.randomUUID();
                SagaWorkflow wf = wfClient.newWorkflowStub(SagaWorkflow.class,
                        io.temporal.client.WorkflowOptions.newBuilder()
                                .setTaskQueue(TASK_QUEUE)
                                .setWorkflowId(workflowId)
                                .build());
                io.temporal.client.WorkflowClient.start(wf::run, orderId, amount);
                byte[] response = ("{\"workflowId\":\"" + workflowId + "\",\"started\":true}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.getResponseBody().close();
            });

            // Memory endpoint
            httpServer.createContext("/memory", exchange -> {
                String query = exchange.getRequestURI().getQuery();
                String op = "remember";
                String scope = "default";
                String content = "";
                if (query != null) {
                    for (String param : query.split("&")) {
                        String[] kv = param.split("=", 2);
                        if (kv.length == 2) {
                            if ("op".equals(kv[0])) op = java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
                            if ("scope".equals(kv[0])) scope = java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
                            if ("content".equals(kv[0])) content = java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
                        }
                    }
                }
                WorkflowClient wfClient = WorkflowClient.newInstance(service);
                String workflowId = "memory-" + java.util.UUID.randomUUID();
                MemoryWorkflow wf = wfClient.newWorkflowStub(MemoryWorkflow.class,
                        io.temporal.client.WorkflowOptions.newBuilder()
                                .setTaskQueue(TASK_QUEUE)
                                .setWorkflowId(workflowId)
                                .build());
                io.temporal.client.WorkflowClient.start(wf::run, op, scope, content);
                byte[] response = ("{\"workflowId\":\"" + workflowId + "\",\"started\":true}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.getResponseBody().close();
            });

            // Metrics endpoint
            httpServer.createContext("/metrics-run", exchange -> {
                String query = exchange.getRequestURI().getQuery();
                String input = "Hello";
                if (query != null) {
                    for (String param : query.split("&")) {
                        String[] kv = param.split("=", 2);
                        if (kv.length == 2 && "input".equals(kv[0])) {
                            input = java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
                        }
                    }
                }
                WorkflowClient wfClient = WorkflowClient.newInstance(service);
                String workflowId = "metrics-" + java.util.UUID.randomUUID();
                MetricsWorkflow wf = wfClient.newWorkflowStub(MetricsWorkflow.class,
                        io.temporal.client.WorkflowOptions.newBuilder()
                                .setTaskQueue(TASK_QUEUE)
                                .setWorkflowId(workflowId)
                                .build());
                io.temporal.client.WorkflowClient.start(wf::run, input);
                byte[] response = ("{\"workflowId\":\"" + workflowId + "\",\"started\":true}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.getResponseBody().close();
            });
            httpServer.start();
            System.out.println("HTTP endpoint: http://localhost:8096/run?agent=build&prompt=Hello");
        } catch (java.io.IOException e) {
            System.err.println("Failed to start HTTP server: " + e.getMessage());
        }
    }

    public QocEngine engine() { return engine; }

    public void shutdown() {
        factory.shutdown();
        service.shutdown();
    }

    public static void main(String[] args) {
        String address = System.getenv().getOrDefault("TEMPORAL_ADDRESS", "localhost:7233");
        TemporalServer server = new TemporalServer(address);
        System.out.println("QOC Temporal Server running. Press Ctrl+C to stop.");
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
