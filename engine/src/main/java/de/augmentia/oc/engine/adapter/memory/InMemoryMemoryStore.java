package de.augmentia.oc.engine.adapter.memory;

import de.augmentia.oc.engine.core.model.AnswerabilityResult;
import de.augmentia.oc.engine.core.model.Fact;
import de.augmentia.oc.engine.core.model.MemoryDiff;
import de.augmentia.oc.engine.core.model.MemoryQuery;
import de.augmentia.oc.engine.core.port.store.MemoryStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class InMemoryMemoryStore implements MemoryStore {
    private final Map<String, Fact> facts = new ConcurrentHashMap<>();

    @Override
    public void upsert(Fact fact) {
        facts.put(fact.factId(), fact);
    }

    @Override
    public void retire(String factId, String reason, String supersededBy) {
        Fact f = facts.get(factId);
        if (f != null) {
            facts.put(factId, new Fact(f.factId(), f.entity(), f.relation(), f.value(),
                    f.eventTime(), f.validFrom(), Instant.now(), f.provenance(), f.confidence(), f.tier()));
        }
    }

    @Override
    public List<Fact> query(MemoryQuery query) {
        List<Fact> out = new ArrayList<>(facts.values());
        if (query.entity() != null && !query.entity().isBlank()) {
            out = out.stream().filter(f -> f.entity().equalsIgnoreCase(query.entity())).collect(Collectors.toList());
        }
        if (query.relation() != null && !query.relation().isBlank()) {
            out = out.stream().filter(f -> f.relation().equalsIgnoreCase(query.relation())).collect(Collectors.toList());
        }
        if (query.asOf() != null) {
            Instant asOf = query.asOf();
            out = out.stream().filter(f -> !f.validFrom().isAfter(asOf) && !f.validTo().isBefore(asOf)).collect(Collectors.toList());
        }
        if (query.text() != null && !query.text().isBlank()) {
            String t = query.text().toLowerCase();
            out = out.stream().filter(f -> (f.entity() + " " + f.relation() + " " + f.value()).toLowerCase().contains(t)).collect(Collectors.toList());
        }
        int limit = query.limit() > 0 ? query.limit() : 20;
        if (out.size() > limit) out = out.subList(0, limit);
        return List.copyOf(out);
    }

    @Override
    public java.util.Optional<Fact> asOf(String entity, String relation, Instant instant) {
        return facts.values().stream()
                .filter(f -> f.entity().equalsIgnoreCase(entity) && f.relation().equalsIgnoreCase(relation))
                .filter(f -> !f.validFrom().isAfter(instant) && !f.validTo().isBefore(instant))
                .findFirst();
    }

    @Override
    public MemoryDiff diff(Instant since) {
        List<Fact> added = new ArrayList<>();
        List<Fact> retired = new ArrayList<>();
        for (Fact f : facts.values()) {
            if (f.eventTime().isAfter(since)) added.add(f);
            if (f.validTo() != null && f.validTo().isAfter(since) && f.validTo().isBefore(Instant.now())) retired.add(f);
        }
        return new MemoryDiff(since, added, retired, List.of());
    }

    @Override
    public AnswerabilityResult checkAnswerable(String question) {
        if (question == null || question.isBlank()) {
            return new AnswerabilityResult(true, List.of(), List.of(), "");
        }
        // Simple heuristic: extract tokens and check entity coverage.
        String[] tokens = question.toLowerCase().split("\\W+");
        List<String> entities = facts.values().stream().map(Fact::entity).distinct().collect(Collectors.toList());
        List<String> missing = new ArrayList<>();
        for (String t : tokens) {
            if (t.length() < 3) continue;
            boolean found = entities.stream().anyMatch(e -> e.toLowerCase().contains(t));
            if (!found) missing.add(t);
        }
        // Not strictly missing if no facts at all (can't judge).
        boolean answerable = !facts.isEmpty() && missing.size() < tokens.length;
        return new AnswerabilityResult(answerable, missing, List.of(), answerable ? "" : "insufficient facts");
    }
}
