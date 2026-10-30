package de.augmentia.oc.engine.runtime.skill;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A small, dependency-free BM25 ranker over the skill ID, name and description.
 */
public final class Bm25SkillRanker implements SkillRanker {

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    @Override
    public List<RankedSkill> rank(List<Skill> candidates, String query, SkillFilterOptions options) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        List<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) {
            return candidates.stream()
                    .map(skill -> new RankedSkill(skill, 0.0))
                    .toList();
        }

        int n = candidates.size();
        Map<String, Integer> documentFrequency = new HashMap<>();
        List<List<String>> documents = new ArrayList<>();
        List<Integer> lengths = new ArrayList<>();

        for (Skill skill : candidates) {
            List<String> terms = tokenize(skill.id().value() + " " + skill.name() + " " + skill.description());
            documents.add(terms);
            lengths.add(Math.max(1, terms.size()));
            Set<String> unique = new HashSet<>(terms);
            for (String term : unique) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }

        double averageLength = lengths.stream().mapToInt(Integer::intValue).average().orElse(1.0);
        if (averageLength <= 0.0) averageLength = 1.0;

        List<RankedSkill> ranked = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Map<String, Integer> termFrequency = new HashMap<>();
            for (String term : documents.get(i)) {
                termFrequency.merge(term, 1, Integer::sum);
            }
            double score = 0.0;
            int length = lengths.get(i);
            for (String term : queryTerms) {
                int tf = termFrequency.getOrDefault(term, 0);
                if (tf == 0) continue;
                int df = documentFrequency.getOrDefault(term, 0);
                double idf = Math.log(1.0 + (n - df + 0.5) / (df + 0.5));
                double denominator = tf + K1 * (1.0 - B + B * length / averageLength);
                score += idf * (tf * (K1 + 1.0)) / denominator;
            }
            ranked.add(new RankedSkill(candidates.get(i), score));
        }

        ranked.sort(Comparator
                .comparingDouble(RankedSkill::score).reversed()
                .thenComparing(result -> result.skill().id().value()));
        return List.copyOf(ranked);
    }

    private static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> terms = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                current.append(Character.toLowerCase(ch));
            } else if (current.length() > 0) {
                terms.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) terms.add(current.toString());
        return terms;
    }
}
