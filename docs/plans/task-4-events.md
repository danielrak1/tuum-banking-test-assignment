# Task 4: Events (`feat/events`)

> **Status:** Part 1 done 2026-10-06 and committed locally. Part 2 (queue-based tests, criterion 4,
> docs) is next.

## Part 1 outcome
- **Built:** steps 1–4, `EventJsonTest` (2 golden cases) and `OutboxPublisherIT`.
  - Correlated confirm futures do complete inside `rabbitTemplate.invoke`, so the fallback in step 4
    wasn't needed.
  - Jackson 3.1.5 classes: `MapperFeature`, `cfg.DateTimeFeature`, `core.StreamWriteFeature`,
    `changeDefaultPropertyInclusion`.
- **`./gradlew check`:** 163 tests, 1 failed, 4 skipped (the `@Disabled` task-6 tests). The coverage
  gate passes on the full run (lines 0.919, branches 0.829).
  - The failure is the expected race: `TransactionApiIT.acceptsAmountWithTrailingZerosOrExponentNormalisedToScale2`
    (`"10.5000000"`) read `outbox_event` after the poller had already published and deleted the rows
    (`[]` instead of the two events).
  - The other outbox-reading ITs passed only because the 200 ms poll lost the race. Part 2 moves them
    to the queue, as planned. Not patched here.
- **Coverage gap:** `OutboxPublisher`'s failure paths (timeout, nack, broker down, the recovery log).
  `EventDeliveryIT`'s pause covers them in Part 2.

## Context
This is Stage 3, task 4 of 7 in `docs/sdlc-plan.md`. It is item 5, "events", in the build order.
Tasks 1–3 write `outbox_event` rows in the business transaction, but nothing publishes them yet.
This task adds the ADR-0003 poller and the design.md §5 topology, so every committed change reaches
RabbitMQ. It also adds the §6 criterion 4 test: events are never lost.

The task also clears two notes left by earlier tasks:
- **Task 2 review, finding H:** the outbox serialises events with Boot's HTTP `JsonMapper`, so a
  `spring.jackson.*` change would silently change the §5 wire format. Events get their own mapper.
- **Task 3 test-writer finding:** the ITs read `outbox_event` directly. Once the poller deletes
  published rows they race with it, so their event checks move to the `banking.events.all` queue,
  waiting with Awaitility. `test-writer.md` gets the new rules.

Scope: the poller, topology, event mapper, test migration, criterion 4 test, and docs. No API changes.

## Decisions (confirmed in chat)
1. **`outbox_event.payload` becomes `json`, not `jsonb`** (new `V2__outbox_payload_json.sql`).
   `jsonb` reorders keys and rewrites whitespace, so the published bytes wouldn't be the bytes the
   event mapper wrote. With `json` they are, and the golden-format test pins the real wire format.
2. **The demo queue is capped:** `x-max-length=10000`, `x-overflow=drop-head`. Queue arguments can't
   change after declaration, so this is decided now. design.md §5 documents the cap, and §9 records
   the alternative for later (see step 7).

## Approach (all under `com.danielrak.banking`)

### 1. Schema (`db/migration`)
- `V2__outbox_payload_json.sql`: `ALTER TABLE outbox_event ALTER COLUMN payload TYPE json USING payload::json;`
- `OutboxMapper.insert`: `CAST(#{payload} AS json)`.
- `BankingApplicationTests` expects Flyway version `2`.

### 2. Event JSON (`messaging`)
- New `EventJson`: a package-private `static final JsonMapper` with every setting that affects the
  wire format spelled out: ISO-8601 instants (`WRITE_DATES_AS_TIMESTAMPS` off), `WRITE_BIGDECIMAL_AS_PLAIN`,
  no alphabetical sorting, and `ALWAYS` inclusion. The exact Jackson 3 feature classes get checked
  with context7 while building.
