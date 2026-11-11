package de.augmentia.qoc.cli.commands;

import com.fasterxml.jackson.databind.JsonNode;
import de.augmentia.qoc.cli.config.GlobalOptions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.Iterator;

@Command(name = "session", description = "Manage sessions.",
        subcommands = {SessionCommand.List.class, SessionCommand.Create.class,
                SessionCommand.Delete.class, SessionCommand.Show.class})
public class SessionCommand implements Runnable {
    @Override
    public void run() { System.out.println("Use 'qoc session --help'."); }

    @Command(name = "list", description = "List sessions.")
    public static class List implements Runnable {
        @Mixin GlobalOptions opts;
        @Override
        public void run() {
            try {
                JsonNode r = opts.client().get("/api/session");
                if (opts.isJson()) {
                    System.out.println(r.toPrettyString());
                    return;
                }
                JsonNode data = r.has("data") ? r.get("data") : r;
                if (data == null || !data.isArray() || data.size() == 0) {
                    System.out.println("(no sessions)");
                    return;
                }
                for (JsonNode s : data) {
                    System.out.printf("%s  %s%n", s.path("id").asText(), s.path("title").asText(""));
                }
            } catch (Exception e) {
                System.err.println("list failed: " + e.getMessage());
                System.exit(1);
            }
        }
    }

    @Command(name = "create", description = "Create a session.")
    public static class Create implements Runnable {
        @Mixin GlobalOptions opts;
        @Option(names = {"-a", "--agent"}, defaultValue = "build") String agent;
        @Override
        public void run() {
            try {
                JsonNode r = opts.client().post("/api/session", java.util.Map.of("agent", agent));
                String id = r.path("id").asText();
                GlobalOptions.saveSessionId(id);
                System.out.println(id);
            } catch (Exception e) {
                System.err.println("create failed: " + e.getMessage());
                System.exit(1);
            }
        }
    }

    @Command(name = "delete", description = "Delete a session.")
    public static class Delete implements Runnable {
        @Mixin GlobalOptions opts;
        @Parameters(index = "0") String id;
        @Override
        public void run() {
            try {
                opts.client().delete("/api/session/" + id);
                System.out.println("deleted " + id);
            } catch (Exception e) {
                System.err.println("delete failed: " + e.getMessage());
                System.exit(1);
            }
        }
    }

    @Command(name = "show", description = "Show session details.")
    public static class Show implements Runnable {
        @Mixin GlobalOptions opts;
        @Parameters(index = "0") String id;
        @Override
        public void run() {
            try {
                JsonNode r = opts.client().get("/api/session/" + id);
                System.out.println(r.toPrettyString());
            } catch (Exception e) {
                System.err.println("show failed: " + e.getMessage());
                System.exit(1);
            }
        }
    }
}
