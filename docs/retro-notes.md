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
