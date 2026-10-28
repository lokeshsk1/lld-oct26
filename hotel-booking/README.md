# Hotel Booking System — LLD

| File | What |
|---|---|
| [README.md](README.md) | Steps: requirements → entities → relationships → patterns → flow → talking points |
| [class-diagram.puml](class-diagram.puml) | UML class diagram |
| [booking-flow.puml](booking-flow.puml) | Booking sequence diagram |
| [db-schema.puml](db-schema.puml) | Database schema (ER diagram) |
| [HotelBookingSystem.java](HotelBookingSystem.java) | Full implementation + runnable demo |
| [HotelBookingSkeleton.java](HotelBookingSkeleton.java) | Skeleton only: classes, fields, method signatures with TODOs |

**Run:**

```bash
javac HotelBookingSystem.java && java HotelBookingSystem
```

---

## Step 1: Requirements

**Functional**
1. Search hotels by city, check-in/check-out dates and number of guests.
2. View the rooms that are free for those dates.
3. Book one or more rooms.
4. Pay for the booking (Card / UPI / Wallet).
5. Cancel a booking and get a refund.
6. Admins can add hotels and rooms.
7. Notify the guest (Email / SMS) when a booking is confirmed or cancelled.

**Non-functional**
- **No double booking.** Two users must never get the same room for overlapping dates.
- **Extensible.** New payment methods, pricing rules or notification channels should not require changing existing code.

---

## Step 2: Core entities

| Entity | Responsibility |
|---|---|
| `User` → `Guest`, `Admin` | Who is using the system |
| `Hotel` | Name, city, address, list of rooms |
| `Room` | Room number, type, base price, capacity, booked date ranges |
| `RoomType` (enum) | SINGLE, DOUBLE, DELUXE, SUITE |
| `DateRange` | Check-in / check-out + overlap check |
| `Booking` | Guest, hotel, rooms, dates, amount, status, payment |
| `BookingStatus` (enum) | PENDING, CONFIRMED, CANCELLED, COMPLETED |
| `Payment` / `PaymentStatus` | Amount, status, payment method |
| `PaymentStrategy` | Card, UPI, Wallet |
| `PricingStrategy` | Normal, Weekend, Seasonal |
| `NotificationObserver` | Email, SMS |
| `SearchService`, `BookingService`, `PaymentService` | Business logic |
| `HotelBookingSystem` | Single entry point (Singleton + Facade) |

---

## Step 3: Relationships

- `Hotel` **has** many `Room`s — composition (a room can't exist without its hotel).
- `Guest` **makes** many `Booking`s.
- `Booking` **contains** one or more `Room`s for one `DateRange`.
- `Booking` **has** one `Payment`.
- `Payment` **uses** one `PaymentStrategy`.
- `BookingService` **uses** a `PricingStrategy` and notifies `NotificationObserver`s.

---

## Step 4: Design patterns

| Pattern | Where | Why |
|---|---|---|
| **Singleton** | `HotelBookingSystem` | One shared system instance |
| **Facade** | `HotelBookingSystem` | One simple API over all services |
| **Strategy** | `PaymentStrategy`, `PricingStrategy` | Swap payment methods / pricing rules without touching callers |
| **Observer** | `NotificationObserver` | Email/SMS listeners react to booking events |
| **Status enum** (not the State pattern) | `BookingStatus` | Guards allowed transitions |

---

## Step 5: Class diagram

See [class-diagram.puml](class-diagram.puml).

---

## Step 6: Booking flow

See [booking-flow.puml](booking-flow.puml).

1. Guest searches → `SearchService` returns hotels with enough free capacity.
2. Guest picks rooms → `BookingService.createBooking()`:
   1. **Lock** each room with `room.tryBook(range)` (synchronized check-and-book). If any room fails, release the ones already locked.
   2. **Price** using the current `PricingStrategy`.
   3. **Pay** through `PaymentService` with the chosen `PaymentStrategy`.
   4. Payment fails → release rooms, mark booking `CANCELLED`.
   5. Payment succeeds → `CONFIRMED`, save, notify observers.
3. Cancel → release rooms, refund payment, mark `CANCELLED`, notify.

**Overlap rule** (check-out is exclusive, so back-to-back stays are allowed):

```
[a, b) overlaps [c, d)   ⇔   a < d  &&  c < b
```

---

## Step 7: Code

See [HotelBookingSystem.java](HotelBookingSystem.java). The `main` method demos:

1. Search
2. Successful booking
3. Rejected double booking (overlapping dates)
4. Back-to-back booking allowed (check-in on previous check-out day)
5. Payment failure → rooms released
6. Switching to weekend pricing at runtime
7. Cancellation + refund
8. 10 threads racing for one room → exactly 1 winner

---

## Step 8: Interview talking points

1. **Preventing double booking**
   - Single server: `synchronized tryBook()` makes check-and-book atomic.
   - Multiple servers: DB transaction with `SELECT ... FOR UPDATE` (pessimistic) or a version column (optimistic), or a distributed lock (Redis).
   - Multi-room booking is all-or-nothing: on any failure, release the rooms already locked.
2. **Hold before payment.** In production, keep the room in a `PENDING` hold for ~10 minutes; a scheduler releases it if payment doesn't finish.
3. **Open/Closed principle.** New payment method, pricing rule or notifier = new class implementing the existing interface. No existing code changes.
4. **Booking state transitions.** `PENDING → CONFIRMED → COMPLETED`, and `PENDING/CONFIRMED → CANCELLED`. Anything else throws.
5. **Extensions:** reviews & ratings, coupons (Decorator over pricing), cancellation policy as a strategy (full / partial / no refund), room search indexing by city + date.

---

## Step 9: Database schema

See [db-schema.puml](db-schema.puml).

| Table | Maps to | Notes |
|---|---|---|
| `users` | `User` / `Guest` / `Admin` | Single table, `role` column |
| `hotels` | `Hotel` | Index on `city` for search |
| `rooms` | `Room` | `UNIQUE(hotel_id, room_number)` |
| `bookings` | `Booking` | `check_in`, `check_out`, `status` |
| `booking_rooms` | `Room.bookedRanges` | One row per room per booking (a booking can have many rooms) |
| `payments` | `Payment` | Many rows per booking allowed (retries, refunds) |

**Availability query** (rooms of a hotel free for `[:in, :out)`):

```sql
SELECT r.* FROM rooms r
WHERE r.hotel_id = :hotelId
  AND NOT EXISTS (
    SELECT 1 FROM booking_rooms br
    JOIN bookings b ON b.id = br.booking_id
    WHERE br.room_id = r.id
      AND b.status IN ('PENDING', 'CONFIRMED')
      AND br.check_in < :out AND :in < br.check_out   -- overlap rule
  );
```

**Preventing double booking in the DB:**
- In one transaction: `SELECT ... FROM rooms WHERE id IN (...) FOR UPDATE` (locks the room rows), run the overlap check, then insert `booking_rooms`.
- PostgreSQL alternative: an exclusion constraint makes overlaps impossible at the DB level:
  `EXCLUDE USING gist (room_id WITH =, daterange(check_in, check_out) WITH &&)`.
- Another alternative: a `room_inventory(room_id, date)` table with one row per booked night and a `UNIQUE(room_id, date)` constraint.
