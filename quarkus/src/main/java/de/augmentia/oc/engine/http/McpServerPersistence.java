package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.runtime.tool.mcp.McpServerConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Simple JSON-file persistence for runtime-registered MCP servers.
 * Survives restarts; works in all storage modes (memory/file/db).
 */
public final class McpServerPersistence {

    private static final String DEFAULT_FILE = "./mcp-servers.json";

    private McpServerPersistence() {}

    public static String filePath() {
        String p = System.getProperty("qoc.mcp.servers.file");
        if (p == null || p.isBlank()) p = System.getenv("QOC_MCP_SERVERS_FILE");
        if (p == null || p.isBlank()) p = DEFAULT_FILE;
        return p;
    }

    /** Load persisted server configs from disk. Returns empty list if file absent. */
    @SuppressWarnings("unchecked")
    public static List<McpServerConfig> load() {
        List<McpServerConfig> result = new ArrayList<>();
        try {
            Path p = Paths.get(filePath());
            if (!Files.exists(p)) return result;
            String json = Files.readString(p).trim();
            if (json.isEmpty() || json.equals("[]")) return result;
            // Minimal JSON parsing: expect array of {name, type, url/command}
            // Use a simple regex-based parse to avoid Jackson dependency in engine.
            // Format: [{"name":"x","type":"remote","url":"http://..."}, ...]
            // We parse with a lightweight approach.
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            List<Map<String, Object>> entries = om.readValue(json, List.class);
            for (Map<String, Object> e : entries) {
                String name = String.valueOf(e.getOrDefault("name", ""));
                if (name.isBlank()) continue;
                String type = String.valueOf(e.getOrDefault("type", "remote"));
                if ("local".equalsIgnoreCase(type)) {
                    Object cmd = e.get("command");
                    List<String> command = new ArrayList<>();
                    if (cmd instanceof List<?> l) for (Object o : l) command.add(String.valueOf(o));
                    result.add(new McpServerConfig.Local(name, command, "", Map.of(), false, false, 0, 0, 0));
                } else {
                    String url = String.valueOf(e.getOrDefault("url", ""));
                    if (url.isBlank()) continue;
                    result.add(new McpServerConfig.Remote(name, url, Map.of(), false, false, false, 0, 0, 0));
                }
            }
        } catch (Exception e) {
            System.err.println("McpServerPersistence: load failed: " + e.getMessage());
        }
        return result;
    }

    /** Add a single server config to the persisted file (dedup by name). */
    public static void addServer(McpServerConfig config) {
        List<McpServerConfig> existing = load();
        existing.removeIf(c -> c.name().equals(config.name()));
        existing.add(config);
        save(existing);
    }

    /** Remove a server by name from the persisted file. */
    public static void removeServer(String name) {
        List<McpServerConfig> existing = load();
        existing.removeIf(c -> c.name().equals(name));
        save(existing);
    }

    /** Persist the given server configs to disk. */
    public static void save(List<McpServerConfig> servers) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            List<Map<String, Object>> entries = new ArrayList<>();
            for (McpServerConfig s : servers) {
                Map<String, Object> e = new java.util.LinkedHashMap<>();
                e.put("name", s.name());
                if (s instanceof McpServerConfig.Local l) {
                    e.put("type", "local");
                    e.put("command", l.command());
                } else if (s instanceof McpServerConfig.Remote r) {
                    e.put("type", "remote");
                    e.put("url", r.url());
                }
                entries.add(e);
            }
            String json = om.writerWithDefaultPrettyPrinter().writeValueAsString(entries);
            Files.writeString(Paths.get(filePath()), json);
        } catch (Exception e) {
            System.err.println("McpServerPersistence: save failed: " + e.getMessage());
        }
    }
}
