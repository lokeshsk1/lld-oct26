import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// ===================== Enums =====================

enum RoomType { SINGLE, DOUBLE, DELUXE, SUITE }

enum BookingStatus { PENDING, CONFIRMED, CANCELLED, COMPLETED }

enum PaymentStatus { PENDING, SUCCESS, FAILED, REFUNDED }

// ===================== Users =====================

abstract class User {
    private final String id = UUID.randomUUID().toString();
    private final String name;
    private final String email;
    private final String phone;

    User(String name, String email, String phone) {
        this.name = name;
        this.email = email;
        this.phone = phone;
    }

    public String getId()    { return id; }
    public String getName()  { return name; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
}

class Guest extends User {
    private final List<Booking> bookings = new ArrayList<>();

    Guest(String name, String email, String phone) { super(name, email, phone); }

    public void addBooking(Booking b)     { bookings.add(b); }
    public List<Booking> getBookings()    { return Collections.unmodifiableList(bookings); }
}

class Admin extends User {
    Admin(String name, String email, String phone) { super(name, email, phone); }

    public void addRoom(Hotel hotel, Room room) { hotel.addRoom(room); }
}

// ===================== DateRange =====================

class DateRange {
    private final LocalDate checkIn;
    private final LocalDate checkOut;   // exclusive

    DateRange(LocalDate checkIn, LocalDate checkOut) {
        if (!checkOut.isAfter(checkIn)) throw new IllegalArgumentException("Check-out must be after check-in");
        this.checkIn = checkIn;
        this.checkOut = checkOut;
    }

    // [a, b) overlaps [c, d)  <=>  a < d && c < b
    public boolean overlaps(DateRange o) {
        return checkIn.isBefore(o.checkOut) && o.checkIn.isBefore(checkOut);
    }

    public long nights()          { return ChronoUnit.DAYS.between(checkIn, checkOut); }
    public LocalDate getCheckIn() { return checkIn; }
    public LocalDate getCheckOut(){ return checkOut; }

    @Override public String toString() { return checkIn + " -> " + checkOut; }
}

// ===================== Hotel & Room =====================

class Room {
    private final String id = UUID.randomUUID().toString();
    private final String roomNumber;
    private final RoomType type;
    private final double basePrice;
    private final int capacity;
    private final List<DateRange> bookedRanges = new ArrayList<>();

    Room(String roomNumber, RoomType type, double basePrice, int capacity) {
        this.roomNumber = roomNumber;
        this.type = type;
        this.basePrice = basePrice;
        this.capacity = capacity;
    }

    public synchronized boolean isAvailable(DateRange r) {
        return bookedRanges.stream().noneMatch(b -> b.overlaps(r));
    }

    // check + book atomically -> prevents double booking
    public synchronized boolean tryBook(DateRange r) {
        if (!isAvailable(r)) return false;
        bookedRanges.add(r);
        return true;
    }

    public synchronized void release(DateRange r) { bookedRanges.remove(r); }

    public String getId()         { return id; }
    public String getRoomNumber() { return roomNumber; }
    public RoomType getType()     { return type; }
    public double getBasePrice()  { return basePrice; }
    public int getCapacity()      { return capacity; }

    @Override public String toString() { return "Room " + roomNumber + " (" + type + ", " + basePrice + "/night)"; }
}

class Hotel {
    private final String id = UUID.randomUUID().toString();
    private final String name;
    private final String city;
    private final String address;
    private final List<Room> rooms = new ArrayList<>();

    Hotel(String name, String city, String address) {
        this.name = name;
        this.city = city;
        this.address = address;
    }

    public void addRoom(Room room) { rooms.add(room); }

    public List<Room> getAvailableRooms(DateRange range, RoomType type) {
        return rooms.stream()
                .filter(r -> type == null || r.getType() == type)
                .filter(r -> r.isAvailable(range))
                .collect(Collectors.toList());
    }

    public String getId()      { return id; }
    public String getName()    { return name; }
    public String getCity()    { return city; }
    public String getAddress() { return address; }
    public List<Room> getRooms() { return Collections.unmodifiableList(rooms); }

    @Override public String toString() { return name + " [" + city + "]"; }
}

// ===================== Payment (Strategy) =====================

interface PaymentStrategy {
    boolean pay(double amount);
    boolean refund(double amount);
}

class CardPayment implements PaymentStrategy {
    public boolean pay(double amount)    { System.out.println("  Paid " + amount + " via Card");   return true; }
    public boolean refund(double amount) { System.out.println("  Refunded " + amount + " to Card"); return true; }
}

class UpiPayment implements PaymentStrategy {
    public boolean pay(double amount)    { System.out.println("  Paid " + amount + " via UPI");    return true; }
    public boolean refund(double amount) { System.out.println("  Refunded " + amount + " to UPI");  return true; }
}

class WalletPayment implements PaymentStrategy {
    private double balance;

