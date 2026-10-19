package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

/** How the payload of an audit event is kept (retention rule, M15). */
public enum RetentionForm {

    /** Every payload field is stored in the event, readable through the query API. */
    STORE_INLINE,

    /** Named field paths are replaced by a salted hash in the event; other fields are stored in the event. */
    STORE_REDACTED,

    /**
     * The payload is kept in the external payload store. The event keeps a reference id and a content digest only,
     * so the payload can be deleted later without breaking the chain.
     */
    STORE_REFERENCE_ONLY
}
