# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Core banking account service: accounts, per-currency balances, transactions, REST API, every
change published to RabbitMQ. What and why: [`intent.md`](intent.md). How: [`docs/design.md`](docs/design.md)
and the ADRs in [`docs/adr/`](docs/adr/). SDLC stages and status: [`docs/sdlc-plan.md`](docs/sdlc-plan.md).

## Commands
Gradle is not installed locally; always use the wrapper. Docker must be running (Testcontainers).

```sh
./gradlew check                                   # compile, all tests, JaCoCo gate (lines ≥ 0.80, branches ≥ 0.70)
./gradlew test --tests 'com.danielrak.banking.SomeTest'           # one class
./gradlew test --tests 'com.danielrak.banking.SomeTest.someMethod' # one method
docker compose up -d                              # Postgres :5432, RabbitMQ :5672, UI :15672 (guest/guest)
./gradlew bootRun                                 # app on :8080 against compose; Swagger UI at /swagger-ui.html
```
Coverage report: `build/reports/jacoco/test/html/index.html`.

## Stack
Java 25 · Spring Boot 4.1 (`spring-boot-starter-webmvc`, Jackson 3 = `tools.jackson`) · MyBatis
starter 4.1 · Flyway · Spring AMQP · springdoc 3 · JUnit 5 + Testcontainers 2. Versions are pinned in
`build.gradle`; check APIs against current docs (context7), since Boot 4 renamed many starters and packages.

## Layout (`com.danielrak.banking`)
| Package | Holds |
|---|---|
| `api` | Controllers, DTOs, Bean Validation, `ProblemDetail` advice |
| `domain` | `@Transactional` services, business rules |
| `persistence` | MyBatis mappers |
| `messaging` | Outbox writer, outbox poller, RabbitMQ topology |

Schema lives in `src/main/resources/db/migration/` and Flyway applies it on startup.

## Hard rules
- **Money** ([ADR-0001](docs/adr/0001-money-handling.md)): `BigDecimal` scale 2 ↔ `NUMERIC(19,2)`.
  Never `float`/`double`, `new BigDecimal(double)` or `BigDecimal.valueOf(double)`, not even in tests.
  Compare with `compareTo`, never `equals`. Reject more than 2 decimals; never round at the boundary.
- **Balances** ([ADR-0002](docs/adr/0002-balance-concurrency.md)): change only through
  `BalanceMapper.applyDelta` (the conditional `UPDATE … WHERE available_amount + delta >= 0 RETURNING`).
  0 rows → 422 `INSUFFICIENT_FUNDS`. No other `UPDATE balance` anywhere.
- **Events** ([ADR-0003](docs/adr/0003-event-delivery-outbox.md)): never publish to RabbitMQ from a
  service. Insert `outbox_event` rows in the same transaction, *after* the balance update; only
  the outbox poller publishes. Ordering is guaranteed per balance, not globally.
- **Event shape** ([ADR-0004](docs/adr/0004-event-granularity.md)): one event per changed record,
  full state after the change; envelope and routing keys in design.md §5.
- **Errors** ([design.md §3](docs/design.md#3-error-contract)): RFC 9457 `ProblemDetail` with the
  `code` from the table there. Validation, then account existence, then business rules.
  Bind `currency`/`direction` as `String`, not enums.
- **IDs** are UUIDs generated in the application. Transaction lists are ordered by `seq`, not `created_at`.
- **Migrations:** never edit an applied Flyway migration; add `V<n>__*.sql`.
- **Tests:** Testcontainers against real Postgres and RabbitMQ, never H2 or mocked brokers. Every
  integration test uses `@Import(TestcontainersConfiguration.class)`: no per-class `@Container`
  fields or extra context config, so the cached context and its containers are shared. Event
  assertions wait with Awaitility; publishing is asynchronous.
