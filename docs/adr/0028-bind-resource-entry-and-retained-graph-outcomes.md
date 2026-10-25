# ADR-0028: Bind resource entry and retained graph outcomes

Status: Proposed contract foundation; durable gate and consumer not implemented

Date: 2026-10-20

A stable invocation thread and a refreshed credential do not prevent repeated
graph work. The pinned LangGraph4j1.9.5 PostgresSaver releases threads on errors
as well as completion, permits multiple physical runs for a thread name, stores
serialized state as Base64 TEXT, and supports checkpoint updates. Reading a
result by calling the graph again or selecting a convenient latest checkpoint
can repeat model/tool work or return another result.

Retain ResourceExecutionEntry as the FULL ResourceCallScope plus a positive
execution epoch. The call key and thread remain derived from the scope. Entry
data is never a capability: a durable runtime gate must serialize by call key,
commit the one fresh NO_ENTRY to ENTERED transition and invoke only its newly
acknowledged callback. A constructed entry, changed epoch, uncertain commit,
empty checkpoint store or expired lease cannot authorize a repeat. UNKNOWN
requires explicit governed resolution; it is not a new run or FAILED.

ResourceExecutionOutcome pins that exact entry, the explicit interim research
backend/serializer profile, physical run UUID, checkpoint UUID, content type and
content fingerprint. The trusted producer verifies actual terminal run/parent/
checkpoint structure in a consistent read before retaining an association.
Released status or field presence is insufficient. A changed/missing row or
content refuses; no latest-row fallback. Value validation and requireMatches
check data consistency only, not storage authenticity, completion or authority.

ResearchOutputFingerprint v1 binds the EXACT stored representation with
CanonicalEncoder domain oa.agent.research.output.v1, text(application/octet-stream),
text(the retained canonical Base64 state_data), SHA256 lowercase. Text is strict
UTF-8 preceded by its four-byte big-endian byte length. It neither hashes decoded
stream bytes nor deserializes or reserializes Java objects. The profile pins
LangGraph4j1.9.5/PostgresSaver schema1.1/ObjectStream. Encoded state must be
nonempty, canonical padded standard Base64 and at most4MiB. Adapters must bound
SQL retrieval before loading it too. Unsupported media/profile/size/encoding
refuses. The limit is for this interim profile; no silent truncation or general
agent/checkpoint/retention quota claim follows.

Pass a private transient ResourceExecutionFence separately, bound to the exact
entry. It composes current registry/full owner/definition/task/approved target/
execution ownership AND current signed expiry/grant/scope/policy. Check after
blocking work before entry, every new model/tool node and private-output release.
Async nodes need the same bounded private context across executor threads;
missing/closed context refuses. Never persist credentials or the fence in state
or metadata, and never use an ambient ThreadLocal as propagation.

The output fingerprint helper is already merged. This increment supplies JDK-only
entry/outcome/guard signatures, four contract tests and compatibility with the
actual pinned serializer. The three helper tests remain in their existing suite;
no second helper or duplicated representation suite is introduced. No schema,
gate, identity adapter, graph change or dispatch is activated.
Durable one-entry/association storage, current composite fence, exact read-only
inspection, one-entry races, actual commit-reply loss/process/owner cancellation,
stable governed UNKNOWN decisions, full occurrence production and real workflow
HTTP/recovery tests remain adoption gates. Package and acceptance status stay partial.
