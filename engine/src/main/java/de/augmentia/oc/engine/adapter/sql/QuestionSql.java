package de.augmentia.oc.engine.adapter.sql;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * JDBC persistence for {@code question_request}. Kept in the adapter layer so
 * the Quarkus wiring/services stay free of java.sql.
 */
public final class QuestionSql {

    private QuestionSql() {
    }

    public static void persistPending(DataSource dataSource, String id, String sessionId,
                                      String questionsJson, Instant createdAt, Instant deadline,
                                      String tokenHash) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "insert into question_request " +
                             "(id, session_id, questions_json, created_at, deadline_at, token_hash, status) " +
                             "values (?, ?, ?, ?, ?, ?, 'pending')")) {
            statement.setString(1, id);
            statement.setString(2, sessionId);
            statement.setString(3, questionsJson);
            statement.setTimestamp(4, Timestamp.from(createdAt));
            statement.setTimestamp(5, Timestamp.from(deadline));
            statement.setString(6, tokenHash);
            statement.executeUpdate();
        }
    }

    public static String[] pollAnswer(DataSource dataSource, String id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select answer_selected, answer_text from question_request " +
                             "where id = ? and status = 'answered'")) {
            statement.setString(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return new String[]{resultSet.getString("answer_selected"), resultSet.getString("answer_text")};
                }
            }
        }
        return null;
    }

    public static void markAnswered(DataSource dataSource, String id) throws SQLException {
        markStatus(dataSource, id, "answered");
    }

    public static void markExpired(DataSource dataSource, String id) throws SQLException {
        markStatus(dataSource, id, "expired");
    }

    public static void updateAnswer(DataSource dataSource, String id, String selected, String text) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "update question_request set answer_selected = ?, answer_text = ?, status = 'answered' where id = ?")) {
            statement.setString(1, selected);
            statement.setString(2, text);
            statement.setString(3, id);
            statement.executeUpdate();
        }
    }

    public static String findIdByToken(DataSource dataSource, String tokenHash) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select id from question_request where token_hash = ? and status = 'pending'")) {
            statement.setString(1, tokenHash);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString("id") : null;
            }
        }
    }

    public static String sessionIdForQuestion(DataSource dataSource, String questionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select session_id from question_request where id = ?")) {
            statement.setString(1, questionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString("session_id") : null;
            }
        }
    }

    private static void markStatus(DataSource dataSource, String id, String status) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "update question_request set status = ? where id = ?")) {
            statement.setString(1, status);
            statement.setString(2, id);
            statement.executeUpdate();
        }
    }
}
