# Intent: Core Banking Account Service

## What
A small core-banking service that keeps track of customer accounts, their
per-currency balances, and the transaction history, exposed through a REST API.
Every insert and update is published to RabbitMQ so other services can react.

## Why
Practise the AI-native SDLC flow (plan → design → build → test → deploy →
maintain) on a realistic, bounded problem. "Done" means the Tuum Software
Engineer Test Assignment is met as written. Polish that doesn't serve learning
the flow is optional.

## Who consumes it
- API clients: create accounts, post transactions, read balances and history.
- Downstream consumers: read events from RabbitMQ and rely on every change
  being published in a predictable shape.

## In scope
| Capability         | Behaviour | Errors |
|--------------------|-----------|--------|
| Create account     | Customer ID, country, currencies (EUR/SEK/GBP/USD). One balance per currency, starting at 0. | Invalid currency |
| Get account        | Account ID, customer ID, balances (available amount, currency) | Account not found |
| Create transaction | Account ID, amount, currency, direction (IN/OUT), description. IN adds to and OUT subtracts from the balance in that currency. Returns the transaction plus the balance after it. A currency the account wasn't opened with is rejected. | Invalid currency, invalid direction, invalid amount (negative), insufficient funds, account missing, description missing |
| Get transactions   | All transactions for an account | Invalid account |
| Events             | Every insert/update (account, balance, transaction) published to RabbitMQ | none |
| API docs           | Swagger UI (springdoc OpenAPI) so reviewers can explore and call the API from a browser | none |

## Out of scope (now)
Authentication and authorisation · currency conversion · pagination ·
idempotency keys · closing accounts · reversing transactions · overdrafts ·
front-end / UI.

## Planned extensions (not now)
- Auto-open a balance (sub-account) when a transaction arrives in a new currency.
- Idempotency keys, so a retried POST can't create duplicate transactions.
- A front-end for browsing accounts and transactions.

## Constraints
- Stack (mandated): Java 17+ (we use 25 LTS), Spring Boot, MyBatis, Gradle,
  PostgreSQL, RabbitMQ, JUnit.
- Money is never a floating-point number.
- A transaction and its balance change succeed or fail together.
- Integration tests run against real Postgres and RabbitMQ, with ≥ 80% coverage
  enforced by the build.
- A fresh clone runs with `docker compose up`: no configuration changes and no
  local Java or Gradle needed. The code is hosted on GitHub.

## Success criteria
1. Every API behaviour and error listed above has a passing automated test.
2. Coverage of at least 80%, with the build failing below that.
3. Balances never go negative, even when many transactions hit the same account
   at once (proven by a concurrency test).
4. Events are never lost: if a change is committed, its event is eventually
   published (proven by a test).
5. A fresh clone plus `docker compose up --build` gives a working API.
6. The README covers build and run instructions, key design choices, a measured
   TPS estimate, horizontal scaling considerations, and how AI was used.

## Open questions (resolve in Design)
- Event mechanism: publish-after-commit or transactional outbox (driven by #4).
- Event granularity: one event per changed record, or one per operation?
- Amount precision: 2 decimals for all four currencies?
- "Invalid account" on Get transactions: 404 like Get account, or 400?
- Should a zero amount be rejected? The spec only says negative.