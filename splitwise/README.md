# Splitwise — LLD

| File | What |
|---|---|
| [README.md](README.md) | Steps: requirements → entities → relationships → patterns → flow → talking points → DB schema |
| [class-diagram.puml](class-diagram.puml) | UML class diagram |
| [expense-flow.puml](expense-flow.puml) | Add expense → settle up → simplify sequence diagram |
| [db-schema.puml](db-schema.puml) | Database schema (ER diagram) |
| [SplitwiseSystem.java](SplitwiseSystem.java) | Full implementation + runnable demo |
| [SplitwiseSkeleton.java](SplitwiseSkeleton.java) | Skeleton only: classes, fields, method signatures with TODOs |

**Run:**

```bash
javac SplitwiseSystem.java && java SplitwiseSystem
```

---

## Step 1: Requirements

**Functional**
1. Users create groups (trip, flat) and add friends.
2. Add an expense: who paid, how much, who it's for, and how to split it:
   - **EQUAL** — same share each
   - **EXACT** — given amounts (must add up to the total)
   - **PERCENT** — given percents (must add up to 100)
   - **SHARE** — given weights (e.g. 2 : 1 : 3)
3. Expenses can also be between friends, outside any group.
4. Show who owes whom: per group and overall.
5. Settle up: record a payment from one user to another.
6. Simplify debts: suggest the fewest payments that settle a group.
7. Delete an expense (balances reverse).
8. Notify people when an expense involving them is added, deleted or settled.

**Non-functional**
- **Exact money.** No rounding errors; every expense's shares add up to its total.
- **Balances always add up.** In any group, the sum of everyone's net balance is 0.
- **Correct under load.** Many expenses added to the same group at the same time must not lose updates.
- **Extensible.** New split types without changing existing code.

---

## Step 2: Core entities

| Entity | Responsibility |
|---|---|
| `User` | Name, email |
| `Group` | Members, expenses, its own `BalanceSheet` |
| `Expense` | Description, total, payer, splits, split type, group (optional) |
| `Split` | One person's share of an expense (in paise) |
| `SplitType` (enum) | EQUAL, EXACT, PERCENT, SHARE |
| `SplitStrategy` | Turns a total + inputs into `Split`s and validates them |
| `SplitStrategyFactory` | Returns the right strategy for a `SplitType` |
| `BalanceSheet` | Who owes whom; nets; `simplify()` |
| `Settlement` | A recorded payment between two users |
| `Transfer` | A suggested payment from `simplify()` |
| `Money` | Rupees ↔ paise conversion and formatting |
| `NotificationObserver` | Email, Push |
| `GroupService`, `ExpenseService` | Business logic |
| `SplitwiseSystem` | Single entry point (Singleton + Facade) |

**Money as `long` paise:** Rs 1000 ÷ 3 can't be split exactly in rupees. Working in paise (100000 ÷ 3 = 33333, remainder 1) and giving the leftover paisa to the first person gives 333.34 + 333.33 + 333.33 = 1000.00 exactly. With `double` you'd get rounding drift over many expenses.

---

## Step 3: Relationships

- `Group` **has** many member `User`s and many `Expense`s, and **owns** one `BalanceSheet` — composition.
- `Expense` **is paid by** one `User`, **has** one or more `Split`s (composition), and **belongs to** zero or one `Group`.
- `Split` **is for** one `User`.
- `ExpenseService` **owns** the global `BalanceSheet` (all balances across groups and friends).
- `ExpenseService` **uses** `SplitStrategyFactory` → `SplitStrategy`.
- `EqualSplitStrategy`, `PercentSplitStrategy`, `ShareSplitStrategy` **extend** `WeightedSplitStrategy` (shared rounding logic); `ExactSplitStrategy` implements `SplitStrategy` directly.

---

## Step 4: Design patterns

| Pattern | Where | Why |
|---|---|---|
| **Strategy** | `SplitStrategy` + 4 implementations | Each split type has its own validation and math |
| **Factory** | `SplitStrategyFactory` | Callers pass a `SplitType`, never `new` a strategy |
| **Template method** | `WeightedSplitStrategy.byWeights()` | EQUAL, PERCENT and SHARE are all "split by weights" — one rounding rule for all |
| **Observer** | `NotificationObserver` | Email / Push on expense, delete, settle |
| **Singleton** | `SplitwiseSystem` | One shared system instance |
| **Facade** | `SplitwiseSystem` | One simple API over groups and expenses |

---

## Step 5: Class diagram

See [class-diagram.puml](class-diagram.puml).

---

## Step 6: Flow

See [expense-flow.puml](expense-flow.puml).

**How balances are stored** (`BalanceSheet`):

```
owedTo[A][B] = 300   means  B owes A Rs 3.00
owedTo[B][A] = -300  (always the mirror, so either side can be read)
net(A) = sum of A's row   (> 0: A gets money back, < 0: A owes)
```

**Add expense** → `ExpenseService.addExpense()`:
1. Validate: amount > 0, no one listed twice, everyone is in the group.
2. `SplitStrategyFactory.get(type).split(total, users, values)` → `Split`s (validates EXACT sums and PERCENT = 100).
3. For each split except the payer's own: `record(payer, user, share)` on the **global** sheet and on the **group** sheet.
4. Notify everyone who owes something.

**Split examples:**

| Type | Input | Result |
|---|---|---|
| EQUAL | Rs 1000, 3 people | 333.34, 333.33, 333.33 |
| EXACT | Rs 500 → 200, 100, 200 | as given (200 + 100 + 150 rejected) |
| PERCENT | Rs 2000 → 40%, 30%, 20%, 10% | 800, 600, 400, 200 (90% total rejected) |
| SHARE | Rs 1200 → 2 : 1 : 3 | 400, 200, 600 |

