import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * Skeleton for the Meeting Scheduler LLD: classes, fields and method signatures only.
 * Every class is nested here so this file can sit next to MeetingSchedulerSystem.java
 * without duplicate-class clashes. Getters/setters omitted.
 */
public class MeetingSchedulerSkeleton {

    // ===================== Enums =====================

    enum MeetingStatus { SCHEDULED, CANCELLED }

    enum InvitationStatus { PENDING, ACCEPTED, DECLINED }

    // ===================== Interval =====================

    static class Interval {
        LocalDateTime start;
        LocalDateTime end;   // exclusive

        Interval(LocalDateTime start, LocalDateTime end) {
            // TODO: validate end > start
        }

        boolean overlaps(Interval other) {
            // TODO: a < d && c < b
            throw new UnsupportedOperationException("TODO");
        }

        Duration duration() {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }
    }

    // ===================== User & Calendar =====================

    static class UserCalendar {
        List<Meeting> meetings;

        synchronized void add(Meeting m) { /* TODO: no duplicates */ }

        synchronized void remove(Meeting m) { /* TODO */ }

        synchronized boolean isFree(Interval interval) {
            // TODO: no SCHEDULED meeting overlaps
            throw new UnsupportedOperationException("TODO");
        }

        synchronized List<Interval> getBusyIntervals(LocalDate day) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }
    }

    static class User {
        String id;
        String name;
        String email;
        UserCalendar calendar;
    }

    // ===================== MeetingRoom =====================

    static class MeetingRoom {
        String id;
        String name;
        int capacity;
        int floor;
        List<Interval> bookedSlots;

        synchronized boolean isAvailable(Interval interval) {
            // TODO: no booked slot overlaps
            throw new UnsupportedOperationException("TODO");
        }

        synchronized boolean tryBook(Interval interval) {
            // TODO: atomic check + book
            throw new UnsupportedOperationException("TODO");
        }

        synchronized void release(Interval interval) { /* TODO */ }

        synchronized boolean tryMove(Interval from, Interval to) {
            // TODO: remove from, check to, roll back on failure
            throw new UnsupportedOperationException("TODO");
        }
    }

    // ===================== Meeting & Invitation =====================

    static class Invitation {
        User participant;
        InvitationStatus status;

        void accept()  { /* TODO */ }

        void decline() { /* TODO */ }

        void reset()   { /* TODO: back to PENDING */ }
    }

    static class Meeting {
        String id;
        String title;
        User organizer;
        MeetingRoom room;
        Interval interval;
        MeetingStatus status;
        Map<String, Invitation> invitations;   // userId -> invitation

        Invitation getInvitation(User user) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }

        List<User> getInvitees() {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }

        int headCount() {
            // TODO: invitees + organizer
            throw new UnsupportedOperationException("TODO");
        }

        void cancel() { /* TODO: SCHEDULED -> CANCELLED */ }

        void reschedule(MeetingRoom newRoom, Interval newInterval) {
            // TODO: update room + interval, reset all invitations
        }
    }

    // ===================== Room selection (Strategy) =====================

    interface RoomSelectionStrategy {
        List<MeetingRoom> rank(List<MeetingRoom> freeRooms, int headCount);
    }

    static class BestFitStrategy implements RoomSelectionStrategy {
        public List<MeetingRoom> rank(List<MeetingRoom> freeRooms, int headCount) {
            // TODO: smallest capacity first
            throw new UnsupportedOperationException("TODO");
        }
    }

    static class FirstAvailableStrategy implements RoomSelectionStrategy {
        public List<MeetingRoom> rank(List<MeetingRoom> freeRooms, int headCount) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }
    }

    static class FloorPreferenceStrategy implements RoomSelectionStrategy {
        int preferredFloor;

        public List<MeetingRoom> rank(List<MeetingRoom> freeRooms, int headCount) {
            // TODO: nearest floor first, then smallest
            throw new UnsupportedOperationException("TODO");
        }
    }

    // ===================== Notification (Observer) =====================

    interface NotificationObserver {
        void notify(User recipient, String message);
    }

    static class EmailNotifier implements NotificationObserver {
        public void notify(User recipient, String message) { /* TODO */ }
    }

    static class SlackNotifier implements NotificationObserver {
        public void notify(User recipient, String message) { /* TODO */ }
    }

    // ===================== Services =====================

    static class RoomService {
        List<MeetingRoom> rooms;

        void addRoom(MeetingRoom room) { /* TODO */ }

        List<MeetingRoom> findAvailableRooms(Interval interval, int headCount) {
            // TODO: capacity >= headCount and free
            throw new UnsupportedOperationException("TODO");
        }
    }

    static class CalendarService {
        List<User> getBusyUsers(List<User> users, Interval interval) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }

        Optional<Interval> findCommonFreeSlot(List<User> users, LocalDate day, Duration length,
                                              LocalTime dayStart, LocalTime dayEnd) {
            // TODO: merge everyone's busy intervals (sorted), return first gap >= length
            throw new UnsupportedOperationException("TODO");
        }
    }

    static class MeetingService {
        Map<String, Meeting> meetings;
        List<NotificationObserver> observers;
        RoomService roomService;
        RoomSelectionStrategy roomStrategy;

        Meeting scheduleMeeting(User organizer, String title, List<User> invitees, Interval interval) {
            // TODO:
            // 1. bookRoom(interval, invitees + 1)
            // 2. create meeting with PENDING invitations
            // 3. add to organizer + invitee calendars
            // 4. notify invitees
            throw new UnsupportedOperationException("TODO");
        }

        void respond(String meetingId, User user, boolean accept) {
            // TODO: accept/decline, decline frees calendar, notify organizer
        }

        void cancelMeeting(String meetingId, User requester) {
            // TODO: organizer only; release room, cancel, remove from calendars, notify
        }

        Meeting rescheduleMeeting(String meetingId, User requester, Interval newInterval) {
            // TODO: organizer only; tryMove in same room, else book new room then release old;
            //       reset invitations, notify
            throw new UnsupportedOperationException("TODO");
        }

        private MeetingRoom bookRoom(Interval interval, int headCount) {
            // TODO: rank free rooms, tryBook each until one succeeds
            throw new UnsupportedOperationException("TODO");
        }

        void setRoomStrategy(RoomSelectionStrategy s) { /* TODO */ }

        void addObserver(NotificationObserver o) { /* TODO */ }
    }

    // ===================== Facade + Singleton =====================

    static class MeetingSchedulerSystem {
        private static volatile MeetingSchedulerSystem instance;
        RoomService roomService;
        CalendarService calendarService;
        MeetingService meetingService;

        private MeetingSchedulerSystem() {}

        static MeetingSchedulerSystem getInstance() {
            // TODO: double-checked locking
            throw new UnsupportedOperationException("TODO");
        }

        void addRoom(MeetingRoom room) { /* TODO */ }

        List<User> getBusyUsers(List<User> users, Interval i) {
            // TODO: delegate to calendarService
            throw new UnsupportedOperationException("TODO");
        }

        Optional<Interval> findCommonFreeSlot(List<User> users, LocalDate day, Duration length) {
            // TODO: delegate to calendarService (9:00-18:00)
            throw new UnsupportedOperationException("TODO");
        }

        Meeting schedule(User organizer, String title, List<User> invitees, Interval i) {
            // TODO: delegate to meetingService
            throw new UnsupportedOperationException("TODO");
        }

        void respond(String meetingId, User user, boolean accept) { /* TODO */ }

        Meeting reschedule(String meetingId, User requester, Interval i) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }

        void cancel(String meetingId, User requester) { /* TODO */ }
    }

    // ===================== Demo =====================

    public static void main(String[] args) {
        // TODO:
        // 1. Add rooms (different capacities / floors) and users
        // 2. Schedule -> best-fit room, invites sent
        // 3. Accept / decline
        // 4. Busy check, room fallback when best room is taken
        // 5. Find common free slot
        // 6. No room big enough -> rejected
        // 7. Reschedule, cancel (organizer only)
        // 8. Concurrent scheduling -> no room double booked
    }
}
