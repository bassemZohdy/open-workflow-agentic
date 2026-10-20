# WP-108: require process recovery beside real provider tests

Owner: GPT (build/CI/effect execution). Reviewer: Claude. Preparation on
gpt/WP-108-effect-authority; #49 remains the sole GPT implementation PR.

The previous manual keycloak-interop matrix selected only its real provider
IT, so it could not prove EffectCrashRecoveryIT against newly prepared V70
and effect tracing. Full PR CI already ran that original acceptance case;
that earlier main result cannot be transferred to a changed preparation head.

The matrix now also explicitly selects EffectCrashRecoveryIT in BOTH lanes.
Provider versions/features, actual Java provider tests and their strict checker
are unchanged. Before starting the isolated server, preflight requires both
real test source files. After verify, --effect-recovery requires the actual
process-test XML, exact suite/class/required method, correct lane property,
consistent nonnegative counters and no failed/errored/skipped case. Duplicate
reports/cases, malformed/oversized/empty XML and missing cases fail closed.
Provider evidence cannot substitute for process evidence or conversely.
Artifacts retain failsafe reports and child process logs for inspection.

The executed Java test is the original
processDeathAfterTargetCommitDoesNotDuplicateEffectOrCompensation, annotated
T03/M06/M07. It launches separate Java workers against PostgreSQL. A worker
abruptly halts77 after the target charge commits but before ledger outcome;
recovery confirms one charge. Another halts77 after refund target commit but
before compensation outcome; repeated recovery proves one refund and retained
correct states. Both charge/refund target commits are independent of ledger
updates. Child deadlines remain bounded and the test cleans only its own
random target schema. It self-migrates, so it does not require another boot
test to create the ledger. The Java test/assertions are unchanged in this task.

This is existing core process-death recovery evidence, NOT an actual governed
Flow/credential/registry-owner/partition test, SIGKILL/VM reboot, process-wide
trace flush, external provider target or every T03/M06/M07 remaining gate.
The ledger/effect target in this test is explicit test plumbing. No acceptance
marker, package status or production consumer is promoted by adding CI.

Local tools76 pass, including8 new negative/positive evidence-check cases;
both provider/process preflights and generated/diff checks pass. Existing
combined-main preparationc18f08b separately passes33 selected plain Java cases
plus application reactor compile. Actual new selected process execution still
requires fresh Linux CI on this CI-change head; no local PostgreSQL/native
runtime is available and fabricated XML selftests are never product evidence.

The required live current-authority, manual OPERATION/oa.recover, exact central
audit, governed adapter/Flow retry, old-owner/partition and complete distributed
tracing gates remain in WP-108-authority.md/WP-108-tracing.md. Bindings/migration
allocation and Claude's code are unchanged. Do not merge this preparation while
#49 is active; its final PR still needs exact Claude review and full CI.
