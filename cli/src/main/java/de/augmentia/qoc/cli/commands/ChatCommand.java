package de.augmentia.qoc.cli.commands;

import com.fasterxml.jackson.databind.JsonNode;
import de.augmentia.qoc.cli.client.QocClient;
import de.augmentia.qoc.cli.config.GlobalOptions;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Command(name = "chat", description = "Interactive REPL.")
public class ChatCommand implements Runnable {
    @Mixin GlobalOptions opts;
    @Option(names = {"-a", "--agent"}, defaultValue = "build") String agent;
    @Option(names = {"-s", "--session"}) String session;

    @Override
    public void run() {
        String sid = session;
        QocClient.EventStream stream = null;
        try {
            if (sid == null || sid.isBlank()) {
                JsonNode s = opts.client().post("/api/session", Map.of("agent", agent));
                sid = s.path("id").asText();
                GlobalOptions.saveSessionId(sid);
                System.out.println("session: " + sid);
            }
            AtomicBoolean textSeen = new AtomicBoolean();
            try {
                stream = opts.client().streamEvents(sid, new EventPrinter(textSeen));
            } catch (Exception e) {
                System.err.println("[sse] " + e.getMessage());
            }

            Terminal terminal = TerminalBuilder.builder().system(true).build();
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
            System.out.println("Type your prompt. Empty line or 'exit' to quit.");
            while (true) {
                String line;
                try {
                    line = reader.readLine("qoc> ");
                } catch (UserInterruptException e) {
                    break;
                }
                if (line == null) break;
                line = line.trim();
                if (line.isEmpty() || "exit".equalsIgnoreCase(line) || "quit".equalsIgnoreCase(line)) break;
                try {
                    textSeen.set(false);
                    JsonNode r = opts.client().post("/api/session/" + sid + "/prompt",
                            Map.of("text", line));
                    if (stream != null) {
                        Thread.sleep(150);
                    }
                    if (!textSeen.get()) {
                        System.out.println(r.path("text").asText());
                    }
                    System.err.printf("[finish=%s in=%d out=%d]%n",
                            r.path("finish").asText(),
                            r.path("inputTokens").asLong(),
                            r.path("outputTokens").asLong());
                } catch (Exception e) {
                    System.err.println("error: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("chat failed: " + e.getMessage());
            System.exit(1);
        } finally {
            if (stream != null) stream.close();
        }
    }
}
