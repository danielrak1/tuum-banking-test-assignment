# Design: Core Banking Account Service

What we build and why is in [`intent.md`](../intent.md). This document is *how*: the API, the error
contract, the data model and the event contract. The four decisions with real trade-offs each have
an ADR in [`docs/adr/`](adr/):

| ADR | Decision |
|---|---|
| [0001](adr/0001-money-handling.md) | Money is `BigDecimal` scale 2 ↔ `NUMERIC(19,2)`; reject, never round |
| [0002](adr/0002-balance-concurrency.md) | Balances change through one atomic conditional `UPDATE` |
| [0003](adr/0003-event-delivery-outbox.md) | Events are delivered through a transactional outbox |
| [0004](adr/0004-event-granularity.md) | One event per changed record, carrying full state |

## 1. Overview

```
            HTTP (JSON)
 client ──────────────► api           controllers, DTOs, Bean Validation, ProblemDetail advice
                          │
                          ▼
                        domain        services (@Transactional), business rules
                          │
                          ▼
                        persistence   MyBatis mappers ───────────────► PostgreSQL
                          │                                              ▲  account, balance,
                          │  outbox rows, same transaction ──────────────┘  account_transaction,
                          │                                                 outbox_event
                        messaging     outbox poller ── confirms ──► RabbitMQ  (banking.events)
```

- **One deployable, stateless.** All state lives in Postgres, so instances can be added behind a
  load balancer. Running several instances is safe because of the conditional update (ADR-0002)
  and the advisory-locked poller (ADR-0003).
- **Packages** are `api / domain / persistence / messaging`.
- **Schema:** Flyway applies it on application startup. That is how "docker compose initialises
  the database structure" is met: compose starts Postgres healthy first, then the app migrates.
- **Running it:** `docker compose up --build` builds the image (multi-stage `Dockerfile`, no local
  Java needed) and starts Postgres, RabbitMQ and the app, which waits for both brokers to be healthy.
  The app reaches them through env vars (`SPRING_DATASOURCE_URL`, `SPRING_RABBITMQ_*`) and its own
  RabbitMQ user, because `guest` only works from loopback.
- **Health:** `/actuator/health` (the only Actuator endpoint exposed) is the app plus the DB. The
  RabbitMQ indicator is off: the outbox keeps the app serving through a broker outage (ADR-0003),
  so the broker must not make it unhealthy. The one endpoint serves as both liveness and readiness;
  behind an orchestrator they would be split.

### Stack (checked with context7, 2026-10-02)
| Concern | Choice |
|---|---|
| Runtime | Java 25, Spring Boot **4.1.1** (supports Java 17–26), Gradle wrapper 9.x |
| Web | `spring-boot-starter-webmvc`, `spring-boot-starter-validation`, Jackson 3 (`tools.jackson`) |
| Persistence | MyBatis Spring Boot Starter **4.x** (pin the patch at build time), `spring-boot-starter-flyway`, PostgreSQL |
| Messaging | `spring-boot-starter-amqp` (Spring AMQP 4, correlated publisher confirms). No message converter: the outbox payload is already the JSON body, serialised by the event mapper (§5) |
| API docs | springdoc-openapi **v3** (`springdoc-openapi-starter-webmvc-ui`), Swagger UI at `/swagger-ui.html` |
| Tests | JUnit 5, `spring-boot-testcontainers` + `@ServiceConnection` (Postgres, RabbitMQ), JaCoCo gate: lines and branches ≥ 0.80 |

## 2. API

| Method | Path | Success | Request → response |
|---|---|---|---|
| POST | `/accounts` | 201 + `Location` | `{customerId, country, currencies[]}` → Account |
| GET | `/accounts/{accountId}` | 200 | Account |
| POST | `/accounts/{accountId}/transactions` | 201 (no `Location`) | `{amount, currency, direction, description}` → Transaction |
| GET | `/accounts/{accountId}/transactions` | 200 | `Transaction[]` in insert order (`seq`); `[]` if none |

```jsonc
// Account
{ "accountId": "0b6f…", "customerId": "C-1001", "country": "EE",
  "balances": [ { "currency": "EUR", "availableAmount": 0.00 },
                { "currency": "USD", "availableAmount": 0.00 } ] }

// Transaction
{ "accountId": "0b6f…", "transactionId": "7c1e…", "amount": 10.50, "currency": "EUR",
  "direction": "IN", "description": "Salary", "balanceAfter": 10.50 }
```

