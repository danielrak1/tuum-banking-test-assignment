# Stage 4, part B: Throughput (`perf/throughput`)

> **Status:** done 2026-10-06. Approved with two additions (drain the setup backlog before the warm-up; note the Hikari pool as a likely cap); outcome at the end.

## Context
Stage 4 (`docs/sdlc-plan.md`) has two items left, plus one carry-over:
- **Throughput:** a k6 run, through its Docker image, that measures TPS and p95. Success criterion 6
  (`intent.md`) needs a *measured* TPS estimate for the README.
- **The task 3 carry-over:** `TransactionService.create` makes three round trips before the update.
  `accountMapper.findById` maps the whole row, then `balanceMapper.exists` runs, then `applyDelta`.
  Fold the two checks into one `SELECT EXISTS …, EXISTS …`. `list` uses `AccountMapper.exists`
  instead of `findById`.
- **design.md §8, "Publish throughput":** the poller waits for each confirm before it sends the next
  row (ADR-0003), and "the Stage 4 k6 run shows whether this matters". So we measure outbox lag
  under load.

Out of scope: changing the publisher (we only record numbers and future work), pool or thread
tuning, balance overflow (still deferred).

## Decisions
1. **An isolated stack, like `verify`.**
   - `scripts/load-test.sh` brings up compose project `banking-perf` on its own host ports, and
     starts a fresh stack (`down -v`, then `up --build --wait`) for every run. A run never sees an
     earlier run's rows, and the dev and verify stacks are left alone.
   - k6 runs as `docker run --rm --network banking-perf_default grafana/k6` (version pinned) against
     `http://app:8080`. It stays inside the compose network, so Docker Desktop's host
     port-forwarding isn't part of the measurement.
2. **The k6 script (`scripts/k6/create-transaction.js`), one scenario per run (`SCENARIO=spread|hot`).**
   - **Seeding is its own k6 invocation** (`MODE=seed`):
     - `spread` creates 1,000 EUR accounts, `hot` creates 1;
     - each account is seeded with a large `IN`, so an `OUT` never hits `INSUFFICIENT_FUNDS`;
     - `handleSummary` writes the account IDs to `build/perf/accounts.json`.
   - **The script then waits until the outbox is empty.** It polls `outbox_event` until the count is
     0, so the measured lag is the load alone, not the setup backlog.
   - **Then the load invocation** (`MODE=load`) reads the IDs with `open()`, and runs the warm-up and
     the measurement.
   - **Load:** a closed model, `constant-vus`, 50 VUs (overridable with `VUS`).
     - 20 s warm-up (JIT, pools), then 60 s measured, as two k6 scenarios.
     - The numbers come from the `measure` scenario only, through tag-filtered thresholds.
     - Each request picks a random account (one account for `hot`) and alternates `IN`/`OUT` of
       small amounts.
   - **Checks:** status 201, with a threshold of >99.9% (a failed run is visible, not silently fast).
   - **Output:** `handleSummary` writes JSON to `build/perf/<label>-<scenario>-<n>.json`: TPS
     (rate of 201 POSTs), p50, p95, p99 and error rate.
3. **Outbox lag, measured the same way in every run.**
   - While k6 runs, the script samples the outbox once a second through `compose exec postgres psql`:
     `SELECT count(*), extract(epoch FROM now() - min(created_at)) FROM outbox_event`.
   - This gives the backlog and the age of the oldest pending event, which is the end-to-end publish
     lag.
   - After k6 stops, it keeps sampling until the backlog is 0. That gives the drain time, and so the
     publisher's own rate (events drained ÷ drain time) with no HTTP load competing.
   - Expected result: each transaction writes 2 events. If 2 × TPS is more than the confirm-bound
     rate (about 1 event per broker round trip with persistent messages), the backlog and the lag
     grow linearly for the length of the run. The data shows whether that happens.
   - CSV goes to `build/perf/…-outbox.csv`, and the peak backlog, peak lag and drain rate go into
     the summary.
4. **Before and after, three runs of each.**
   - Run on `main`'s code first (before), then after the query change (after): 2 scenarios × 3 runs
     each, and we report the median.
   - The script takes a `LABEL` (before or after), so both result sets sit side by side in
     `build/perf/`.
5. **The existence fold.**
   - **`AccountMapper`:**
     - `exists(UUID id)`: `SELECT EXISTS (SELECT 1 FROM account WHERE id = …)`, used by
       `TransactionService.list`;
     - `findExistence(UUID accountId, Currency currency)` returns a new `persistence.Existence`
       record `(boolean account, boolean balance)` from
       `SELECT EXISTS (… account …) AS account, EXISTS (… balance …) AS balance`. It maps by
       constructor argument name, as the other records do.
   - **`create`:** `!account` → `AccountNotFoundException`, then `!balance` →
     `CurrencyNotOpenException`. The order is unchanged: existence, then business rules
     (design.md §3).
   - `BalanceMapper.exists` is deleted (no callers left). `AccountService.get` keeps `findById`,
     because it needs the row.
   - **Behaviour is unchanged.** The existing ITs (404 `ACCOUNT_MISSING` before 422
     `INVALID_CURRENCY`, the concurrency test, events) are the regression net. No new tests unless
     JaCoCo shows a gap.
6. **Machine specs** are recorded by the script into each result and into `docs/performance.md`:
   - **Host:** Apple M5 Pro, 18 cores, 48 GB.
   - **Docker Desktop VM:** 18 CPUs, 7.7 GiB.
   - **Software:** Docker 29.8.1, the image tags (postgres:18, rabbitmq:4-management, k6), and the
     JDK.
   - **App config:** Hikari defaults (pool 10), Tomcat defaults, outbox poll 200 ms / batch 100.

   Caveat for the doc: everything runs on one laptop (client, app, DB and broker share the CPUs), so
   the numbers are a floor, not a capacity plan.

