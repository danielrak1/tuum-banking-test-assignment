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

### Stack (checked with context7, 2026-10-02)
| Concern | Choice |
|---|---|
| Runtime | Java 25, Spring Boot **4.1.0** (supports Java 17–26), Gradle wrapper 9.x |
| Web | `spring-boot-starter-webmvc`, `spring-boot-starter-validation`, Jackson 3 (`tools.jackson`) |
| Persistence | MyBatis Spring Boot Starter **4.x** (pin the patch at build time), `spring-boot-starter-flyway`, PostgreSQL |
| Messaging | `spring-boot-starter-amqp` (Spring AMQP 4, `JacksonJsonMessageConverter`, correlated publisher confirms) |
| API docs | springdoc-openapi **v3** (`springdoc-openapi-starter-webmvc-ui`), Swagger UI at `/swagger-ui.html` |
| Tests | JUnit 5, `spring-boot-testcontainers` + `@ServiceConnection` (Postgres, RabbitMQ), JaCoCo gate ≥ 0.80 |

## 2. API

| Method | Path | Success | Request → response |
|---|---|---|---|
| POST | `/accounts` | 201 + `Location` | `{customerId, country, currencies[]}` → Account |
| GET | `/accounts/{accountId}` | 200 | Account |
| POST | `/accounts/{accountId}/transactions` | 201 | `{amount, currency, direction, description}` → Transaction |
| GET | `/accounts/{accountId}/transactions` | 200 | `Transaction[]`, oldest first; `[]` if none |

```jsonc
// Account
{ "accountId": "0b6f…", "customerId": "C-1001", "country": "EE",
  "balances": [ { "currency": "EUR", "availableAmount": 0.00 },
                { "currency": "USD", "availableAmount": 0.00 } ] }

// Transaction
{ "accountId": "0b6f…", "transactionId": "7c1e…", "amount": 10.50, "currency": "EUR",
  "direction": "IN", "description": "Salary", "balanceAfter": 10.50 }
```

### Input rules
| Field | Rule |
|---|---|
| `accountId` (path) | UUID |
| `customerId` | String, not blank, ≤ 64 characters. This service doesn't own customers, so the format is free. |
| `country` | `[A-Z]{2}` (ISO 3166 alpha-2 shape) |
| `currencies` | Non-empty, no duplicates, each one of `EUR`, `SEK`, `GBP`, `USD` |
| `currency` | One of `EUR`, `SEK`, `GBP`, `USD`, case-sensitive |
| `direction` | `IN` or `OUT`, case-sensitive |
| `amount` | JSON number, > 0, at most 2 decimals, at most 17 integer digits (`@Positive @Digits(integer=17, fraction=2)`) |
| `description` | Not blank, ≤ 255 characters |

- **`currency` and `direction` are bound as `String`** and validated, not bound as Java enums.
  With enums, a bad value would fail inside Jackson as a generic parse error instead of returning
  the PDF's `INVALID_CURRENCY` / `INVALID_DIRECTION`.
- **IDs are UUIDs generated in the application.** They can't be enumerated, and they are known
  before the insert, which the outbox payloads need.

## 3. Error contract

Errors are RFC 9457 `ProblemDetail` (`application/problem+json`):
- Every error carries a machine-readable `code`, named after the PDF's error wording.
- Validation failures also list every failing field in `errors[]`.

```json
{ "type": "about:blank", "title": "Bad Request", "status": 400,
  "detail": "Request validation failed", "instance": "/accounts/0b6f…/transactions",
  "code": "INVALID_AMOUNT",
  "errors": [ { "field": "amount", "code": "INVALID_AMOUNT", "message": "must be greater than 0" },
              { "field": "description", "code": "DESCRIPTION_MISSING", "message": "must not be blank" } ] }
```

| Case | Status | `code` | PDF error |
|---|---|---|---|
| Currency missing, or not EUR/SEK/GBP/USD (create account or transaction) | 400 | `INVALID_CURRENCY` | Invalid currency |
| Supported currency, but the account has no balance in it | 422 | `INVALID_CURRENCY` | Invalid currency |
| Direction missing, or not `IN`/`OUT` | 400 | `INVALID_DIRECTION` | Invalid direction |
| Amount missing, unparseable, ≤ 0, more than 2 decimals, or more than 17 integer digits | 400 | `INVALID_AMOUNT` | Invalid amount |
| Description missing, blank, or whitespace only | 400 | `DESCRIPTION_MISSING` | Description missing |
| `OUT` larger than the available balance | 422 | `INSUFFICIENT_FUNDS` | Insufficient funds |
| GET account: ID malformed / unknown | 400 / 404 | `ACCOUNT_NOT_FOUND` | Account not found |
| POST transaction: ID malformed / unknown | 400 / 404 | `ACCOUNT_MISSING` | Account missing |
| GET transactions: ID malformed / unknown | 400 / 404 | `INVALID_ACCOUNT` | Invalid account |
| Malformed JSON, bad country, empty or duplicate currencies, blank or too long `customerId`, description > 255 | 400 | `VALIDATION_FAILED` | (not in PDF) |

