# Food Delivery System (Swiggy / Zomato) — LLD

| File | What |
|---|---|
| [README.md](README.md) | Steps: requirements → entities → relationships → patterns → flow → talking points → DB schema |
| [class-diagram.puml](class-diagram.puml) | UML class diagram |
| [order-states.puml](order-states.puml) | Order lifecycle state diagram |
| [order-flow.puml](order-flow.puml) | Cart → order → accept → deliver sequence diagram |
| [db-schema.puml](db-schema.puml) | Database schema (ER diagram) |
| [FoodDeliverySystem.java](FoodDeliverySystem.java) | Full implementation + runnable demo |
| [FoodDeliverySkeleton.java](FoodDeliverySkeleton.java) | Skeleton only: classes, fields, method signatures with TODOs |

**Run:**

```bash
javac FoodDeliverySystem.java && java FoodDeliverySystem
```

---

## Step 1: Requirements

**Functional**
1. Customer searches open restaurants near their address.
2. Customer adds menu items to a cart — items from **one restaurant** only.
3. Customer places the order with an optional coupon and pays (UPI / Card / Wallet / Cash on delivery).
4. Bill = items + delivery fee (by distance) + 5% tax − coupon.
5. Restaurant accepts or rejects; then marks preparing and ready.
6. On accept, the system assigns a nearby delivery partner. If none is free, the order waits and goes to the next partner who becomes free.
7. Partner picks up and delivers.
8. Customer can cancel only before cooking starts (full refund).
9. Notify customer, restaurant and partner at each step.

**Non-functional**
- **No overselling.** Limited items (e.g. "3 portions left") can never be sold more times than stock.
- **No double assignment.** A partner never carries two orders at once.
- **Valid order flow only.** E.g. can't pick up before ready, can't cancel while cooking.
- **Extensible.** New coupons, fee rules, assignment rules, payment methods or notification channels without changing existing code.

---

## Step 2: Core entities

| Entity | Responsibility |
|---|---|
| `User` → `Customer`, `DeliveryPartner` | Common id, name, phone |
| `Customer` | Address, cart, order history |
| `DeliveryPartner` | Location, status, deliveries today |
| `PartnerStatus` (enum) | OFFLINE, AVAILABLE, BUSY |
| `Restaurant` | Name, cuisine, location, menu, open / closed |
| `MenuItem` | Name, price, veg, stock, available |
| `Cart` | One restaurant + item quantities |
| `Order` | Customer, restaurant, items, address, bill, status, partner, payment |
| `OrderItem` | Name + price **snapshot** + qty |
| `Bill` | Subtotal, delivery fee, tax, discount, total |
| `OrderStatus` (enum) | PLACED → ACCEPTED → PREPARING → READY_FOR_PICKUP → PICKED_UP → DELIVERED, or CANCELLED |
| `Payment` / `PaymentStrategy` | UPI, Card, Wallet, Cash on delivery |
| `DeliveryFeeStrategy` | StandardDeliveryFee, FreeDeliveryAbove |
| `Coupon` | FlatCoupon, PercentCoupon |
| `PartnerAssignmentStrategy` | NearestPartner, FairDistribution |
| `NotificationObserver` | Push, SMS |
| `RestaurantService`, `OrderService`, `DeliveryService` | Business logic |
| `FoodDeliverySystem` | Single entry point (Singleton + Facade) |

**Why `OrderItem` copies name and price:** if the restaurant changes the menu price tomorrow, today's order and receipt must not change.

---

## Step 3: Relationships

- `Customer` and `DeliveryPartner` **are** `User`s.
- `Customer` **has** one `Cart` — composition; `Cart` **points to** at most one `Restaurant`.
- `Restaurant` **has** many `MenuItem`s — composition.
- `Customer` **places** many `Order`s; each `Order` **is from** one `Restaurant`.
- `Order` **has** many `OrderItem`s and one `Bill` — composition.
- `Order` **is delivered by** zero or one `DeliveryPartner`, and **has** one `Payment`.
- `OrderService` **uses** `DeliveryService`, a `DeliveryFeeStrategy` and `Coupon`s.
- `DeliveryService` **uses** a `PartnerAssignmentStrategy` and keeps a queue of orders waiting for a partner.
- `FreeDeliveryAbove` **wraps** another `DeliveryFeeStrategy` (Decorator).

---

## Step 4: Design patterns

| Pattern | Where | Why |
|---|---|---|
| **Singleton** | `FoodDeliverySystem` | One shared system instance |
| **Facade** | `FoodDeliverySystem` | One simple API for customers, restaurants and partners |
| **Strategy** | `PaymentStrategy`, `DeliveryFeeStrategy`, `Coupon`, `PartnerAssignmentStrategy` | Swap payment, fee, discount and assignment rules at runtime |
| **Decorator** | `FreeDeliveryAbove` wraps `StandardDeliveryFee` | Stack fee rules (free above X, surge, membership) |
| **Observer** | `NotificationObserver` | Push / SMS listeners react to order events |
| **Status enum** (not the State pattern) | `OrderStatus.canMoveTo()` | All allowed transitions in one place; `Order.moveTo()` rejects the rest |

---

## Step 5: Class diagram

See [class-diagram.puml](class-diagram.puml).

---

## Step 6: Order flow

See [order-flow.puml](order-flow.puml) and [order-states.puml](order-states.puml).

1. **Cart** → `Cart.add()` rejects items from a second restaurant.
2. **Place order** → `OrderService.placeOrder()`, inside `synchronized(cart)`:
   1. Restaurant must be open.
   2. Build the `Bill`: subtotal, `DeliveryFeeStrategy` (distance restaurant → customer), 5% tax, `Coupon` (checks minimum order). The coupon is validated **before** stock is touched.
   3. `MenuItem.tryReserve(qty)` for each item (synchronized) — all-or-nothing; if one is sold out, release the others.
   4. Pay. Failure → release stock and **keep the cart** so the customer can retry.
   5. Clear cart, save order as `PLACED`, notify the restaurant.
