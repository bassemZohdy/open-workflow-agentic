# ADR-0027: Identify retained audit projections and draft content

Status: Proposed contract foundation; production projection not implemented

Date: 2026-10-20

The effect journal is not the central hash-chain audit. An append may commit
before its reply is lost. A fresh event id on retry duplicates history; resealing
an existing id can fail without proving its exact previously committed content.

Use shared AuditSource/AuditReceipt and AuditDraftFingerprint v1 as specified in
docs/handoffs/gpt/WP-108-audit-contract.md. Source identity binds schema/scope/key/
finite transition/committed source sequence independently of content. Retain ONE
draft ULID, original microsecond time and content. Changed content for the same
source conflicts; central chain position/hash remain separately assigned.
Canonical draft bytes bind id/time/full actor/action/resource/selected chain/all
sorted attributes, excluding central sealing fields. Attribute keys use Java String natural order: UTF-16 code-unit order, which can differ from Unicode code-point or UTF-8 byte order for supplementary characters. Other-language implementations must reproduce that exact order. Refuse malformed text/finer
precision rather than normalizing. Existing AuditHash encoding is unchanged.

This data contract does no I/O and grants no authority. GPT later retains source/
outbox atomically with effect facts; Claude appends idempotently, returning the
same stored receipt on an exact retry. Unknown reply requires same-identity
readback. Mandatory audit still precedes target entry; current authority/execution
checks follow all blocking work. Source binding SQL, producer/consumer/relay,
concurrency/commit-loss/restart/backup/retention and actual governed Flow evidence
are adoption gates. No applied migration or package/acceptance status changes here.
