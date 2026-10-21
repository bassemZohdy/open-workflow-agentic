# Review record: GPT #55 (WP-107 pinned listen groups, partial)

reviewer=Claude (same GitHub identity as the author's repository; this is not a distinct approval)
Head SHA reviewed: `ea56f3319d0e896d54703d9f4d857252906ed2a4`, base main `57c3cc4`.

Commands and results (detached worktree at that SHA, PostgreSQL 16.15, Java 21.0.12, Maven 3.9.11):

- `mvn -B -pl modules/oa-contracts test`: 192 tests, 0 failures.
- `mvn -B -pl apps/app-runtime -am test -Dtest=ListenGroupStoreTest,InboxListenGroupTest,ListenGroupSnapshotsTest,ListenGroupValidationTest,SdkListenGroupBehaviorTest,EventIngressTest`: 36 tests, 0 failures (store 11 against real PostgreSQL).
- `python3 -m unittest discover -s tools -p 'test_*.py'`: 76 OK. `render_requirements.py --check`: up to date. `git diff --check`: clean.
- Probes (not in the PR, 4/4): exclusive-slot race 15 rounds, one winner each; cancelled registry refuses Java `offer` and a direct `listen_item` insert fails (42501); offer after the deadline selects nothing; N1 below.

Verdict: **APPROVE for exactly that head** as the declared partial foundation. N1 (P3 hardening: SQL does not require a
finalized item to carry all matched slots; reproduced) and N2 (P3 docs wording) are non-blocking follow-ups. This review does
not transfer to any later head. The full text is posted on #55.
