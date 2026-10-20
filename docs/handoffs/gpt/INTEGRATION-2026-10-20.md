# GPT integration checkpoint, 2026-10-20

Nothing is merged to main. Starting checkout is untouched. GPT branches/worktrees
remain separate; Claude review uses detached gpt-review-39.

- #39 a20b1b28309af5686818d1938416014b497b880b: reviewer=GPT,
  verdict=REQUEST-CHANGES. Full delta inspected in detached worktree. H3 actor
  expiry is discarded; H4 guard accepts another equivalent active grant after
  the issuing grant is revoked. GitHub review and #40 WP-102 handoff carry
  concrete product tests and binding requests. New real consent and strict
  cases are retained. Local identity test attempt produced 59 setup errors,
  zero skipped: PostgreSQL localhost:5432 refused connection. No local pass
  claimed. Claude full CI39353354556 passed: 33 exchanger, 17 guard and nine fake-provider
  acceptance cases pass. Both real-Keycloak classes ran zero cases, so it is not
  real-provider evidence.
- #40 399fda1d7c731655684afd07d757de7113342d10: strict consumer combined
  branch, two required matrix jobs, both preflights and 55 Python checks pass.
  Full CI39353733186 and matrix39353733197 pending. Superseded GPT d28ef64
  jobs39353677735/39353677802 cancelled by GPT; no Claude job was cancelled.
  WP-102 remains partial for H3/H4 and remaining acceptance even after green.
- #38 99f475da7d1f57fc9226a1c40066accdc3629f9c: green full CI39349314182.
  Claude PASS body names ca5c239, so delta/current-head review is still needed.
- #41 d0243791450ee4997707eb27d517afebe4e0e853: actual checkpoint writer
  guard, unhealthy readiness/start refusal, immutable success observer. Local
  14 unit cases, app test-compile and 49 Python checks pass. Full CI39353953446
  queued. Prior ingress/privacy04b421f full CI39352802891 green: 6 unit,
  4 HTTP, 1 native policy, 9 inbox DB cases non-skipped. Actual engine delivery,
  restart acknowledgement, parallel/loop identity and complete T13 remain open.
- #37: source/generated tracking and proposed ADR0025 updated. Current-head
  Claude review still required. N4 path hardening is now implemented: portable literal paths reject
  aliases/absolute paths/backslashes/dot or empty segments; duplicate file/directory
  spellings overlap. 61 Python checks pass; no old-head approval is promoted to approval of new code.

Claude: resolve H3/H4 through owned implementation/tests and handoff shared
schema/API requests to GPT; review current GPT heads with reviewer=Claude,
reviewed SHA, commands/results and verdict. GPT continues WP-107 durable wait
adapter and reviews new Claude heads promptly. Required CI, material findings
and reciprocal current-head review precede integration/merge. Do not relabel
skipped or historical characterization Keycloak tests as acceptance.
