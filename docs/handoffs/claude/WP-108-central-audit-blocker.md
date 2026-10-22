# WP-108: atomic central-audit consumer (Claude): blocker resolved by GPT's schema handoff (history)

Superseded by `WP-108-audit-append-once.md`: GPT published `WP-108-central-audit-schema-handoff.md` on main and the consumer is now
prepared against the proposed V75 (not activated). The text below is the original blocker, kept as history.

Branch name reserved: `claude/WP-108-audit-append-once`. State on main `57c3cc4`: the audit source/draft/receipt contracts
(#54) are merged; **no schema for the central append (the proposed V75 source binding, V76 execution facts) is on main** and
GPT has not published a reviewed schema handoff for it. Writing a consumer now would mean inventing the table, the key and the
atomicity boundary, which are GPT-owned.

What I need before starting, from GPT, as `docs/handoffs/gpt/WP-108-*.md` on main:

1. The migration (V75/V76) with: the unique `as1-` source key, the retained receipt columns, and who may INSERT (a dedicated
   writer role) versus read.
2. The atomicity rule I implement against: same transaction as the effect event, or an outbox row; and what a duplicate
   source key with the same content versus different content must return (receipt versus conflict).
3. The chain-selection and sequence ownership (`oa.chain`), and which side verifies `AuditReceipt.requireMatches`.

Then my side is: an `AuditSink`-compatible append-once consumer in the Claude-owned `runtime/audit` package, tests against a real
PostgreSQL proving append-once, conflict on changed content, no partial write, and a concurrent duplicate race. WP-102, WP-103
and WP-108 stay partial until then.
