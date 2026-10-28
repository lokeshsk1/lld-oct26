# Library Management System — LLD

| File | What |
|---|---|
| [README.md](README.md) | Steps: requirements → entities → relationships → patterns → flow → talking points → DB schema |
| [class-diagram.puml](class-diagram.puml) | UML class diagram |
| [lending-flow.puml](lending-flow.puml) | Search → borrow → return sequence diagram |
| [db-schema.puml](db-schema.puml) | Database schema (ER diagram) |
| [LibrarySystem.java](LibrarySystem.java) | Full implementation + runnable demo |
| [LibrarySkeleton.java](LibrarySkeleton.java) | Skeleton only: classes, fields, method signatures with TODOs |

**Run:**

```bash
javac LibrarySystem.java && java LibrarySystem
```

---

## Step 1: Requirements

**Functional**
1. **Book Management:** the system manages a catalog of books, each with multiple copies.
2. **Member Management:** the system manages library members who can borrow and return books.
3. **Loan Management:** the system tracks which member has borrowed which book copy, and when.
4. **Borrowing and Returning:** members can borrow available book copies and return them.
5. **Catalog Search:** members can search for books by title, author or ISBN.

**Non-functional**
- **No double lending.** A copy can never be lent to two members at the same time.
- **Extensibility.** It should be easy to add features such as reservations, fines or notifications.

---

## Step 2: Core entities

| Entity | Responsibility |
|---|---|
| `Book` | The title in the catalog: ISBN, title, author |
| `BookItem` | One physical copy: barcode, status |
| `BookItemStatus` (enum) | AVAILABLE, LOANED |
| `Member` | id, name, email, current loans |
| `Loan` | Which copy, which member, issue / due / return dates |
| `SearchStrategy` | TitleSearch, AuthorSearch, IsbnSearch |
| `LoanListener` | Hook for future features (notifications, fines, reservations) |
| `CatalogService` | Books, copies, search |
| `MemberService` | Member registry |
| `LoanService` | Borrow, return, active loans, history |
| `LibrarySystem` | Single entry point (Singleton + Facade) |

**Key idea: `Book` vs `BookItem`.** A `Book` is the title ("Clean Code"). A `BookItem` is one physical copy with its own barcode. You **search** for a `Book`, but you **borrow and return** a `BookItem`. This is the same idea as `Seat` vs `ShowSeat` in movie booking.

---

## Step 3: Relationships

- `Book` **has** many `BookItem`s (copies).
- `Member` **has** many active `Loan`s; each `Loan` **is for** one `BookItem`.
- `CatalogService` **holds** books and copies; `search()` **uses** a `SearchStrategy`.
- `MemberService` **holds** members.
- `LoanService` **holds** loans and **notifies** `LoanListener`s.
- `LibrarySystem` **uses** all three services.

---

## Step 4: Design patterns

| Pattern | Where | Why |
|---|---|---|
| **Singleton** | `LibrarySystem` | One shared system instance |
| **Facade** | `LibrarySystem` | One simple API over all services |
| **Strategy** | `SearchStrategy` | New search types (publisher, year) without touching `CatalogService` |
| **Observer** | `LoanListener` | New features react to borrow / return without changing `LoanService` |

---

## Step 5: Class diagram

See [class-diagram.puml](class-diagram.puml).

---

## Step 6: Lending flow

See [lending-flow.puml](lending-flow.puml).

1. **Search** → `CatalogService.search(strategy, query)` returns the matching `Book`s.
2. **Borrow** → `LibrarySystem.borrow(memberId, barcode)`:
   1. Look up the `Member` and the `BookItem`.
   2. `item.tryCheckout()` (synchronized): AVAILABLE → LOANED, or fail if it's already out.
   3. Create a `Loan` (issued today, due in 14 days), store it by barcode and add it to the member.
   4. Tell every `LoanListener` (`onBorrow`).
3. **Return** → `LibrarySystem.returnBook(barcode)`:
   1. Remove the active loan and set its return date.
   2. `item.markAvailable()`.
   3. Tell every `LoanListener` (`onReturn`).

```
AVAILABLE ──borrow──> LOANED ──return──> AVAILABLE
```

---

## Step 7: Code

See [LibrarySystem.java](LibrarySystem.java). The `main` method demos:

1. Search by title, author and ISBN
2. Alice borrows two books; her loans are listed
3. Bob tries a copy that's out → rejected; borrows the other free copy instead
4. "Who has DP-1?" → the active loan
5. Alice returns it (returning twice → rejected), and Bob borrows it
6. 10 members grab the last copy at once → exactly 1 wins

---

## Step 8: Interview talking points

1. **Preventing double lending.** `BookItem.tryCheckout()` is synchronized, so checking and lending a copy happen in one step. Across servers, use a conditional `UPDATE` on `book_items` (see below).
2. **Book vs BookItem.** Search works on titles; loans work on physical copies.
3. **Loan history.** A loan isn't deleted on return. Its `returnDate` is set, so "who borrowed what, when" is always available.
4. **Extensibility via `LoanListener`.** Each new feature is a new listener, and `LoanService` doesn't change:
   - **Notifications:** `EmailNotifier.onBorrow()` sends "due on …".
   - **Fines:** `FineCalculator.onReturn()` charges `daysLate × rate`, using a `FineStrategy` for different rules.
   - **Reservations:** `ReservationService.onReturn()` gives the copy to the first member in a FIFO queue for that book.
5. **Other extensions:**
   - borrow limit per member (check `activeLoans.size()` before borrowing)
   - renewals (`renewCount` on `Loan`)
   - lost / damaged copies (a new `BookItemStatus`)
   - multiple branches

---

## Step 9: Database schema

See [db-schema.puml](db-schema.puml).

| Table | Maps to | Notes |
|---|---|---|
| `books` | `Book` | Index on title and author for search |
| `book_items` | `BookItem` | Status per copy |
| `members` | `Member` | |
| `loans` | `Loan` | `return_date IS NULL` = active loan; kept forever as history |

**Constraint that enforces "no double lending" in the DB:**
- `UNIQUE(barcode) WHERE return_date IS NULL` on `loans` → a copy can have only one active loan.

**Borrow** (atomic, the DB version of `tryCheckout()`):

```sql
BEGIN;
UPDATE book_items SET status = 'LOANED'
WHERE barcode = :b AND status = 'AVAILABLE';
-- 0 rows -> already loaned -> ROLLBACK

INSERT INTO loans (barcode, member_id, issue_date, due_date)
VALUES (:b, :m, CURRENT_DATE, CURRENT_DATE + 14);
COMMIT;
```

**Return:**

```sql
BEGIN;
UPDATE loans SET return_date = CURRENT_DATE
WHERE barcode = :b AND return_date IS NULL;
UPDATE book_items SET status = 'AVAILABLE' WHERE barcode = :b;
COMMIT;
```

**Search:**

```sql
SELECT * FROM books WHERE title LIKE '%code%';      -- or author LIKE ..., or isbn = ...
SELECT barcode FROM book_items WHERE isbn = :isbn AND status = 'AVAILABLE';
```
