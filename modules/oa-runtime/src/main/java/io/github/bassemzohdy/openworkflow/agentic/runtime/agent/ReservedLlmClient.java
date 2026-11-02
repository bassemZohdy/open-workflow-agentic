package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import java.time.Instant;

/**
 * Trusted transport for a reserved model call. Implementations bind model, provider, price ceiling and retry
 * configuration into their stable configuration fingerprint. Their quoted maximum is a binding upper bound for the
 * complete physical request, not a best-effort estimate, and remains guaranteed through the resulting call. They make at most one physical attempt, enforce the
 * supplied output-token limit and enforce the absolute deadline against the reservation database's clock, including
 * clock-skew margin and transport setup. They must finish synchronously before returning. A string result is not
 * measured usage. Implementations without those guarantees must not implement this contract.
 *
 * This interface does not establish approval; trusted composition must supply the implementation and approved
 * profile. No production provider currently implements this contract.
 */
public interface ReservedLlmClient extends LlmClient {
    String reservedConfigurationFingerprint();
    /** Local conservative upper bound for the entire request, including input and output; no I/O or side effects. */
    long maximumCostMicros(String prompt, int maxOutputTokens);
    String completeReserved(String prompt, int maxOutputTokens, Instant reservationDeadline);
}
