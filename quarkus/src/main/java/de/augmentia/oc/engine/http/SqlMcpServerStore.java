package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.runtime.tool.mcp.McpServerConfig;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SQL-backed persistence for MCP servers. Used when engine.storage is
 * sqlite or postgres. Falls back to file-based McpServerPersistence otherwise.
 */
public final class SqlMcpServerStore {

    private final DataSource dataSource;
    private final ObjectMapper mapper = new ObjectMapper();

    public SqlMcpServerStore(DataSource dataSource) {
        if (dataSource == null) throw new IllegalArgumentException("dataSource must not be null");
        this.dataSource = dataSource;
    }

    @SuppressWarnings("unchecked")
    public List<McpServerConfig> load() {
        List<McpServerConfig> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("select config from mcp_server");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String json = rs.getString(1);
                Map<String, Object> e = mapper.readValue(json, Map.class);
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
            // fall through
        }
        return result;
    }

    public void save(List<McpServerConfig> servers) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement del = c.prepareStatement("delete from mcp_server")) {
                del.executeUpdate();
            }
            try (PreparedStatement ins = c.prepareStatement(
                    "insert into mcp_server (name, config, created_at, updated_at) values (?, ?, now(), now())")) {
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
                    String json = mapper.writeValueAsString(e);
                    ins.setString(1, s.name());
                    ins.setString(2, json);
                    ins.addBatch();
                }
                ins.executeBatch();
            }
            c.commit();
        } catch (Exception e) {
            // fall through
        }
    }

    public void addServer(McpServerConfig config) {
        List<McpServerConfig> existing = load();
        existing.removeIf(c -> c.name().equals(config.name()));
        existing.add(config);
        save(existing);
    }

    public void removeServer(String name) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("delete from mcp_server where name = ?")) {
            ps.setString(1, name);
            ps.executeUpdate();
        } catch (SQLException e) {
            // fall through
        }
    }
}