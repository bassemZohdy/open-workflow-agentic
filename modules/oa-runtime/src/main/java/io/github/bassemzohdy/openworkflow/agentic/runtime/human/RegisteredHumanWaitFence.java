package io.github.bassemzohdy.openworkflow.agentic.runtime.human;

import io.github.bassemzohdy.openworkflow.agentic.contracts.event.InboxWait;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Set;

/**
 * Transaction-local check of a real registered wait and its pinned workflow instance. This is not an event dispatch
 * lease or checkpoint proof. Delivery still requires the inbox ownership protocol; this class is not wired to an API.
 */
final class RegisteredHumanWaitFence implements HumanDecisionCoordinator.WaitFence {
    private final InboxWait wait;
    RegisteredHumanWaitFence(InboxWait wait) { this.wait = Objects.requireNonNull(wait); }

    @Override
    public void requireCurrent(Connection connection, String tenant, Ulid instance, String waitKey) throws SQLException {
        if (connection.getAutoCommit()) throw new IllegalArgumentException("an existing transaction is required");
        if (!wait.tenant().equals(tenant) || !wait.instanceId().equals(instance) || !wait.key().equals(waitKey)) {
            throw new HumanDecisionCoordinator.Refused();
        }
        // Match the existing inbox lock order: registry, registered delivery, then human task.
        try (var q = connection.prepareStatement("select definition_id,definition_version,engine_instance_id,status,"
                + "(wait_deadline is null or wait_deadline > clock_timestamp()) from task.instance "
                + "where tenant=? and id=? for update")) {
            q.setString(1, tenant); q.setString(2, instance.value());
            try (var r = q.executeQuery()) {
                if (!r.next() || !(wait.namespace() + "/" + wait.definition()).equals(r.getString(1))
                        || !wait.version().equals(r.getString(2)) || !wait.engineInstanceId().equals(r.getString(3))
                        || !Set.of("RUNNING", "WAITING").contains(r.getString(4)) || !r.getBoolean(5)) {
                    throw new HumanDecisionCoordinator.Refused();
                }
            }
        }
        try (var q = connection.prepareStatement("select identity_digest,state,deadline > clock_timestamp() "
                + "from inbox.delivery where tenant=? and instance_id=? and wait_key=? for update")) {
            q.setString(1, tenant); q.setString(2, instance.value()); q.setString(3, waitKey);
            try (var r = q.executeQuery()) {
                if (!r.next() || !wait.digest().equals(r.getString(1)) || !"REGISTERED".equals(r.getString(2))
                        || !r.getBoolean(3)) throw new HumanDecisionCoordinator.Refused();
            }
        }
    }
}
