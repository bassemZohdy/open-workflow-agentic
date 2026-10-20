package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

import java.time.Instant;

/** Persisted work awaiting authoritative reconciliation or an authorized operator integration. */
public record ManualEffect(EffectKey key, String reason, Instant firstSeen, Instant lastSeen) { }
