package de.augmentia.qoc.cli.commands;

import com.fasterxml.jackson.databind.JsonNode;
import de.augmentia.qoc.cli.client.QocClient;
import de.augmentia.qoc.cli.config.GlobalOptions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Command(name = "run", description = "Run a single prompt (one-shot).")
public class RunCommand implements Runnable {
    @Mixin
    GlobalOptions opts;

    @Option(names = {"-a", "--agent"}, defaultValue = "build") String agent;
    @Option(names = {"-s", "--session"}) String session;
    @Parameters(index = "0", arity = "1..*", description = "Prompt text") String[] prompt;

    @Override
    public void run() {
        String text = String.join(" ", prompt);
        try {
            String sid = session;
            if (sid == null || sid.isBlank()) {
                JsonNode s = opts.client().post("/api/session", Map.of("agent", agent));
                sid = s.path("id").asText();
                GlobalOptions.saveSessionId(sid);
            }
            if (opts.isJson()) {
                JsonNode r = opts.client().post("/api/session/" + sid + "/prompt",
                        Map.of("text", text));
                System.out.println(r.toPrettyString());
                return;
            }
            AtomicBoolean textSeen = new AtomicBoolean();
            QocClient.EventStream stream = null;
            try {
                stream = opts.client().streamEvents(sid, new EventPrinter(textSeen));
            } catch (Exception e) {
                System.err.println("[sse] " + e.getMessage());
            }
            try {
                JsonNode r = opts.client().post("/api/session/" + sid + "/prompt",
                        Map.of("text", text));
                if (stream != null) {
                    Thread.sleep(150);
                }
                if (!textSeen.get()) {
                    System.out.println(r.path("text").asText());
                }
                System.err.printf("[finish=%s input=%d output=%d session=%s]%n",
                        r.path("finish").asText(),
                        r.path("inputTokens").asLong(),
                        r.path("outputTokens").asLong(), sid);
            } finally {
                if (stream != null) stream.close();
            }
        } catch (Exception e) {
            System.err.println("run failed: " + e.getMessage());
            System.exit(1);
        }
    }
}
