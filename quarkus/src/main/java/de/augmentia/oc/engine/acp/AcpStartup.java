package de.augmentia.oc.engine.acp;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

/**
 * Starts ACP mode (stdio JSON-RPC) when enabled via config.
 * Default: disabled. Enable via ENGINE_ACP_ENABLED=true.
 */
@ApplicationScoped
public class AcpStartup {

    private static final Logger LOG = Logger.getLogger(AcpStartup.class.getName());

    @Inject
    de.augmentia.oc.engine.http.Engine engine;

    void onStart(@Observes StartupEvent event) {
        if (!AcpConfig.enabled()) {
            LOG.info("ACP mode disabled (ENGINE_ACP_ENABLED != true)");
            return;
        }
        LOG.info("ACP mode enabled — starting stdio JSON-RPC server");
        // Run in a separate thread to not block Quarkus startup.
        Thread t = new Thread(() -> {
            AcpTransport transport = new AcpTransport();
            AcpServer server = new AcpServer(transport);
            AcpSessionManager sessionManager = new AcpSessionManager(engine);
            AcpHandler handler = new AcpHandler(engine, sessionManager, transport);
            handler.register(server);
            server.run();
            transport.close();
        }, "acp-server");
        t.setDaemon(true);
        t.start();
    }
}