    WalletPayment(double balance) { this.balance = balance; }

    public boolean pay(double amount) {
        if (amount > balance) { System.out.println("  Wallet: insufficient balance"); return false; }
        balance -= amount;
        System.out.println("  Paid " + amount + " via Wallet");
        return true;
    }

    public boolean refund(double amount) {
        balance += amount;
        System.out.println("  Refunded " + amount + " to Wallet");
        return true;
    }
}

class Payment {
    private final String id = UUID.randomUUID().toString();
    private final double amount;
    private final PaymentStrategy strategy;
    private PaymentStatus status = PaymentStatus.PENDING;

    Payment(double amount, PaymentStrategy strategy) {
        this.amount = amount;
        this.strategy = strategy;
    }

    public boolean process() {
        status = strategy.pay(amount) ? PaymentStatus.SUCCESS : PaymentStatus.FAILED;
        return status == PaymentStatus.SUCCESS;
    }

    public boolean refund(double refundAmount) {
        if (status != PaymentStatus.SUCCESS) return false;
        if (strategy.refund(refundAmount)) {
            status = PaymentStatus.REFUNDED;
            return true;
        }
        return false;
    }

    public String getId()            { return id; }
    public double getAmount()        { return amount; }
    public PaymentStatus getStatus() { return status; }
}

class PaymentService {
    public Payment makePayment(double amount, PaymentStrategy ps) {
        Payment payment = new Payment(amount, ps);
        payment.process();
        return payment;
    }

    public boolean refund(Payment payment, double amount) { return payment.refund(amount); }
}

// ===================== Pricing (Strategy) =====================

interface PricingStrategy {
    double calculate(Room room, DateRange range);
}

class NormalPricing implements PricingStrategy {
    public double calculate(Room room, DateRange range) {
        return room.getBasePrice() * range.nights();
    }
}

// Fri & Sat nights cost 1.5x
class WeekendPricing implements PricingStrategy {
    public double calculate(Room room, DateRange range) {
        double total = 0;
        for (LocalDate d = range.getCheckIn(); d.isBefore(range.getCheckOut()); d = d.plusDays(1)) {
            boolean weekend = d.getDayOfWeek() == DayOfWeek.FRIDAY || d.getDayOfWeek() == DayOfWeek.SATURDAY;
            total += room.getBasePrice() * (weekend ? 1.5 : 1.0);
        }
        return total;
    }
}

// December nights cost 2x
class SeasonalPricing implements PricingStrategy {
    public double calculate(Room room, DateRange range) {
        double total = 0;
        for (LocalDate d = range.getCheckIn(); d.isBefore(range.getCheckOut()); d = d.plusDays(1)) {
            total += room.getBasePrice() * (d.getMonth() == Month.DECEMBER ? 2.0 : 1.0);
        }
        return total;
    }
}

// ===================== Notification (Observer) =====================

interface NotificationObserver {
    void notify(Booking booking, String message);
}

class EmailNotifier implements NotificationObserver {
    public void notify(Booking b, String msg) {
        System.out.println("  [EMAIL to " + b.getGuest().getEmail() + "] " + msg);
    }
}

class SmsNotifier implements NotificationObserver {
    public void notify(Booking b, String msg) {
        System.out.println("  [SMS to " + b.getGuest().getPhone() + "] " + msg);
    }
}

// ===================== Booking =====================

class Booking {
    private final String id = UUID.randomUUID().toString().substring(0, 8);
    private final Guest guest;
    private final Hotel hotel;
    private final List<Room> rooms;
    private final DateRange dateRange;
    private final double totalAmount;
    private BookingStatus status = BookingStatus.PENDING;
    private Payment payment;

    Booking(Guest guest, Hotel hotel, List<Room> rooms, DateRange dateRange, double totalAmount) {
        this.guest = guest;
        this.hotel = hotel;
        this.rooms = new ArrayList<>(rooms);
        this.dateRange = dateRange;
        this.totalAmount = totalAmount;
    }

    // Allowed transitions: PENDING -> CONFIRMED -> COMPLETED, PENDING/CONFIRMED -> CANCELLED
    public void confirm() {
        if (status != BookingStatus.PENDING) throw new IllegalStateException("Only PENDING can be confirmed");
        status = BookingStatus.CONFIRMED;
    }

