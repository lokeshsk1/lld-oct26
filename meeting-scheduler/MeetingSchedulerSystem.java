import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// ===================== Enums =====================

enum MeetingStatus { SCHEDULED, CANCELLED }

enum InvitationStatus { PENDING, ACCEPTED, DECLINED }

// ===================== Interval =====================

class Interval {
    private static final DateTimeFormatter DAY  = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final LocalDateTime start;
    private final LocalDateTime end;   // exclusive

    Interval(LocalDateTime start, LocalDateTime end) {
        if (!end.isAfter(start)) throw new IllegalArgumentException("End must be after start");
        this.start = start;
        this.end = end;
    }

    // [a, b) overlaps [c, d)  <=>  a < d && c < b
    public boolean overlaps(Interval o) {
        return start.isBefore(o.end) && o.start.isBefore(end);
    }

    public Duration duration()      { return Duration.between(start, end); }
    public LocalDateTime getStart() { return start; }
    public LocalDateTime getEnd()   { return end; }

    @Override public String toString() {
        return start.format(DAY) + " " + start.format(TIME) + "-" + end.format(TIME);
    }
}

// ===================== User & Calendar =====================

class UserCalendar {
    private final List<Meeting> meetings = new ArrayList<>();

    public synchronized void add(Meeting m)    { if (!meetings.contains(m)) meetings.add(m); }
    public synchronized void remove(Meeting m) { meetings.remove(m); }

    public synchronized boolean isFree(Interval interval) {
        return meetings.stream()
                .noneMatch(m -> m.getStatus() == MeetingStatus.SCHEDULED && m.getInterval().overlaps(interval));
    }

    public synchronized List<Interval> getBusyIntervals(LocalDate day) {
        return meetings.stream()
                .filter(m -> m.getStatus() == MeetingStatus.SCHEDULED)
                .map(Meeting::getInterval)
                .filter(i -> i.getStart().toLocalDate().equals(day))
                .collect(Collectors.toList());
    }

    public synchronized List<Meeting> getMeetings() { return new ArrayList<>(meetings); }
}

class User {
    private final String id = UUID.randomUUID().toString();
    private final String name;
    private final String email;
    private final UserCalendar calendar = new UserCalendar();

    User(String name, String email) {
        this.name = name;
        this.email = email;
    }

    public String getId()             { return id; }
    public String getName()           { return name; }
    public String getEmail()          { return email; }
    public UserCalendar getCalendar() { return calendar; }

    @Override public String toString() { return name; }
}

// ===================== MeetingRoom =====================

class MeetingRoom {
    private final String id = UUID.randomUUID().toString();
    private final String name;
    private final int capacity;
    private final int floor;
    private final List<Interval> bookedSlots = new ArrayList<>();

    MeetingRoom(String name, int capacity, int floor) {
        this.name = name;
        this.capacity = capacity;
        this.floor = floor;
    }

    public synchronized boolean isAvailable(Interval interval) {
        return bookedSlots.stream().noneMatch(b -> b.overlaps(interval));
    }

    // check + book atomically -> prevents double booking
    public synchronized boolean tryBook(Interval interval) {
        if (!isAvailable(interval)) return false;
        bookedSlots.add(interval);
        return true;
    }

    public synchronized void release(Interval interval) { bookedSlots.remove(interval); }

    // atomically move a booking to a new slot in the same room (used by reschedule)
    public synchronized boolean tryMove(Interval from, Interval to) {
        bookedSlots.remove(from);
        if (!isAvailable(to)) {
            bookedSlots.add(from);   // roll back
            return false;
        }
        bookedSlots.add(to);
        return true;
    }

    public String getId()     { return id; }
    public String getName()   { return name; }
    public int getCapacity()  { return capacity; }
    public int getFloor()     { return floor; }

    @Override public String toString() { return name + "(cap " + capacity + ", floor " + floor + ")"; }
}

// ===================== Meeting & Invitation =====================

class Invitation {
    private final User participant;
    private InvitationStatus status = InvitationStatus.PENDING;

    Invitation(User participant) { this.participant = participant; }

    public void accept() { status = InvitationStatus.ACCEPTED; }
    public void decline() { status = InvitationStatus.DECLINED; }
    public void reset()   { status = InvitationStatus.PENDING; }

