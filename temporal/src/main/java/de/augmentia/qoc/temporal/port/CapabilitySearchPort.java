package de.augmentia.qoc.temporal.port;

import java.util.List;

/**
 * Port: Capability Search/RAG — Tools/Skills für Query finden.
 */
public interface CapabilitySearchPort {
    List<String> search(String query, int topK);
    void index(String capability);
}