- **Account `balances`** are listed in currency order (EUR, GBP, SEK, USD), not request order.
- **`Location`** on create account is relative: `/accounts/{accountId}`. Create transaction has
  no `Location`, because there is no endpoint for a single transaction.

### Input rules
| Field | Rule |
|---|---|
| `accountId` (path) | Canonical UUID: 36 characters, 8-4-4-4-12 hex digits, case-insensitive. Anything else (no dashes, braces, short groups) is malformed. |
| `customerId` | Free text: not blank, ≤ 64 characters. This service doesn't own customers, so the format is free. |
| `country` | `[A-Z]{2}` (ISO 3166 alpha-2 shape) |
| `currencies` | Non-empty, no duplicates, each one of `EUR`, `SEK`, `GBP`, `USD` |
| `currency` | One of `EUR`, `SEK`, `GBP`, `USD`, case-sensitive |
| `direction` | `IN` or `OUT`, case-sensitive |
| `amount` | JSON number (a JSON string such as `"10.50"` is rejected), > 0, at most 2 decimals, at most 17 integer digits (`NUMERIC(19,2)`). Trailing zeros don't count: `10.500` is 10.50 and `1e2` is 100.00. The response always has scale 2. (`@ValidAmount`, ADR-0001) |
| `description` | Free text: not blank, ≤ 255 characters. Unicode spaces such as U+00A0 count as blank. |

- **Free-text fields** (`customerId`, `description`) are single-line and stored exactly as sent.
  They reject:
  - control characters (U+0000–U+001F, U+007F–U+009F), including tab, newline, NEL and NUL.
    Postgres can't store NUL in `varchar` or `jsonb`;
  - the line and paragraph separators U+2028 and U+2029;
  - unpaired UTF-16 surrogates, which the JDBC driver would silently store as `?`.
- **String fields must be JSON strings.** A number or boolean (`"description": 42`, `"currency": 1`)
  is rejected, not coerced to text.
- **`currency` and `direction` are bound as `String`** and validated, not bound as Java enums.
  With enums, a bad value would fail inside Jackson as a generic parse error instead of returning
  the PDF's `INVALID_CURRENCY` / `INVALID_DIRECTION`.
- **IDs are UUIDs generated in the application.** They can't be enumerated, and they are known
  before the insert, which the outbox payloads need.

## 3. Error contract

Errors are RFC 9457 `ProblemDetail` (`application/problem+json`):
- Every error carries a machine-readable `code`, named after the PDF's error wording.
- Validation failures also list every failing field in `errors[]`. A `field` is the JSON path of
  the failing value; a list element is indexed, e.g. `currencies[1]`.
- `type` is omitted. RFC 9457 treats a missing `type` as `about:blank`.
- `title`, `detail` and `errors[].message` are human-readable and not part of the contract; clients
  and tests rely on `status`, `code` and `errors[].field`/`code`.

```json
{ "title": "Bad Request", "status": 400,
  "detail": "Request validation failed", "instance": "/accounts/0b6f…/transactions",
  "code": "INVALID_AMOUNT",
  "errors": [ { "field": "amount", "code": "INVALID_AMOUNT", "message": "must be greater than 0" },
              { "field": "description", "code": "DESCRIPTION_MISSING", "message": "must not be blank" } ] }
```