- It is deliberately **not a `@Bean`.** A `JsonMapper` bean would replace Boot's HTTP mapper
  (`@ConditionalOnMissingBean`), which is the opposite of what we want.
- `OutboxWriter` stops injecting `JsonMapper` and uses `EventJson`.
- New unit test `EventJsonTest`: serialise a fixed envelope (fixed UUIDs, a fixed `Instant`, a
  `BalanceUpdatedData` with `10.50`) and assert the exact JSON string.

### 3. Topology (`messaging`)
New `MessagingConfiguration` (`@Configuration`, `@EnableScheduling`):
- `TopicExchange banking.events`, durable;
- `Queue banking.events.all`, durable, with `x-max-length=10000` and `x-overflow=drop-head`;
- `Binding` to the exchange with `#`.

Boot's auto-configured `RabbitAdmin` declares them on the first connection. The names become
constants (`EXCHANGE`, `DEMO_QUEUE`).

### 4. Poller (`messaging` + `persistence`)
`OutboxMapper` gets three new methods:
- `tryLock(long key)`: `SELECT pg_try_advisory_xact_lock(#{key})`;
- `findBatch(int limit)`: `SELECT id, event_id, routing_key, payload FROM outbox_event ORDER BY id LIMIT #{limit}`.
  It returns a new `persistence.OutboxRow(long id, UUID eventId, String routingKey, String payload)`;
- `deleteByIds(List<Long>)`: a `<script>` `DELETE … WHERE id IN <foreach>`. It deletes by ID, never
  by `id <= max`, because a lower ID can commit later.

New `OutboxPublisher`:
- `@Scheduled(fixedDelayString = "${banking.outbox.poll-interval}")` calls `poll()`. `poll()` runs
  batches until one comes back short or fails, so a backlog drains faster than 100 rows per 200 ms.
- Each batch runs in its own `TransactionTemplate` transaction:
  1. If `tryLock(LOCK_KEY)` fails, return. Another instance is publishing.
  2. `findBatch(batchSize)`.
  3. `rabbitTemplate.invoke(...)` pins **one channel** for the batch. For each row, send a raw
     `Message`: the stored payload bytes; `content_type=application/json`, UTF-8; `PERSISTENT`;
     `message_id = eventId`; routing key = `routing_key`; and a `CorrelationData(eventId)`.
     Then wait on the futures in order against one batch deadline (`confirm-timeout`).
  4. Collect the **acked prefix**. Stop at the first nack or timeout, so a failed row is never
     overtaken by a later one. That keeps per-balance order (ADR-0003 step 3).
  5. `deleteByIds(ackedPrefix)`, then commit.
- An `AmqpException`, a timeout or a nack ends the batch. The remaining rows stay for the next poll.
  Logging: one WARN when publishing starts failing, then DEBUG for each repeat, then one INFO when
  it recovers. A broker outage doesn't print 5 stack traces a second.
- No message converter: the payload is already JSON in the outbox. design.md §1 currently says
  `JacksonJsonMessageConverter`, so that gets corrected.
- `LOCK_KEY` is a `public static final long` constant, so `OutboxPublisherIT` can hold the same lock.
- Fallback, if correlated futures don't complete inside `invoke` (to check against the Spring AMQP 4
  docs while building): send outside `invoke`. Only the poller publishes, under the lock, so in
  practice it reuses the same cached channel. Record the change in the plan if this happens.

`application.yml`:
```yaml
banking:
  outbox:
    poll-interval: 200ms
    batch-size: 100
    confirm-timeout: 5s
spring.rabbitmq:
  connection-timeout: 5s      # don't hang the poller (and its open DB transaction) on a dead broker
  channel-rpc-timeout: 5s     # default is 10 min; a channel.open during a paused broker would block that long
```

### 5. Test event helper (`src/test`), written by me before `test-writer` runs
New `BankingEvents` helper (`BankingApi` stays HTTP-only):
- It receives from `banking.events.all` with an `@Autowired RabbitTemplate`, using `receive`
  (basic.get) until the queue is empty.
