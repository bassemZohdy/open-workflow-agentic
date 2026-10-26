# WP-102 bound credential pairing

Base `6ef3565` (identity PR 77 merged). Branch `claude/WP-102-bound-credential-pairing`. Not pushed; no PR. WP-102 remains partial.

## Why

An equal `ResourceExecutionEntry` does not say which credential was admitted: two independently admitted credentials for the
same full scope and entry are indistinguishable by entry. Before any production composition, the exact SAME private admitted
credential must match the JDBC resource-call binding.

## API (minimum handshake)

- `AdmittedCall.bindingKey()` and `BoundCall.bindingKey()`: the derived `ResourceCallBinding.key()` (`rb1-` + domain-separated
  sha256 of issuer+jti). Historical attribution DATA like `callKey()`. It exposes no issuer, jti, token, claim, bearer or
  permission, is not authority, and mints/binds/revives nothing. It stays readable after close.
- `LiveIdentityCheck.requireLive(BoundCall, ResourceExecutionEntry, Subject, ResourceCallBinding expectedBinding)`: everything
  the 3-arg method does, plus the candidate (DATA a canonical store found by `bindingKey()`) must equal the PRIVATE admitted
  binding in every field (issuer, jti, issuing grant, full scope, issued, expiry). The comparison happens right after the
  witness-open check and before any store is read. A null or different candidate (including the same key with changed
  grant/scope/times) throws `WitnessRefusedException`. Fingerprint equality alone never suffices because the whole record is
  compared. The candidate is not retained.
- The 3-arg identity-only method is unchanged and compatible; production composition must use the 4-arg overload.
- No factory from data, no default bridge, no worker/ThreadLocal/state/config serialization. `AdmittedCall`/`BoundCall` stay
  non-serializable and redacted; the existing witness suite (default ObjectMapper/ObjectStream refusal) was re-run unchanged.

## Tests

`LiveIdentityPairingTest` (7 cases) on a private PostgreSQL database with the real guard and real `DelegationGrants`:
exact record passes and is repeatable (3-arg still passes); two credentials of the same scope/entry have different
fingerprints, own records pass, swapped records refuse despite the equal entry; forged records with the same key but altered
issuing grant, issued, expiry or scope refuse; null candidate refuses before any store read; wrong epoch, wrong workload, null
workload, wrong minter, per-call close and later grant revocation still refuse with an exact record; revocation during the
lookup still refuses after a passing pairing; the fingerprint stays readable after close and nothing is revived.

Command (JDK 21.0.12.1, Maven 3.9.11 offline, PG16 localhost:5432):
`mvn.cmd -B -o -pl modules/oa-runtime -am test "-Dtest=LiveIdentityPairingTest,LiveIdentityCheckTest,AdmittedCallWitnessTest,AgentResourceBindingAdmissionTest,AgentResourceAuthorityTest,AgentResourceUserFreshnessTest" "-Dsurefire.failIfNoSpecifiedTests=false"`
Result: 49 run (Pairing 7, LiveIdentityCheck 11, AdmittedCallWitness 18, BindingAdmission 6, Authority 4, UserFreshness 3), 0
failures/errors/skips. No full, native or process suites; no shared reset; CI has not run on this branch.

## Still future (not done here)

GPT canonical store lookup by `bindingKey()` and production composition (which must call the 4-arg overload); server-trusted
workload source; registry/task/lease/PDP/audit composite; JDBC bounds; actual graph/node/output and private async context.
No production activation and no package closure.
