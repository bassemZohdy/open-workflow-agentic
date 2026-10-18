package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static io.restassured.RestAssured.given;

import io.restassured.response.ValidatableResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

/** Shared helpers for the lifecycle boot tests. Each test uses its own tenant, so tests do not see each other's rows. */
final class LifecycleSupport {

    static final String DEFINITION_ID = "agentic/research-and-approve";
    static final String DEFINITION_VERSION = "0.1.0";

    private LifecycleSupport() {
    }

    static String newTenant() {
        return "tenant-" + UUID.randomUUID();
    }

    static String newKey() {
        return "key-" + UUID.randomUUID();
    }

    static Map<String, Object> body(String definitionId, String version, String key, Map<String, Object> input) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("definitionId", definitionId);
        body.put("definitionVersion", version);
        body.put("input", input);
        body.put("idempotencyKey", key);
        return body;
    }

    static Map<String, Object> standardBody(String key) {
        return body(DEFINITION_ID, DEFINITION_VERSION, key, Map.of("topic", "agentic workflows"));
    }

    static ValidatableResponse post(String tenant, Map<String, Object> body) {
        return given()
                .header("X-Tenant", tenant)
                .header("X-Subject", "alice")
                .contentType("application/json")
                .body(body)
                .when().post("/instances")
                .then();
    }

    static ValidatableResponse get(String tenant, String id) {
        return given()
                .header("X-Tenant", tenant)
                .header("X-Subject", "alice")
                .when().get("/instances/" + id)
                .then();
    }

    /** Runs a count query and returns the single number it gives. */
    static long count(DataSource dataSource, String sql, Object... params) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("count failed", e);
        }
    }

    /** Runs a statement and returns the SQL error it raised, or null when it succeeded. */
    static SQLException execute(DataSource dataSource, String sql) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
            return null;
        } catch (SQLException e) {
            return e;
        }
    }
}
