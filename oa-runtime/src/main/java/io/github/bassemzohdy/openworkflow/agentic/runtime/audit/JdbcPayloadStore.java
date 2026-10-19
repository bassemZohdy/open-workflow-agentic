package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** {@link PayloadStore} in {@code audit.payload_blob}. The table is not append-only, by design. */
public final class JdbcPayloadStore implements PayloadStore {

    private final DataSource dataSource;

    public JdbcPayloadStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void put(String refId, Map<String, String> payload) {
        Objects.requireNonNull(payload, "payload");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                     "insert into audit.payload_blob (ref_id, content, digest) values (?, cast(? as jsonb), ?)")) {
            insert.setString(1, refId);
            insert.setString(2, AuditRows.toJson(payload));
            insert.setString(3, PayloadProtection.digest(payload));
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new AuditStoreException("payload " + refId + " could not be stored", e);
        }
    }

    @Override
    public Optional<Map<String, String>> get(String refId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "select content::text from audit.payload_blob where ref_id = ?")) {
            select.setString(1, refId);
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() ? Optional.of(AuditRows.fromJson(rs.getString(1))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new AuditStoreException("payload " + refId + " could not be read", e);
        }
    }

    @Override
    public void delete(String refId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement delete = connection.prepareStatement("delete from audit.payload_blob where ref_id = ?")) {
            delete.setString(1, refId);
            delete.executeUpdate();
        } catch (SQLException e) {
            throw new AuditStoreException("payload " + refId + " could not be deleted", e);
        }
    }
}
