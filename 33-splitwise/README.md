# Topic 33: Splitwise

"Alice paid for dinner, Bob paid for the taxi, who pays whom?" sounds like a bit of arithmetic. The interviewer is waiting for what breaks: 100 cents cannot be split three ways without losing a cent, percents and exact amounts can be typed wrong, people want fewer payments than "everyone pays everyone", and a trip abroad mixes currencies.

A staff answer splits with integers only, keeps one net per person that always adds up to exactly 0, simplifies debts with a greedy rule while saying out loud that the true minimum is NP-hard, and keeps history append-only so balances can be rebuilt.

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [45-Minute Interview Timeline](#5-45-minute-interview-timeline)
6. [Senior vs Staff Rubric](#6-senior-vs-staff-rubric)
7. [Key Takeaways](#7-key-takeaways)

---

## 1. The Problem

### Worked example first

Money is in cents (minor units). Alice pays 90.00 for dinner shared by Alice, Bob and Carol. Bob pays 30.00 for a taxi shared by Alice and Bob. A *net* is what a person is owed (positive) or owes (negative) overall.

| Step | Alice | Bob | Carol | Sum |
|---|---|---|---|---|
| Dinner: Alice paid 9000, each owes 3000 | +9000 -3000 = +6000 | -3000 | -3000 | 0 |
| Taxi: Bob paid 3000, Alice and Bob owe 1500 each | +6000 -1500 = +4500 | -3000 +3000 -1500 = -1500 | -3000 | 0 |
| Nets after both | +4500 | -1500 | -3000 | **0** |

The sum is always 0, because every cent someone paid is a cent someone owes. Now simplify. Without it, the pairs are Bob to Alice 15.00 and Carol to Alice 30.00, which here is already 2 payments. With more people it gets long, so we use the nets: pair the biggest debtor with the biggest creditor.

| Step | Biggest debtor | Biggest creditor | Transfer | Nets left |
|---|---|---|---|---|
| 1 | Carol -3000 | Alice +4500 | Carol pays Alice 3000 | Alice +1500, Bob -1500, Carol 0 |
| 2 | Bob -1500 | Alice +1500 | Bob pays Alice 1500 | all 0 |

Two payments settle three people. The test is `GroupTest.readmeExample`.

### v1 requirements (assumed answers)

| Question | Assumed answer |
|---|---|
| Money type? | `long` minor units. Never `double`. |
| Split kinds? | Equal, exact amounts, percent (basis points), shares (weights). |
| Who pays? | One payer per expense. |
| Spare cent? | Largest remainder (see [Topic 11](../11-money-ids-idempotency/)). Tie: smaller user id. |
| Bad input? | `IllegalArgumentException`, and nothing is written. |
| Out of scope | Users, login, notifications, multiple payers in one expense, editing old expenses, real FX feeds, a REST layer. |

---

## 2. How It Solves It

### Entities and invariants

| Entity | Meaning |
|---|---|
| `SplitStrategy` | One interface, four rules (`Splits.equal/exact/percent/shares`). Turns a total into a share per person. |
| `ExpenseEntry` / `PaymentEntry` | Immutable history lines: "payer paid total, these shares" and "A really paid B". |
| `ExpenseGroup` | A group with members, an append-only history and running nets per currency. |
| `Debts` | Greedy simplifier and the exact solver. |
| `FxRates` / `Fx` | Injected rate (micros) and a rounding rule that keeps the sum at 0. |

- I1: the shares of an expense add up to its total exactly, and none is negative.
- I2: in every currency the nets add up to exactly 0.
- I3: the history is append-only. Nets are a pure function of the history (`rebuild == incremental`).
- I4: a rejected call changes nothing.

### Splitting (the Strategy pattern)

Largest remainder is taught in [Topic 11](../11-money-ids-idempotency/); here is only what Splitwise adds. Each person gets the rounded-down share, then the left-over cents go to the largest remainders, ties to the smaller user id.

| Input | Result |
|---|---|
| 100 equal among bob, alice, carol | alice 34, bob 33, carol 33 (alice has the smallest id, so alice gets the extra cent) |
| 101 equal among a, b, c | a 34, b 34, c 33 |
| 100 at 33.33%, 33.33%, 33.34% (3333, 3333, 3334 basis points) | 33, 33, 34 |
| 10 shares 1:2 | 3 and 7 |
| Exact 70 + 29 against total 100 | rejected |

A basis point is 1/100 of a percent, so 10000 means 100% and no `double` is needed. All maths is `Math.multiplyExact`, so overflow throws instead of wrapping.

### The interface and its error contract

```java
public interface SplitStrategy {
    Map<String, Long> split(long total, Map<String, Long> input);
    // IllegalArgumentException: total <= 0, null/empty input, negative value, exact sum != total, percent sum != 10000, all weights zero. ArithmeticException: overflow (never wraps).
    // Result: no negative share, sums to total EXACTLY. The built-in strategies return a map in user-id order, but the group does not rely on that.
}

public interface ExpenseGroup {
    ExpenseEntry addExpense(String payer, String currency, long total, SplitStrategy s, Map<String, Long> input);
    PaymentEntry recordPayment(String from, String to, String currency, long amount);
    Map<String, Long> nets(String currency);      // sums to 0; members with no activity show 0
    List<Transfer> pairwise(String currency);     // who owes whom, one direction per pair
    List<Transfer> simplify(String currency);     // greedy
    List<Transfer> settleUpIn(String target);     // F2: convert then simplify
    List<Entry> history();
    // IllegalArgumentException: null, stranger, bad currency (3 capitals, checked on reads too), amount <= 0, missing rate, a strategy result that is not members-only, non-negative and exactly the total. ArithmeticException: a balance would overflow; nothing is written.
}
```

### Class and sequence diagrams

```
 ExpenseGroup <|-- LockedExpenseGroup ----> history: List<Entry> (append-only)
      |  uses                                      ExpenseEntry | PaymentEntry (sealed Entry)
      +--> SplitStrategy <-- Splits.equal/exact/percent/shares --> Allocator (largest remainder)
      +--> Debts.greedy / Debts.optimalCount
      +--> FxRates (injected) --> Fx.convert (remainder-absorbing)

 addExpense(payer, USD, 9000, equal, {a,b,c})
   caller --> group: validate members/currency
   group  --> strategy.split()     (throws? nothing written)
   group  : lock; append ExpenseEntry; nets[payer] += total; nets[u] -= share; unlock
   caller <-- ExpenseEntry
```

### The algorithm and its cost

Greedy simplification, from the example above. Keep two max-heaps, creditors and debtors (ties: smaller id). Pop the top of each, transfer the smaller of the two amounts, push back whoever still has a balance. Each step zeroes at least one person and the last step zeroes two, so the result has at most k-1 transfers for k people with a non-zero net. It always settles everyone: the loop only ends when no creditor or debtor is left, and because the nets sum to 0, they run out together. Cost O(k log k). Tests: `greedySettlesWithAtMostNMinusOneAndNeverBeatsOptimal` (3000 seeded cases).

It is not always optimal. Take nets -9, -8, +2, +7, +8.

| Plan | Transfers | How |
|---|---|---|
| Greedy | 4 | 9 pays 8 (1 left), 8 pays 7 (1 left), then 1 pays 2, 1 pays 2 |
| Optimal | 3 | Group {-9, +2, +7} needs 2, group {-8, +8} needs 1 |

Why: split the people into groups that each sum to 0 inside. A group of g people needs g-1 transfers, so with k non-zero people the count is k minus the number of groups. The optimum is therefore k minus the MOST zero-sum groups you can carve out.

Hardness sketch (my own reduction, not from a paper): the optimum is below k-1 exactly when some proper non-empty subset of the nets sums to 0. To encode a subset-sum instance (positive numbers x1..xm and a target T, assume 0 < T < S where S is their sum, other cases are trivial), use the nets +x1..+xm, then -T and -(S-T). A proper zero-sum subset exists exactly when some subset of the x's adds up to T. Subset-sum is NP-complete, so this shows weak NP-hardness, which is enough to say "NP-hard". Verhoeff (see References) states the same result in one line.

Exact solver, `Debts.optimalCount`: bitmask DP. `best[mask]` is the most zero-sum groups inside subset `mask`. Adding one person finishes at most one more group, and only if the subset now sums to 0: `best[mask] = max over i in mask of best[mask minus i] + (sum[mask] == 0 ? 1 : 0)`. Answer is k - best[all]. Cost O(2^k · k) time, O(2^k) memory, refused above 20 people. `dpMatchesBruteForce` checks it against a plain recursive search over all groupings (400 seeded cases up to 8 people).

### F2 choice: per-currency balances, convert only at settle-up

A trip mixes EUR and USD. We keep one set of nets per currency, so every expense stays exactly in the money it was paid in. Only `settleUpIn("USD")` converts, once, at the injected rate. A rate is in micros (1_080_000 means 1.08). Rounding rule: round each net DOWN (floor), then the total is a few units below 0, so give +1 to the largest rounding remainders (ties: smaller id) until the total is exactly 0. This is the same largest-remainder trick, so no one moves by more than 1 unit and the converted nets still sum to 0. Tests: `conversionIsDeterministicAndNetsToZero`, `conversionPropertyHolds`, `settleUpInConvertsAtTheEnd`. Rates are minor-unit to minor-unit, so this topic assumes the same number of minor digits (USD, EUR); JPY (0 digits) needs scaling, not done here.

### The concurrency model

| Question | Answer |
|---|---|
| Shared state | The history list and the running nets of one group. |
| Invariant | `seq` numbers are 1..n with no gaps and I2 holds at every lock release. |
| Primitive | One `ReentrantLock` per group. Splitting (pure) runs before the lock; only append and update are inside. |
| Why | Expenses in a group must be ordered, and groups never touch each other, so there is nothing to deadlock on. |
| Test | `concurrentExpensesKeepExactTotals`: 8 threads released by a start gate add 2000 expenses; exact totals, gap-free `seq`, rebuild equals running. |

### Code and tests

| File | Role |
|---|---|
| [`SplitStrategy`](src/main/java/com/lld/splitwise/SplitStrategy.java), [`Splits`](src/main/java/com/lld/splitwise/Splits.java), [`Allocator`](src/main/java/com/lld/splitwise/Allocator.java) | Interface and error contract, the four rules, largest remainder. |
| [`ExpenseGroup`](src/main/java/com/lld/splitwise/ExpenseGroup.java), [`LockedExpenseGroup`](src/main/java/com/lld/splitwise/LockedExpenseGroup.java) | Group interface, history, nets, pairwise, lock. |
| [`Entry`](src/main/java/com/lld/splitwise/Entry.java), [`ExpenseEntry`](src/main/java/com/lld/splitwise/ExpenseEntry.java), [`PaymentEntry`](src/main/java/com/lld/splitwise/PaymentEntry.java), [`Transfer`](src/main/java/com/lld/splitwise/Transfer.java) | Immutable records. |
| [`Debts`](src/main/java/com/lld/splitwise/Debts.java) | F1 greedy and exact solver. |
| [`FxRates`](src/main/java/com/lld/splitwise/FxRates.java), [`Fx`](src/main/java/com/lld/splitwise/Fx.java) | F2 rates and remainder-absorbing conversion. |
| `SplitStrategyTest` (11) | Tie-break with a LinkedHashMap in non-sorted order, 11 shares 1:2, rejections, overflow, seeded property test that also checks the largest-remainder rule itself. |
| `DebtsTest` (8) | Settles exactly, at most k-1, counterexample 4 vs 3, DP vs brute force, solver boundary at 20 and 21 people, no wrap in subset sums. |
| `GroupTest` (20) | Worked example, nets sum to 0, FX, immutability, overflow writes nothing, bad strategy output, nulls, currency checks, pairwise per currency, rebuild == incremental == an independent model, concurrency. |

---

## 3. The Gaps

### F1: Debt simplification

Done above: greedy (at most k-1 transfers, O(k log k)), the counterexample (4 vs 3), and the exact DP for small k. In production, run the greedy and offer the exact solver only for tiny groups. Greedy is at most k-1 and the optimum is at least ceil(k/2) (every zero-sum group has at least 2 people), so greedy is at most 2·OPT − 1. That bound holds for ANY plan with at most k-1 transfers (for example everyone pays one hub), so the heaps buy nothing in the worst case; they only make the typical answer shorter. This is my reasoning, not from a source.

### F2: Multi-currency

Done above. Per-currency nets, one conversion at settle-up, floor plus remainder-absorbing so the sum stays 0.

### F3: Groups and settle-up history

`ExpenseGroup` is one group. A settle-up is a `PaymentEntry` line that moves the nets, so history is never edited and an audit can replay it. `LockedExpenseGroup.rebuild(history)` recomputes the nets from the lines alone, and `rebuildEqualsIncremental` checks it equals the running nets after 300 mixed random actions. Corrections are new lines (a reversing expense), not edits.

### Misconceptions and common mistakes

| Mistake | Why wrong |
|---|---|
| Money as `double` | 0.1 + 0.2 is not 0.3, and the nets stop adding up to 0. Use `long` cents. |
| `total / n` for each person | 100 / 3 three times loses a cent. Use largest remainder with a stated tie-break. |
| Letting the tie-break be "whoever is first in a HashMap" | Not deterministic. Sort by user id. |
| "Greedy gives the fewest transfers" | It gives at most k-1, not the fewest. Counterexample: 4 vs 3. Fewest is NP-hard. |
| Converting every expense eagerly at today's rate | The rate changes, so the same history gives different balances. Keep native currency, convert once at settle-up. |
| Rounding each converted net to nearest | The sum can drift to +1 or -1 and settle-up leaves a stray cent. Floor, then give the remainders out. |
| Editing an old expense to settle up | Loses the audit trail. Append a payment. |
| Percent as `double` 33.33 | Use basis points (3333). |

### Trade-offs and when not to use

Greedy is fast but may use extra payments; the exact solver is exponential, so it is capped at 20 people. The per-group lock is simple and fine for a friends group. For a very hot group, shard by expense and merge nets, but then I4 needs more care.

### Production and distributed delta

Splitwise balances are a ledger: every expense is a balanced set of debits and credits and the nets are account balances. The production version is [Topic 32: Double-Entry Ledger](../32-double-entry-ledger/): idempotency keys on each expense, a database transaction in place of the lock, and reconciliation. Also see [Topic 11](../11-money-ids-idempotency/) for idempotency, [Topic 10](../10-time-and-testability/) for the injected clock, and [Java concurrency](../../internal-pocs/Java-poc/concurrency/) for locks.

---

## 4. Official References

- Martin Fowler, [Money pattern](https://martinfowler.com/eaaCatalog/money.html): why money is a value type and how allocation works.
- Oracle, [Math.multiplyExact / floorDiv / floorMod](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Math.html) and [ReentrantLock](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/ReentrantLock.html).
- [Subset sum problem](https://en.wikipedia.org/wiki/Subset_sum_problem) and [Partition problem](https://en.wikipedia.org/wiki/Partition_problem): both NP-complete, the base of the hardness argument above.
- Tom Verhoeff, "Settling Multiple Debts Efficiently: An Invitation to Computing Science", *Informatics in Education* 3(1), 2004, pp. 105-126. The journal PDF links are dead (404), so cite the author's classroom problems, which I downloaded and read: [settling-debts-problems.pdf](https://www.win.tue.nl/~wstomv/publications/settling-debts-problems.pdf) (200). Its answer to problem 9 says the greedy gives at most N-1 transfers, "is not guaranteed to have a minimal number of transfers", and that minimising the number of transfers "is a NP-hard problem". Answers 10 and 11 show that minimising equals maximising zero-sum groups. Author's list page: [Verhoeff's publications](https://www.win.tue.nl/~wstomv/publications/) (200). I read the problem sheet, not the paper itself.
- [Splitwise: Debts made simple](https://blog.splitwise.com/2012/09/14/debts-made-simple/): the product's own description (200). I did not find the NP-hardness claim on that page.

---

## 5. 45-Minute Interview Timeline

| Time | What you do |
|---|---|
| 0-5 | Ask: who can pay, split kinds, currencies, can expenses be edited. State `long` cents. |
| 5-12 | Do the dinner and taxi example on the board; show the nets sum to 0. |
| 12-22 | Write `SplitStrategy` and its error contract; largest remainder with the tie-break; 100 / 3. |
| 22-30 | Group, nets, append-only history, payment entries. |
| 30-38 | Greedy with two heaps, at most k-1; give the counterexample and say optimal is NP-hard. |
| 38-42 | Multi-currency: per-currency nets, convert at settle-up with a remainder rule. |
| 42-45 | Tests: property test of the sums, rebuild == incremental, lock; link to the ledger. |

---

## 6. Senior vs Staff Rubric

| Signal | Senior | Staff |
|---|---|---|
| Money | `BigDecimal` or `long`, rounds each share | `long`, largest remainder, deterministic tie-break, property test that shares sum exactly |
| Validation | Trusts the caller | Exact must sum, percent must be 10000 basis points, overflow throws, rejected call writes nothing |
| Simplification | Greedy, says it is optimal | Greedy at most k-1, counterexample in code, knows optimum is NP-hard, exact DP for small k |
| Currency | Converts each expense when entered | Per-currency balances, one conversion at settle-up, remainder-absorbing rounding |
| History | Mutates balances | Append-only lines, balances derived, rebuild == incremental test |
| Concurrency | `synchronized` everywhere | One lock per group with the reason, exact-total hammer test |

---

## 7. Key Takeaways

1. Nets always sum to exactly 0. If they do not, you lost a cent somewhere.
2. Integers only: `long` cents, basis points, rate micros. Largest remainder with a tie-break by user id.
3. Strategy pattern: one interface, four rules, one error contract.
4. Greedy settles everyone in at most k-1 transfers but is not minimal (4 vs 3 in the test); the minimum is NP-hard and the DP is O(2^k · k).
5. Keep balances per currency and convert once at settle-up, rounding so the sum stays 0.
6. Settle-ups are new history lines; balances are derived and can be rebuilt.
7. A group is a small ledger: production adds idempotency and transactions (Topic 32).

**Self-grade: STRONG.** Full spine, F1-F3 coded, 39 tests, eight hand-made mutations (wrong sort key, pairwise ignoring currency, shared map, live history, unordered map, off-by-one solver limit, wrapping sums) each killed 3 runs out of 3. Gaps: the NP-hardness claim is cited from a problem sheet by Verhoeff, not the paper itself; FX assumes equal minor digits; the concurrency test checks totals, not fairness; one lock per group is not sharded.

[← Topic 32: Double-Entry Ledger](../32-double-entry-ledger/) · [Index](../README.md) · [Topic 34: Auction →](../34-auction/)
