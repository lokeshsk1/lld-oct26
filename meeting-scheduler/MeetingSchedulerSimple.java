import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// Minimal meeting scheduler: rooms, meetings, no double booking.
// Classes are nested so this file compiles alongside MeetingSchedulerSystem.java.
public class MeetingSchedulerSimple {

    // ===================== Interval =====================

    static class Interval {
        final LocalDateTime start;
        final LocalDateTime end;   // exclusive

        Interval(LocalDateTime start, LocalDateTime end) {
            if (!end.isAfter(start)) throw new IllegalArgumentException("End must be after start");
            this.start = start;
            this.end = end;
        }

        // [a, b) overlaps [c, d)  <=>  a < d && c < b
        boolean overlaps(Interval o) {
            return start.isBefore(o.end) && o.start.isBefore(end);
        }

        @Override public String toString() {
            return start.toLocalTime() + "-" + end.toLocalTime();
        }
    }

    // ===================== User =====================

    static class User {
        final String name;
        final List<Meeting> meetings = new ArrayList<>();

        User(String name) { this.name = name; }

        synchronized void addMeeting(Meeting m)    { meetings.add(m); }
        synchronized void removeMeeting(Meeting m) { meetings.remove(m); }

        @Override public String toString() { return name; }
    }

    // ===================== Room =====================

    static class Room {
        final String name;
        final int capacity;
        private final List<Interval> booked = new ArrayList<>();

        Room(String name, int capacity) {
            this.name = name;
            this.capacity = capacity;
        }

        synchronized boolean isFree(Interval i) {
            return booked.stream().noneMatch(b -> b.overlaps(i));
        }

        // check + book in one locked step -> no double booking
        synchronized boolean tryBook(Interval i) {
            if (!isFree(i)) return false;
            booked.add(i);
            return true;
        }

        synchronized void release(Interval i) { booked.remove(i); }

        @Override public String toString() { return name + "(cap " + capacity + ")"; }
    }

    // ===================== Meeting =====================

    static class Meeting {
        final String id = UUID.randomUUID().toString().substring(0, 8);
        final String title;
        final User organizer;
        final List<User> participants;
        final Room room;
        final Interval interval;

        Meeting(String title, User organizer, List<User> participants, Room room, Interval interval) {
            this.title = title;
            this.organizer = organizer;
            this.participants = participants;
            this.room = room;
            this.interval = interval;
        }

        List<User> everyone() {
            List<User> all = new ArrayList<>(participants);
            all.add(organizer);
            return all;
        }

        @Override public String toString() {
            return "'" + title + "' " + interval + " in " + room.name + " by " + organizer + " with " + participants;
        }
    }

    // ===================== Scheduler =====================

    static class MeetingScheduler {
        private final List<Room> rooms = new ArrayList<>();
        private final Map<String, Meeting> meetings = new ConcurrentHashMap<>();

        void addRoom(Room r) { rooms.add(r); }

        Meeting schedule(User organizer, String title, List<User> participants, Interval interval) {
            int headCount = participants.size() + 1;

            // smallest room that fits first; if another thread grabs it, try the next one
            List<Room> candidates = new ArrayList<>();
            for (Room r : rooms) if (r.capacity >= headCount) candidates.add(r);
            candidates.sort(Comparator.comparingInt(r -> r.capacity));

            for (Room r : candidates) {
                if (r.tryBook(interval)) {
                    Meeting m = new Meeting(title, organizer, participants, r, interval);
                    meetings.put(m.id, m);
                    m.everyone().forEach(u -> u.addMeeting(m));
                    return m;
                }
            }
            throw new IllegalStateException("No room for " + headCount + " people at " + interval);
        }

        void cancel(String meetingId) {
            Meeting m = meetings.remove(meetingId);
            if (m == null) throw new IllegalStateException("No meeting " + meetingId);
            m.room.release(m.interval);
            m.everyone().forEach(u -> u.removeMeeting(m));
        }
    }

    // ===================== Demo =====================

    private static Interval at(int h1, int h2) {
        return new Interval(LocalDateTime.of(2026, 11, 16, h1, 0), LocalDateTime.of(2026, 11, 16, h2, 0));
    }

    public static void main(String[] args) throws InterruptedException {
        MeetingScheduler scheduler = new MeetingScheduler();
        Room small = new Room("Small", 2);
        Room big   = new Room("Big", 6);
        scheduler.addRoom(small);
        scheduler.addRoom(big);

        User alice = new User("Alice");
        User bob   = new User("Bob");
        User carol = new User("Carol");

        // 1. Smallest room that fits
        Meeting m1 = scheduler.schedule(alice, "1:1", List.of(bob), at(10, 11));
        System.out.println("1. " + m1);

        // 2. Small is taken at 10:00 -> falls back to Big
        Meeting m2 = scheduler.schedule(carol, "Sync", List.of(bob), at(10, 11));
        System.out.println("2. " + m2);

        // 3. Both rooms taken -> rejected
        try {
            scheduler.schedule(bob, "Chat", List.of(), at(10, 11));
        } catch (IllegalStateException e) {
            System.out.println("3. Rejected: " + e.getMessage());
        }

        // 4. Cancel frees the room
        scheduler.cancel(m1.id);
        System.out.println("4. Cancelled 1:1. Small free at 10:00? " + small.isFree(at(10, 11))
                + ", Alice's meetings: " + alice.meetings);

        // 5. Race: 5 threads want 15:00, only 2 rooms -> exactly 2 winners
        List<String> winners = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            User org = new User("O" + i);
            Thread t = new Thread(() -> {
                try {
                    Meeting m = scheduler.schedule(org, "Race", List.of(), at(15, 16));
                    winners.add(org + "->" + m.room.name);
                } catch (IllegalStateException ignored) { }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join();
        System.out.println("5. Winners: " + winners + " (expected exactly 2)");
    }
}
