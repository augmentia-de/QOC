package de.augmentia.qoc.workflowrunner.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.oc.engine.adapter.sql.SqlDialect;
import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition.Agent;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared JDBC implementation for the agent specification table.
 */
public abstract class AbstractJdbcAgentStore implements AgentStorePort {

    protected final DataSource dataSource;
    protected final SqlDialect dialect;
    protected final ObjectMapper mapper = new ObjectMapper();

    protected AbstractJdbcAgentStore(DataSource dataSource, SqlDialect dialect) {
        if (dataSource == null) throw new IllegalArgumentException("dataSource must not be null");
        if (dialect == null) throw new IllegalArgumentException("dialect must not be null");
        this.dataSource = dataSource;
        this.dialect = dialect;
    }

    @Override
    public Agent get(String id) {
        return dialect.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "select agent from agent_definition where id = ?")) {
                statement.setString(1, id);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? mapper.readValue(resultSet.getString(1), Agent.class) : null;
                }
            } catch (SQLException | java.io.IOException e) {
                throw new IllegalStateException("Cannot read agent: " + id, e);
            }
        });
    }

    @Override
    public boolean delete(String id) {
        return dialect.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "delete from agent_definition where id = ?")) {
                statement.setString(1, id);
                return statement.executeUpdate() > 0;
            } catch (SQLException e) {
                throw new IllegalStateException("Cannot delete agent: " + id, e);
            }
        });
    }

    @Override
    public List<String> list() {
        return dialect.transaction(dataSource, connection -> {
            List<String> ids = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "select id from agent_definition order by id asc");
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) ids.add(resultSet.getString(1));
                return ids;
            } catch (SQLException e) {
                throw new IllegalStateException("Cannot list agents", e);
            }
        });
    }

    @Override
    public List<Agent> listAll() {
        return listAll(null, true);
    }

    @Override
    public List<Agent> listAll(String userId, boolean isAdmin) {
        List<Agent> agents = new ArrayList<>();
        for (String id : list()) {
            Agent a = get(id);
            if (a == null) continue;
            if (isAdmin || userId == null || userId.isBlank()) {
                agents.add(a);
            } else {
                String owner = a.ownerId();
                if (owner == null || owner.isBlank() || owner.equals(userId)) {
                    agents.add(a);
                }
            }
        }
        return agents;
    }

    @Override
    public Agent save(String id, Agent agent) {
        if (agent == null) throw new IllegalArgumentException("agent must not be null");
        final Agent toSave;
        if (agent.id() == null || agent.id().isBlank()) {
            toSave = new Agent(id, agent.name(), agent.version(), agent.category(), agent.model(),
                    agent.temperature(), agent.maxTokens(), agent.topP(), agent.tools(),
                    agent.guardrailsInput(), agent.guardrailsOutput(), agent.hooks(),
                    agent.jsonOutput(), agent.active(), agent.systemPrompt(), agent.responseSchema(),
                    agent.ownerId(), agent.rolesAllowed(),
                    agent.mode() == null ? "all" : agent.mode(),
                    agent.skills() == null ? java.util.List.of() : agent.skills(),
                    agent.maxTurns(), agent.maxCost(), agent.requiresHITL());
        } else {
            toSave = agent;
        }
        String json;
        try {
            json = mapper.writeValueAsString(toSave);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot serialize agent: " + id, e);
        }
        String now = dialect.nowExpression();
        String sql = "insert into agent_definition "
                + "(id, name, agent, created_at, updated_at) "
                + "values (?, ?, " + dialect.jsonParameter() + ", " + now + ", " + now + ") "
                + "on conflict (id) do update set "
                + "name = excluded.name, agent = excluded.agent, updated_at = " + now;
        dialect.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, id);
                statement.setString(2, toSave.name());
                statement.setString(3, json);
                statement.executeUpdate();
                return null;
            } catch (SQLException e) {
                throw new IllegalStateException("Cannot save agent: " + id, e);
            }
        });
        return toSave;
    }

    @Override
    public Agent create(String name) {
        String id = name == null ? "" : name.toLowerCase().replaceAll("[^a-z0-9-]", "-");
        if (id.isBlank()) id = "agent-" + System.currentTimeMillis();
        Agent agent = new Agent(id, name, "1.0.0", "", "deepseek-v4.1-flash", 0.3, 4096, 1.0,
                List.of(), List.of(), List.of(), List.of(), false, true, "", null, "", List.of("*"),
                "all", List.of(), 10, 1.0, false);
        return save(id, agent);
    }
}