- Every message goes into a **static, JVM-wide** map, keyed by envelope `accountId`, in arrival
  order. Every test class drains the same queue, so a message for another test's account is kept,
  not dropped.
- Each `Received` record holds the routing key, exchange, `message_id`, content type, delivery
  mode, the raw body and the parsed envelope.
- `awaitEvents(accountId, n)`: Awaitility (`atMost 10s`) drains until the account has at least n
  events, then returns them.
- `fence()`: creates a throwaway account and waits for its `account.created` event. Use it before
  asserting that **nothing** was published. Any row a rejected request could have committed got an
  earlier `id`, and committed before the fence started. One poller publishes in `id` order, so that
  row would already have arrived. This is deterministic, with no sleep-and-hope.
- `eventsMentioning(marker)`: for rejected creates, where there is no account ID.
- `BankingApi`'s `maxOutboxId`, `outboxRowsAfter` and `assertNoOutboxRowMentions` are removed.

### 6. Test migration and new tests
**Via `test-writer`** (black-box, briefed with the spec, never `src/main`):
- **Migrate** the event checks in `AccountApiIT`, `TransactionApiIT`, `BalanceConcurrencyIT` and
  `ProtocolErrorsIT` (the last one also reads the outbox) from outbox rows to `BankingEvents`:
  - positive: the exact event types and order per account, the envelope (`eventId` is a UUID equal
    to `message_id`, `eventType` equals the routing key, `occurredAt` is ISO-8601, `accountId`), the
    message properties (exchange `banking.events`, `application/json`, persistent), and `data` per §5;
  - negative: `fence()`, then no event for the account or marker;
  - `BalanceConcurrencyIT` also checks per-balance order: the `balance.updated` events for the
    balance arrive in `seq` order, with `availableAmount` equal to each transaction's `balanceAfter`.
- **New `EventDeliveryIT`**, criterion 4 (§6):
  1. `@Autowired RabbitMQContainer`. Pause it with `DockerClientFactory.instance().client().pauseContainerCmd(id)`,
     and unpause in `finally`.
  2. POST an IN: 201. The outbox has 2 rows for the account, and still has them `during(1s)`, with
     no event arriving. A paused broker can't ack, so this isn't racy.
  3. Unpause, then wait up to 30 s until both events have arrived (deduped by `eventId`: at-least-once
     can repeat a message published during the pause), in order `transaction.created`,
     `balance.updated`, and the outbox has no rows for the account.

**By me** (white-box, so not for `test-writer`):
- **`OutboxPublisherIT`**: open a separate JDBC connection and take `pg_advisory_lock(LOCK_KEY)`, the
  "other instance". POST a transaction. Its rows stay and nothing arrives `during(1s)`. Release the
  lock, and the events arrive and the outbox empties. This proves the multi-instance claim in
  design.md §1.
- `EventJsonTest` (step 2).

**`.claude/agents/test-writer.md`** updates:
- It may read §6.
- The Events section is rewritten: assert on the queue through `BankingEvents` and never consume
  the queue directly. Assert message properties. Use the fence before any "nothing published" check,
  and never sleep. Dedupe by `eventId` only where §5's at-least-once applies (pause tests). Read
  `outbox_event` only to show rows are pending or gone, never for event content.
- Isolation: never purge the queue. Any test that pauses a container unpauses it in `finally`.

`.claude/skills/add-endpoint/SKILL.md` step 6: "outbox rows" becomes "queue events via `BankingEvents`".

