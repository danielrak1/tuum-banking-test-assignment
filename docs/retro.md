# Retro: the AI-native SDLC run on the account service

Stage 6 of [`sdlc-plan.md`](sdlc-plan.md). Sources: the raw [`retro-notes.md`](retro-notes.md), the PR
descriptions and plan files, CI runs, and a `session-report` over this project's Claude Code
transcripts (2026-10-02 → 2026-10-07: 12 PRs over 4 working days).

## Effort

Active time counts main sessions only, skipping gaps over 5 min. Prompts include slash commands.
Tokens include subagents. Each stage ends at its last merge.

| Stage | Active | Tokens | Prompts | Subagent runs | Delivered |
|---|---|---|---|---|---|
| 1. Plan | 0.6 h | 7 M | 37 | 0 | `intent.md`, `sdlc-plan.md` |
| 2. Design | 0.5 h | 4 M | 13 | 2 | `design.md`, ADRs 0001–0004 |
| 3. Build | 5.1 h | 153 M | 150 | 58 | PRs #1–#7: four endpoints, outbox, errors, Docker, review agents |
| 4. Test | 1.4 h | 47 M | 38 | 5 | PRs #8–#9: contract check, `verify`, k6 load test |
| 5. Deploy | 0.8 h | 9 M | 34 | 3 | PRs #10–#11: three hooks, CI |
| **Total** | **8.4 h** | **≈ 220 M** | **272** | **68** | PR #12 (CI follow-up) is Stage 6 |

- **Build took 61% of the time and 70% of the tokens**, review included: each endpoint was a slice
  with its own review.
- **The cache hit rate was 95.5%.** The three costliest prompts were stage kickoffs (13.5, 9.1 and
  7.3 M tokens).
- **Subagents used 20% of the tokens.** `general-purpose` made 47 of the 68 calls, at 693 k tokens per
  call. Per call, `banking-reviewer` (267 k) and `spec-checker` (399 k) cost far less, and `test-writer`
  (630 k) a little less.

## Outcome

- **Tests:** 204 on real Postgres and RabbitMQ. Coverage: **95.9% of lines, 89.3% of branches** (gate
  80%). The contract check passes.
- **Throughput** ([`performance.md`](performance.md)):
  - **≈ 7,400 transactions/s** spread over 1,000 accounts (p95 13 ms);
  - **≈ 2,300/s** on one hot account (p95 32 ms);
  - events keep up only below ≈ 575–1,650 TPS. Above that they arrive late, but none are lost.
- **CI:** 3.8–5.0 min per PR run. `check` took 169–179 s on a cold Gradle cache and 124 s on a warm
  one.

## Review findings by category

Counts come from the PR descriptions and plan files. Doc fixes aren't counted.

| Category | Findings (source) | Worst |
|---|---|---|
| API contract, input edge cases | 18: PR #2 (6 fixed, 2 deferred), PR #3 A–J (10) | PR #3 **A**: an XML `Accept` header got a 406 *after* the debit committed, so a retry double-debits |
| Event delivery, outbox | 8: PR #4 SF-1–6 and CR-1, PR #7 F1 | PR #7 **F1**: a nack let later events of one balance overtake it. Three earlier reviews missed it |
| Scripts, hooks, CI | 20: PR #8 (4), #9 (5), #10 (2), #11 (9) | PR #10: without `jq`, every hook let its input through |
| No findings | PR #5, #6 (`/code-review low`) and the PR #9 query fold | |

- **Specific checks beat general ones.** `banking-reviewer` found F1 by checking against ADR-0003.
  `test-writer` tests only the spec, and found lenient UUID parsing and a false claim in ADR-0001.
- **A and F1 were fixed in the PR that found them.** A file-scoped review would have missed A: it was
  in content negotiation, not the money code.

## Hooks and gates

- **Blocks:** 3, all planned:
  - gate 3 (a migration edit) once;
  - gate 2 (`check` before a commit) twice: the trigger test, then a re-run after the review fixes.
- **Gate 1** (compile) runs after the edit, so it can't block. It reported the planted error in 0.5 s.
- **The CI guard** went red on a deliberate V1 edit. `verify` stayed green on the same edit, because
  every test DB starts empty.
- **Gaps:**
  - Bash edits bypass gates 1 and 3.
  - The CI guard covers PRs only.
  - With no branch protection on a free private repo, a red PR can still be merged.

## Where Claude needed steering

- **Racing ahead of the process.** Claude was ready to code before the design was committed. A plan
  dropped instructions given in the request. Plan mode and pauses caught both.
- **Stating guesses as facts.** These included a library behaviour written into an ADR, a hook's cost
  ("a minute per commit"; it was 1 s), and a guard's failure mode. Each was doubted, then settled by
  measuring or reading the source.
- **Bending a rule under pressure.** An agent commented out an assertion while debugging, and said so.
  `test-writer.md` now forbids it.
- **Defaults that cost.** `/code-review` with no level reused the last one and ran 10 agents. One
  session hit the usage limit.
- **Committing too eagerly.** The user had to ask to see review findings before a commit. That is now a
  standing rule.

## What to change next time

1. **Write narrow, named reviewers early**, each tied to one set of rules. They cost less per call than
   `general-purpose`, and they found the two worst bugs.
2. **Start a fresh session per task, with a plan file.** This keeps kickoff prompts small, and lets work
   survive the usage limit.
3. **Keep `design.md` as the short contract.** Tests can't see the ADRs, and the ADR template was heavier
   than four endpoints needed.
4. **Turn each rule that must be remembered into a test or a gate**, then prove it by making it fail.
5. **Always pass a review level, and scope by risk, not by file.**
6. **Close the review loop before merging.** PR #11 was merged before its 9 findings were acted on.
   PR #12 fixed 5 of them and this PR one more; the other 3 were skipped on purpose.

## Carried forward

- **Product:**
  - idempotency keys (finding A);
  - a clean error instead of a 500 on balance overflow;
  - shorter outbox WARN logs;
  - a dead-letter path, which conflicts with per-balance order;
  - RabbitMQ policies.
- **CI, skipped:** the guard on pushes to `main`, annotations for paths with spaces, SHA-pinned actions.
- **Stage 6:**
  - done: this retro and `/revise-claude-md`;
  - to do: the README;
  - dropped: the deploy-checklist (no deploy target).
