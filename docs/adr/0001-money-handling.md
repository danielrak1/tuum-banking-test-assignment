# ADR-0001: Money handling

**Status:** Accepted
**Date:** 2026-10-02
**Deciders:** Daniel Rak

## Context
- The service stores balances and transaction amounts in EUR, SEK, GBP and USD.
- intent.md requires that money is never a floating-point number.
- ISO 4217 gives all four currencies 2 minor units.
- The PDF only says a negative amount is invalid. It says nothing about precision, zero, or range.
- Amounts cross three boundaries: JSON ↔ Java ↔ PostgreSQL. Every one of them must be exact.

## Decision
- **Java:** `BigDecimal`, normalised to scale 2 with `setScale(2, RoundingMode.UNNECESSARY)`.
  `UNNECESSARY` throws instead of rounding, which asserts that validation already ran.
- **PostgreSQL:** `NUMERIC(19,2)`. The maximum is 17 integer digits plus 2 decimals.
- **JSON:** a number (`10.50`). Jackson reads it straight into `BigDecimal` and writes it back
  without passing through `double`.
- **Validation:** `@Positive @Digits(integer = 17, fraction = 2)`.
  - Zero is rejected.
  - More than 2 decimals is rejected. The API never rounds.
  - Anything outside `NUMERIC(19,2)` is rejected.
  - All of these return 400 `INVALID_AMOUNT`.
  - `10.500` is accepted as 10.50, because the validator strips trailing zeros first.
- **Code rules** (these go into `CLAUDE.md`):
  - Compare amounts with `compareTo`, never `equals` (`10.0` is not `equals` `10.00`).
  - Never use `new BigDecimal(double)` or `BigDecimal.valueOf(double)` on amounts.
  - Never use `float` or `double` for money anywhere, including tests.

## Options Considered

### Option A: `BigDecimal` scale 2 ↔ `NUMERIC(19,2)` (chosen)
| Dimension | Assessment |
|---|---|
| Complexity | Low. Maps natively in JDBC, MyBatis and Jackson. |
| Cost | None |
| Scalability | Fine. `BigDecimal` cost is negligible next to I/O. |
| Team familiarity | High. It is the standard in Java finance code. |

**Pros:** exact; readable in code and in SQL; no conversions at the boundaries.
**Cons:** `equals` vs `compareTo` and `double`-constructor traps, so the code rules above are needed.

### Option B: `long` minor units ↔ `BIGINT`
| Dimension | Assessment |
|---|---|
| Complexity | Medium. ×100 / ÷100 at every boundary, and in every test fixture. |
| Cost | None |
| Scalability | Marginally faster arithmetic |
| Team familiarity | Common in payment processors (card networks, Stripe) |

**Pros:** exact; no scale bugs; fast.
**Cons:** amounts are unreadable in the DB and in logs; the "2 decimals" assumption is hard-coded
and breaks for JPY (0) or KWD (3); the JSON contract either exposes minor units or converts.

### Option C: JSR-354 (`javax.money`, Moneta) `MonetaryAmount`
| Dimension | Assessment |
|---|---|
| Complexity | High. New dependency, custom MyBatis type handlers, Jackson module. |
| Cost | Dependency upkeep |
| Scalability | Fine |
| Team familiarity | Low |

**Pros:** real currency semantics, and each currency's own precision.
**Cons:** heavy for four fixed 2-decimal currencies with no conversion (conversion is out of scope).

## Trade-off Analysis
- **Exactness:** A and B are both exact; the real difference is readability and boundary friction.
  A has no conversions anywhere, and the SQL reads like the domain (`available_amount >= 0`).
- **Choosing B or C:** B pays off at very high volume. C pays off with many currencies or FX.
  Neither applies here.
- **Reject, don't round:** this follows banking practice for posting APIs. Rounding belongs inside
  calculations (interest, FX, fees), with an explicit mode such as `HALF_EVEN`. It does not belong
  at the boundary, where an amount like €10.555 is a client bug that silent rounding would hide.

## Consequences
- **Easier:** SQL and JSON are human-readable, and assertions in tests are simple.
- **Harder:** reviewers must watch for `equals` and `double` misuse. `banking-reviewer` checks for it.
- **Deliberate deviation:** zero amounts are rejected although the PDF only names negative amounts
  (see design.md §7).
- **JS clients:** numbers above 2^53 lose precision in JavaScript. The README tells JS clients to
  parse amounts as decimals.
- **Revisit:** if currencies with other precisions are added, make scale per-currency
  (or move to Option C).
- **Known limitation:** a balance growing past `NUMERIC(19,2)` through repeated `IN`s is not
  handled gracefully; it's unrealistic at 17 integer digits.

## Action Items
1. [ ] DTO validation: `@NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal amount`.
2. [ ] Map Jackson parse errors on `amount` to 400 `INVALID_AMOUNT`.
3. [ ] Tests:
   - zero, negative, `10.555`, `1e18`, `"abc"` and a missing amount all give 400 `INVALID_AMOUNT`;
   - `10.500` is accepted;
   - the response amount has scale 2.
4. [ ] Add the money rules to `CLAUDE.md`.
