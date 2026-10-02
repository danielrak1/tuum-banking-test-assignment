# ADR-0002: Balance concurrency

**Status:** Accepted
**Date:** 2026-10-02
**Deciders:** Daniel Rak

## Context
- Success criterion 3 requires that balances never go negative, even when many transactions hit
  the same account at once, and a concurrency test must prove it.
- intent.md requires that a transaction and its balance change succeed or fail together.
- The response must carry the *exact* balance after the transaction.
- A naive read-check-write in Java races: two concurrent `OUT`s both read 100, both pass a
  `>= 60` check, and the balance ends at −20.

## Decision
Inside one `@Transactional` service call (PostgreSQL default, READ COMMITTED):

1. **Read the account and its balances.**
   - Unknown account → 404 (`ACCOUNT_MISSING`).
   - No balance in the requested currency → 422 `INVALID_CURRENCY`.
2. **Apply the change as one atomic conditional update:**
   ```sql
   UPDATE balance
      SET available_amount = available_amount + #{delta}   -- delta = +amount for IN, −amount for OUT
    WHERE account_id = #{accountId}
      AND currency   = #{currency}
      AND available_amount + #{delta} >= 0
   RETURNING available_amount
   ```
   - **0 rows → 422 `INSUFFICIENT_FUNDS`.** Step 1 proved the row exists, and balances are never
     deleted, so this is the only possible reason.
   - **1 row:** the returned value is `balanceAfter`.
3. **Insert `account_transaction`** with that `balance_after`, then the outbox rows (ADR-0003).
4. **Backstop:** `CHECK (available_amount >= 0)` on `balance`. If the code ever got the condition
   wrong, the database would still refuse a negative balance.

## Options Considered

### Option A: Atomic conditional `UPDATE … RETURNING` (chosen)
| Dimension | Assessment |
|---|---|
| Complexity | Low. One SQL statement, no retry loop. |
| Cost | None |
| Scalability | One round trip; the row lock is held only from the update until commit |
| Team familiarity | Medium. It relies on knowing Postgres re-checks `WHERE` after a lock wait. |

**Pros:**
- Correct by construction. A concurrent writer blocks on the row lock. Once the first commits,
  Postgres re-evaluates the `WHERE` against the *new* row version.
- `RETURNING` gives an exact `balanceAfter` with no extra read.

**Cons:** the business rule ("enough funds") lives in SQL, so it needs a comment and a test.

### Option B: `SELECT … FOR UPDATE`, check in Java, then `UPDATE`
| Dimension | Assessment |
|---|---|
| Complexity | Low–medium |
| Cost | None |
| Scalability | 2 round trips, with the lock held between them |
| Team familiarity | High |

**Pros:** the rule is in Java and reads naturally.
**Cons:** it is more statements, a longer lock, and an easy-to-forget `FOR UPDATE` that silently
reintroduces the race.

### Option C: Optimistic locking (`version` column + retry)
| Dimension | Assessment |
|---|---|
| Complexity | Medium. Retry loop, backoff, retry limit. |
| Cost | None |
| Scalability | Poor on hot accounts: contention turns into retry storms |
| Team familiarity | High |

**Pros:** no long-held locks.
**Cons:** the concurrency test, by design, creates exactly the contention where this degrades.

### Option D: `SERIALIZABLE` isolation
| Dimension | Assessment |
|---|---|
| Complexity | Medium. Every caller must handle serialization failures and retry. |
| Cost | None |
| Scalability | Worse under contention |
| Team familiarity | Low–medium |

**Pros:** protects every invariant without per-query care.
**Cons:** overkill for one row-level invariant, and it still needs retries.

## Trade-off Analysis
- **A vs B:** both are pessimistic and correct. A wins on round trips, lock duration, and having
  no way to forget the lock.
- **C and D:** both trade locks for retries, which is the wrong trade when the workload is
  contention on one row.
- **Cost of A:** the rule lives in SQL. The schema `CHECK` and the concurrency test make that
  explicit and verified rather than hidden.

## Consequences
- **Easier:**
  - The concurrency test is deterministic to reason about.
  - `balanceAfter` is always exact.
  - Multiple service instances need no coordination, because Postgres does it.
- **Harder:**
  - Every balance change *must* go through this one mapper method. A plain `UPDATE … SET
    available_amount = ?` anywhere else would bypass the rule. `banking-reviewer` checks for it.
  - Writes to one account are serialised on its balance row, so its throughput is bounded by
    commit latency. This goes in the README's TPS and scaling sections.
- **Revisit:** for extreme single-account TPS, split the balance into sub-balance rows or batch postings.

## Action Items
1. [ ] Add the `BalanceMapper.applyDelta(accountId, currency, delta)` mapper method, returning `Optional<BigDecimal>`.
2. [ ] Add `CHECK (available_amount >= 0)` in `V1__init.sql`.
3. [ ] Write the concurrency integration test (design.md §6): N parallel `OUT`s mixed with `IN`s.
   Assert the balance never goes below 0, that exactly ⌊B/a⌋ `OUT`s succeed, and that the sums reconcile.
4. [ ] Add to `CLAUDE.md`: "balances change only through `applyDelta`".