    public User getParticipant()        { return participant; }
    public InvitationStatus getStatus() { return status; }

    @Override public String toString() { return participant.getName() + ":" + status; }
}

class Meeting {
    private final String id = UUID.randomUUID().toString().substring(0, 8);
    private final String title;
    private final User organizer;
    private final Map<String, Invitation> invitations = new LinkedHashMap<>();   // userId -> invitation
    private MeetingRoom room;
    private Interval interval;
    private MeetingStatus status = MeetingStatus.SCHEDULED;

    Meeting(String title, User organizer, List<User> invitees, MeetingRoom room, Interval interval) {
        this.title = title;
        this.organizer = organizer;
        this.room = room;
        this.interval = interval;
        for (User u : invitees) invitations.put(u.getId(), new Invitation(u));
    }

    public Invitation getInvitation(User user) {
        Invitation inv = invitations.get(user.getId());
        if (inv == null) throw new IllegalArgumentException(user + " is not invited to " + title);
        return inv;
    }

    public List<User> getInvitees() {
        return invitations.values().stream().map(Invitation::getParticipant).collect(Collectors.toList());
    }

    public int headCount() { return invitations.size() + 1; }   // invitees + organizer

    public void cancel() {
        if (status != MeetingStatus.SCHEDULED) throw new IllegalStateException("Meeting already cancelled");
        status = MeetingStatus.CANCELLED;
    }

    // new time/room -> everyone has to respond again
    public void reschedule(MeetingRoom newRoom, Interval newInterval) {
        if (status != MeetingStatus.SCHEDULED) throw new IllegalStateException("Cannot reschedule a cancelled meeting");
        this.room = newRoom;
        this.interval = newInterval;
        invitations.values().forEach(Invitation::reset);
    }

    public String getId()                       { return id; }
    public String getTitle()                    { return title; }
    public User getOrganizer()                  { return organizer; }
    public MeetingRoom getRoom()                { return room; }
    public Interval getInterval()               { return interval; }
    public MeetingStatus getStatus()            { return status; }
    public Collection<Invitation> getInvitations() { return invitations.values(); }

    @Override public String toString() {
        return "Meeting#" + id + " '" + title + "' " + interval + " in " + room.getName()
                + " by " + organizer.getName() + " " + invitations.values() + " " + status;
    }
}

// ===================== Room selection (Strategy) =====================

interface RoomSelectionStrategy {
    // order the free rooms by preference; scheduler tries them in this order
    List<MeetingRoom> rank(List<MeetingRoom> freeRooms, int headCount);
}

// smallest room that fits -> keeps big rooms free for big meetings
class BestFitStrategy implements RoomSelectionStrategy {
    public List<MeetingRoom> rank(List<MeetingRoom> freeRooms, int headCount) {
        return freeRooms.stream()
                .sorted(Comparator.comparingInt(MeetingRoom::getCapacity))
                .collect(Collectors.toList());
    }
}

class FirstAvailableStrategy implements RoomSelectionStrategy {
    public List<MeetingRoom> rank(List<MeetingRoom> freeRooms, int headCount) {
        return new ArrayList<>(freeRooms);
    }
}

// closest to the organizer's floor, then smallest
class FloorPreferenceStrategy implements RoomSelectionStrategy {
    private final int preferredFloor;

    FloorPreferenceStrategy(int preferredFloor) { this.preferredFloor = preferredFloor; }

    public List<MeetingRoom> rank(List<MeetingRoom> freeRooms, int headCount) {
        return freeRooms.stream()
                .sorted(Comparator.comparingInt((MeetingRoom r) -> Math.abs(r.getFloor() - preferredFloor))
                        .thenComparingInt(MeetingRoom::getCapacity))
                .collect(Collectors.toList());
    }
}

// ===================== Notification (Observer) =====================

interface NotificationObserver {
    void notify(User recipient, String message);
}

class EmailNotifier implements NotificationObserver {
    public void notify(User recipient, String message) {
        System.out.println("  [EMAIL to " + recipient.getEmail() + "] " + message);
    }
}

class SlackNotifier implements NotificationObserver {
    public void notify(User recipient, String message) {
        System.out.println("  [SLACK @" + recipient.getName().toLowerCase() + "] " + message);
    }
}

// ===================== Services =====================

