# Parking Lot — LLD

| File | What |
|---|---|
| [README.md](README.md) | Steps: requirements → entities → relationships → patterns → flow → talking points → DB schema |
| [class-diagram.puml](class-diagram.puml) | UML class diagram |
| [parking-flow.puml](parking-flow.puml) | Park → unpark sequence diagram |
| [db-schema.puml](db-schema.puml) | Database schema (ER diagram) |
| [ParkingLotSystem.java](ParkingLotSystem.java) | Full implementation + runnable demo |
| [ParkingLotSkeleton.java](ParkingLotSkeleton.java) | Skeleton only: classes, fields, method signatures with TODOs |

**Run:**

```bash
javac ParkingLotSystem.java && java ParkingLotSystem
```

---

## Step 1: Requirements

**Functional**
1. The lot has multiple floors; each floor has spots of size SMALL, MEDIUM or LARGE.
2. Vehicles are BIKE, CAR or TRUCK. A vehicle fits a spot of its size or bigger (bike → any, car → medium / large, truck → large).
3. On entry, assign a free spot and issue a ticket.
4. On exit, calculate the fee from the parked time and free the spot.
5. Show free spots per size.
6. Find where a vehicle is parked.

**Non-functional**
- **No double allocation.** Two vehicles must never get the same spot, even when they arrive at the same time.
- **Extensible.** New spot-choosing rules, pricing rules, or features (display boards, payments) without changing the core.

---

## Step 2: Core entities

| Entity | Responsibility |
|---|---|
| `VehicleType` (enum) | BIKE, CAR, TRUCK |
| `SpotType` (enum) | SMALL, MEDIUM, LARGE + `fits(vehicleType)` |
| `Vehicle` | Number plate, type |
| `ParkingSpot` | id, size, floor, the vehicle in it (or none) |
| `ParkingFloor` | Floor number + its spots |
| `Ticket` | Vehicle, spot, entry / exit time, fee |
| `SpotAllocationStrategy` | NearestFirst, BestFit |
| `FeeStrategy` | HourlyFee |
| `ParkingListener` | Hook for features like the display board |
| `ParkingLotSystem` | Single entry point (Singleton + Facade): park, unpark, availability |

---

## Step 3: Relationships

- `ParkingLotSystem` **has** many `ParkingFloor`s; each floor **has** many `ParkingSpot`s — composition.
- `ParkingSpot` **holds** zero or one `Vehicle`.
- `Ticket` **links** one `Vehicle` to one `ParkingSpot` for a time range.
- `ParkingLotSystem` **uses** one `SpotAllocationStrategy` and one `FeeStrategy`, and notifies `ParkingListener`s.

---

## Step 4: Design patterns

| Pattern | Where | Why |
|---|---|---|
| **Singleton** | `ParkingLotSystem` | One lot, one shared instance |
| **Facade** | `ParkingLotSystem` | One simple API: `park`, `unpark`, `availability` |
| **Strategy** | `SpotAllocationStrategy`, `FeeStrategy` | Change how spots are chosen or how fees are charged without touching the lot |
| **Observer** | `ParkingListener` | Display boards, payment, alerts react to park / unpark without changing the lot |

---

## Step 5: Class diagram

See [class-diagram.puml](class-diagram.puml).

---

## Step 6: Parking flow

See [parking-flow.puml](parking-flow.puml).

**Park** → `park(vehicle, entryTime)`:
1. Reject if this vehicle is already inside (`parkedVehicles.putIfAbsent`).
2. Collect free spots that fit the vehicle.
3. `SpotAllocationStrategy.rank()` orders them:
   - **NearestFirst:** lowest floor first.
   - **BestFit:** smallest spot that fits first, so big spots stay free for big vehicles.
4. Walk the list and call `spot.tryPark(vehicle)` (synchronized) until one succeeds. Another vehicle may take a spot between step 2 and step 4, so this loop is what makes it safe.
5. Create a `Ticket`, notify listeners. Nothing left → "No free spot".