    public void cancel() {
        if (status != BookingStatus.PENDING && status != BookingStatus.CONFIRMED)
            throw new IllegalStateException("Cannot cancel a " + status + " booking");
        status = BookingStatus.CANCELLED;
    }

    public void complete() {
        if (status != BookingStatus.CONFIRMED) throw new IllegalStateException("Only CONFIRMED can be completed");
        status = BookingStatus.COMPLETED;
    }

    public void setPayment(Payment payment) { this.payment = payment; }

    public String getId()            { return id; }
    public Guest getGuest()          { return guest; }
    public Hotel getHotel()          { return hotel; }
    public List<Room> getRooms()     { return rooms; }
    public DateRange getDateRange()  { return dateRange; }
    public double getTotalAmount()   { return totalAmount; }
    public BookingStatus getStatus() { return status; }
    public Payment getPayment()      { return payment; }

    @Override public String toString() {
        return "Booking#" + id + " " + hotel.getName() + " " + rooms + " " + dateRange
                + " amount=" + totalAmount + " status=" + status;
    }
}

// ===================== Services =====================

class SearchService {
    private final List<Hotel> hotels = new ArrayList<>();

    public void addHotel(Hotel hotel) { hotels.add(hotel); }

    // hotels in the city that have enough free capacity for the guests
    public List<Hotel> search(String city, DateRange range, int guests) {
        return hotels.stream()
                .filter(h -> h.getCity().equalsIgnoreCase(city))
                .filter(h -> h.getAvailableRooms(range, null).stream()
                        .mapToInt(Room::getCapacity).sum() >= guests)
                .collect(Collectors.toList());
    }
}

class BookingService {
    private final Map<String, Booking> bookings = new ConcurrentHashMap<>();
    private final List<NotificationObserver> observers = new ArrayList<>();
    private final PaymentService paymentService = new PaymentService();
    private PricingStrategy pricingStrategy = new NormalPricing();

    public Booking createBooking(Guest guest, Hotel hotel, List<Room> rooms,
                                 DateRange range, PaymentStrategy ps) {
        // 1. Lock all rooms atomically; roll back if any one fails
        List<Room> locked = new ArrayList<>();
        for (Room room : rooms) {
            if (!room.tryBook(range)) {
                locked.forEach(r -> r.release(range));
                throw new IllegalStateException(room + " not available for " + range);
            }
            locked.add(room);
        }

        // 2. Price
        double total = rooms.stream().mapToDouble(r -> pricingStrategy.calculate(r, range)).sum();
        Booking booking = new Booking(guest, hotel, rooms, range, total);

        // 3. Pay
        Payment payment = paymentService.makePayment(total, ps);
        booking.setPayment(payment);
        if (payment.getStatus() != PaymentStatus.SUCCESS) {
            rooms.forEach(r -> r.release(range));
            booking.cancel();
            throw new IllegalStateException("Payment failed, rooms released");
        }

        // 4. Confirm + notify
        booking.confirm();
        bookings.put(booking.getId(), booking);
        guest.addBooking(booking);
        notifyAll(booking, "Booking confirmed: " + booking.getId());
        return booking;
    }

    public void cancelBooking(String bookingId) {
        Booking b = bookings.get(bookingId);
        if (b == null || b.getStatus() != BookingStatus.CONFIRMED)
            throw new IllegalStateException("Cannot cancel booking " + bookingId);
        b.getRooms().forEach(r -> r.release(b.getDateRange()));
        paymentService.refund(b.getPayment(), b.getTotalAmount());
        b.cancel();
        notifyAll(b, "Booking cancelled: " + bookingId);
    }

    public void setPricingStrategy(PricingStrategy p) { this.pricingStrategy = p; }
    public void addObserver(NotificationObserver o)    { observers.add(o); }
    public Booking getBooking(String id)               { return bookings.get(id); }

    private void notifyAll(Booking b, String msg) { observers.forEach(o -> o.notify(b, msg)); }
}

// ===================== Facade + Singleton =====================

public class HotelBookingSystem {
    private static volatile HotelBookingSystem instance;
    private final SearchService searchService = new SearchService();
    private final BookingService bookingService = new BookingService();

    private HotelBookingSystem() {}

    public static HotelBookingSystem getInstance() {
        if (instance == null) {
            synchronized (HotelBookingSystem.class) {
                if (instance == null) instance = new HotelBookingSystem();
            }
        }
        return instance;
    }

    public void addHotel(Hotel hotel) { searchService.addHotel(hotel); }

