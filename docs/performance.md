# Performance

Create-transaction throughput and latency (`POST /accounts/{id}/transactions`), measured with k6 against
the full compose stack, plus the outbox's publish lag under that load. Plan:
[`docs/plans/stage-4b-performance.md`](plans/stage-4b-performance.md).

## Summary (for the README)

On one laptop (Apple M5 Pro), with the client, app, Postgres and RabbitMQ all sharing the same CPUs:
- **Many accounts:** about **7,400 transactions/s**, with a p95 of 13 ms.
- **One hot account:** about **2,300 transactions/s**, with a p95 of 32 ms. Its writers are
  serialised by the balance row lock (ADR-0002).

Every request succeeded (100% 201s).

Events are the bottleneck. The outbox publisher waits for each broker confirm before it sends the
next event (ADR-0003). That keeps per-balance order, but it caps publishing:
- at about 3,300 events/s when the API is idle, and about 1,150–2,800 events/s under load;
- each transaction writes 2 events, so events keep up only below about **1,650 TPS** at best
  (3,300 ÷ 2). Under heavy load the publisher slows down, and the limit falls to as low as about
  **575 TPS** (1,150 ÷ 2). The table under "Outbox lag" shows the arithmetic.

Above the limit, events are still never lost, but they arrive late. After the 80 s spread run, the
oldest pending event was 75 s old, and the backlog needed about 5 more minutes to drain.

## Setup

| | |
|---|---|
| Host | Apple M5 Pro, 18 cores, 48 GB, macOS (Darwin 25.4.0) |
| Docker | Docker Desktop 29.8.1, VM with 18 CPUs and 7.7 GiB |
| Images | `postgres:18`, `rabbitmq:4-management`, the app (`eclipse-temurin:25-jre`), `grafana/k6:2.3.0` |
| App config | Defaults: Hikari pool **10** connections, Tomcat 200 threads. Outbox poll 200 ms, batch 100, publisher confirms (`application.yml`) |
| Load | k6 closed model: **50 VUs**, 20 s warm-up (discarded), then **60 s measured** |
| Runs | 3 per scenario and code version, each on a fresh stack (`down -v`). Medians reported, ranges in brackets |

**Scenarios:**
- **spread:** 1,000 EUR accounts, with each request going to a random one. Almost no lock
  contention: the API, pool and DB path.
- **hot:** a single EUR account. Every request waits on the same balance row lock.

Each request alternates `IN` and `OUT` of 1.00. Every account is seeded first with a large `IN`, so an
`OUT` never fails for insufficient funds.

**What the script controls for:**
- Seeding is a separate k6 invocation. The script waits until `outbox_event` is empty before the
  warm-up starts, so the measured lag comes from the load alone.
- k6 runs inside the compose network (`http://app:8080`), so Docker Desktop's port forwarding isn't
  measured.

**Caveat:** this is a single machine, with the load generator competing with the system under test.
Treat the numbers as a floor and a comparison between runs, not as a capacity plan.

**Reproduce** (about 21 min, needs Docker and jq):
```sh
scripts/load-test.sh --label mine            # 3 runs × spread and hot, medians at the end
VUS=10 scripts/load-test.sh --runs 1 --scenario hot   # a quick single run
```
Per-run JSON, outbox CSVs and logs land in `build/perf/`.

## Results: before and after the existence-check fold

`TransactionService.create` used to make two existence checks before the balance update:
`AccountMapper.findById` (which maps the whole row), then `BalanceMapper.exists`. Now one query does
both (`AccountMapper.findExistence`: `SELECT EXISTS (…account…), EXISTS (…balance…)`). That saves one
round trip per transaction. The check order is unchanged (404 before 422).

| Scenario | Version | TPS | p50 ms | p95 ms | p99 ms | 201s |
|---|---|---|---|---|---|---|
| spread | before | 6,840 [6,795–6,909] | 7.1 | 13.9 | 20.0 | 100% |
| spread | **after** | **7,371** [7,223–7,387] | 6.6 | 13.0 | 18.8 | 100% |
| hot | before | 2,280 [2,276–2,309] | 20.1 | 32.8 | 41.2 | 100% |
| hot | **after** | **2,345** [2,295–2,377] | 19.6 | 31.7 | 39.8 | 100% |

- **spread: +7.8% TPS, with p95 down about 1 ms.**
  - The run ranges don't overlap, so this is a real gain.
  - Before the change, a transaction took about 8 statements (begin, 2 checks, update, transaction
    insert, 2 outbox inserts, commit). Removing one of them is in line with the gain.
