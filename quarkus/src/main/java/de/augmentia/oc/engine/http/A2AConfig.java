package de.augmentia.oc.engine.http;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class A2AConfig {
    private final Config config;

    public A2AConfig(Config config) {
        this.config = config;
    }

    public List<de.augmentia.oc.engine.runtime.tool.a2a.A2AToolProvider.A2AEndpoint> endpoints() {
        List<de.augmentia.oc.engine.runtime.tool.a2a.A2AToolProvider.A2AEndpoint> result = new ArrayList<>();
        // Support env/MP config: engine.a2a.endpoints.0.url etc not easily mapped, use simple list via comma
        Optional<String> list = config.getOptionalValue("engine.a2a.endpoints", String.class);
        if (list.isEmpty()) {
            Optional<String> urls = config.getOptionalValue("engine.a2a.urls", String.class);
            if (urls.isPresent()) {
                String[] parts = urls.get().split(",");
                int i = 0;
                for (String part : parts) {
                    String url = part.trim();
                    if (url.isEmpty()) continue;
                    result.add(new de.augmentia.oc.engine.runtime.tool.a2a.A2AToolProvider.A2AEndpoint("a2a" + i, url, true, ""));
                    i++;
                }
            }
            return result;
        }
        String raw = list.get();
        String[] entries = raw.split(";");
        int i = 0;
        for (String entry : entries) {
            String e = entry.trim();
            if (e.isEmpty()) continue;
            String url = null;
            String id = "a2a" + i;
            String tool = "";
            boolean enabled = true;
            for (String pair : e.split("\\|")) {
                String[] kv = pair.split("=", 2);
                if (kv.length != 2) continue;
                String k = kv[0].trim();
                String v = kv[1].trim();
                if ("url".equalsIgnoreCase(k)) url = v;
                else if ("id".equalsIgnoreCase(k)) id = v;
                else if ("tool".equalsIgnoreCase(k) || "toolName".equalsIgnoreCase(k)) tool = v;
                else if ("enabled".equalsIgnoreCase(k)) enabled = Boolean.parseBoolean(v);
            }
            if (url != null && !url.isBlank()) {
                result.add(new de.augmentia.oc.engine.runtime.tool.a2a.A2AToolProvider.A2AEndpoint(id, url, enabled, tool));
                i++;
            }
        }
        return result;
    }
}
