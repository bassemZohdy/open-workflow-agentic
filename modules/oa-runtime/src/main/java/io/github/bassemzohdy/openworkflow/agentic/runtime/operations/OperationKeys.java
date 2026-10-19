package io.github.bassemzohdy.openworkflow.agentic.runtime.operations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.Transactions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Idempotency of the operations API (WP-105d; table {@code task.operation_request}, migration V40). A mutating request
 * first reserves its key, then runs, then stores the response it returned. The reservation is one insert on the key, so
 * two identical requests at once cannot both run: the second one sees the first one's row.
 */
@ApplicationScoped
public class OperationKeys {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    /** What a reservation found. */
    public enum State {
        /** The key is now reserved for this request; the caller runs the operation. */
        RESERVED,
        /** The same request already completed; the stored response is returned. */
        REPLAY,
        /** The key was used for a different request. */
        KEY_REUSED,
        /** The same request is running, or a crash left it unfinished. */
        IN_PROGRESS
    }

    /** The result of {@link #reserve}. {@code status} and {@code body} are set only for {@link State#REPLAY}. */
    public record Reservation(State state, int status, Map<String, Object> body) {
    }

    private final DataSource dataSource;

    @Inject
    public OperationKeys(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * Reserves the key for this request, or reports what the key already is. The fingerprint identifies the request
     * (see {@link #fingerprint}); the same key with another fingerprint is {@link State#KEY_REUSED}.
     */
    public Reservation reserve(String tenant, String key, String operation, Ulid instanceId, Subject operator,
                               String fingerprint) {
        return Transactions.inTransaction(dataSource, connection -> {
            int inserted;
            try (PreparedStatement insert = connection.prepareStatement("""
                    insert into task.operation_request (tenant, idempotency_key, operation, instance_id,
                        operator_kind, operator_id, fingerprint, state)
                    values (?, ?, ?, ?, ?, ?, ?, 'PENDING')
                    on conflict (tenant, idempotency_key) do nothing
                    """)) {
                insert.setString(1, tenant);
                insert.setString(2, key);
                insert.setString(3, operation);
                insert.setString(4, instanceId.value());
                insert.setString(5, operator.kind().name());
                insert.setString(6, operator.id());
                insert.setString(7, fingerprint);
                inserted = insert.executeUpdate();
            }
            if (inserted == 1) {
                return new Reservation(State.RESERVED, 0, null);
            }
            try (PreparedStatement select = connection.prepareStatement("""
                    select fingerprint, state, response_status, response_body::text as response_text
                      from task.operation_request where tenant = ? and idempotency_key = ?
                    """)) {
                select.setString(1, tenant);
                select.setString(2, key);
                try (ResultSet rs = select.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("idempotency row vanished for key " + key);
                    }
                    if (!fingerprint.equals(rs.getString("fingerprint"))) {
                        return new Reservation(State.KEY_REUSED, 0, null);
                    }
                    if (!"DONE".equals(rs.getString("state"))) {
                        return new Reservation(State.IN_PROGRESS, 0, null);
                    }
                    return new Reservation(State.REPLAY, rs.getInt("response_status"),
                            parse(rs.getString("response_text")));
                }
            }
        });
    }

    /** Stores the response of a reserved request. The row becomes DONE and is never changed again. */
    public void complete(String tenant, String key, int status, Map<String, Object> body) {
        Transactions.inTransaction(dataSource, connection -> {
            try (PreparedStatement update = connection.prepareStatement("""
                    update task.operation_request
                       set state = 'DONE', response_status = ?, response_body = cast(? as jsonb), completed_at = now()
                     where tenant = ? and idempotency_key = ? and state = 'PENDING'
                    """)) {
                update.setInt(1, status);
                update.setString(2, write(body));
                update.setString(3, tenant);
                update.setString(4, key);
                if (update.executeUpdate() != 1) {
                    throw new IllegalStateException("no pending operation for key " + key);
                }
            }
            return null;
        });
    }

    /** Removes a reservation whose operation was refused or failed, so that the key can be used again. */
    public void release(String tenant, String key) {
        Transactions.inTransaction(dataSource, connection -> {
            try (PreparedStatement delete = connection.prepareStatement("""
                    delete from task.operation_request
                     where tenant = ? and idempotency_key = ? and state = 'PENDING'
                    """)) {
                delete.setString(1, tenant);
                delete.setString(2, key);
                delete.executeUpdate();
            }
            return null;
        });
    }

    /** The fingerprint of a request: its operation and its instance. Equal fingerprints are the same request. */
    public static String fingerprint(String operation, Ulid instanceId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((operation + "|" + instanceId.value()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", impossible);
        }
    }

    private static String write(Map<String, Object> body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the operation response is not serializable", e);
        }
    }

    private static Map<String, Object> parse(String text) {
        try {
            return JSON.readValue(text, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the stored operation response is not readable", e);
        }
    }
}
