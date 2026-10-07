# Retro: the AI-native SDLC run on the account service

Stage 6 of [`sdlc-plan.md`](sdlc-plan.md). It distils the raw [`retro-notes.md`](retro-notes.md) and a
`session-report` over every Claude Code transcript for this project (2026-10-02 → 2026-10-07).

## Effort

Active time means time between transcript events, with gaps over 5 minutes left out (the session-report
rule). It counts the main sessions only. Stage boundaries are the merge commits. Tokens include subagents.

| Stage | Active | Tokens | Prompts | Subagent runs | Delivered |
|---|---|---|---|---|---|
| 1. Plan | 0.6 h | 7 M | 37 | 0 | `intent.md`, `sdlc-plan.md` |
| 2. Design | 0.5 h | 4 M | 13 | 2 | `design.md`, ADRs 0001–0004 |
| 3. Build | 5.1 h | 153 M | 150 | 58 | PRs #1–#7: 4 endpoints, outbox, errors, Docker, review agents |
| 4. Test | 1.4 h | 47 M | 38 | 5 | PRs #8–#9: contract check, `verify`, k6 load test |
| 5. Deploy | 0.8 h | 9 M | 34 | 3 | PRs #10–#11: three hooks, CI |
| **Total** | **8.4 h** | **≈ 220 M** | **≈ 270** | **68** | 11 PRs over 5 calendar days |

- **Build took 61% of the time and 69% of the tokens.** The four endpoints were built as vertical
  slices, each with its own review round, so most of the review cost also sits in Build.
- **Cache hit rate was 95.5%.** Only 12 prompts broke the cache by more than 100 k tokens.
- **Subagents used 20% of all tokens.** `general-purpose` made 47 of the 68 calls (33 M tokens), more
  than all the named agents combined. The named ones (`test-writer`, `banking-reviewer`,
  `spec-checker`) came late, and they were cheaper per call.
- **The three most expensive prompts were stage kickoffs** (13.5 M, 9.1 M and 7.3 M tokens). Each
  started a long session that read the plan, built, reviewed and fixed.

## Outcome

- **Tests:** 204 tests on Testcontainers (real Postgres and RabbitMQ). Coverage is **95.9% of lines and
  89.3% of branches**; the gate is 80% for both. The contract check covers every request and error in
  the PDF, plus the events.
- **Throughput** ([`performance.md`](performance.md)): about **7,400 transactions/s** spread across
  accounts (p95 13 ms), and **2,300/s** on one hot account (p95 32 ms). The outbox is the limit: with
  one confirm per event, events keep up only below about 575–1,650 TPS. Above that they arrive late,
  but none are lost.
- **CI:** about 4.5 min per PR. `check` takes 170–180 s there, about 2.6× longer than on the laptop.

## Review findings by category

Every PR got one review round. Counts come from the PR descriptions.

| Category | Findings | Worst example |
|---|---|---|
| API contract and input edge cases | ≈ 15 (PR #2: 5, PR #3: A–J) | **Finding A:** an `Accept: application/xml` debit committed, then returned 406. A client retry double-debits. This was the only finding that lost money. |
| Event ordering and delivery | 3 | **banking-reviewer F1:** the poller sent a whole batch before the confirms, so a nack reordered one balance's events. Three earlier reviews missed it. |
| Stale or overclaiming docs | ≈ 12 | A false ADR-0001 claim about trailing zeros. Black-box tests found it, not reviewers. |
| Scripts, hooks and CI | ≈ 20 | A hook timeout fails open; CI cancels runs on `main` (PR #11 finding 2, still open). |

- **The domain reviewer earned its cost.** `banking-reviewer` found the ordering bug on its first run,
  because it checks against ADR-0003's rule rather than looking for generic bugs.
- **Black-box tests found what reviews missed.** `test-writer` found the lenient UUID parsing and the
  false ADR claim, because it tests the spec and never reads the code.
- **Both bugs that could lose money or misorder events (A and F1) were fixed in the PR that found them.**

## Hooks

Three gates went live in Stage 5. Each was triggered once on purpose and blocked as designed: 4 blocks
in all, counting gate 2's re-run after its review fixes. No block was unplanned, but the gates only ran
for the final day.

The review of the hooks mattered more than the gates themselves. It found three ways they could fail
open:
- stale JUnit XML named the wrong test;
- without `jq`, every input passed;
- a timeout let the commit through.

All three are fixed. Bash edits still bypass the file gates. The CI migration guard covers that gap
for migrations, but only on PRs, and it can't be a required check on a private free-plan repo.

## Where Claude needed steering

- **Skipping ahead.** Stage 2 was nearly skipped, because the session was "ready to code" with the
  design only in chat. In task 2, the plan dropped instructions that had been given in the request.
  Plan mode caught both.
- **Confident wrong claims.**
  - An ADR stated library behaviour as fact.
  - The session said every commit would pay a minute of `check`, but it was 1 s when up to date.
  - It called a guard "a loud failure" when it wasn't.

  Measuring or reading the source corrected each one. None was corrected by argument.
- **Bending a rule under pressure.** An agent commented out an assertion while debugging, and said so.
  A rule in `test-writer.md` now forbids it.
- **Cost defaults.** `/code-review` with no level reused the last one and ran 10 agents. The session
  limit was hit once, at the end of task 3.
- **Commit discipline.** The user had to ask to see the review findings before a commit. That is now
  a standing rule in memory.

## What to change next time

1. **Write named agents early.** A narrow `test-writer` or reviewer on `sonnet` costs less than
   `general-purpose`, and does the job better.
2. **Start a fresh session per task, with a plan file.** This keeps kickoff prompts small, and lets a
   session survive the usage limit.
3. **Keep design.md as the contract, and short.** The tests can't see the ADRs, so a rule that lives
   only in an ADR goes untested. The ADR template was heavier than four endpoints needed.
4. **Turn every rule that must be remembered into a test or a gate**, as was done for
   `@NotFoundCode`, the UUID binder and migrations. Then prove each gate by making it fail.
5. **Always pass an explicit review level, and scope reviews by risk, not by file count.** Finding A
   was in content negotiation, not in the money code.
6. **Close the review loop before merging.** PR #11 was merged with its 8 findings open (below).

## Carried forward

- **PR #11 review, still open:**
  - the guard doesn't run on pushes to `main`;
  - `cancel-in-progress` also cancels runs on `main`;
  - a `T` (type change) status passes the guard;
  - annotations break on paths with spaces;
  - small doc drift in `sdlc-plan.md` and `test-plan.md`, and a missing CI line in `CLAUDE.md`.
- **From the notes:**
  - idempotency keys (motivated by finding A);
  - a 500 on balance overflow;
  - full stack traces in outbox WARN logs;
  - a dead-letter path for poison rows, which conflicts with per-balance order;
  - RabbitMQ policies instead of queue arguments.
- **Stage 6, still to do:** the README and the `engineering:deploy-checklist` pass.
