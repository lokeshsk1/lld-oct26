# Meeting Scheduler — LLD

| File | What |
|---|---|
| [README.md](README.md) | Steps: requirements → entities → relationships → patterns → flow → talking points → DB schema |
| [class-diagram.puml](class-diagram.puml) | UML class diagram |
| [scheduling-flow.puml](scheduling-flow.puml) | Scheduling sequence diagram |
| [db-schema.puml](db-schema.puml) | Database schema (ER diagram) |
| [MeetingSchedulerSystem.java](MeetingSchedulerSystem.java) | Full implementation + runnable demo |
| [MeetingSchedulerSkeleton.java](MeetingSchedulerSkeleton.java) | Skeleton only: classes, fields, method signatures with TODOs |

**Run:**

```bash
javac MeetingSchedulerSystem.java && java MeetingSchedulerSystem
```

---

## Step 1: Requirements

**Functional**
1. Schedule a meeting: organizer, title, invitees, start/end time.
2. Automatically book a free meeting room that fits everyone.
3. Invitees accept or decline; the organizer is notified.
4. Check who is busy at a given time.
5. Find the earliest common free slot for a group of people.
6. Reschedule or cancel a meeting (organizer only).
7. Notify participants (Email / Slack) on invite, reschedule and cancel.

**Non-functional**
- **No double booking.** A room can never hold two overlapping meetings.
- **Extensible.** New room-selection rules or notification channels should not require changing existing code.

---

## Step 2: Core entities

| Entity | Responsibility |
|---|---|
| `User` | Name, email, own calendar |
| `UserCalendar` | Meetings the user is in; `isFree(interval)` |
| `Interval` | Start / end time + overlap check |
| `MeetingRoom` | Name, capacity, floor, booked intervals |
| `Meeting` | Title, organizer, room, interval, status, invitations |
| `MeetingStatus` (enum) | SCHEDULED, CANCELLED |
| `Invitation` | One invitee + their response |
| `InvitationStatus` (enum) | PENDING, ACCEPTED, DECLINED |
| `RoomSelectionStrategy` | BestFit, FirstAvailable, FloorPreference |
| `NotificationObserver` | Email, Slack |
| `RoomService`, `CalendarService`, `MeetingService` | Business logic |
| `MeetingSchedulerSystem` | Single entry point (Singleton + Facade) |

---

## Step 3: Relationships

- `User` **has** one `UserCalendar` — composition.
- `UserCalendar` **lists** many `Meeting`s (aggregation; meetings are shared between calendars).
- `MeetingRoom` **has** many booked `Interval`s.
- `Meeting` **is organized by** one `User`, **held in** one `MeetingRoom` for one `Interval`.
- `Meeting` **has** many `Invitation`s — composition; each `Invitation` **points to** one `User`.
- `MeetingService` **uses** `RoomService`, a `RoomSelectionStrategy`, and notifies `NotificationObserver`s.

---

## Step 4: Design patterns

| Pattern | Where | Why |
|---|---|---|
| **Singleton** | `MeetingSchedulerSystem` | One shared system instance |
| **Facade** | `MeetingSchedulerSystem` | One simple API over all services |
| **Strategy** | `RoomSelectionStrategy` | Swap how a room is picked (best fit, nearest floor, …) |
| **Observer** | `NotificationObserver` | Email/Slack listeners react to meeting events |
| **Status enum** (not the State pattern) | `MeetingStatus`, `InvitationStatus` | Guards allowed transitions |

---

## Step 5: Class diagram

See [class-diagram.puml](class-diagram.puml).

---

## Step 6: Scheduling flow

See [scheduling-flow.puml](scheduling-flow.puml).

1. **(Optional) find a time:** `findCommonFreeSlot(users, day, length)` or `getBusyUsers(users, interval)`.
2. **Schedule** → `MeetingService.scheduleMeeting()`:
   1. `RoomService.findAvailableRooms(interval, headCount)` — capacity ≥ invitees + organizer, and free.
   2. `RoomSelectionStrategy.rank()` orders them (BestFit = smallest room that fits).
   3. Walk the ranked list and call `room.tryBook(interval)` (synchronized check-and-book) until one succeeds. Another thread may have taken a room between the search and the booking, so this loop is what makes it safe.
   4. No room → reject.
   5. Create the `Meeting` with `PENDING` invitations, add it to every calendar, notify invitees.