class RoomService {
    private final List<MeetingRoom> rooms = new ArrayList<>();

    public void addRoom(MeetingRoom room) { rooms.add(room); }

    public List<MeetingRoom> findAvailableRooms(Interval interval, int headCount) {
        return rooms.stream()
                .filter(r -> r.getCapacity() >= headCount)
                .filter(r -> r.isAvailable(interval))
                .collect(Collectors.toList());
    }
}

class CalendarService {
    public List<User> getBusyUsers(List<User> users, Interval interval) {
        return users.stream()
                .filter(u -> !u.getCalendar().isFree(interval))
                .collect(Collectors.toList());
    }

    // earliest slot of the given length when ALL users are free, within working hours
    public Optional<Interval> findCommonFreeSlot(List<User> users, LocalDate day, Duration length,
                                                 LocalTime dayStart, LocalTime dayEnd) {
        List<Interval> busy = new ArrayList<>();
        for (User u : users) busy.addAll(u.getCalendar().getBusyIntervals(day));
        busy.sort(Comparator.comparing(Interval::getStart));

        LocalDateTime cursor = day.atTime(dayStart);
        LocalDateTime limit = day.atTime(dayEnd);
        for (Interval b : busy) {
            if (!cursor.plus(length).isAfter(b.getStart())) break;   // gap before this busy block fits
            if (b.getEnd().isAfter(cursor)) cursor = b.getEnd();
        }
        if (cursor.plus(length).isAfter(limit)) return Optional.empty();
        return Optional.of(new Interval(cursor, cursor.plus(length)));
    }
}

class MeetingService {
    private final Map<String, Meeting> meetings = new ConcurrentHashMap<>();
    private final List<NotificationObserver> observers = new ArrayList<>();
    private final RoomService roomService;
    private RoomSelectionStrategy roomStrategy = new BestFitStrategy();

    MeetingService(RoomService roomService) { this.roomService = roomService; }

    public Meeting scheduleMeeting(User organizer, String title, List<User> invitees, Interval interval) {
        int headCount = invitees.size() + 1;
        MeetingRoom room = bookRoom(interval, headCount);

        Meeting m = new Meeting(title, organizer, invitees, room, interval);
        meetings.put(m.getId(), m);
        organizer.getCalendar().add(m);
        invitees.forEach(u -> u.getCalendar().add(m));
        invitees.forEach(u -> notify(u, "Invite from " + organizer + ": '" + title + "' " + interval + " in " + room.getName()));
        return m;
    }

    public void respond(String meetingId, User user, boolean accept) {
        Meeting m = getScheduled(meetingId);
        Invitation inv = m.getInvitation(user);
        if (accept) {
            inv.accept();
            user.getCalendar().add(m);
        } else {
            inv.decline();
            user.getCalendar().remove(m);   // declined meetings don't block the user's calendar
        }
        notify(m.getOrganizer(), user + (accept ? " accepted" : " declined") + " '" + m.getTitle() + "'");
    }

    public void cancelMeeting(String meetingId, User requester) {
        Meeting m = getScheduled(meetingId);
        requireOrganizer(m, requester);
        m.getRoom().release(m.getInterval());
        m.cancel();
        m.getOrganizer().getCalendar().remove(m);
        m.getInvitees().forEach(u -> {
            u.getCalendar().remove(m);
            notify(u, "Cancelled: '" + m.getTitle() + "' " + m.getInterval());
        });
    }

    public Meeting rescheduleMeeting(String meetingId, User requester, Interval newInterval) {
        Meeting m = getScheduled(meetingId);
        requireOrganizer(m, requester);
        MeetingRoom oldRoom = m.getRoom();
        Interval oldInterval = m.getInterval();

        // 1. try to keep the same room; 2. else book a new room first, then free the old one
        MeetingRoom newRoom;
        if (oldRoom.tryMove(oldInterval, newInterval)) {
            newRoom = oldRoom;
        } else {
            newRoom = bookRoom(newInterval, m.headCount());
            oldRoom.release(oldInterval);
        }

        m.reschedule(newRoom, newInterval);
        // declined invitees are invited again for the new time
        m.getInvitees().forEach(u -> {
            u.getCalendar().add(m);
            notify(u, "Rescheduled: '" + m.getTitle() + "' now " + newInterval + " in " + newRoom.getName());
        });
        return m;
    }