    public List<Hotel> searchHotels(String city, DateRange range, int guests) {
        return searchService.search(city, range, guests);
    }

    public Booking bookRooms(Guest g, Hotel h, List<Room> rooms, DateRange range, PaymentStrategy ps) {
        return bookingService.createBooking(g, h, rooms, range, ps);
    }

    public void cancel(String bookingId) { bookingService.cancelBooking(bookingId); }

    public SearchService getSearchService()   { return searchService; }
    public BookingService getBookingService() { return bookingService; }

    // ===================== Demo =====================

    public static void main(String[] args) throws InterruptedException {
        HotelBookingSystem system = HotelBookingSystem.getInstance();
        system.getBookingService().addObserver(new EmailNotifier());
        system.getBookingService().addObserver(new SmsNotifier());

        // Admin sets up hotels
        Admin admin = new Admin("Admin", "admin@hotel.com", "9000000000");
        Hotel taj = new Hotel("Taj", "Chennai", "Marina Beach Rd");
        Room r101 = new Room("101", RoomType.SINGLE, 2000, 1);
        Room r102 = new Room("102", RoomType.DOUBLE, 3500, 2);
        Room r201 = new Room("201", RoomType.SUITE, 8000, 4);
        system.addHotel(taj);
        admin.addRoom(taj, r101);
        admin.addRoom(taj, r102);
        admin.addRoom(taj, r201);

        Guest alice = new Guest("Alice", "alice@mail.com", "9111111111");
        Guest bob   = new Guest("Bob",   "bob@mail.com",   "9222222222");

        DateRange stay = new DateRange(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 13));

        // 1. Search
        System.out.println("1. Search Chennai for 2 guests " + stay);
        for (Hotel h : system.searchHotels("Chennai", stay, 2)) {
            System.out.println("  " + h + " -> " + h.getAvailableRooms(stay, null));
        }

        // 2. Book
        System.out.println("\n2. Alice books room 102");
        Booking b1 = system.bookRooms(alice, taj, List.of(r102), stay, new UpiPayment());
        System.out.println("  " + b1);

        // 3. Double booking attempt
        System.out.println("\n3. Bob tries room 102 for overlapping dates");
        try {
            system.bookRooms(bob, taj, List.of(r102),
                    new DateRange(LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 14)), new CardPayment());
        } catch (IllegalStateException e) {
            System.out.println("  Rejected: " + e.getMessage());
        }

        // 4. Back-to-back booking is fine (check-out day == check-in day)
        System.out.println("\n4. Bob books room 102 starting on Alice's check-out day");
        Booking b2 = system.bookRooms(bob, taj, List.of(r102),
                new DateRange(LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 15)), new CardPayment());
        System.out.println("  " + b2);

        // 5. Payment failure releases rooms
        System.out.println("\n5. Bob tries suite with low wallet balance");
        try {
            system.bookRooms(bob, taj, List.of(r201), stay, new WalletPayment(1000));
        } catch (IllegalStateException e) {
            System.out.println("  Rejected: " + e.getMessage());
        }
        System.out.println("  Suite still available? " + r201.isAvailable(stay));

        // 6. Weekend pricing
        System.out.println("\n6. Switch to weekend pricing, Alice books room 101 (Fri-Sun)");
        system.getBookingService().setPricingStrategy(new WeekendPricing());
        Booking b3 = system.bookRooms(alice, taj, List.of(r101),
                new DateRange(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 22)), new UpiPayment());
        System.out.println("  " + b3);

        // 7. Cancel
        System.out.println("\n7. Alice cancels " + b1.getId());
        system.cancel(b1.getId());
        System.out.println("  " + b1);
        System.out.println("  Room 102 available again? " + r102.isAvailable(stay));

        // 8. Concurrency: 10 threads race for the same room, only 1 must win
        System.out.println("\n8. 10 concurrent guests race for room 201");
        system.getBookingService().setPricingStrategy(new NormalPricing());
        DateRange xmas = new DateRange(LocalDate.of(2026, 12, 24), LocalDate.of(2026, 12, 26));
        List<Thread> threads = new ArrayList<>();
        List<String> winners = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 10; i++) {
            Guest g = new Guest("G" + i, "g" + i + "@mail.com", "90000000" + i);
            Thread t = new Thread(() -> {
                try {
                    system.bookRooms(g, taj, List.of(r201), xmas, new CardPayment());
                    winners.add(g.getName());
                } catch (IllegalStateException ignored) { }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join();
        System.out.println("  Winners: " + winners + " (expected exactly 1)");
    }
}
