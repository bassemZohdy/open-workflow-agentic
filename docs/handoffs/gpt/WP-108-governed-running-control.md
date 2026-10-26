# WP-108 governed running control and JDBC bounds

Internal producer increment on merged canonical lookup #80. No CDI admission, graph adapter, default model allowance,
production governed root, V77..V80 activation or package/acceptance closure.

## Producer / consumer boundary

`JdbcResourceExecutionGate.openGoverned` requires an immutable association of the FULL canonical binding with the
trusted fence. This association is data, not verified identity; the future reviewed composition root must construct its
fence from the SAME private admitted credential via the four-argument live identity check and independently trusted
workload, plus current registry/full owner/task/lease/target/PDP and mandatory central audit. Neither a supplied lambda,
a binding hash nor stored execution data grants production admission.

Only a fresh, committed, fully acknowledged governed entry calls `executeGoverned` with a private-minted `Running`.
Per-run closures can carry this same control across threads; each node/output must call `requireCurrent`.
Full-entry mismatch, close, interruption or actual callback termination refuses. Close invalidates promptly but retains
the slot until the synchronous callback actually terminates. Returning an asynchronous value is not a termination
protocol; graph/SDK cancellation and worker joining remain a separate reviewed consumer deliverable.

The legacy component callback cannot receive governed control, and governed attempts cannot invoke the legacy callback.
Java serialization and Jackson serialization of Running/CredentialFence are rejected. No raw credential getters exist.
An ArchUnit production rule permits construction/governed use only at the exact future
`runtime.execution.GovernedExecutionRoot`, while legacy entry is forbidden even there. The root does not exist yet.
Independent negative fixtures exercise constructor, legacy entry/callback, fence association and misleading simple name.

## JDBC ownership and limits

Default owned-transaction limits: lock5s, statement15s, network30s; explicit positive whole-millisecond bounds up to5min,
lock<=statement<network. PostgreSQL local limits and network timeout preserve any stricter role/session values.
Unsupported bounds refuse before writing. Data-source acquisition, transaction-manager and external fences still need
their own independently reviewed bounds; these settings are not a global wall-clock deadline.

Save/restore network timeout, isolation and auto-commit before connection close and caller-transaction resume.
No callback follows failed commit acknowledgement, reset, close or resume. Failed rollback aborts/discards the connection
without switching to auto-commit (which could commit uncertain work); failures remain conservative UNKNOWN when writes
might have applied. Abort/reset failures cannot permit callback. No background worker or retry is introduced.

## Verification

Real private PostgreSQL tests cover a held parent lock (55P03), sleeping trigger statement timeout (57014), stricter
session settings, successful reset before callback, reset failure after actual commit, rollback failure after insert,
unsupported network bounds, retained entry without callback, exact pairing through real opaque identity handles and
canonical lookup, cross-thread close/actual termination and accidental serialization. Existing real JTA caller-rollback
coverage remains; its fixture now derives token issue time from PostgreSQL to avoid a JVM/DB clock-skew false refusal.

Final commands/results and peer verdict are recorded on the implementation PR. Production composition remains absent.