    // walk the strategy's ranking until one tryBook succeeds (another thread may grab a room first)
    private MeetingRoom bookRoom(Interval interval, int headCount) {
        List<MeetingRoom> ranked = roomStrategy.rank(roomService.findAvailableRooms(interval, headCount), headCount);
        for (MeetingRoom r : ranked) {
            if (r.tryBook(interval)) return r;
        }
        throw new IllegalStateException("No room for " + headCount + " people at " + interval);
    }

    private Meeting getScheduled(String meetingId) {
        Meeting m = meetings.get(meetingId);
        if (m == null || m.getStatus() != MeetingStatus.SCHEDULED)
            throw new IllegalStateException("No scheduled meeting " + meetingId);
        return m;
    }

    private void requireOrganizer(Meeting m, User requester) {
        if (!m.getOrganizer().getId().equals(requester.getId()))
            throw new IllegalStateException("Only the organizer can change '" + m.getTitle() + "'");
    }

    public void setRoomStrategy(RoomSelectionStrategy s) { this.roomStrategy = s; }
    public void addObserver(NotificationObserver o)      { observers.add(o); }
    public Meeting getMeeting(String id)                 { return meetings.get(id); }

    private void notify(User recipient, String msg) { observers.forEach(o -> o.notify(recipient, msg)); }
}

// ===================== Facade + Singleton =====================

public class MeetingSchedulerSystem {
    private static volatile MeetingSchedulerSystem instance;
    private final RoomService roomService = new RoomService();
    private final CalendarService calendarService = new CalendarService();
    private final MeetingService meetingService = new MeetingService(roomService);

    private MeetingSchedulerSystem() {}

    public static MeetingSchedulerSystem getInstance() {
        if (instance == null) {
            synchronized (MeetingSchedulerSystem.class) {
                if (instance == null) instance = new MeetingSchedulerSystem();
            }
        }
        return instance;
    }

    public void addRoom(MeetingRoom room) { roomService.addRoom(room); }

    public List<MeetingRoom> findAvailableRooms(Interval i, int headCount) { return roomService.findAvailableRooms(i, headCount); }
    public List<User> getBusyUsers(List<User> users, Interval i)          { return calendarService.getBusyUsers(users, i); }

    public Optional<Interval> findCommonFreeSlot(List<User> users, LocalDate day, Duration length) {
        return calendarService.findCommonFreeSlot(users, day, length, LocalTime.of(9, 0), LocalTime.of(18, 0));
    }

    public Meeting schedule(User organizer, String title, List<User> invitees, Interval i) {
        return meetingService.scheduleMeeting(organizer, title, invitees, i);
    }

    public void respond(String meetingId, User user, boolean accept) { meetingService.respond(meetingId, user, accept); }
    public void cancel(String meetingId, User requester)             { meetingService.cancelMeeting(meetingId, requester); }

    public Meeting reschedule(String meetingId, User requester, Interval i) {
        return meetingService.rescheduleMeeting(meetingId, requester, i);
    }

    public MeetingService getMeetingService() { return meetingService; }

    // ===================== Demo =====================

    private static Interval at(LocalDate day, int h1, int m1, int h2, int m2) {
        return new Interval(day.atTime(h1, m1), day.atTime(h2, m2));
    }

