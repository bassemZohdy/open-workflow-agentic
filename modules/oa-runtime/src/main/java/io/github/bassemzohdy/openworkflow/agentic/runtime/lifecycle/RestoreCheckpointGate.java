package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

/** Repair committed receipt acknowledgements BEFORE starting restored SDK work. This grants no user authority. */
@FunctionalInterface
public interface RestoreCheckpointGate {
    /** False means more bounded repair work remains; throwing means unavailable. Either must hold restored work. */
    boolean prepare(InstanceRecord instance);
    RestoreCheckpointGate ALLOW=instance->true; // Isolated engine fixtures without persisted inbox receipts.
}
