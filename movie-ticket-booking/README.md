# Movie Ticket Booking System — LLD

| File | What |
|---|---|
| [README.md](README.md) | Steps: requirements → entities → relationships → patterns → flow → talking points |
| [class-diagram.puml](class-diagram.puml) | UML class diagram |
| [booking-flow.puml](booking-flow.puml) | Booking sequence diagram |
| [db-schema.puml](db-schema.puml) | Database schema (ER diagram) |
| [MovieTicketBookingSystem.java](MovieTicketBookingSystem.java) | Full implementation + runnable demo |
| [MovieTicketBookingSkeleton.java](MovieTicketBookingSkeleton.java) | Skeleton only: classes, fields, method signatures with TODOs |

**Run:**

```bash
javac MovieTicketBookingSystem.java && java MovieTicketBookingSystem
```

---

## Step 1: Requirements

**Functional**
1. Search movies playing in a city.
2. View shows for a movie (cinema, screen, time).
3. View the seat map of a show (free / locked / booked).
4. Select one or more seats — they are **held** for a few minutes while the customer pays.
5. Pay for the booking (Card / UPI / Wallet).
6. Cancel a booking and get a refund.
7. Admins can add cinemas, screens, movies and shows.
8. Notify the customer (Email / SMS) when a booking is confirmed or cancelled.

**Non-functional**
- **No double booking.** Two customers must never get the same seat for the same show.
- **Seat hold with timeout.** Seats held by a customer who doesn't pay must become free again automatically.
- **Extensible.** New payment methods, pricing rules or notification channels should not require changing existing code.

---

## Step 2: Core entities

| Entity | Responsibility |
|---|---|
| `User` → `Customer`, `Admin` | Who is using the system |
| `Movie` | Title, language, genre, duration |
| `Cinema` | Name, city, list of screens |
| `Screen` | Name, physical seat layout |
| `Seat` | Row, number, `SeatType` — physical seat, same for every show |
| `SeatType` (enum) | SILVER, GOLD, PLATINUM |
| `Show` | Movie + screen + start time + price per seat type + `ShowSeat`s |
| `ShowSeat` | State of one seat **for one show**: status, lockedBy, lockExpiresAt |
| `SeatStatus` (enum) | AVAILABLE, LOCKED, BOOKED |
| `SeatLockService` | Locks / books / releases seats safely (with timeout) |
| `Booking` | Customer, show, seats, amount, status, payment |
| `BookingStatus` (enum) | PENDING, CONFIRMED, CANCELLED, EXPIRED |
| `Payment` / `PaymentStatus` | Amount, status, payment method |
| `PaymentStrategy` | Card, UPI, Wallet |
| `PricingStrategy` | Normal, Weekend, PrimeTime |
| `NotificationObserver` | Email, SMS |
| `SearchService`, `BookingService`, `PaymentService` | Business logic |
| `MovieTicketBookingSystem` | Single entry point (Singleton + Facade) |

**Key idea — `Seat` vs `ShowSeat`:** a `Seat` is the physical chair in a screen. The same chair is shown many times a day, so its booking state lives in a `ShowSeat`, one per seat **per show**.

---

## Step 3: Relationships

- `Cinema` **has** many `Screen`s; `Screen` **has** many `Seat`s — composition.
- `Show` **plays** one `Movie` on one `Screen` of one `Cinema`.
- `Show` **has** one `ShowSeat` per `Seat` of its screen — composition.
- `Customer` **makes** many `Booking`s.
- `Booking` **is for** one `Show` and one or more seats.
- `Booking` **has** one `Payment`; `Payment` **uses** one `PaymentStrategy`.
- `BookingService` **uses** `SeatLockService`, a `PricingStrategy`, and notifies `NotificationObserver`s.

---

## Step 4: Design patterns

| Pattern | Where | Why |
|---|---|---|
| **Singleton** | `MovieTicketBookingSystem` | One shared system instance |
| **Facade** | `MovieTicketBookingSystem` | One simple API over all services |
| **Strategy** | `PaymentStrategy`, `PricingStrategy` | Swap payment methods / pricing rules without touching callers |
| **Observer** | `NotificationObserver` | Email/SMS listeners react to booking events |
| **Status enum** (not the State pattern) | `SeatStatus`, `BookingStatus` | Guards allowed transitions |

---

## Step 5: Class diagram

See [class-diagram.puml](class-diagram.puml).

---

## Step 6: Booking flow

See [booking-flow.puml](booking-flow.puml).

Booking is **two steps**, like BookMyShow: select seats (hold) → pay.

1. **Browse:** `searchMovies(city)` → `getShows(city, movie)` → seat map.
2. **Select seats** → `BookingService.createBooking()`:
   1. `SeatLockService.lockSeats()` — inside `synchronized(show)`: if **every** seat is free, lock them all with `lockExpiresAt = now + timeout`; otherwise lock **none**.
   2. Price each seat with the current `PricingStrategy`.
   3. Save the booking as `PENDING`.