    public static void main(String[] args) throws InterruptedException {
        MeetingSchedulerSystem system = MeetingSchedulerSystem.getInstance();
        system.getMeetingService().addObserver(new EmailNotifier());

        MeetingRoom focus  = new MeetingRoom("Focus", 2, 1);
        MeetingRoom huddle = new MeetingRoom("Huddle", 4, 1);
        MeetingRoom board  = new MeetingRoom("Board", 10, 2);
        system.addRoom(focus);
        system.addRoom(huddle);
        system.addRoom(board);

        User alice = new User("Alice", "alice@corp.com");
        User bob   = new User("Bob",   "bob@corp.com");
        User carol = new User("Carol", "carol@corp.com");
        User dave  = new User("Dave",  "dave@corp.com");
        User eve   = new User("Eve",   "eve@corp.com");

        LocalDate day = LocalDate.of(2026, 11, 16);

        // 1. Schedule -> best-fit room
        System.out.println("1. Alice schedules Sprint Planning 10:00-11:00 with Bob, Carol (3 people)");
        Meeting sprint = system.schedule(alice, "Sprint Planning", List.of(bob, carol), at(day, 10, 0, 11, 0));
        System.out.println("  " + sprint);

        // 2. Responses
        System.out.println("\n2. Bob accepts, Carol declines");
        system.respond(sprint.getId(), bob, true);
        system.respond(sprint.getId(), carol, false);
        System.out.println("  " + sprint);

        // 3. Busy check + room fallback
        Interval review = at(day, 10, 30, 11, 30);
        System.out.println("\n3. Dave checks who is busy " + review + ": "
                + system.getBusyUsers(List.of(bob, carol, eve), review) + " (Carol declined, so she is free)");
        System.out.println("  Dave schedules Design Review with Carol, Eve (3 people), Huddle is taken");
        Meeting design = system.schedule(dave, "Design Review", List.of(carol, eve), review);
        System.out.println("  " + design);

        // 4. Common free slot
        System.out.println("\n4. Common free slots for Alice, Bob, Dave, Eve (9:00-18:00)");
        Meeting standup = system.schedule(eve, "Standup", List.of(), at(day, 9, 0, 9, 30));
        System.out.println("  Busy: Eve 09:00-09:30, Alice+Bob 10:00-11:00, Dave+Eve 10:30-11:30");
        System.out.println("  30 min -> " + system.findCommonFreeSlot(List.of(alice, bob, dave, eve), day, Duration.ofMinutes(30)).orElse(null));
        System.out.println("  60 min -> " + system.findCommonFreeSlot(List.of(alice, bob, dave, eve), day, Duration.ofMinutes(60)).orElse(null));

        // 5. No room big enough / free
        System.out.println("\n5. Someone needs a room for 12 people");
        List<User> crowd = new ArrayList<>();
        for (int i = 0; i < 11; i++) crowd.add(new User("U" + i, "u" + i + "@corp.com"));
        try {
            system.schedule(alice, "All Hands", crowd, at(day, 15, 0, 16, 0));
        } catch (IllegalStateException e) {
            System.out.println("  Rejected: " + e.getMessage());
        }

        // 6. Reschedule (same room kept if free)
        System.out.println("\n6. Alice reschedules Sprint Planning to 14:00-15:00");
        system.reschedule(sprint.getId(), alice, at(day, 14, 0, 15, 0));
        System.out.println("  " + sprint);
        System.out.println("  Huddle free at 10:00-11:00 now? " + huddle.isAvailable(at(day, 10, 0, 11, 0)));

        // 7. Cancel (organizer only)
        System.out.println("\n7. Carol tries to cancel Design Review, then Dave cancels it");
        try {
            system.cancel(design.getId(), carol);
        } catch (IllegalStateException e) {
            System.out.println("  Rejected: " + e.getMessage());
        }
        system.cancel(design.getId(), dave);
        System.out.println("  " + design);
        System.out.println("  Board free at 10:30-11:30 again? " + board.isAvailable(review));

        // 8. Strategy switch
        System.out.println("\n8. Switch to floor-2 preference, Eve schedules a 3-person sync at 16:00");
        system.getMeetingService().setRoomStrategy(new FloorPreferenceStrategy(2));
        Meeting sync = system.schedule(eve, "Sync", List.of(alice, bob), at(day, 16, 0, 16, 30));
        System.out.println("  " + sync);
        system.getMeetingService().setRoomStrategy(new BestFitStrategy());

        // 9. Concurrency: 10 organizers race for 17:00, only 3 rooms -> exactly 3 winners
        System.out.println("\n9. 10 concurrent 2-person meetings at 17:00 (3 rooms exist)");
        List<Thread> threads = new ArrayList<>();
        List<String> winners = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 10; i++) {
            User org = new User("O" + i, "o" + i + "@corp.com");
            User guest = new User("G" + i, "g" + i + "@corp.com");
            Thread t = new Thread(() -> {
                try {
                    Meeting m = system.schedule(org, "Chat", List.of(guest), at(day, 17, 0, 17, 30));
                    winners.add(org.getName() + "->" + m.getRoom().getName());
                } catch (IllegalStateException ignored) { }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join();
        System.out.println("  Winners: " + winners + " (expected exactly 3, one per room)");
    }
}
