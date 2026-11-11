package de.augmentia.qoc.temporal.port;

import java.util.List;
import java.util.Map;

/**
 * Port: Long-term Memory — remember/recall über Scopes.
 */
public interface LongTermMemoryPort {
    void remember(String scope, String content);
    List<String> recall(String scope, String query);
}