3. **Respond** → accept / decline. A declined meeting is removed from the invitee's calendar, so it no longer makes them busy. The organizer is notified.
4. **Reschedule** → try `tryMove()` in the same room first (atomic remove-old + book-new). If that fails, book a new room **first**, then release the old one, so the meeting is never left without a room. All invitations go back to `PENDING`.
5. **Cancel** → release the room, mark `CANCELLED`, remove from calendars, notify.

**Overlap rule** (end is exclusive, so 10:00–11:00 and 11:00–12:00 don't clash):

```
[a, b) overlaps [c, d)   ⇔   a < d  &&  c < b
```

**Common free slot algorithm** (`CalendarService.findCommonFreeSlot`):

```
busy = all users' meetings on that day, sorted by start
cursor = 9:00
for each busy block b:
    if cursor + length <= b.start: break        // gap found
    cursor = max(cursor, b.end)
return cursor + length <= 18:00 ? [cursor, cursor + length) : none
```

Time: O(n log n) for n busy blocks.

---

## Step 7: Code

See [MeetingSchedulerSystem.java](MeetingSchedulerSystem.java). The `main` method demos:

1. Schedule → best-fit room (3 people → Huddle, not Board)
2. Accept / decline → organizer notified
3. Busy check (declined user shows as free) + room fallback when the best room is taken
4. Common free slot for 30 and 60 minutes
5. No room big enough → rejected
6. Reschedule (same room kept), old slot freed
7. Cancel — non-organizer rejected, organizer succeeds, room freed
8. Switching to floor-preference strategy at runtime
9. 10 threads scheduling at the same time with 3 rooms → exactly 3 winners, one per room

---

## Step 8: Interview talking points

1. **Preventing double booking**
   - Single server: `synchronized tryBook()` on the room makes check-and-book atomic. Different rooms don't block each other.
   - Search-then-book can race, so `bookRoom()` walks the ranked list and falls back to the next room if `tryBook` fails.
   - Multiple servers: see the DB section below (row lock or exclusion constraint).
2. **Reschedule safely.** Book the new slot before releasing the old one (or move atomically in the same room). The meeting never ends up with no room.
3. **Busy participants.** This design warns (via `getBusyUsers`) but doesn't block, as Google Calendar and Outlook do. A stricter policy could be another strategy.
4. **Open/Closed principle.** New room rule or notifier = new class implementing the existing interface.
5. **Extensions:** recurring meetings (store a recurrence rule, expand into instances), time zones (store UTC, convert on display), room equipment filters (projector, VC), optional vs required attendees, reminders (scheduled job N minutes before start), find a slot that also has a free room.

---

## Step 9: Database schema

See [db-schema.puml](db-schema.puml).

| Table | Maps to | Notes |
|---|---|---|
| `users` | `User` | |
| `meeting_rooms` | `MeetingRoom` | |
| `meetings` | `Meeting` + `MeetingRoom.bookedSlots` | A room's bookings are simply its scheduled meetings |
| `meeting_participants` | `Invitation` | `(meeting_id, user_id)` primary key, response status |

A user's calendar (`UserCalendar`) is a query, not a table: meetings they organize + meetings they're invited to and haven't declined.

**Free rooms for a slot:**

```sql
SELECT r.* FROM meeting_rooms r
WHERE r.capacity >= :headCount
  AND NOT EXISTS (
    SELECT 1 FROM meetings m
    WHERE m.room_id = r.id AND m.status = 'SCHEDULED'
      AND m.start_time < :end AND :start < m.end_time   -- overlap rule
  )
ORDER BY r.capacity;                                   -- best fit
```

**A user's calendar for a day:**

```sql
SELECT m.* FROM meetings m
LEFT JOIN meeting_participants p ON p.meeting_id = m.id AND p.user_id = :userId
WHERE m.status = 'SCHEDULED'
  AND (m.organizer_id = :userId OR p.status IN ('PENDING', 'ACCEPTED'))
  AND m.start_time >= :dayStart AND m.start_time < :dayEnd;
```

**Preventing double booking in the DB:**
- In one transaction: `SELECT * FROM meeting_rooms WHERE id = :roomId FOR UPDATE` (locks the room row), run the overlap check, then insert the meeting.
- PostgreSQL alternative: an exclusion constraint makes overlaps impossible at the DB level:
  `EXCLUDE USING gist (room_id WITH =, tsrange(start_time, end_time) WITH &&) WHERE (status = 'SCHEDULED')`.