How the rules apply:
1. **Order of checks:** request validation first, then account existence, then business rules.
   For example, an unknown account plus a negative amount returns 400 `INVALID_AMOUNT`, not 404.
2. **400 vs 422:** 400 means the request is wrong on its own. 422 means the request is well formed,
   but the account's state rejects it.
3. **One top-level code:** when several fields fail, `code` is taken in this priority:
   currency > direction > amount > description > other. `errors[]` lists all of them.
4. **Jackson parse errors** (an unparseable `amount` such as `"abc"`, a wrong JSON type) are mapped
   by field path to that field's code. Anything else gets `VALIDATION_FAILED`.
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
    created_at    timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX account_transaction_account_idx ON account_transaction (account_id, created_at, id);

CREATE TABLE outbox_event (
    id          bigserial    PRIMARY KEY,   -- publish order
    event_id    uuid         NOT NULL UNIQUE,
    routing_key varchar(64)  NOT NULL,
    payload     jsonb        NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now()
);
```

- **`balance`:** the composite primary key enforces one balance per currency per account.
  `CHECK (available_amount >= 0)` is a backstop behind ADR-0002.
- **`account_transaction`:** named this way to avoid quoting the SQL keyword `transaction`. Its
  `balance_after` comes from the `RETURNING` clause of the balance update.
- **`outbox_event`:** a row is deleted once the broker confirms the message (ADR-0003). The table
  is infrastructure, not a domain record, so it is excluded from "every insert/update is published".

## 5. Event contract

| Property | Value |
|---|---|
| Exchange | `banking.events`, topic, durable. Declared by the service. |
| Messages | JSON, persistent, `content_type=application/json`, `message_id = eventId` |
| Demo queue | `banking.events.all`, bound to `#`, so events are visible in the RabbitMQ UI (localhost:15672). Real consumers declare and own their own queues. |
| Delivery | At-least-once. Consumers must dedupe on `eventId`. |
| Ordering | Events are published in commit order (outbox `id`); see ADR-0003. |

Every message uses this envelope:
```json
{ "eventId": "…", "eventType": "balance.updated", "occurredAt": "2026-10-02T12:00:00Z",
  "accountId": "…", "data": { … } }
```

Events are emitted per changed record (ADR-0004). `data` is the record's full state after the change.

| Routing key = `eventType` | Emitted by | `data` |
|---|---|---|
| `account.created` | Create account | `{accountId, customerId, country}` |
| `balance.created` | Create account (one per currency) | `{accountId, currency, availableAmount}` |
| `transaction.created` | Create transaction | `{transactionId, accountId, amount, currency, direction, description, balanceAfter}` |
| `balance.updated` | Create transaction | `{accountId, currency, availableAmount, transactionId}` |

## 6. Tests for the hard success criteria

The full test plan is Stage 4 (`docs/test-plan.md`). The design commits to these three tests:

- **Criterion 3, balances never go negative.**
  - Setup: an account with opening balance B. Fire N concurrent `OUT`s of amount a, mixed with `IN`s.
  - Assert the stored balance never goes below 0.
  - Assert that, with no `IN`s, exactly ⌊B/a⌋ `OUT`s succeed and the rest return 422.
  - Assert opening balance + Σ transactions = final balance, and the last `balanceAfter` equals
    the stored balance.
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

- **Balance overflow:** a balance pushed past `NUMERIC(19,2)` by `IN`s is not handled gracefully.
  An individual amount is capped at 17 integer digits, so this is unrealistic.
- **No outbox latency tuning:** events are published up to one poll interval (~200 ms) after commit.
- **Docker image build skips tests:** the Dockerfile builds with `bootJar -x test`, because
  Testcontainers can't run inside `docker build`. Tests and the coverage gate belong to
  `./gradlew check` (locally and in CI).

## 9. Revisit as the system grows

- **Auto-open balances:** open a balance when a transaction arrives in a new currency (planned
  extension). This replaces the 422 `INVALID_CURRENCY`.
- **Idempotency keys** on POST, so a retried request can't post twice.
- **Pagination** on GET transactions.
- **`schemaVersion` in the envelope**, once a second consumer exists.
- **Outbox:** batch deletes or partitioning at high volume; `LISTEN/NOTIFY` to cut publish latency.
- **Hot accounts:** a single balance row serialises its writers. At very high TPS on one account,
  consider sharded sub-balances.
