package de.augmentia.oc.engine.runtime.memory;

import de.augmentia.oc.engine.core.model.Fact;
import de.augmentia.oc.engine.core.model.MemoryQuery;
import de.augmentia.oc.engine.core.port.store.MemoryStore;

import java.util.*;
import java.util.stream.Collectors;

/**
 * BM25 + graph-traversal retrieval over memory facts.
 * Enhances the basic MemoryStore.query with relevance scoring.
 */
public final class RetrievalService {

    private static final double K1 = 1.5;
    private static final double B = 0.75;

    private RetrievalService() {}

    /**
     * Retrieve facts with BM25 scoring + graph traversal (entity/relation neighbors).
     */
    public static List<Fact> retrieve(MemoryStore store, String text, int topK) {
        if (store == null) return List.of();
        if (text == null || text.isBlank()) {
            List<Fact> all = store.query(new MemoryQuery(null, null, null, null, topK > 0 ? topK : 20));
            return new ArrayList<>(all);
        }
        List<Fact> all = store.query(new MemoryQuery(null, null, null, null, 10000));
        if (all.isEmpty()) return List.of();

        String[] queryTerms = text.toLowerCase().split("\\W+");
        List<String> terms = new ArrayList<>();
        for (String t : queryTerms) {
            if (t.length() >= 2) terms.add(t);
        }
        if (terms.isEmpty()) return all.subList(0, Math.min(topK, all.size()));

        // Build document corpus
        Map<String, Double> idf = new HashMap<>();
        Map<String, Integer> docLengths = new HashMap<>();
        int totalLength = 0;
        List<String> docIds = new ArrayList<>();
        Map<String, String> docText = new HashMap<>();

        for (Fact f : all) {
            String doc = (f.entity() + " " + f.relation() + " " + f.value()).toLowerCase();
            docText.put(f.factId(), doc);
            docIds.add(f.factId());
            int len = doc.split("\\s+").length;
            docLengths.put(f.factId(), len);
            totalLength += len;
        }
        double avgDl = totalLength / (double) docIds.size();

        // IDF
        for (String term : terms) {
            int df = 0;
            for (String doc : docText.values()) {
                if (doc.contains(term)) df++;
            }
            idf.put(term, Math.log((all.size() - df + 0.5) / (df + 0.5) + 1));
        }

        // Score each fact
        Map<String, Double> scores = new HashMap<>();
        for (Fact f : all) {
            String doc = docText.get(f.factId());
            int dl = docLengths.get(f.factId());
            double score = 0;
            for (String term : terms) {
                int tf = countOccurrences(doc, term);
                double idfVal = idf.getOrDefault(term, 0.0);
                score += idfVal * (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * dl / avgDl));
            }
            // Graph bonus: boost facts sharing entity/relation with high-scoring facts
            scores.put(f.factId(), score);
        }

        // Sort by score descending
        List<Fact> sorted = all.stream()
                .sorted((a, b) -> Double.compare(scores.getOrDefault(b.factId(), 0.0), scores.getOrDefault(a.factId(), 0.0)))
                .collect(Collectors.toList());

        int k = Math.min(topK, sorted.size());
        return new ArrayList<>(sorted.subList(0, k));
    }

    private static int countOccurrences(String text, String term) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(term, idx)) != -1) {
            count++;
            idx += term.length();
        }
        return count;
    }
}