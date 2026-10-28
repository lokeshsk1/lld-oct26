# Cab Booking System (Uber / Ola) — LLD

| File | What |
|---|---|
| [README.md](README.md) | Steps: requirements → entities → relationships → patterns → flow → talking points → DB schema |
| [class-diagram.puml](class-diagram.puml) | UML class diagram |
| [ride-flow.puml](ride-flow.puml) | Ride sequence diagram |
| [db-schema.puml](db-schema.puml) | Database schema (ER diagram) |
| [CabBookingSystem.java](CabBookingSystem.java) | Full implementation + runnable demo |
| [CabBookingSkeleton.java](CabBookingSkeleton.java) | Skeleton only: classes, fields, method signatures with TODOs |

**Run:**

```bash
javac CabBookingSystem.java && java CabBookingSystem
```

---

## Step 1: Requirements

**Functional**
1. Rider sees a fare estimate for each vehicle type (Bike, Auto, Mini, Sedan, SUV).
2. Rider requests a ride: pickup, drop, vehicle type.
3. System matches the best available driver nearby and shares driver details + OTP.
4. Driver starts the ride only with the correct OTP.
5. Ride ends → rider pays (Cash / Card / UPI / Wallet).
6. Rider or driver can cancel before the trip starts. If the driver cancels, the system finds another driver.
7. Rider and driver rate each other.
8. Drivers go online/offline and update their location.
9. Notify rider and driver (SMS / Push) at each step.

**Non-functional**
- **No double assignment.** A driver can never be assigned two rides at the same time.
- **Low latency matching.** Nearby-driver search must be fast (geo index in production).
- **Extensible.** New matching rules, fare rules (surge), payment methods or notification channels should not require changing existing code.

---

## Step 2: Core entities

| Entity | Responsibility |
|---|---|
| `User` → `Rider`, `Driver` | Common id, name, phone, rating |
| `Driver` | Vehicle, current location, status |
| `DriverStatus` (enum) | OFFLINE, AVAILABLE, ON_TRIP |
| `Vehicle` | Number, model, `VehicleType` |
| `VehicleType` (enum) | BIKE, AUTO, MINI, SEDAN, SUV — with base fare + per-km rate |
| `Location` | Lat / lng + Haversine distance |
| `Ride` | Rider, driver, pickup, drop, fare, OTP, status, rejected drivers |
| `RideStatus` (enum) | REQUESTED, DRIVER_ASSIGNED, IN_PROGRESS, COMPLETED, CANCELLED |
| `Payment` / `PaymentStatus` | Amount, status, payment method |
| `PaymentStrategy` | Cash, Card, UPI, Wallet |
| `FareStrategy` | StandardFare, SurgeFare |
| `DriverMatchingStrategy` | NearestDriver, HighestRated |
| `NotificationObserver` | SMS, Push |
| `DriverService`, `RideService`, `PaymentService` | Business logic |
| `CabBookingSystem` | Single entry point (Singleton + Facade) |

---

## Step 3: Relationships

- `Rider` and `Driver` **are** `User`s (inheritance).
- `Driver` **has** one `Vehicle` — composition.
- `Driver` **is at** one current `Location`.
- `Rider` **books** many `Ride`s.
- `Ride` **has** a pickup and drop `Location`, **is served by** zero or one `Driver`, and **has** zero or one `Payment`.
- `RideService` **uses** `DriverService`, a `DriverMatchingStrategy`, a `FareStrategy`, `PaymentService`, and notifies `NotificationObserver`s.
- `SurgeFare` **wraps** another `FareStrategy` (Decorator).

---

## Step 4: Design patterns

| Pattern | Where | Why |
|---|---|---|
| **Singleton** | `CabBookingSystem` | One shared system instance |
| **Facade** | `CabBookingSystem` | One simple API over all services |
| **Strategy** | `DriverMatchingStrategy`, `FareStrategy`, `PaymentStrategy` | Swap matching, pricing and payment rules at runtime |
| **Decorator** | `SurgeFare` wraps `StandardFare` | Stack surge (or discounts) on top of any base fare |
| **Observer** | `NotificationObserver` | SMS / Push listeners react to ride events |
| **Status enum** (not the State pattern) | `RideStatus`, `DriverStatus` | Guards allowed transitions |

---

## Step 5: Class diagram

See [class-diagram.puml](class-diagram.puml).

---

## Step 6: Ride flow

See [ride-flow.puml](ride-flow.puml).

1. **Estimate:** `estimateFare(pickup, drop, type)` = `FareStrategy.calculate(type, distance)`.
2. **Request** → `RideService.requestRide()`:
   1. Lock in the **upfront fare** (current fare strategy, incl. surge) on the ride.
   2. `DriverService.findNearbyDrivers()` — AVAILABLE, same vehicle type, within radius (10 km).
   3. `DriverMatchingStrategy.rank()` orders them (nearest first by default).
   4. Walk the list and call `driver.tryAssign()` (synchronized AVAILABLE → ON_TRIP) until one succeeds. Another ride may grab a driver between the search and the assignment, so this loop is what makes it safe.
   5. Generate a 4-digit OTP, ride → `DRIVER_ASSIGNED`, notify both.
   6. Nobody free → ride `CANCELLED`, rider told "no drivers".
