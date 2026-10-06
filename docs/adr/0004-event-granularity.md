# ADR-0004: Event granularity

**Status:** Accepted
**Date:** 2026-10-02
**Deciders:** Daniel Rak

## Context
The PDF says the service "must publish all insert and update operations to RabbitMQ". The API
operations that write are:
- **Create account:** inserts one `account` row and N `balance` rows.
- **Create transaction:** inserts one `account_transaction` row and updates one `balance` row.

The open question in intent.md is whether we publish one event per changed record or one per API
operation. Consumers rely on "every change being published in a predictable shape".

## Decision
- **One event per changed record.** The routing key equals the event type:

  | Operation | Events |
  |---|---|
  | Create account | `account.created`, then `balance.created` × N (one per currency) |
  | Create transaction | `transaction.created`, then `balance.updated` |

- **Each payload carries the record's full state after the change** (state transfer, not a delta).
  A consumer never has to call back to the API.
- **`balance.updated` also carries the `transactionId`** that caused it.
- **Envelope:** `{eventId, eventType, occurredAt, accountId, data}`. See design.md §5.

## Options Considered

### Option A: One event per changed record (chosen)
| Dimension | Assessment |
|---|---|
| Complexity | Low. One event type per table and operation. |
| Cost | More messages (1 + N per account, 2 per transaction) |
| Scalability | Fine at this volume |
| Team familiarity | High. It mirrors the data model. |

**Pros:**
- Matches the PDF wording literally, so a reviewer can tick off each insert/update.
- Easy to test: one assertion per changed row.
- A balance-only consumer just binds `balance.*`.

**Cons:** a consumer that wants "a transaction happened, and here is the new balance" receives
two events and relates them by `transactionId`.

### Option B: One business event per operation
`account.opened` carries its balances; `transaction.posted` carries `balanceAfter`.

| Dimension | Assessment |
|---|---|
| Complexity | Low |
| Cost | Fewest messages |
| Scalability | Fine |
| Team familiarity | Medium. Domain-event style. |

**Pros:** each event is self-contained and meaningful to the business.
**Cons:** the balance *update* is only implied, inside another event. That is a weaker match to
"publish all insert and update operations", and harder to verify against the spec.

### Option C: Option A plus a `correlationId` per API call
| Dimension | Assessment |
|---|---|
| Complexity | Low–medium. An ID threaded through the service layer. |
| Cost | One envelope field |
| Scalability | Fine |
| Team familiarity | Medium |

**Pros:** consumers can regroup the events of one operation.
**Cons:** no current consumer or success criterion needs it. The simplicity review cut it:
`transactionId` on `balance.updated` covers the one real correlation.

## Trade-off Analysis
- **The deciding factor is the spec's wording.** "All insert and update operations" maps one-to-one
  onto per-record events.
- **What A costs:** consumers do slightly more correlation. That is mitigated by `transactionId`
  on `balance.updated` and by `accountId` in every envelope.
- **Message volume** is irrelevant at this scale.
- **Option C** stays available as a non-breaking, additive envelope field if a consumer asks for it.

## Consequences
- **Easier:** spec compliance is obvious; event tests mirror the DB changes; consumers subscribe
  by record type.
- **Harder:** a business-level consumer must combine `transaction.created` and `balance.updated`.
- **Revisit:**
  - Add `correlationId`, and `schemaVersion`, when a second consumer appears.
  - Consider business events on a separate exchange if downstream teams want domain semantics.

## Action Items
1. [x] Add event records for the four types, and a shared envelope. They are serialised once, by the event `JsonMapper` (`EventJson`), when the outbox row is written. The publisher sends that JSON as is, with no message converter.
2. [x] Services write outbox rows in this order:
   - create account: account, then balances;
   - create transaction: transaction, then balance.
3. [x] Integration tests consume `banking.events.all` and assert the exact event types, order and
   payloads for each operation.