- **hot: +2.9%, and the ranges overlap.**
  - The checks run *before* the row lock is taken, so they were never inside the serialised
    section.
  - What limits hot is the time the lock is held: from `applyDelta`, through the transaction and
    outbox inserts, to commit. 1 / 2,345 TPS ≈ 0.43 ms per transaction.

### The connection pool is a likely cap
- In a single short smoke run with **10 VUs**, spread reached about 6,400 TPS with a p95 of 2 ms.
- With **50 VUs**, it reaches only about 7,000 TPS, while p95 grows to about 14 ms.
- Five times the concurrency for about 10% more throughput means the extra VUs mostly queue. With 50
  VUs against Hikari's default pool of **10** connections, most requests wait for a connection.
- Tuning the pool is out of scope for this stage. A larger pool, or more app instances (design.md
  §1), is the first thing to try for spread throughput. The hot-account rate wouldn't change.

## Outbox lag under load (the cost of waiting for each confirm)

The script samples `outbox_event` once a second:
- the backlog, `count(*)`;
- the lag: the age of the oldest pending row, `now() - min(created_at)`. This is the end-to-end
  delay from commit to publish.

After k6 stops, it samples a 60 s drain window, with no HTTP load competing.

| Scenario (after) | Events written/s | Published/s during load | Backlog at load end | Lag at load end | Drain rate (idle) | Est. full drain |
|---|---|---|---|---|---|---|
| spread | ~14,700 | ~1,150 | 1.09 M events | 74.8 s | 3,322 events/s | ~326 s |
| hot | ~4,700 | ~2,770 | 149 k events | 32.4 s | 3,217 events/s | ~46 s |

"Published/s during load" = events written − backlog growth, over the measured window. The "before"
runs give the same figures within a few percent: the fold doesn't touch publishing.

- **The confirm wait sets a hard ceiling.**
  - The poller sends a row, waits for its confirm, and only then sends the next row (ADR-0003). It
    does this so that a nacked row is never overtaken by a later row for the same balance.
  - With an idle API, a round trip costs about 0.30 ms. That covers the persistent publish, the
    broker's confirm, and the batch's DB work, spread over the batch's 100 rows. So one publisher
    sends about 3,300 events/s.
  - Each transaction writes 2 events. Events therefore keep up only below about **1,650 TPS** in the
    best case.
- **Under load it drops further.**
  - At about 7,000 TPS (spread) the publisher manages only about 1,150 events/s. Its `SELECT … LIMIT
    100` and `DELETE` compete with the API for Postgres and the CPUs, and the table grows
    quickly. This is likely, not proven.
  - At about 2,300 TPS (hot) it manages about 2,770 events/s.
- **The keep-up limit, in numbers.** Each transaction writes 2 events, so the publisher's rate ÷ 2 is
  the highest TPS the events can keep pace with:

  | Publisher rate | ÷ 2 = TPS events keep up with | Measured during |
  |---|---|---|
  | 3,300 events/s | ~1,650 TPS | the drain after the load, with no HTTP load |
  | 2,770 events/s | ~1,385 TPS | the hot load (2,345 TPS offered) |
  | 1,150 events/s | ~575 TPS | the spread load (7,371 TPS offered) |

  - The limit isn't one number: the more load the API has, the slower the publisher gets.
  - In both scenarios the offered load was above the limit, so the backlog grew.
  - The break-even point lies somewhere between ~575 and ~1,650 TPS on this machine, and these runs
    don't pin it down. A k6 run at a fixed arrival rate (`constant-arrival-rate`), stepped up until
    the backlog stops staying flat, would find it.
- **Nothing is lost, it only arrives late.** The backlog grows linearly while the load lasts, then
  drains at a steady rate (the 15 s smoke runs drained fully to 0). The lag is the price: at the end
  of the spread run, the oldest pending event was 75 s old, almost the whole run.

## Future work
- **Pipelined confirms that keep per-balance order:** send a window of rows and wait for confirms in
  bulk. On a nack, stop and re-send from the first unconfirmed row; the duplicates are already
  allowed by at-least-once delivery. Alternatively, publish per balance in parallel, since order only
  matters within one balance. Either could take the publisher far past one event per round trip.
- **Partitioned or several publishers:** shard the outbox by balance (for example, a hash of
  `account_id`) and give each shard its own advisory lock, keeping per-balance order.
- **`LISTEN/NOTIFY`:** wakes the poller at once instead of every 200 ms. This cuts idle lag, not
  throughput.
- **Pool size and app instances:** for spread throughput. Re-run with `VUS` and a larger
  `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` to find the next limit.
- **Hot accounts:** sharded sub-balances (design.md §9), if a single account really needs more than
  about 2,000 TPS.