**Settle up** → inside `synchronized(sheet)`: `from` must owe `to`, and can't pay more than owed. Recorded like an expense in the opposite direction, on the group sheet and the global sheet.

**Delete expense** → payer only; replays the splits with the opposite sign, so balances go back exactly.

**Simplify debts** (`BalanceSheet.simplify()`):

```
nets = net balance of every person (sum is 0)
creditors = max-heap of positive nets, debtors = max-heap of |negative nets|
while both non-empty:
    c = biggest creditor, d = biggest debtor
    pay = min(c, d)  ->  "d pays c pay"
    push back whatever is left of c or d
```

Each step settles at least one person fully, so there are **at most n − 1 payments** for n people. Time O(n log n). (Finding the true minimum is NP-hard; greedy is what's expected in interviews.)

---

## Step 7: Code

See [SplitwiseSystem.java](SplitwiseSystem.java). The `main` method demos:

1. EQUAL Rs 1000 between 3 → 333.34 / 333.33 / 333.33, adds up exactly
2. EXACT with wrong total → rejected; correct one added
3. PERCENT adding to 90 → rejected; 40 / 30 / 20 / 10 added
4. SHARE 2 : 1 : 3
5. Group balances ("X owes Y"), nets, sum of nets = 0
6. Simplify: 5 pairwise debts → 3 payments
7. Settle up: overpay rejected, paying someone you don't owe rejected, exact settle → 0
8. Delete: only the payer can; balances reverse; deleting twice rejected
9. Group rules: non-member in expense rejected; can't leave with an unsettled balance
10. Friends expense outside any group; Alice's overall balances across group + friends
11. 4 threads add 250 random expenses each to one group at the same time → 1000 expenses, nets match the expected values exactly (group and global), sum 0, simplified to ≤ 3 payments

---

## Step 8: Interview talking points

1. **Why paise (`long`)?** Floating point can't represent 0.1 exactly; errors add up across thousands of expenses. Integers in the smallest unit are exact. Leftover paise from dividing go to people one by one, so shares always add up to the total.
2. **Concurrency**
   - `BalanceSheet.record()` is synchronized: updating both mirror entries is atomic, so "A owes B" and "B is owed by A" can't disagree.
   - `settleUp()` locks the sheet around "check how much is owed + record", so two concurrent settlements can't overpay.
   - Each group has its own sheet (own lock), so different groups don't block each other.
   - In the DB: one row per pair with a conditional / versioned update (see below).
3. **Pairwise vs net balances.** Pairwise ("Alice owes Carol") is what users see; net (one number per person) is all `simplify()` needs. Storing pairwise lets you show both.
4. **Group vs global.** Each expense updates its group's sheet and the global sheet. Settling within a group updates both. Settling "overall" across many groups would need to spread the payment over the groups' debts (Splitwise does this oldest-first).
5. **Open/Closed principle.** A new split type (e.g. "adjustment": equal plus/minus extra) = a new `SplitStrategy` registered in the factory.
6. **Extensions:** multiple payers for one expense, currencies (store currency per expense, convert on display), recurring expenses, receipts / images, comments, activity feed, reminders to settle, editing an expense (delete + re-add with a history entry).

---

## Step 9: Database schema

See [db-schema.puml](db-schema.puml).

| Table | Maps to | Notes |
|---|---|---|
| `users` | `User` | |
| `groups`, `group_members` | `Group` | Many-to-many membership |
| `expenses` | `Expense` | `group_id` NULL = friends expense; soft delete with `is_deleted` |
| `expense_splits` | `Split` | Amount in paise + the original input (percent / share / exact) |
| `settlements` | `Settlement` | Payments between users |
| `balances` | `BalanceSheet` | **Cached** pairwise balances; one row per (group, pair) with `user_a < user_b` |

Balances can always be **recomputed** from `expense_splits` and `settlements`; the `balances` table is a cache so "who owes whom" is one indexed read.

**Add expense** (one transaction):

```sql
BEGIN;
INSERT INTO expenses (group_id, description, total_paise, paid_by, split_type, is_deleted, created_at)
VALUES (:g, :desc, :total, :payer, :type, false, NOW()) RETURNING id;
INSERT INTO expense_splits (expense_id, user_id, amount_paise, input_value) VALUES (...), (...);

-- for each split user u != payer: payer is owed u's share
-- store each pair once with user_a < user_b; the sign says who owes whom
INSERT INTO balances (group_id, user_a, user_b, amount_paise, version)
VALUES (:g, LEAST(:payer, :u), GREATEST(:payer, :u), CASE WHEN :payer < :u THEN :share ELSE -:share END, 1)
ON CONFLICT (group_id, user_a, user_b)
DO UPDATE SET amount_paise = balances.amount_paise + EXCLUDED.amount_paise,
              version = balances.version + 1;
COMMIT;
```

**Net balance of each member in a group:**

```sql
SELECT user_id, SUM(amt) AS net_paise FROM (
  SELECT user_a AS user_id,  amount_paise AS amt FROM balances WHERE group_id = :g
  UNION ALL
  SELECT user_b AS user_id, -amount_paise AS amt FROM balances WHERE group_id = :g
) t GROUP BY user_id;
```

**Recompute from scratch** (audit / repair the cache):

```sql
SELECT e.paid_by AS creditor, s.user_id AS debtor, SUM(s.amount_paise) AS owed
FROM expenses e JOIN expense_splits s ON s.expense_id = e.id
WHERE e.group_id = :g AND NOT e.is_deleted AND s.user_id <> e.paid_by
GROUP BY e.paid_by, s.user_id;
-- then subtract settlements between the same pairs
```
