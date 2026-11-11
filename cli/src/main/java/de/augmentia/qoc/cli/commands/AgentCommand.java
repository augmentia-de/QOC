package de.augmentia.qoc.cli.commands;

import com.fasterxml.jackson.databind.JsonNode;
import de.augmentia.qoc.cli.config.GlobalOptions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

@Command(name = "agent", description = "List agents.",
        subcommands = {AgentCommand.List.class})
public class AgentCommand implements Runnable {
    @Override
    public void run() { System.out.println("Use 'qoc agent --help'."); }

    @Command(name = "list", description = "List agents.")
    public static class List implements Runnable {
        @Mixin GlobalOptions opts;
        @Override
        public void run() {
            try {
                JsonNode r = opts.client().get("/api/agent");
                if (opts.isJson()) {
                    System.out.println(r.toPrettyString());
                    return;
                }
                JsonNode data = r.has("data") ? r.get("data") : r;
                if (data == null || !data.isArray() || data.size() == 0) {
                    System.out.println("(no agents)");
                    return;
                }
                for (JsonNode a : data) {
                    System.out.printf("%s  hidden=%s%n", a.path("name").asText(), a.path("hidden").asText());
                }
            } catch (Exception e) {
                System.err.println("list failed: " + e.getMessage());
                System.exit(1);
            }
        }
    }
}