### 7. Docs
- **design.md:**
  - §1: the stack row (raw `Message` from the outbox payload, no converter).
  - §4: `payload json`, and why.
  - §5:
    - message properties, including the exchange;
    - the demo queue's cap: 10,000 messages, drop-head, so the oldest events are discarded when it's full;
    - a new ordering line: a write that commits before another starts is published first. The
      fence depends on this, and it follows from one poller publishing in `id` order.
    - duplicates: they can follow **any** publish failure, not only a crash. A row that timed out
      may still have reached the queue, and rows sent after a nack or timeout in the same batch are
      sent again on the next poll. The same note goes in ADR-0003's "Guarantee" line.
  - §9: the demo-queue alternative for later: a RabbitMQ **stream** (`x-queue-type=stream`) with
    size or age retention, so history can be replayed instead of dropped; or no demo queue outside
    dev, where real consumers own their queues.
- **ADR-0003:** tick action items 3–5. **ADR-0004:** correct item 1 (raw JSON from the outbox, not
  `JacksonJsonMessageConverter`).
- **`docs/sdlc-plan.md`:** task 4 done, and the item 5 notes resolved.
- **This plan** is saved as `docs/plans/task-4-events.md` first, then updated with the outcome.

## Files
- **New:**
  - `db/migration/V2__outbox_payload_json.sql`;
  - `messaging/{EventJson, MessagingConfiguration, OutboxPublisher}`;
  - `persistence/OutboxRow`;
  - tests `BankingEvents`, `EventJsonTest`, `OutboxPublisherIT`, `EventDeliveryIT`;
  - `docs/plans/task-4-events.md`.
- **Changed:**
  - `OutboxWriter`, `OutboxMapper`, `application.yml`;
  - tests `BankingApi`, `BankingApplicationTests`, `AccountApiIT`, `TransactionApiIT`,
    `BalanceConcurrencyIT`, `ProtocolErrorsIT`;
  - `.claude/agents/test-writer.md`, `.claude/skills/add-endpoint/SKILL.md`;
  - `docs/design.md`, ADR-0003, ADR-0004, `docs/sdlc-plan.md`.

## Risks
- **The pause test and connection recovery.** A pause of a few seconds is well under the 60 s
  heartbeat, so the connection should survive. If it doesn't, Spring AMQP reconnects to the same
  mapped port, and the 30 s wait covers it. The `channel-rpc-timeout` keeps the poller from blocking
  for 10 minutes.
- **The poller holds a DB transaction while it waits for confirms**, at most `confirm-timeout` per
  batch. That costs one pool connection. Accepted (ADR-0003).
- **A static event buffer** relies on test classes running sequentially, which is today's JUnit
  default. If parallel execution is ever turned on, the account-keyed map still works, but the pause
  test would need isolation.

## Delivery, in two parts (auto mode)
- **Part 1:**
  - work: steps 1–4, `EventJsonTest` and `OutboxPublisherIT`;
  - check: `./gradlew check`, then show the results;
  - commit locally on `feat/events`.

  The existing ITs still read `outbox_event`. If one goes flaky because the poller now deletes
  rows, report it as a known race that Part 2 fixes; don't patch it here.
- **Part 2:**
  - work: `BankingEvents`, the `test-writer` migration, `EventDeliveryIT`, `test-writer.md`,
    SKILL.md and the docs;
  - findings: show them and wait;
  - then commit, push, and open the PR.

## Verification
1. `./gradlew test --tests 'com.danielrak.banking.EventJsonTest'`, then `OutboxPublisherIT`, then `EventDeliveryIT`.
2. `./gradlew check` is green: every IT on the queue, and the JaCoCo gate (lines ≥ 0.80, branches ≥ 0.70).
3. Manually: `docker compose up -d`, then `./gradlew bootRun`. POST an account and a transaction.
   The RabbitMQ UI (localhost:15672) shows `banking.events.all` with 1 + N + 2 messages, and
   `psql` shows `outbox_event` empty. `docker compose pause rabbitmq`, POST, and the rows stay
   pending; `unpause`, and they drain.
4. Show you the `test-writer` findings and wait before any commit (per memory). Then commit on `feat/events`.