**Unpark** → `unpark(ticketId, exitTime)`:
1. Remove the active ticket (unknown or already used → reject).
2. Fee = `FeeStrategy.calculate(vehicleType, exit − entry)`. `HourlyFee`: every started hour is charged, minimum 1 hour. 2h30m for a car = 3 × Rs 30 = Rs 90.
3. Free the spot, notify listeners.

---

## Step 7: Code

See [ParkingLotSystem.java](ParkingLotSystem.java). The `main` method demos:

1. Park a bike, a car and a truck; the display board updates after each
2. Second truck (no large spot left) and the same car again → rejected
3. Nearest-first puts a bike in a medium spot on floor 0; best fit puts the next bike in the small spot on floor 1
4. Find a car by plate; car leaves after 2h30m → Rs 90; using the same ticket again → rejected
5. 20 cars arrive at once with only 3 spots that fit → exactly 3 parked, all in different spots

---

## Step 8: Interview talking points

1. **Preventing double allocation.** `ParkingSpot.tryPark()` is synchronized, so checking and occupying a spot happen in one step. The lot falls back to the next ranked spot if it loses the race. Across servers, use a conditional `UPDATE` on `spots` (see below).
2. **Why spots fit "this size or bigger".** A bike can use a car spot when bike spots are full. `BestFitStrategy` avoids wasting big spots while small ones are free.
3. **Testable time.** `park` / `unpark` accept the time, so fees can be tested without waiting hours (the no-argument versions use `now()`).
4. **Extensibility via `ParkingListener`.** Each new feature is a new listener, and the lot doesn't change:
   - **Display boards per floor / entrance:** update counts on `onPark` / `onUnpark` (the demo's `DisplayBoard`).
   - **Payment:** `PaymentHandler.onUnpark()` charges `ticket.getFee()` by card / UPI / cash (a `PaymentStrategy`).
   - **Alerts:** notify the operator when a floor is full.
5. **Other extensions:**
   - more fee rules as new `FeeStrategy`s: flat daily cap, weekend rates, first hour free
   - electric-vehicle spots with chargers (a new `SpotType`, and a rule in `fits`)
   - entry / exit gates (each gate calls `park` / `unpark`)
   - reservations (hold a spot for a time window)
   - monthly passes (skip the fee)

---

## Step 9: Database schema

See [db-schema.puml](db-schema.puml).

| Table | Maps to | Notes |
|---|---|---|
| `floors` | `ParkingFloor` | |
| `spots` | `ParkingSpot` | Size + free flag, index on `(type, is_free)` for fast lookup |
| `tickets` | `Ticket` | `exit_time IS NULL` = vehicle still inside; kept forever as history |

**Constraints that enforce the rules in the DB:**
- `UNIQUE(spot_id) WHERE exit_time IS NULL` → a spot has at most one vehicle.
- `UNIQUE(vehicle_number) WHERE exit_time IS NULL` → a vehicle can't be inside twice.

**Park** (atomic, the DB version of `tryPark()`):

```sql
BEGIN;
SELECT id FROM spots
WHERE is_free AND type IN ('MEDIUM', 'LARGE')       -- sizes that fit a car
ORDER BY type, floor_id                              -- best fit
LIMIT 1 FOR UPDATE SKIP LOCKED;                      -- skip spots another entry is taking

UPDATE spots SET is_free = false WHERE id = :spotId;
INSERT INTO tickets (vehicle_number, vehicle_type, spot_id, entry_time)
VALUES (:number, 'CAR', :spotId, NOW());
COMMIT;
```

**Unpark:**

```sql
BEGIN;
UPDATE tickets SET exit_time = NOW(), fee = :fee WHERE id = :ticketId AND exit_time IS NULL;
UPDATE spots SET is_free = true WHERE id = :spotId;
COMMIT;
```

**Free spots per size:**

```sql
SELECT type, COUNT(*) FROM spots WHERE is_free GROUP BY type;
```
