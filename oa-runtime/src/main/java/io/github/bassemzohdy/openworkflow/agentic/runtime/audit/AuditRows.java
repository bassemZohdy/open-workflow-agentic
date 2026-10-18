package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/** JDBC mapping shared by the audit classes. Package-private: the rest of the code sees {@link AuditEvent}. */
final class AuditRows {

    /** Columns read by {@link #event(ResultSet)}, in the order the statements select them. */
    static final String EVENT_COLUMNS = "event_id, attributes::text as attributes, seq, occurred_at, actor_kind, "
            + "actor_id, event_type, resource_type, resource_id, previous_hash, hash";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {
    };

    private AuditRows() {
    }

    static AuditEvent event(ResultSet rs) throws SQLException {
        return new AuditEvent(
                Ulid.parse(rs.getString("event_id")),
                rs.getLong("seq"),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                new Subject(SubjectKind.valueOf(rs.getString("actor_kind")), rs.getString("actor_id")),
                rs.getString("event_type"),
                rs.getString("resource_type"),
                rs.getString("resource_id"),
                fromJson(rs.getString("attributes")),
                rs.getString("previous_hash"),
                rs.getString("hash"));
    }

    static OffsetDateTime offset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    static String toJson(Map<String, String> attributes) {
        try {
            return JSON.writeValueAsString(attributes);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("attributes are plain strings and always serialise", e);
        }
    }

    static Map<String, String> fromJson(String json) {
        try {
            return JSON.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored attributes are not a JSON object of strings", e);
        }
    }

    static ObjectMapper json() {
        return JSON;
    }
}
