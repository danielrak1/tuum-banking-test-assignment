# Retro notes (raw, collected during the build)

Input for Stage 6 (`docs/retro.md`). These were collected by the coach session as they came up; they're
unpolished on purpose. Sorted by theme, with the task where each happened.

## Agents and black-box testing
- **test-writer found real bugs by testing the spec, not the code.**
  - Task 2: lenient `UUID.fromString` accepted malformed IDs (a short group was padded into a
    *different* valid UUID). This affected every path-ID endpoint.
  - Task 3: `10.500` was rejected because ADR-0001 contained a false claim about library behaviour
    ("the validator strips trailing zeros"). An AI wrote the claim in Stage 2, and neither the coach
    nor the red-team caught it. A black-box test did.
- **test-writer can't read ADRs, which exposed that design.md is the contract** and the ADRs are only
  the reasoning. Rules that lived only in an ADR were invisible to the tests.
- **An agent bent a rule under pressure** (task 2): it commented out an assertion while debugging,
  then restored it. It reported this honestly. Fix: an explicit rule in `test-writer.md` ("never
  edit, comment out or remove an assertion, even temporarily").
- **Agents flagging their own deviations** (task 1 summary, task 2 report) made the review fast.
- **The session corrected its own earlier claim** ("the guard is a loud failure"), with Spring source
  as evidence (task 2 review, finding C).
- **The agent improved on the coach's wording**: "per balance", not "per account" (Stage 2 fix).
- **Agents dropped instructions between the request and the plan** (task 2: the review pauses and
  the black-box rule were missing). Plan mode caught both.

## Reviews
- **A domain-specific reviewer found a bug three general reviews missed** (post-build audit). The
  outbox poller sent a whole batch before waiting for confirms, so a row after a nacked one could be
  acked and enqueued first. That broke per-balance order, and it turned a "stalled" poison row into a
  flood of duplicates. The task 4 plan review, `/code-review` and silent-failure-hunter all missed it;
  `banking-reviewer` found it on its first run, checking against ADR-0003's ordering rule. The fix
  waits for each confirm. A test forces a real nack (a `reject-publish` queue with max length 0) and
  fails against the old code. Running that test also showed that a nacked message can still reach
  the queues that accepted it.
- **spec-checker's one false positive came from stale context**: it reported CLAUDE.md as out of
  date because it trusted the copy in its session context over the file on disk. Its definition now
  says to read every file from disk.
- **Independent reviewers agreeing raised confidence** (task 2: `/code-review` and
  silent-failure-hunter both found B, D and E).
- **The broad review found the worst bug** (task 3, finding A: a 406 *after* commit meant a client
  retry double-debited). The scoped review that had been suggested to save cost would have missed
  it, because the bug was in content negotiation, not in the money code.
- **Finding A is the concrete argument for idempotency keys**: any failure after commit (a dropped
  connection, a serialisation error) has the same effect.
- **The fix → review → fix loop** (task 4): the manual check led to backoff, the review found bugs
  in the backoff, and the rework fixed them. Rule adopted: one review round per task, then only
  high-severity findings reopen it.
- **The review discovered head-of-line blocking** (task 4, SF-1): a poison row stalls all later
  events. Recorded as a decision (order over availability), not a bug.

## Manual checks still earn their place
- **The automated tests paused the broker; a real outage stops it** (task 4). Only the stop showed
  the Spring AMQP log flood (about 5 lines a second) and the per-poll DB work.
- **Swagger showed `0` instead of `0.00`** (task 2): a live example of the ADR-0001 "JS clients"
  consequence. JavaScript drops trailing zeros.
- **48 messages survived a broker restart** (task 4), proving durable and persistent delivery.
  No automated test covers this.
- **The outbox backlog drained on first start** (task 4): rows written since task 2 were published
  the moment the poller existed.

## Process and the SDLC flow
- **`intent.md` was reviewed in chat before it was written**, and scope decisions came out of that
  review (unknown currency, no front end, Swagger, "events never lost").
- **Design docs were dense** (design.md plus 4 ADRs ≈ 5k words for 4 endpoints). The ADR template
  was heavier than the project needed (rows like "Cost: None", "Team familiarity").
- **Stage 2 was nearly skipped**: the IntelliJ session was "ready to code" with no design files
  committed. The design existed only in chat history.
- **Plans saved to `docs/plans/`** let a fresh session resume after a break, or after the usage
  limit.
- **PR size**: PR #2 was +1,870 lines in 40 files, because it also built the shared infrastructure
  and absorbed the review fixes.
- **Vertical slices**: errors and outbox rows were built with each endpoint, not deferred, so the
  `add-endpoint` skill learned the full pattern.
- **Deferrals were written down** (in `docs/sdlc-plan.md` or as `@Disabled` tests) so they show up
  in every test report.

## Environment flakiness (Stage 4B)
- **A Testcontainers Ryuk flake:** the first `./gradlew check` right after the load runs failed 188 of
  204 tests with `Could not connect to Ryuk at localhost:52022`. The re-run, with no changes, was
  green. Docker had probably not recovered yet from minutes of sustained load. Before blaming the
  code, read the root cause (`grep 'Caused by'` over `build/test-results`).
- **Slow Docker container starts:** late in the session, `compose up --wait` took 805 s inside
  `verify`. Every build step finished in about 1 s; the time went in starting containers. One perf
  smoke run's app container started 12 minutes after Postgres. Earlier the same steps took about
  20 s. Docker Desktop was likely worn down by the 1.1 M-event load runs. Restart Docker Desktop
  between heavy load runs and timing-sensitive checks, and don't read slow starts as a regression.

## Hooks (Stage 5)
Three gates in `.claude/settings.json`, scripts in `.claude/hooks/`. Each was triggered once on purpose
(2026-10-06), seen blocking, then reverted.
- **Gate 3, committed migration edit (PreToolUse, Edit|Write|MultiEdit).** An `Edit` that appended a
  comment to `V2__outbox_payload_json.sql` was blocked before it touched the file: "Blocked:
  src/main/resources/db/migration/V2__outbox_payload_json.sql is a committed Flyway migration…".
  Pass case: `Write` and then `Edit` on a new `V3__hook_trigger_test.sql` both went through (file deleted
  after). "Existing" means "in HEAD", so a new migration stays editable until it is committed.
- **Gate 1, compile after a `.java` edit (PostToolUse).** An `Edit` that added
  `int hookTriggerTest() { return "not an int"; }` to `Existence.java` came back with "error: incompatible
  types: String cannot be converted to int" in 0.5 s. PostToolUse can't undo the edit; it makes the
  breakage loud at once. The `Edit` that reverted it compiled silently.
- **Gate 2, `./gradlew check` before `git commit` (PreToolUse, Bash).** A planted
  `HookTriggerTest` with `fail(...)`, then `git commit --dry-run` (a dry run, so a gate that failed open
  could not commit anything): blocked after 1 min 4 s with "205 tests completed, 1 failed". The first
  message did not name the test, because `-q` hides test names; the hook now lists failing classes from
  the JUnit XML. After deleting the test, the same dry run passed. Re-run after the review fixes: blocked
  in 1 min 5 s, now with "Failing test classes: com.danielrak.banking.HookTriggerTest".
- **Stale results could name the wrong test** (review finding). After a compile failure no new JUnit XML
  is written, so the old files would be listed. The hook touches a marker before `check` and lists only
  XML newer than it.
- **The hooks fail closed without `jq`** (review finding). Each script exits 2 with "Blocked: jq
  missing" before reading its input; before the fix, an empty input let everything through. Side
  effect: without `jq`, every Bash call is blocked, not only commits.
- **A wrong cost claim, caught by measuring.** The session reported that every commit, docs-only ones
  included, would pay about 1 min of `check`. The reviewer doubted it: docs aren't task inputs. Measured
  with `--console=plain`: all 6 tasks UP-TO-DATE, `check` in 409 ms, the whole hook in 1.0 s. A Bash call
  that isn't a commit costs the hook 22 ms.
- **A hook timeout fails open.** Claude Code treats a timed-out hook as a non-blocking error, so the
  commit would go through unchecked. The script stops `check` itself at 300 s and blocks; the
  settings.json timeout is 330 s. The 300 s comes from a measured `./gradlew check --rerun-tasks` of
  67 s, with room for slow Docker starts (see "Environment flakiness").
- **`pkill -P` leaves the Gradle daemon running after the deadline.**
- **Known gap: Bash edits bypass the file gates.** `sed -i`, a heredoc, `mv` or `rm` on a migration or a
  `.java` file never pass through gates 1 and 3. Gate 2 still catches a broken build at commit, but not
  an edited migration whose tests still pass. Deferred to CI part 2: fail when a `V*.sql` is modified,
  deleted or renamed compared with `origin/main`.
- **The Stop hook (item 4) was skipped.**

## CI (Stage 5)
`.github/workflows/ci.yml` (PR #11): `verify.sh` on Temurin 25, plus a `migration-guard` job on PRs.
- **`verify` stayed green with V1 edited**: every test and the compose stack start from an empty DB, so Flyway never compares a checksum. Only the guard caught it (run 37522190374).
- **setup-gradle's cache is read-only off `main`**: both PR runs logged "Gradle User Home cache not found" and saved nothing; the cache fills on the first push to `main`.
- **`check` takes about 2.6× longer on the runner**: 174 s and 179 s, against about 67 s locally.
- **`compose up --build` took 87 s every run**: the Dockerfile's BuildKit cache mount doesn't persist between runners.
- **No branch protection on a free private repo**: the protection and rulesets APIs return 403, so `migration-guard` can't be a required check and a red PR can still be merged.
- **`gh run list --commit` needs the full SHA**: a short SHA matched nothing, without an error, so a wait loop polled forever.

## Skills
- **The `add-endpoint` skill was revised after its first real use** (task 3): 14 gaps, including
  `Location` without a GET, 422 business exceptions, MyBatis `flushCache`, and test-writer not
  seeing ADRs.
- **Timing**: the skill was extracted after 1 of 4 endpoints, and its first real test (task 3) was
  also its last in this project. Its value would build up over many features on a real team.
- **Skill duplication risk**: copies of the code (the `codeFor` table) and of design.md (settled
  points) were trimmed back to pointers.
- **Rules that must be remembered became tests**: the `@NotFoundCode` convention test, the
  `@PathVariable` order check, and the strict UUID binder.

## Cost and usage
- **`/code-review` with no level reuses the last one typed**: it quietly ran 10 agents (task 3).
  Always pass a level, and scope reviews to the risky files.
- **Hit 100% of the session usage limit** at the end of task 3. Nothing was lost, because the work
  was committed and the plans were in files.
- **Mitigations**: a fresh session per task, `model: sonnet` for test-writer, splitting big tasks
  into parts with a local commit in between (task 4).

## Things to fix later (not yet scheduled)
- Each outbox WARN prints a full stack trace (about 40 lines, twice a minute during an outage).
  Log traces only for unexpected exception types.
- Balance overflow past `NUMERIC(19,2)` returns a 500 after two requests: a known limitation (§8),
  for the README.
- Idempotency keys (planned extension); finding A is the motivation.
- A dead-letter mechanism for poison rows: it would break per-balance ordering.
- RabbitMQ policies instead of queue arguments, for limits that can be changed later.
- Money as JSON strings rather than numbers, a trade-off to mention in the README.
