package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import io.github.bassemzohdy.openworkflow.agentic.contracts.event.InboxWait;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;

/** Immutable retained data. A reconstructed wait is not a current claim, policy decision or callback permission. */
final class WaitProfiles {
    private WaitProfiles() { }
    static void retain(Connection c,InboxWait wait) throws SQLException {
        if(c.getAutoCommit())throw new IllegalArgumentException("an existing transaction is required");
        try(var q=c.prepareStatement("insert into inbox.wait_profile (tenant,instance_id,wait_key,identity_digest,"
                +"application_id,engine_instance_id,namespace,definition,version,task_position,iteration,route,event_type,filter_digest,max_wait) "
                +"values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) on conflict (tenant,instance_id,wait_key) do nothing")) {
            q.setString(1,wait.tenant());q.setString(2,wait.instanceId().value());q.setString(3,wait.key());q.setString(4,wait.digest());
            q.setString(5,wait.applicationId());q.setString(6,wait.engineInstanceId());q.setString(7,wait.namespace());
            q.setString(8,wait.definition());q.setString(9,wait.version());q.setString(10,wait.taskPosition());q.setInt(11,wait.iteration());
            q.setString(12,wait.route());q.setString(13,wait.eventType());q.setString(14,wait.filterDigest());q.setString(15,wait.maxWait().toString());
            q.executeUpdate();
        }
        if(!load(c,wait.tenant(),wait.instanceId(),wait.key()).filter(wait::equals).isPresent())
            throw new IllegalStateException("retained wait profile differs from registration");
    }
    static Optional<InboxWait> load(Connection c,String tenant,Ulid instance,String waitKey) throws SQLException {
        try(var q=c.prepareStatement("select p.identity_digest,p.application_id,p.engine_instance_id,p.namespace,p.definition,p.version,"
                +"p.task_position,p.iteration,p.route,p.event_type,p.filter_digest,p.max_wait,d.identity_digest "
                +"from inbox.wait_profile p join inbox.delivery d using(tenant,instance_id,wait_key) "
                +"where p.tenant=? and p.instance_id=? and p.wait_key=?")) {
            q.setString(1,tenant);q.setString(2,instance.value());q.setString(3,waitKey);
            try(var r=q.executeQuery()) {
                if(!r.next())return Optional.empty();
                try {
                    var wait=new InboxWait(tenant,instance,r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),
                            r.getString(7),r.getInt(8),r.getString(9),r.getString(10),r.getString(11),Duration.parse(r.getString(12)));
                    if(!wait.digest().equals(r.getString(1))||!wait.digest().equals(r.getString(13))||!wait.key().equals(waitKey))
                        throw new IllegalStateException("retained wait profile identity differs");
                    return Optional.of(wait);
                } catch(RuntimeException invalid) {
                    throw new IllegalStateException("retained wait profile is invalid");
                }
            }
        }
    }
}