## Steps
1. Save this plan to `docs/plans/stage-4b-performance.md`.
2. Write `scripts/k6/create-transaction.js` and `scripts/load-test.sh`:
   - usage `scripts/load-test.sh [--label before|after] [--runs 3] [--scenario spread|hot|all]`;
   - always tears down (trap), as `verify.sh` does;
   - ends with a table of the median TPS, p95, peak backlog, peak lag and drain rate for each scenario.
3. **Before:** run it on the unchanged code, with `--label before --runs 3 --scenario all`.
4. Make the existence fold (decision 5), then run `./gradlew check`.
5. **After:** run it again with `--label after`.
6. **`docs/performance.md`:**
   - the machine specs, the method and how to reproduce it;
   - a before/after table per scenario (TPS, p50, p95, p99, errors);
   - an outbox-lag table (peak backlog, peak lag, drain rate) and what it means for the
     per-confirm wait;
   - the hot vs spread explanation (row lock, ADR-0002);
   - the pool: 50 VUs against Hikari's default pool of 10 make the pool a likely cap on spread TPS;
   - a README-ready summary paragraph, and future work: pipelined confirms that keep per-balance
     order, `LISTEN/NOTIFY`, a pool size.
7. **Sync the docs:**
   - **design.md §8:** "Publish throughput" gets the measured number, and §2/§6 too if the query
     change is mentioned there;
   - **sdlc-plan.md Stage 4:** throughput and carry-over ✅;
   - **test-plan.md:** the load row points to the script and the doc;
   - **CLAUDE.md Commands:** the `load-test.sh` line.
8. **Review:**
   - `banking-reviewer` on the diff (the service/mapper change);
   - `/code-review low` on the scripts.

   **Show the findings and wait before committing** (standing preference).
9. **Verify and ship:**
   - `.claude/skills/verify/verify.sh`;
   - commits: `perf: k6 load test …`, `perf: fold existence checks …`, `docs: performance results`;
   - push and open a PR to `main`;
   - add an Outcome section to the plan file.

## Verification
- **`./gradlew check`:** green, with the JaCoCo gate (the behaviour net for the fold).
- **`load-test.sh`:**
  - the k6 checks are >99.9% 201s in every run;
  - the backlog drains to 0 at the end of each run;
  - the teardown leaves no `banking-perf` containers or volumes (`docker ps -a`, `docker volume ls`).
- **A negative check:** with the app stopped, `load-test.sh` exits non-zero with a clear message.
- **`verify.sh`:** VERIFY PASSED before the PR.

## Critical files
- `src/main/java/com/danielrak/banking/domain/TransactionService.java`
- `src/main/java/com/danielrak/banking/persistence/AccountMapper.java`, `BalanceMapper.java`, new
  `Existence.java`
- new `scripts/load-test.sh`, `scripts/k6/create-transaction.js`, `docs/performance.md`
- reused: `docker-compose.yml`'s port overrides (from part A), `verify.sh`'s trap and teardown
  pattern

## Outcome
- **Results** are in `docs/performance.md` (medians of 3 runs, 50 VUs, 60 s measured):
  - spread: 6,840 → **7,371 TPS** (+7.8%), p95 13.9 → 13.0 ms;
  - hot: 2,280 → **2,345 TPS** (+2.9%, the ranges overlap), p95 32.8 → 31.7 ms;
  - 100% 201s in every run.
- **The outbox is the real limit.**
  - One publisher, waiting for each confirm, drains about 3,300 events/s with an idle API. Under
    load that drops to about 1,150 events/s (spread) and about 2,770 events/s (hot).
  - Events keep up only below about 1,650 TPS (3,300 ÷ 2) at best, and about 575 TPS (1,150 ÷ 2)
    under the spread load. The exact break-even point isn't measured. Past it, the lag grows
    linearly: 75 s at the end of the spread run.
  - design.md §8 now cites these numbers. Future work is listed in the performance doc.
- **A deviation from the plan, the drain window:**
  - A full drain after a spread run takes about 5 min (1.1 M events), so waiting for one in every
    run would have added about 30 min per label.
  - After the load, the script now samples a 60 s drain window and extrapolates the full drain
    time. The next run starts on a fresh stack anyway.
  - The seed's backlog is still drained to 0 before the warm-up, as approved.
- **The pool:** a 10-VU smoke run gave about 6,400 TPS at a p95 of 2 ms, and 50 VUs give about
  7,000 TPS at 14 ms, so Hikari's 10 connections are the likely cap. It isn't tuned in this stage.
- **The fold:** `AccountMapper.findExistence` returns an `Existence` record, and `list` uses
  `AccountMapper.exists`. `BalanceMapper.exists` is gone. The 204 tests are unchanged and green.
  banking-reviewer found nothing.
- **The negative check:** the app was stopped while the spread seed was running. The script printed
  `load-test: seeding failed, see build/perf/negative-spread-1-seed.log` and exited 2, and the
  teardown left no `banking-perf` containers or volumes.
- **Script review** (feature-dev:code-reviewer): 5 findings, all fixed, and a smoke run passed after
  the fixes. None of them affected the recorded numbers.
  - `DURATION` units: `1m` was read as 1 s;
  - a stale result JSON after a failed k6 run;
  - `-` cells skewing the medians;
  - a late sampler write, and a false 0 when the post-load sample was missing;
  - `--label`/`--runs` with no value exited 1 instead of 2.
- **Environment note:** the first `./gradlew check` straight after the load runs failed on
  Testcontainers' "Could not connect to Ryuk". The re-run was green.
