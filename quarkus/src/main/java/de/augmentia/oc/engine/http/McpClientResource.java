package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.runtime.tool.mcp.McpRegistrar;
import de.augmentia.oc.engine.runtime.tool.mcp.McpServerConfig;
import de.augmentia.oc.engine.runtime.tool.mcp.McpServerManager;
import de.augmentia.oc.engine.runtime.tool.mcp.McpServerStatus;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/api/mcp-client")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class McpClientResource {

    @Inject
    Engine engine;

    private McpServerManager manager() {
        return engine.mcp().manager();
    }

    @GET
    @Path("/tools")
    public Response listTools() {
        McpServerManager mgr = manager();
        List<Map<String, Object>> tools = new ArrayList<>();
        for (String serverName : mgr.serverNames()) {
            if (mgr.status(serverName) != McpServerStatus.CONNECTED) continue;
            for (McpRegistrar.McpToolSpec t : mgr.discovered(serverName)) {
                Map<String, Object> tool = new LinkedHashMap<>();
                tool.put("name", serverName + "/" + t.name());
                tool.put("description", t.description());
                tool.put("source", "mcp:" + serverName);
                tools.add(tool);
            }
        }
        // Built-in tools
        for (String builtin : List.of("read_file", "write_file", "edit_file", "glob", "grep",
                "patch", "shell", "process", "web_fetch", "secure_rest", "firecrawl",
                "todo", "skill", "skill_list")) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", builtin);
            tool.put("description", builtin);
            tool.put("source", "builtin");
            tools.add(tool);
        }
        return Response.ok(Map.of("tools", tools)).build();
    }

    @GET
    @Path("/servers")
    public Response listServers() {
        McpServerManager mgr = manager();
        List<Map<String, Object>> servers = new ArrayList<>();
        for (String name : mgr.serverNames()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", name);
            s.put("status", mgr.status(name).name());
            s.put("toolCount", mgr.discovered(name).size());
            s.put("error", mgr.error(name));
            servers.add(s);
        }
        return Response.ok(Map.of("servers", servers)).build();
    }

    @GET
    @Path("/servers/{name}/tools")
    public Response serverTools(@PathParam("name") String name) {
        McpServerManager mgr = manager();
        List<McpRegistrar.McpToolSpec> tools = mgr.discovered(name);
        List<Map<String, Object>> result = new ArrayList<>();
        for (McpRegistrar.McpToolSpec t : tools) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", t.name());
            tool.put("description", t.description());
            tool.put("inputSchema", t.inputSchema());
            result.add(tool);
        }
        return Response.ok(Map.of("server", name, "tools", result)).build();
    }

    @POST
    @Path("/servers/{name}/reload")
    public Response reloadServer(@PathParam("name") String name) {
        McpServerManager mgr = manager();
        mgr.reload(name);
        return Response.ok(Map.of(
                "server", name,
                "status", mgr.status(name).name(),
                "tools", mgr.discovered(name).size()
        )).build();
    }

    @POST
    @Path("/servers")
    public Response addServer(Map<String, Object> body) {
        if (body == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "body required")).build();
        }
        String name = String.valueOf(body.getOrDefault("name", ""));
        if (name.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "name required")).build();
        }
        String type = String.valueOf(body.getOrDefault("type", "remote"));
        McpServerConfig config;
        if ("local".equalsIgnoreCase(type)) {
            Object cmdObj = body.get("command");
            List<String> command = new ArrayList<>();
            if (cmdObj instanceof List<?> list) {
                for (Object o : list) command.add(String.valueOf(o));
            }
            config = new McpServerConfig.Local(name, command, "", Map.of(), false, false, 0, 0, 0);
        } else {
            String url = String.valueOf(body.getOrDefault("url", ""));
            config = new McpServerConfig.Remote(name, url, Map.of(), false, false, false, 0, 0, 0);
        }
        manager().add(config);
        McpServerManager mgr = manager();
        return Response.status(Response.Status.CREATED).entity(Map.of(
                "name", name,
                "status", mgr.status(name).name()
        )).build();
    }

    @DELETE
    @Path("/servers/{name}")
    public Response removeServer(@PathParam("name") String name) {
        manager().remove(name);
        return Response.ok(Map.of("deleted", true)).build();
    }
}