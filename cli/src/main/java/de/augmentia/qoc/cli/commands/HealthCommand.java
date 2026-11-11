package de.augmentia.qoc.cli.commands;

import de.augmentia.qoc.cli.config.GlobalOptions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import com.fasterxml.jackson.databind.JsonNode;

@Command(name = "health", description = "Check server health.")
public class HealthCommand implements Runnable {
    @Mixin
    GlobalOptions opts;

    @Override
    public void run() {
        try {
            JsonNode r = opts.client().get("/api/health");
            if (opts.isJson()) {
                System.out.println(r.toPrettyString());
                return;
            }
            System.out.println("status: " + r.path("status").asText("ok"));
            System.out.println("url:    " + opts.resolvedUrl());
        } catch (Exception e) {
            System.err.println("health check failed: " + e.getMessage());
            System.exit(1);
        }
    }
}
