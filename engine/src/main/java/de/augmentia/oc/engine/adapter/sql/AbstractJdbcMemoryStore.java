package de.augmentia.oc.engine.adapter.sql;

import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.AnswerabilityResult;
import de.augmentia.oc.engine.core.model.Fact;
import de.augmentia.oc.engine.core.model.MemoryDiff;
import de.augmentia.oc.engine.core.model.MemoryQuery;
import de.augmentia.oc.engine.core.port.store.MemoryStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Shared JDBC memory store. Persists {@link Fact} records in a {@code memory_fact}
 * table. Works for both PostgreSQL and SQLite via the dialect.
 */
public abstract class AbstractJdbcMemoryStore implements MemoryStore {

    protected final DataSource dataSource;
    protected final SqlDialect dialect;

    protected AbstractJdbcMemoryStore(DataSource dataSource, SqlDialect dialect) {
        if (dataSource == null) throw new IllegalArgumentException("dataSource must not be null");
        if (dialect == null) throw new IllegalArgumentException("dialect must not be null");
        this.dataSource = dataSource;
        this.dialect = dialect;
    }

    @Override
    public void upsert(Fact fact) {
        if (fact == null) throw new IllegalArgumentException("fact must not be null");
        dialect.transaction(dataSource, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "insert into memory_fact (fact_id, entity, relation, value_json, event_time, valid_from, valid_to, provenance_json, confidence, tier) " +
                            "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                            "on conflict (fact_id) do update set entity=excluded.entity, relation=excluded.relation, " +
                            "value_json=excluded.value_json, event_time=excluded.event_time, valid_from=excluded.valid_from, " +
                            "valid_to=excluded.valid_to, provenance_json=excluded.provenance_json, confidence=excluded.confidence, tier=excluded.tier")) {
                ps.setString(1, fact.factId());
                ps.setString(2, fact.entity());
                ps.setString(3, fact.relation());
                ps.setString(4, toJson(fact.value()));
                setInstant(ps, 5, fact.eventTime());
                setInstant(ps, 6, fact.validFrom());
                Instant vt = (fact.validTo() == null || fact.validTo().equals(Instant.MAX)) ? null : fact.validTo();
                setInstant(ps, 7, vt);
                ps.setString(8, toJson(fact.provenance()));
                ps.setDouble(9, fact.confidence());
                ps.setInt(10, fact.tier());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new de.augmentia.oc.engine.core.error.StoreFailure("cannot upsert fact", e);
            }
            return null;
        });
    }

    @Override
    public void retire(String factId, String reason, String supersededBy) {
        dialect.transaction(dataSource, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "update memory_fact set valid_to = ? where fact_id = ?")) {
                setInstant(ps, 1, Instant.now());
                ps.setString(2, factId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new de.augmentia.oc.engine.core.error.StoreFailure("cannot retire fact", e);
            }
            return null;
        });
    }

    @Override
    public java.util.List<Fact> query(MemoryQuery query) {
        StringBuilder sql = new StringBuilder("select * from memory_fact where 1=1");
        List<Object> params = new ArrayList<>();
        if (query != null) {
            if (query.entity() != null && !query.entity().isBlank()) {
                sql.append(" and lower(entity) = lower(?)");
                params.add(query.entity());
            }
            if (query.relation() != null && !query.relation().isBlank()) {
                sql.append(" and lower(relation) = lower(?)");
                params.add(query.relation());
            }
            if (query.asOf() != null) {
                sql.append(" and valid_from <= ? and (valid_to is null or valid_to >= ?)");
                params.add(query.asOf());
                params.add(query.asOf());
            }
            if (query.text() != null && !query.text().isBlank()) {
                sql.append(" and (lower(entity) like ? or lower(relation) like ? or lower(value_json) like ?)");
                String like = "%" + query.text().toLowerCase() + "%";
                params.add(like);
                params.add(like);
                params.add(like);
            }
        }
        sql.append(" order by event_time desc");
        int limit = (query != null && query.limit() > 0) ? query.limit() : 20;
        sql.append(" limit ").append(limit);

        List<Fact> out = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                Object p = params.get(i);
                if (p instanceof Instant inst) setInstant(ps, i + 1, inst);
                else if (p instanceof Timestamp t) ps.setTimestamp(i + 1, t);
                else if (p instanceof String s) ps.setString(i + 1, s);
                else if (p instanceof Integer n) ps.setInt(i + 1, n);
                else if (p instanceof Double d) ps.setDouble(i + 1, d);
                else ps.setObject(i + 1, p);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new de.augmentia.oc.engine.core.error.StoreFailure("cannot query facts", e);
        }
        return List.copyOf(out);
    }

    @Override
    public java.util.Optional<Fact> asOf(String entity, String relation, Instant instant) {
        if (entity == null || relation == null || instant == null) return java.util.Optional.empty();
        String sql = "select * from memory_fact where lower(entity)=lower(?) and lower(relation)=lower(?) " +
                "and valid_from <= ? and (valid_to is null or valid_to >= ?) order by valid_from desc limit 1";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, entity);
            ps.setString(2, relation);
            setInstant(ps, 3, instant);
            setInstant(ps, 4, instant);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return java.util.Optional.of(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new de.augmentia.oc.engine.core.error.StoreFailure("cannot read fact asOf", e);
        }
        return java.util.Optional.empty();
    }

    @Override
    public MemoryDiff diff(Instant since) {
        List<Fact> added = new ArrayList<>();
        List<Fact> retired = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement("select * from memory_fact")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Fact f = mapRow(rs);
                    if (f.eventTime().isAfter(since)) added.add(f);
                    if (f.validTo() != null && !f.validTo().equals(Instant.MAX)
                            && f.validTo().isAfter(since) && f.validTo().isBefore(Instant.now())) {
                        retired.add(f);
                    }
                }
            }
        } catch (SQLException e) {
            throw new de.augmentia.oc.engine.core.error.StoreFailure("cannot diff facts", e);
        }
        return new MemoryDiff(since, added, retired, List.of());
    }

    @Override
    public AnswerabilityResult checkAnswerable(String question) {
        if (question == null || question.isBlank()) {
            return new AnswerabilityResult(true, List.of(), List.of(), "");
        }
        List<Fact> facts = query(new MemoryQuery(null, null, null, null, 1000));
        String[] tokens = question.toLowerCase().split("\\W+");
        List<String> entities = facts.stream().map(Fact::entity).distinct().collect(Collectors.toList());
        List<String> missing = new ArrayList<>();
        for (String t : tokens) {
            if (t.length() < 3) continue;
            boolean found = entities.stream().anyMatch(e -> e.toLowerCase().contains(t));
            if (!found) missing.add(t);
        }
        boolean answerable = !facts.isEmpty() && missing.size() < tokens.length;
        return new AnswerabilityResult(answerable, missing, List.of(), answerable ? "" : "insufficient facts");
    }

    private void setInstant(PreparedStatement ps, int idx, Instant instant) throws SQLException {
        dialect.setInstant(ps, idx, instant);
    }

    private Fact mapRow(ResultSet rs) throws SQLException {
        Instant validTo = dialect.instant(rs, "valid_to");
        if (validTo == null) validTo = Instant.MAX;
        return new Fact(
                rs.getString("fact_id"),
                rs.getString("entity"),
                rs.getString("relation"),
                fromJson(rs.getString("value_json")),
                dialect.instant(rs, "event_time"),
                dialect.instant(rs, "valid_from"),
                validTo,
                fromJsonList(rs.getString("provenance_json")),
                rs.getDouble("confidence"),
                rs.getInt("tier"));
    }

    private static String toJson(Object value) {
        try {
            return Json.encode(de.augmentia.oc.engine.core.json.JsonValues.fromJava(value));
        } catch (Exception e) {
            return "\"" + String.valueOf(value) + "\"";
        }
    }

    private static Object fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            de.augmentia.oc.engine.core.json.Result<de.augmentia.oc.engine.core.json.JsonValue> parsed =
                    de.augmentia.oc.engine.core.json.Json.parse(json);
            if (parsed instanceof de.augmentia.oc.engine.core.json.Result.Ok ok) {
                return de.augmentia.oc.engine.core.json.JsonValues.toJava((de.augmentia.oc.engine.core.json.JsonValue) ok.value());
            }
            return json;
        } catch (Exception e) {
            return json;
        }
    }



    private static List<String> fromJsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            de.augmentia.oc.engine.core.json.Result<de.augmentia.oc.engine.core.json.JsonValue> parsed =
                    de.augmentia.oc.engine.core.json.Json.parse(json);
            Object decoded = parsed.valueOrNull() == null ? null : de.augmentia.oc.engine.core.json.JsonValues.toJava((de.augmentia.oc.engine.core.json.JsonValue) parsed.valueOrNull());
            if (decoded instanceof List<?> list) {
                return list.stream().map(String::valueOf).collect(Collectors.toList());
            }
            return List.of();
        } catch (Exception e) {
            return List.of();
        }
    }
}