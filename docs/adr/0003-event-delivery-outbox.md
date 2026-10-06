# ADR-0003: Event delivery through a transactional outbox

**Status:** Accepted
**Date:** 2026-10-02
**Deciders:** Daniel Rak

## Context
- The PDF requires the service to "publish all insert and update operations to RabbitMQ".
- Success criterion 4 sets the bar: *if a change is committed, its event is eventually published*,
  proven by a test.
- PostgreSQL and RabbitMQ can't share one atomic transaction (no XA, deliberately).
- So any design must answer two questions:
  - What happens if the app crashes, or the broker is down, between the DB commit and the publish?
  - What happens if the DB transaction rolls back after a publish?

## Decision
Use a **transactional outbox**:

1. **Write events in the business transaction.** The service inserts its event rows into
   `outbox_event` in the *same* transaction as the change. They go in after the balance update, so
   rows are in commit order per balance (account + currency).
2. **Poll and publish.** A `@Scheduled` poller runs every ~200 ms. Each run:
   1. Opens a transaction and calls `pg_try_advisory_xact_lock(<outbox key>)`. If the lock is not
      acquired, it returns immediately, because another instance is publishing.
   2. Reads `SELECT … FROM outbox_event ORDER BY id LIMIT 100`.
   3. Publishes the rows to `banking.events` one at a time, with correlated publisher confirms
      (`spring.rabbitmq.publisher-confirm-type=correlated`). It waits for each row's confirm before
      sending the next, within one timeout for the whole batch.
   4. `DELETE`s every acked row, then commits.
3. **On nack, timeout, or connection failure:** stop the batch and leave the remaining rows.
   They are retried on the next poll. The rows after the failed one were never sent, so none of them
   can reach a queue before it. Sending the whole batch first and then waiting would break this: a
   later row could be acked while an earlier one is nacked, and per-balance order would be lost.

**Guarantee: at-least-once.** A crash after the broker acks but before the `DELETE` commits
re-publishes that row. Duplicates also follow any publish failure: a message whose confirm timed out,
or that was nacked, may still have reached a queue (a nack can come from one queue while the others
accepted it), and it is sent again on the next poll. Consumers dedupe on `eventId` (= AMQP `message_id`).

**Not used:** `mandatory`/publisher returns. The service declares a demo queue bound to `#`, so
every message is routable. Without that queue, a message no queue is bound for would still be acked,
then deleted and lost. So removing the demo queue (design.md §9) requires an alternate exchange with a
catch-all queue, or `mandatory` plus returns treated as failures.

## Options Considered

### Option A: Publish inside the DB transaction
| Dimension | Assessment |
|---|---|
| Complexity | Low |
| Cost | API latency includes the broker round trip |
| Scalability | Broker outage = API outage |
| Team familiarity | High |

**Pros:** simplest code.
**Cons:**
- **Phantom events:** the message is out before commit. If the transaction then rolls back
  (insufficient funds, constraint violation), consumers have seen a change that never happened.
- A broker outage fails every write.

### Option B: Publish after commit (`@TransactionalEventListener(AFTER_COMMIT)`)
| Dimension | Assessment |
|---|---|
| Complexity | Low |
| Cost | None |
| Scalability | Fine |
| Team familiarity | High |

**Pros:** no phantom events; writes don't depend on the broker.
**Cons:** **lost events.** A crash, deploy or broker outage between commit and publish drops the
event for good. That fails criterion 4 by construction, and retries in memory don't survive a crash.

### Option C: Transactional outbox + poller (chosen)
| Dimension | Assessment |
|---|---|
| Complexity | Medium. One table, one scheduled publisher. |
| Cost | Extra insert per event; up to ~200 ms publish latency |
| Scalability | Safe with N instances: advisory lock means one publisher at a time, in `id` order |
| Team familiarity | Medium. A well-known pattern. |

**Pros:**
- The commit *is* the guarantee: no lost events, no phantom events.
- Writes keep working while the broker is down.

**Cons:**
- At-least-once, so duplicates are possible.
- Publish latency.
- One instance's poller is the throughput ceiling for events.

### Option D: Change data capture (Debezium reading the WAL)
| Dimension | Assessment |
|---|---|
| Complexity | High. Debezium Server or Kafka Connect, plus a replication slot. |
| Cost | Another moving part in `docker compose` |
| Scalability | Excellent |
| Team familiarity | Low |

**Pros:** no outbox writes in application code; captures every change.
**Cons:** far beyond the assignment's scope, and it publishes row-level change shapes rather than
a designed contract unless it is combined with an outbox anyway.

## Trade-off Analysis
- **Why not A or B:** criterion 4 rules out B, and correctness rules out A.
- **C vs D:** D gives the same guarantee for much more infrastructure.
- **Costs of C:**
  - Duplicates are handled by the `eventId` contract.
  - Latency (~200 ms) is acceptable for downstream consumers.
  - The single active publisher is acceptable at this scale, and it is what keeps per-balance
    ordering intact.
- **The advisory lock:** it costs one SQL call. Without it, N instances would publish the same
  rows concurrently: more duplicates, and events out of order. With it, the README can say
  "scale the API horizontally; events stay ordered per balance".

## Consequences
- **Easier:**
  - Criterion 4 is provable: pause the broker, write, unpause, and see the event arrive.
  - The API stays available during broker outages.
- **Harder:**
  - Consumers must be idempotent.
  - Tests must wait for asynchronous publication (Awaitility), not assert immediately.
  - Event throughput is capped by one poller; the README notes this under scaling. Waiting for each
    confirm before the next send costs one broker round trip per event.
  - **A row that fails every time stalls publishing, by design: order over availability.** If the
    broker nacks a row, or never confirms it, on every attempt, that row and every row after it in
    `id` order stay pending. The poller keeps retrying with backoff, and logs WARN again every 60 s
    and whenever the kind of failure changes. An operator has to step in: fix the broker side, or
    remove or repair the row. Moving the row to a dead-letter queue would let later events for the
    same balance overtake it, which breaks per-balance order. A DLQ that also parks every later row
    of that balance is future work.
  - **Ordering is per balance (account + currency), not global.** `outbox_event.id` is assigned
    at insert, not at commit, so two transactions on different balances can commit in one order
    and publish in the other. Writers on the same balance serialise on its row lock and insert
    their outbox rows after the update, so their `id`s follow commit order. Consumers must not
    assume a global order across accounts or currencies.
- **Revisit:**
  - `LISTEN/NOTIFY` to wake the poller right after commit (lower latency).
  - Parallel publishing partitioned by `account_id` if one publisher becomes the bottleneck.
  - Outbox table bloat at very high volume (vacuum tuning, partitioning).

## Action Items
1. [x] Add the `outbox_event` table in `V1__init.sql` (`id bigserial`, `event_id uuid UNIQUE`, `routing_key`, `payload jsonb`, `created_at`). `payload` is `json` since `V2` (design.md §4).
2. [x] Add `OutboxWriter`, called by services in the business transaction.
3. [x] Add `OutboxPublisher`: `@Scheduled`, advisory xact lock, batch, correlated confirms, `DELETE` on ack.
4. [x] Declare the `banking.events` topic exchange and the `banking.events.all` demo queue bound to `#`.
5. [x] Tests:
   - pause the broker, post a transaction (201, rows pending), unpause, assert the event arrives
     and the outbox is empty;
   - a rejected request leaves no outbox row.