3. **Driver cancels** → ride back to `REQUESTED`, driver added to `rejectedDriverIds`, driver freed, re-match skipping rejected drivers.
4. **Rider cancels** → ride `CANCELLED`, driver freed.
5. **Start** → driver enters OTP; wrong OTP is rejected; ride → `IN_PROGRESS`.
6. **Complete** → pay the upfront fare. If payment fails the ride stays `IN_PROGRESS` so the rider can retry. On success: ride `COMPLETED`, driver moved to drop location and back to `AVAILABLE`.
7. **Rate** → both sides rate (1–5) only after `COMPLETED`.

**Ride states:**

```
REQUESTED --assign--> DRIVER_ASSIGNED --OTP--> IN_PROGRESS --pay--> COMPLETED
    ^                     |    |
    +--- driver cancels --+    +-- rider cancels --> CANCELLED
REQUESTED --no driver / rider cancels--> CANCELLED
```

**Fare:** `baseFare + perKm × distance`, e.g. MINI = 50 + 12/km. Surge = `SurgeFare(new StandardFare(), 1.5)`.

---

## Step 7: Code

See [CabBookingSystem.java](CabBookingSystem.java). The `main` method demos:

1. Fare estimates for every vehicle type
2. Request → nearest driver assigned, OTP shared
3. Wrong OTP rejected, correct OTP starts the ride
4. Wallet payment fails → ride stays in progress → UPI succeeds; rider and driver rate each other
5. No driver of that type → rejected
6. Driver cancels → auto re-match to the next driver; rider cancels → driver freed
7. Surge pricing (Decorator) switched at runtime
8. 10 riders, 3 bikes, all at once → exactly 3 rides, no bike assigned twice

---

## Step 8: Interview talking points

1. **Preventing double assignment**
   - Single server: `synchronized tryAssign()` on the driver makes check-and-reserve atomic.
   - Search-then-assign can race, so `assignDriver()` falls back to the next driver if `tryAssign` fails.
   - Multiple servers: conditional update in the DB (see below) or a Redis lock per driver.
2. **Finding nearby drivers fast.** A linear scan is fine for an interview. In production, drivers send their location every 3–5 s into a **geo index**:
   - Geohash: bucket drivers by geohash prefix, search the pickup cell + 8 neighbours.
   - QuadTree / S2 / H3 cells.
   - Redis `GEOADD` / `GEOSEARCH ... BYRADIUS`.
   - Locations stay in memory / Redis, not written to the main DB on every update.
3. **Driver accept / reject.** Real apps send the request to one driver at a time with a ~15 s timeout. This design auto-assigns; extend with a `PENDING_ACCEPT` state and move to the next driver on timeout or reject (same `rejectedDriverIds` mechanism).
4. **Upfront pricing.** The fare is fixed when the ride is requested, so surge changes mid-trip don't affect the rider. For metered pricing, calculate at completion from the actual route.
5. **Open/Closed principle.** New matching rule, fare rule, payment method or notifier = new class implementing the existing interface.
6. **Extensions:** ride sharing / pool (multiple riders, route matching), scheduled rides, cancellation fee after N minutes, promo codes (another fare Decorator), driver earnings & payouts, live tracking (WebSocket), SOS, trip history.

---

## Step 9: Database schema

See [db-schema.puml](db-schema.puml).

| Table | Maps to | Notes |
|---|---|---|
| `users` | `User` / `Rider` / `Driver` | Single table, `role` column, rating sum/count |
| `drivers` | `Driver` | Status + last known location |
| `vehicles` | `Vehicle` | One vehicle per driver, unique number plate |
| `fare_rates` | `VehicleType` base / per-km | Rates editable without a code change |
| `rides` | `Ride` | Pickup / drop coordinates, fare, surge multiplier, OTP, status, timestamps |
| `ride_rejections` | `Ride.rejectedDriverIds` | Drivers who cancelled / declined this ride |
| `payments` | `Payment` | Many rows per ride allowed (failed attempt + retry) |
| `ratings` | `User.addRating` | One rating per ride per side, `PK(ride_id, from_user_id)` |

**Assigning a driver atomically** (the DB version of `tryAssign()`):

```sql
UPDATE drivers SET status = 'ON_TRIP'
WHERE user_id = :driverId AND status = 'AVAILABLE';
-- 1 row updated -> driver is ours; 0 rows -> try the next driver
```

**Nearby available drivers** (SQL fallback; production uses Redis GEO / PostGIS):

```sql
SELECT d.user_id FROM drivers d
JOIN vehicles v ON v.driver_id = d.user_id
WHERE d.status = 'AVAILABLE' AND v.type = :type
  AND d.current_lat BETWEEN :lat - 0.09 AND :lat + 0.09     -- ~10 km bounding box
  AND d.current_lng BETWEEN :lng - 0.09 AND :lng + 0.09;
-- then compute exact distance and rank in the app

-- PostGIS: WHERE ST_DWithin(d.location, ST_MakePoint(:lng, :lat)::geography, 10000)
```

**Notes:**
- `rides.fare` and `surge_multiplier` are stored at request time, so the receipt matches what the rider was shown.
- Ride history: `INDEX(rider_id, requested_at)` and `INDEX(driver_id, requested_at)`.
- `ratings` updates `users.rating_sum` / `rating_count` in the same transaction, so the average is O(1) to read.
