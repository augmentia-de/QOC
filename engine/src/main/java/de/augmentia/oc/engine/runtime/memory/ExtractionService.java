package de.augmentia.oc.engine.runtime.memory;

import de.augmentia.oc.engine.core.model.Fact;
import de.augmentia.oc.engine.core.port.store.MemoryStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tiered fact extraction. Tier 0 = deterministic (regex/keywords).
 * Tier 1+ = LLM-based (not implemented in Phase 1).
 * Controlled by maxTier config (0 = manual only).
 */
public final class ExtractionService {

    private static final Pattern FACT_PATTERN =
            Pattern.compile("(?i)(\\w[\\w\\s-]{1,40})\\s*[:=]\\s*(.+?)(?:\\.|$)");

    private ExtractionService() {}

    /**
     * Deterministically extract facts from text (tier 0).
     * Returns a list of candidate facts (not yet stored).
     */
    public static List<Fact> extractDeterministic(String text, String source) {
        List<Fact> facts = new ArrayList<>();
        if (text == null || text.isBlank()) return facts;
        List<String> provenance = source == null ? List.of() : List.of(source);
        // Simple: look for "X is/are Y" or "X: Y" patterns
        String[] sentences = text.split("[.!?\\n]");
        for (String sentence : sentences) {
            sentence = sentence.trim();
            if (sentence.length() < 5) continue;
            String[] parts = sentence.split("\\s+(?:is|are|was|were|has|have)\\s+", 2);
            if (parts.length == 2) {
                String entity = parts[0].trim();
                String value = parts[1].trim();
                if (entity.length() > 2 && value.length() > 2 && entity.length() < 50) {
                    String factId = "ext_" + UUID.nameUUIDFromBytes((entity + "|is|" + value).getBytes()).toString().replace("-", "");
                    facts.add(new Fact(factId, entity, "is", value, Instant.now(), Instant.now(), null, provenance, 0.6, 0));
                }
            }
        }
        return facts;
    }

    /**
     * Extract and store facts if maxTier >= 1.
     */
    public static int extractAndStore(MemoryStore store, String text, String source, int maxTier) {
        if (store == null || maxTier < 1) return 0;
        List<Fact> facts = extractDeterministic(text, source);
        int count = 0;
        for (Fact f : facts) {
            // Only store if not already present
            if (store.query(new de.augmentia.oc.engine.core.model.MemoryQuery(f.entity(), f.relation(), null, null, 1)).isEmpty()) {
                store.upsert(f);
                count++;
            }
        }
        return count;
    }
}