3. **Restaurant accepts** → `ACCEPTED` → `DeliveryService.assign()`:
   - Rank available partners near the restaurant (nearest first by default), `tryAssign()` each until one succeeds.
   - Nobody free → order goes to a FIFO waiting queue.
4. **Cook** → `PREPARING` → `READY_FOR_PICKUP`.
5. **Pick up** → only the assigned partner, only when ready → `PICKED_UP`.
6. **Deliver** → `DELIVERED`; partner moves to the customer's address and becomes free → `onPartnerAvailable()` gives them the oldest waiting order.
7. **Cancel** (restaurant rejects while `PLACED`, or customer cancels before `PREPARING`) → restore stock, refund, free the partner, remove from the waiting queue.

**Bill example** (2 Masala Dosa + 2 Filter Coffee, 5.1 km, WELCOME50):
`items 260 + delivery 45.63 (20 + 5/km) + tax 13 − coupon 50 = Rs 268.63`

---

## Step 7: Code

See [FoodDeliverySystem.java](FoodDeliverySystem.java). The `main` method demos:

1. Search restaurants within 7 km (the far one is excluded)
2. Cart rejects an item from a second restaurant
3. Order with a flat coupon → bill breakdown
4. Full lifecycle; invalid moves rejected (pick up before ready, cancel while cooking, wrong partner picks up)
5. Percent coupon below its minimum → rejected; free delivery above Rs 300 (Decorator) → fee 0
6. Restaurant rejects → refund
7. Customer cancels after accept → refund, partner freed
8. Payment fails → stock restored, cart kept; closed restaurant → rejected
9. 10 customers order the last 3 portions at once → exactly 3 orders; 3 accepted at once with 2 partners → 2 assigned, 1 waits; when a partner delivers, they're auto-assigned the waiting order

---

## Step 8: Interview talking points

1. **Concurrency**
   - `MenuItem.tryReserve()` is synchronized: limited stock can't be oversold.
   - `DeliveryPartner.tryAssign()` is synchronized: a partner never gets two orders.
   - `DeliveryService.assign()` / `onPartnerAvailable()` are synchronized, so the waiting queue and assignment don't race.
   - `Order.moveTo()` is synchronized: a cancel and a "start preparing" can't both win.
   - Across servers: conditional `UPDATE`s (see DB section) or Redis locks.
2. **Why a state check in one place?** `OrderStatus.canMoveTo()` holds the whole lifecycle. Every action calls `moveTo()`, so no service can skip a step or move a delivered order back.
3. **When to assign the partner.** On accept (not on ready), so the partner arrives about when the food is ready. Real apps estimate prep time + travel time and assign a bit later.
4. **Finding partners fast.** Same as cab booking: geohash / Redis GEO for nearby partners; linear scan is fine for the interview.
5. **Batching.** One partner can carry two orders from the same restaurant going the same way. Extend `DeliveryPartner` with a capacity and `PartnerAssignmentStrategy` with route matching.
6. **Extensions:** ratings for restaurant and partner, live tracking (WebSocket), ETA, scheduled orders, restaurant prep-time per item, surge delivery fee (another fee Decorator), multiple addresses per customer, tipping, partner earnings.

---

## Step 9: Database schema

See [db-schema.puml](db-schema.puml).

| Table | Maps to | Notes |
|---|---|---|
| `users` | `User` | Customers, partners and restaurant owners, `role` column |
| `addresses` | `Customer.address` | Many saved addresses per user |
| `restaurants` | `Restaurant` | Location + open flag |
| `menu_items` | `MenuItem` | `stock` NULL = unlimited, `CHECK(stock >= 0)` |
| `cart_items` | `Cart` | Server-side cart (one restaurant enforced in app) |
| `delivery_partners` | `DeliveryPartner` | Status + last known location |
| `orders` | `Order` + `Bill` | Bill columns stored, so the receipt never changes |
| `order_items` | `OrderItem` | Name + price snapshot |
| `order_status_history` | status changes | Timeline shown in the app; audit |
| `coupons` | `Coupon` | Flat / percent, max discount, min order |
| `payments` | `Payment` | Many per order allowed (retry, refund) |

**Reserving limited stock** (one row per item, atomic):

```sql
UPDATE menu_items SET stock = stock - :qty
WHERE id = :itemId AND is_available AND (stock IS NULL OR stock >= :qty);
-- 0 rows -> sold out -> ROLLBACK the whole order
```

**Order status change** (only from the expected state):

```sql
UPDATE orders SET status = 'PREPARING'
WHERE id = :orderId AND status = 'ACCEPTED';
-- 0 rows -> someone cancelled / moved it first
INSERT INTO order_status_history (order_id, status, changed_at) VALUES (:orderId, 'PREPARING', NOW());
```

**Assigning a partner:**

```sql
UPDATE delivery_partners SET status = 'BUSY'
WHERE user_id = :partnerId AND status = 'AVAILABLE';     -- 1 row = partner is ours
UPDATE orders SET partner_id = :partnerId
WHERE id = :orderId AND partner_id IS NULL AND status <> 'CANCELLED';
```

**Orders waiting for a partner** (oldest first):

```sql
SELECT id FROM orders
WHERE partner_id IS NULL AND status IN ('ACCEPTED', 'PREPARING', 'READY_FOR_PICKUP')
ORDER BY placed_at
LIMIT 1 FOR UPDATE SKIP LOCKED;
```
