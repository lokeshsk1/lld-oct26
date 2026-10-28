# ATM System — LLD

| File | What |
|---|---|
| [README.md](README.md) | Steps: requirements → entities → relationships → patterns → flow → talking points → DB schema |
| [class-diagram.puml](class-diagram.puml) | UML class diagram |
| [atm-states.puml](atm-states.puml) | ATM state machine diagram |
| [withdraw-flow.puml](withdraw-flow.puml) | Card → PIN → withdraw sequence diagram |
| [db-schema.puml](db-schema.puml) | Database schema (ER diagram) |
| [ATMSystem.java](ATMSystem.java) | Full implementation + runnable demo |
| [ATMSkeleton.java](ATMSkeleton.java) | Skeleton only: classes, fields, method signatures with TODOs |

**Run:**

```bash
javac ATMSystem.java && java ATMSystem
```

---

## Step 1: Requirements

**Functional**
1. Customer inserts a card; the ATM validates it with the bank (known, not blocked, not expired).
2. Customer enters a PIN. 3 wrong PINs → card blocked and retained.
3. After login: check balance, withdraw cash, deposit cash, transfer to another account.
4. Withdrawals must be multiples of 100 and within the account's daily limit.
5. The ATM pays out using the available notes (500, 200, 100).
6. Customer ejects the card to end the session.
7. Operator can take the ATM out of service, refill cash and bring it back.
8. Every transaction (success or failure) is logged; the customer gets an SMS.

**Non-functional**
- **Money is never lost or created.** Balance can't go negative even if the same account is used at several ATMs at once.
- **No deadlocks.** Transfers between the same two accounts in opposite directions must not lock each other.
- **Security.** PINs are stored only as hashes; card numbers are masked on screen.
- **Extensible.** New states, note types or notification channels should not require changing existing code.

---

## Step 2: Core entities

| Entity | Responsibility |
|---|---|
| `Card` | Card number, holder name, expiry (what the ATM reads) |
| `CardInfo` | Bank-side card record: PIN hash, failed attempts, status |
| `CardStatus` (enum) | ACTIVE, BLOCKED |
| `Account` | Balance, daily withdrawal limit, amount withdrawn today |
| `AccountType` (enum) | SAVINGS, CURRENT |
| `Transaction` | Type, amount, status, ATM, time |
| `TransactionType` / `TransactionStatus` (enums) | WITHDRAWAL, DEPOSIT, BALANCE_INQUIRY, TRANSFER / SUCCESS, FAILED |
| `BankService` | Validates cards, checks PINs, moves money, logs transactions |
| `ATM` | The machine: current state, current card / account, cash dispenser |
| `ATMState` | Idle, CardInserted, Authenticated, OutOfService |
| `CashDispenser` | Note inventory per denomination; reserve / restore notes |
| `NoteDispenser` | One link in the note chain (500 → 200 → 100) |
| `NotificationObserver` | SMS, Email |

---

## Step 3: Relationships

- `ATM` **has** one `CashDispenser` — composition.
- `ATM` **is in** exactly one `ATMState` at a time.
- `ATM` **talks to** one `BankService`; many ATMs share the same bank.
- `ATM` **holds** the current `Card` and `Account` during a session.
- `BankService` **stores** many `Account`s, `CardInfo`s and `Transaction`s.
- `CardInfo` **belongs to** one `Account` (via account number). One account can have many cards.
- `CashDispenser` **starts** a chain of `NoteDispenser`s; each **points to** the next.
- `BankService` notifies `NotificationObserver`s.

---

## Step 4: Design patterns

| Pattern | Where | Why |
|---|---|---|
| **State** | `ATMState` + `IdleState`, `CardInsertedState`, `AuthenticatedState`, `OutOfServiceState` | Each state allows only its own actions (no withdraw before PIN). No `if (state == ...)` chains |
| **Chain of Responsibility** | `NoteDispenser` 500 → 200 → 100 | Each note type pays what it can and passes the rest on. A new note = one new link |
| **Singleton** | `BankService` | One bank shared by all ATMs |
| **Observer** | `NotificationObserver` | SMS / Email on every transaction |

---

## Step 5: Class diagram

See [class-diagram.puml](class-diagram.puml).

---

## Step 6: Flow

See [atm-states.puml](atm-states.puml) and [withdraw-flow.puml](withdraw-flow.puml).

**State machine:**

| State | Allowed actions | Goes to |
|---|---|---|
| `Idle` | `insertCard` | `CardInserted` if the card is valid |
| `CardInserted` | `enterPin`, `ejectCard` | `Authenticated` on correct PIN; `Idle` on eject or 3rd wrong PIN (card retained) |
| `Authenticated` | `checkBalance`, `withdraw`, `deposit`, `transfer`, `ejectCard` | stays until `ejectCard` → `Idle` |
| `OutOfService` | none for customers; operator can `refill` | `Idle` on `backInService` |

Any action not allowed in the current state throws `"Cannot <action> in <STATE> state"` (a default method on `ATMState`).

**Withdraw** (`AuthenticatedState.withdraw`):
1. Amount must be > 0 and a multiple of 100.
2. `CashDispenser.reserve(amount)` — the note chain works out the breakdown (e.g. 2800 = 5×500 + 1×200 + 1×100) and removes those notes from the inventory. Can't be made → reject.
3. `BankService.withdraw()` → `Account.withdraw()` (synchronized): checks balance and daily limit, then debits.
4. Bank says no → `restore(notes)` puts the notes back. Bank says yes → log transaction, SMS, dispense.

