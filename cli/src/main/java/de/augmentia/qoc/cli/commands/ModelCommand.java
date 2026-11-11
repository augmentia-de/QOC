package de.augmentia.qoc.cli.commands;

import com.fasterxml.jackson.databind.JsonNode;
import de.augmentia.qoc.cli.config.GlobalOptions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

@Command(name = "model", description = "List models.",
        subcommands = {ModelCommand.List.class})
public class ModelCommand implements Runnable {
    @Override
    public void run() { System.out.println("Use 'qoc model --help'."); }

    @Command(name = "list", description = "List models.")
    public static class List implements Runnable {
        @Mixin GlobalOptions opts;
        @Override
        public void run() {
            try {
                JsonNode r = opts.client().get("/api/model");
                System.out.println(r.toPrettyString());
            } catch (Exception e) {
                System.err.println("list failed: " + e.getMessage());
                System.exit(1);
            }
        }
    }
}