| Case | Status | `code` | PDF error |
|---|---|---|---|
| Transaction `currency` missing, or any currency (transaction, or an element of `currencies`) null or not EUR/SEK/GBP/USD | 400 | `INVALID_CURRENCY` | Invalid currency |
| Supported currency, but the account has no balance in it | 422 | `INVALID_CURRENCY` | Invalid currency |
| Direction missing, or not `IN`/`OUT` | 400 | `INVALID_DIRECTION` | Invalid direction |
| Amount missing, unparseable or a JSON string, ≤ 0, more than 2 decimals, or more than 17 integer digits (trailing zeros don't count) | 400 | `INVALID_AMOUNT` | Invalid amount |
| Description missing, blank, or whitespace only (Unicode spaces such as U+00A0 included) | 400 | `DESCRIPTION_MISSING` | Description missing |
| `OUT` larger than the available balance | 422 | `INSUFFICIENT_FUNDS` | Insufficient funds |
| GET account: ID malformed / unknown | 400 / 404 | `ACCOUNT_NOT_FOUND` | Account not found |
| POST transaction: ID malformed / unknown | 400 / 404 | `ACCOUNT_MISSING` | Account missing |
| GET transactions: ID malformed / unknown | 400 / 404 | `INVALID_ACCOUNT` | Invalid account |
| Malformed JSON (including a duplicate key), bad country, `currencies` list missing, empty or with duplicates, blank or too long `customerId`, description > 255, a control character, line separator or unpaired surrogate in a free-text field | 400 | `VALIDATION_FAILED` | (not in PDF) |
| Any other malformed parameter (a path or query value that doesn't convert to its type) | 400 | `VALIDATION_FAILED` | (not in PDF) |

Protocol errors (not in PDF) come from the HTTP layer rather than the request's content, and get a
code by status:

| Case | Status | `code` |
|---|---|---|
| No such route (e.g. `/accounts/{id}/foo`) | 404 | `NOT_FOUND` |
| Method not supported on the route | 405 | `METHOD_NOT_ALLOWED` |
| `Content-Type` not `application/json` | 415 | `UNSUPPORTED_MEDIA_TYPE` |
| Any other 4xx from the HTTP layer (e.g. 406) | 4xx | `BAD_REQUEST` |
| Any unexpected server error | 500 | `INTERNAL_ERROR` |

A 500's `detail` is generic and never carries the exception message; the full exception goes to the
ERROR log.

Every endpoint declares `consumes`/`produces` `application/json`, so a 415 or 406 is decided before
the request is processed: a rejected `Content-Type` or `Accept` never posts a transaction or
creates an account.

How the rules apply:
1. **Order of checks:** request validation first, then account existence, then business rules.
   For example, an unknown account plus a negative amount returns 400 `INVALID_AMOUNT`, not 404.
   - **A malformed path ID wins.** It is part of request validation and is checked before the body:
     a malformed ID plus an invalid or malformed body returns 400 with the endpoint's not-found
     code (e.g. `ACCOUNT_MISSING`), not the body's code.
   - **Business rules, in order:** first, the account must hold the currency; then there must be
     enough funds. A currency the account doesn't hold returns 422 `INVALID_CURRENCY`, even for an
     `OUT` larger than any balance.
2. **400 vs 422:** 400 means the request is wrong on its own. 422 means the request is well formed,
   but the account's state rejects it.
3. **One top-level code:** when several fields fail, `code` is taken in this priority:
   currency > direction > amount > description > other. `errors[]` lists all of them, sorted the
   same way (then by field).
4. **Parse errors:** a value that can't be read as its field's type (a wrong JSON type such as
   `"amount": "10.50"` or `"description": 42`, or an unparseable value) gets the code of that field's
   validation rule, and a one-entry `errors[]` with the value's path. For example, `currencies[1]`
   gets `INVALID_CURRENCY`, while `currencies` and `customerId` get `VALIDATION_FAILED`.
   - Parsing stops at the first such value, so the other fields aren't validated: `"amount": "abc"`
     plus a bad currency returns only `INVALID_AMOUNT`.
   - A document that isn't well-formed JSON gets `VALIDATION_FAILED` with no `errors[]`. That
     covers a syntax error (even inside a list), a duplicate key, a number longer than Jackson's
     limit, or a body that isn't a JSON object.
5. **Not-found codes are per endpoint** and use the PDF's own name for that endpoint. The status
   is the same everywhere: 400 if the ID is malformed, 404 if it is well formed but unknown.

## 4. Data model

Flyway `V1__init.sql`. Applied migrations are never edited; changes go in a new `V<n>__*.sql`.

```sql
CREATE TABLE account (
    id          uuid         PRIMARY KEY,
    customer_id varchar(64)  NOT NULL,
    country     char(2)      NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE balance (
    account_id       uuid          NOT NULL REFERENCES account(id),
    currency         varchar(3)    NOT NULL CHECK (currency IN ('EUR','SEK','GBP','USD')),
    available_amount numeric(19,2) NOT NULL DEFAULT 0 CHECK (available_amount >= 0),
    PRIMARY KEY (account_id, currency)
);

CREATE TABLE account_transaction (
    id            uuid          PRIMARY KEY,
    account_id    uuid          NOT NULL REFERENCES account(id),
    currency      varchar(3)    NOT NULL CHECK (currency IN ('EUR','SEK','GBP','USD')),
    direction     varchar(3)    NOT NULL CHECK (direction IN ('IN','OUT')),
    amount        numeric(19,2) NOT NULL CHECK (amount > 0),
    description   varchar(255)  NOT NULL,
    balance_after numeric(19,2) NOT NULL,
    seq           bigserial     NOT NULL,   -- list order
    created_at    timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX account_transaction_account_idx ON account_transaction (account_id, seq);

CREATE TABLE outbox_event (
    id          bigserial    PRIMARY KEY,   -- publish order (per balance = commit order)
    event_id    uuid         NOT NULL UNIQUE,
    routing_key varchar(64)  NOT NULL,
    payload     jsonb        NOT NULL,   -- json since V2
    created_at  timestamptz  NOT NULL DEFAULT now()
);
```

`V2__outbox_payload_json.sql` changes `outbox_event.payload` to `json`.

- **`balance`:** the composite primary key enforces one balance per currency per account.
  `CHECK (available_amount >= 0)` is a backstop behind ADR-0002.
- **`account_transaction`:** named this way to avoid quoting the SQL keyword `transaction`. Its
  `balance_after` comes from the `RETURNING` clause of the balance update. GET transactions orders
  by `seq`, not `created_at`: `now()` is the *transaction start* time, so it can disagree with the
  order rows were written, and ties would fall back to a random UUID. `seq` is assigned at insert,
  after the balance update, while the balance row lock is held, so per balance `seq` order is
  commit order and `balance_after` reads as a running balance.
- **`outbox_event`:** a row is deleted once the broker confirms the message (ADR-0003). The table
  is infrastructure, not a domain record, so it is excluded from "every insert/update is published".
  `payload` is `json`, not `jsonb`: `json` keeps the text exactly as written, so the published body
  is byte for byte what the event mapper serialised. `jsonb` would reorder keys and rewrite
  whitespace.

## 5. Event contract

| Property | Value |
|---|---|
| Exchange | `banking.events`, topic, durable. Declared by the service. |
| Messages | Published to `banking.events` with routing key = `eventType`. JSON body (UTF-8), persistent, `content_type=application/json`, `content_encoding=UTF-8`, `message_id = eventId` |
| Demo queue | `banking.events.all`, durable, bound to `#`, so events are visible in the RabbitMQ UI (localhost:15672). It is capped at 10,000 messages with `x-overflow=drop-head`: nothing consumes it under compose, so when it is full the oldest events are dropped. Real consumers declare and own their own queues. |
| Delivery | At-least-once. Duplicates can follow **any** publish failure, not only a crash: a message whose confirm timed out, or that was nacked, may still have reached a queue, and it is sent again on the next poll. Consumers must dedupe on `eventId`. |
| Ordering | Ordered per balance (account + currency), by outbox `id`. Also, a write that commits before another one starts is published first, because one publisher at a time sends rows in `id` order. The order holds through failures, because a row is sent only after every earlier row is confirmed. Concurrent writes on different balances have no defined order: see ADR-0003. |
| Latency | Asynchronous: an event is published up to one poll interval (~200 ms) after commit. While publishing fails, the poller backs off, doubling from 200 ms up to 5 s. |

The event body is written by its own `JsonMapper`, separate from the HTTP one, so a `spring.jackson.*`
setting can't change this contract. Instants are ISO-8601 strings, amounts are plain JSON numbers with
scale 2, and fields appear in the order listed below.

Every message uses this envelope:
```json
{ "eventId": "…", "eventType": "balance.updated", "occurredAt": "2026-10-02T12:00:00Z",
  "accountId": "…", "data": { … } }
```

Events are emitted per changed record (ADR-0004). `data` is the record's full state after the change.
Within one request, rows are written in a fixed order: `account.created`, then `balance.created`
in currency order; `transaction.created`, then `balance.updated`.

| Routing key = `eventType` | Emitted by | `data` |
|---|---|---|
| `account.created` | Create account | `{accountId, customerId, country}` |
| `balance.created` | Create account (one per currency, in currency order: EUR, GBP, SEK, USD) | `{accountId, currency, availableAmount}` |
| `transaction.created` | Create transaction | `{transactionId, accountId, amount, currency, direction, description, balanceAfter}` |
| `balance.updated` | Create transaction | `{accountId, currency, availableAmount, transactionId}` |

## 6. Tests for the hard success criteria

The full test plan is Stage 4 (`docs/test-plan.md`). The design commits to these three tests:

- **Criterion 3, balances never go negative.**
  - Setup: an account with opening balance B. Fire N concurrent `OUT`s of amount a, mixed with `IN`s.
  - Assert the stored balance never goes below 0.
  - Assert that, with no `IN`s, exactly ⌊B/a⌋ `OUT`s succeed and the rest return 422.
  - Assert opening balance + Σ transactions = final balance, and the last `balanceAfter` by `seq`
    equals the stored balance.
- **Criterion 4, events are never lost.**
  - `docker pause` the RabbitMQ container. Pausing keeps the mapped port, so the app can reconnect;
    a stop/start would give the broker a new port.
  - POST a transaction. Expect 201, with its outbox rows still pending.
  - `docker unpause`. Assert the events arrive on `banking.events.all` and the outbox is empty.
- **No phantom events.** A rejected request (for example insufficient funds) leaves no outbox row
  and publishes nothing.

## 7. Deviations from the PDF

- **Zero amounts are rejected** (400 `INVALID_AMOUNT`). The PDF only names negative amounts. We
  reject zero on purpose: a zero transaction is a meaningless ledger row and would still emit events.
- **Extra response fields:** `country` in Account, and `balanceAfter` in the GET transactions list.
  Both are supersets of the PDF's output and harmless to clients.
- **The account ID is a path parameter** on transaction endpoints rather than a body field.

## 8. Known limitations

- **Balance overflow:** a balance pushed past `NUMERIC(19,2)` by `IN`s fails as a 500
  `INTERNAL_ERROR` (rolled back, logged at ERROR). An amount is capped at 17 integer digits, but two
  requests are enough: `IN 99999999999999999.99`, then `IN 0.01` on the same balance. Deferred: no money
  or event leaks, only the status is wrong. A 422 is a Stage 4 carry-over in `docs/sdlc-plan.md`.
- **No outbox latency tuning:** events are published up to one poll interval (~200 ms) after commit.
- **Publish throughput:** the poller waits for each confirm before sending the next row, which keeps
  per-balance order through a nack (ADR-0003). One publisher therefore sends at most one event per
  broker round trip. The Stage 4 k6 run shows whether this matters.
- **Docker image build skips tests:** the Dockerfile builds with `bootJar`, which runs no tests, because
  Testcontainers can't run inside `docker build`. Tests and the coverage gate belong to
  `./gradlew check` (locally and in CI).

## 9. Revisit as the system grows

- **Auto-open balances:** open a balance when a transaction arrives in a new currency (planned
  extension). This replaces the 422 `INVALID_CURRENCY`.
- **Idempotency keys** on POST, so a retried request can't post twice.
- **Pagination** on GET transactions.
- **`schemaVersion` in the envelope**, once a second consumer exists.
- **A version field in `balance.updated`** (e.g. a per-balance sequence number), so a consumer can
  discard a stale state on its own instead of relying on delivery order. For the README's future work.
- **Demo queue:** the 10,000-message, drop-head cap is a stopgap. Alternatives:
  - a RabbitMQ **stream** (`x-queue-type=stream`) with size or age retention, so history can be
    replayed rather than dropped;
  - no demo queue outside dev, where only real consumers' own queues exist.

  Either one means deleting the existing queue, because RabbitMQ won't change a declared queue's
  arguments. Without a `#`-bound queue, the broker acks an unroutable event and the poller deletes
  it, so the event is lost. Removing the demo queue therefore also needs an alternate exchange with
  a catch-all queue, or `mandatory` plus returns treated as failures (ADR-0003).
- **Poison rows:** a row that is nacked, or never confirmed, on every attempt stalls publishing for
  every row after it. That is deliberate: order over availability. It needs an operator (ADR-0003).
  A dead-letter queue that keeps per-balance order is future work.
- **Outbox:** batch deletes or partitioning at high volume; `LISTEN/NOTIFY` to cut publish latency.
- **Hot accounts:** a single balance row serialises its writers. At very high TPS on one account,
  consider sharded sub-balances.