Reserving notes **before** debiting the account means a customer is never charged for cash the ATM can't give out.

**Transfer** (`BankService.transfer`): lock both accounts in a fixed order (lower account number first), debit, credit. Two opposite transfers at the same time then lock in the same order and can't deadlock.

---

## Step 7: Code

See [ATMSystem.java](ATMSystem.java). The `main` method demos:

1. Withdraw before inserting a card → rejected by the state
2. Card inserted, wrong PIN (2 attempts left), correct PIN → authenticated
3. Balance inquiry, withdraw 2800 → `{500=5, 200=1, 100=1}`
4. Withdraw 250 (not a multiple of 100) and 7500 (over daily limit) → rejected, ATM cash unchanged
5. Deposit, transfer to Bob, transfer to unknown account → rejected
6. Eject card → Idle; transaction history (failed attempts included)
7. 3 wrong PINs → card blocked and retained; inserting it again → rejected
8. ATM can't make the amount → operator takes it out of service, refills, brings it back → withdrawal works
9. Same account withdrawn at 10 ATMs at once (balance 10000, 2000 each) → exactly 5 succeed, balance 0
10. 1000 transfers X→Y and 1000 Y→X at the same time → no deadlock, total unchanged

---

## Step 8: Interview talking points

1. **Why the State pattern?** ATM behaviour depends entirely on the current step. Each state class only implements the actions it allows; everything else falls back to the default "invalid in this state" error. Adding a state (e.g. `PinChangeState`) doesn't touch the others.
2. **Consistency between cash and account.**
   - Reserve notes → debit account → dispense. If the debit fails, restore the notes.
   - If the dispenser jams **after** the debit, log a "dispense failed" event and auto-reverse the debit (real ATMs do this via the bank's reconciliation).
3. **Concurrency**
   - `Account.withdraw()` is synchronized: check-and-debit is atomic, so the same card used at many ATMs can't overdraw.
   - `ATM` methods are synchronized: one customer at a time per machine.
   - Transfers lock accounts in a fixed order → no deadlock.
   - Across servers: conditional `UPDATE ... WHERE balance >= :amount` or `SELECT ... FOR UPDATE` (see below).
4. **Greedy note dispensing.** The chain is greedy (largest note first). With 500/200/100 it can miss some combinations (e.g. 600 with one 500 and three 200s, no 100s). Fix with a small DP/backtracking over the inventory if needed.
5. **Security.** Only the PIN hash is stored (real banks: salted hash inside an HSM, PIN block encrypted from the keypad). Card numbers are masked. Lock after 3 failures. Session timeout → auto eject (add a timer that calls `ejectCard`).
6. **Extensions:** PIN change, mini statement, cardless (UPI QR) withdrawal, multi-currency, receipt printer, deposit counting / fake note detection, per-transaction fee for other-bank cards, ATM cash-low alert to operator (another observer).

---

## Step 9: Database schema

See [db-schema.puml](db-schema.puml).

| Table | Maps to | Notes |
|---|---|---|
| `customers` | account holder | |
| `accounts` | `Account` | `CHECK(balance >= 0)`, `version` for optimistic locking |
| `cards` | `Card` + `CardInfo` | PIN hash, failed attempts, status |
| `atms` | `ATM` | Location, in / out of service |
| `atm_cash` | `CashDispenser.notes` | One row per ATM per denomination |
| `transactions` | `Transaction` | Includes failed ones (with reason) for audit; `to_account_number` for transfers |

"Withdrawn today" is not a column: it's the sum of today's successful withdrawals (or a cached counter reset daily).

**Withdraw** (one DB transaction, with the daily limit):

```sql
BEGIN;
SELECT balance, daily_withdraw_limit FROM accounts
WHERE account_number = :acc FOR UPDATE;                    -- lock the account row

SELECT COALESCE(SUM(amount), 0) FROM transactions
WHERE account_number = :acc AND type = 'WITHDRAWAL'
  AND status = 'SUCCESS' AND created_at >= CURRENT_DATE;   -- withdrawn today

-- app checks: amount <= balance AND today + amount <= limit
UPDATE accounts SET balance = balance - :amount, version = version + 1
WHERE account_number = :acc;
UPDATE atm_cash SET note_count = note_count - :n
WHERE atm_id = :atm AND denomination = :d AND note_count >= :n;   -- per denomination
INSERT INTO transactions (...) VALUES (..., 'WITHDRAWAL', :amount, 'SUCCESS', ...);
COMMIT;
```

**Transfer** — lock both rows in account-number order to avoid deadlocks:

```sql
BEGIN;
SELECT * FROM accounts WHERE account_number IN (:a, :b)
ORDER BY account_number FOR UPDATE;
UPDATE accounts SET balance = balance - :amount WHERE account_number = :from AND balance >= :amount;
UPDATE accounts SET balance = balance + :amount WHERE account_number = :to;
INSERT INTO transactions (...) VALUES (...);
COMMIT;
```

**Wrong PIN:**

```sql
UPDATE cards SET failed_attempts = failed_attempts + 1,
       status = CASE WHEN failed_attempts + 1 >= 3 THEN 'BLOCKED' ELSE status END
WHERE card_number = :card;
```