3. **Pay** → `BookingService.confirmBooking()`:
   1. Lock no longer held (expired) → booking `EXPIRED`.
   2. Pay through `PaymentService`.
   3. Payment fails → unlock seats, booking `CANCELLED`.
   4. Payment succeeds → seats `BOOKED`, booking `CONFIRMED`, notify.
4. **Cancel** → seats `AVAILABLE`, refund, `CANCELLED`, notify.

**Seat states:**

```
AVAILABLE --lock--> LOCKED --pay ok--> BOOKED
    ^                 |                  |
    +--- expire/fail -+                  |
    +------------- cancel ---------------+
```

**Lock expiry is lazy:** no background thread is needed. `ShowSeat.isFree()` treats a `LOCKED` seat whose `lockExpiresAt` has passed as free.

---

## Step 7: Code

See [MovieTicketBookingSystem.java](MovieTicketBookingSystem.java). The `main` method demos:

1. Search movies and shows
2. Select seats + pay → confirmed
3. Booked seat → rejected; all-or-nothing (other seats not partially locked)
4. Locked seat → rejected for other customers (seat map shows `[L]`)
5. Lock expires → booking `EXPIRED`, another customer gets the seat
6. Payment failure → seats released
7. Cancellation + refund
8. Weekend pricing switched at runtime
9. 10 threads racing for one seat → exactly 1 winner

---

## Step 8: Interview talking points

1. **Preventing double booking**
   - Single server: all lock/book checks run inside `synchronized(show)`, so check-and-lock is atomic per show. Different shows don't block each other.
   - Multiple servers: DB row per `ShowSeat` with a unique constraint / `SELECT ... FOR UPDATE`, optimistic locking with a version column, or Redis `SET seat:{showId}:{seatId} userId NX PX 300000` as a distributed lock with TTL.
   - Multi-seat selection is all-or-nothing.
2. **Why a seat hold?** Payment takes time. Without a hold, two customers could both reach the payment page for the same seat. The hold timeout (5 min default) frees seats from abandoned checkouts.
3. **Lock expired during payment.** Keep the payment gateway timeout shorter than the lock timeout, or extend the lock when payment starts. If payment succeeds after expiry, refund automatically.
4. **Open/Closed principle.** New payment method, pricing rule or notifier = new class implementing the existing interface.
5. **Extensions:** coupons / offers (Decorator over pricing), food & beverages add-ons, cancellation policy as a strategy (refund % by time before show), waitlist, seat recommendation (best available).

---

## Step 9: Database schema

See [db-schema.puml](db-schema.puml).

| Table | Maps to | Notes |
|---|---|---|
| `users` | `User` / `Customer` / `Admin` | Single table, `role` column |
| `movies` | `Movie` | |
| `cinemas` | `Cinema` | Index on `city` for search |
| `screens` | `Screen` | |
| `seats` | `Seat` | Physical layout, `UNIQUE(screen_id, row_label, seat_number)` |
| `shows` | `Show` | Index `(movie_id, start_time)` |
| `show_prices` | `Show.prices` | One row per seat type per show |
| `show_seats` | `ShowSeat` | One row per seat per show, created when the show is added. `UNIQUE(show_id, seat_id)` |
| `bookings` | `Booking` | |
| `booking_seats` | `Booking.seatIds` | Keeps history even after a seat is released on cancel |
| `payments` | `Payment` | Many rows per booking allowed (retries, refunds) |

**Locking seats** (all-or-nothing, one atomic statement):

```sql
UPDATE show_seats
SET status = 'LOCKED', locked_by = :userId,
    lock_expires_at = NOW() + INTERVAL '5 minutes', version = version + 1
WHERE show_id = :showId
  AND seat_id IN (:seatIds)
  AND (status = 'AVAILABLE' OR (status = 'LOCKED' AND lock_expires_at < NOW()));
-- rows updated == number of seats requested -> COMMIT, else ROLLBACK
```

**Confirming after payment:**

```sql
UPDATE show_seats SET status = 'BOOKED'
WHERE show_id = :showId AND seat_id IN (:seatIds)
  AND status = 'LOCKED' AND locked_by = :userId AND lock_expires_at >= NOW();
```

**Notes:**
- The `WHERE` condition makes check-and-lock atomic, so two users can't lock the same seat. This is the DB version of `synchronized(show)`.
- `version` supports optimistic locking if the app reads first and updates later.
- Expired locks need no cleanup job: the `lock_expires_at < NOW()` condition treats them as free. A periodic job can still reset them to `AVAILABLE` to keep the data tidy.
- At high traffic, seat locks are often kept in Redis (`SET ... NX PX`) instead, and only confirmed bookings are written to the DB.
