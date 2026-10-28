import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// ===================== Enums =====================

enum SeatType { SILVER, GOLD, PLATINUM }

enum SeatStatus { AVAILABLE, LOCKED, BOOKED }

enum BookingStatus { PENDING, CONFIRMED, CANCELLED, EXPIRED }

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

class Customer extends User {
    private final List<Booking> bookings = new ArrayList<>();

    Customer(String name, String email, String phone) { super(name, email, phone); }

    public void addBooking(Booking b)  { bookings.add(b); }
    public List<Booking> getBookings() { return Collections.unmodifiableList(bookings); }
}

class Admin extends User {
    Admin(String name, String email, String phone) { super(name, email, phone); }
}

// ===================== Movie, Cinema, Screen, Seat =====================

class Movie {
    private final String id = UUID.randomUUID().toString();
    private final String title;
    private final String language;
    private final String genre;
    private final int durationMins;

    Movie(String title, String language, String genre, int durationMins) {
        this.title = title;
        this.language = language;
        this.genre = genre;
        this.durationMins = durationMins;
    }

    public String getId()        { return id; }
    public String getTitle()     { return title; }
    public String getLanguage()  { return language; }
    public String getGenre()     { return genre; }
    public int getDurationMins() { return durationMins; }

    @Override public String toString() { return title + " (" + language + ", " + durationMins + " min)"; }
}

class Seat {
    private final String id;      // e.g. "B3"
    private final String row;
    private final int number;
    private final SeatType type;

    Seat(String row, int number, SeatType type) {
        this.id = row + number;
        this.row = row;
        this.number = number;
        this.type = type;
    }

    public String getId()     { return id; }
    public String getRow()    { return row; }
    public int getNumber()    { return number; }
    public SeatType getType() { return type; }

    @Override public String toString() { return id + "(" + type + ")"; }
}

class Screen {
    private final String id = UUID.randomUUID().toString();
    private final String name;
    private final List<Seat> seats = new ArrayList<>();

    Screen(String name) { this.name = name; }

    public void addSeat(Seat seat) { seats.add(seat); }

    // helper: add a whole row of seats
    public void addRow(String row, int count, SeatType type) {
        for (int i = 1; i <= count; i++) seats.add(new Seat(row, i, type));
    }

    public String getId()        { return id; }
    public String getName()      { return name; }
    public List<Seat> getSeats() { return Collections.unmodifiableList(seats); }
}

class Cinema {
    private final String id = UUID.randomUUID().toString();
    private final String name;
    private final String city;
    private final List<Screen> screens = new ArrayList<>();

    Cinema(String name, String city) {
        this.name = name;
        this.city = city;
    }

    public void addScreen(Screen screen) { screens.add(screen); }

    public String getId()            { return id; }
    public String getName()          { return name; }
    public String getCity()          { return city; }
    public List<Screen> getScreens() { return Collections.unmodifiableList(screens); }

    @Override public String toString() { return name + " [" + city + "]"; }
}

// ===================== Show & ShowSeat =====================

// Seat state for one particular show
class ShowSeat {
    private final Seat seat;
    private SeatStatus status = SeatStatus.AVAILABLE;
    private String lockedBy;          // userId holding the lock
    private Instant lockExpiresAt;

    ShowSeat(Seat seat) { this.seat = seat; }

    // free = available, or locked but the lock has expired
    public boolean isFree() {
        return status == SeatStatus.AVAILABLE
                || (status == SeatStatus.LOCKED && Instant.now().isAfter(lockExpiresAt));
    }

    public boolean isLockedBy(String userId) {
        return status == SeatStatus.LOCKED && userId.equals(lockedBy) && !Instant.now().isAfter(lockExpiresAt);
    }

    public void lock(String userId, Instant expiresAt) {
        status = SeatStatus.LOCKED;
        lockedBy = userId;
        lockExpiresAt = expiresAt;
    }

    public void book() {
        status = SeatStatus.BOOKED;
        lockExpiresAt = null;
    }

    public void release() {
        status = SeatStatus.AVAILABLE;
        lockedBy = null;
        lockExpiresAt = null;
    }

    public Seat getSeat()          { return seat; }
    public SeatStatus getStatus()  { return status; }
    public String getLockedBy()    { return lockedBy; }
}

class Show {
    private final String id = UUID.randomUUID().toString().substring(0, 8);
    private final Movie movie;
    private final Cinema cinema;
    private final Screen screen;
    private final LocalDateTime startTime;
    private final Map<SeatType, Double> prices;
    private final Map<String, ShowSeat> showSeats = new LinkedHashMap<>();

    Show(Movie movie, Cinema cinema, Screen screen, LocalDateTime startTime, Map<SeatType, Double> prices) {
        this.movie = movie;
        this.cinema = cinema;
        this.screen = screen;
        this.startTime = startTime;
        this.prices = new EnumMap<>(prices);
        for (Seat s : screen.getSeats()) showSeats.put(s.getId(), new ShowSeat(s));
    }

    public ShowSeat getShowSeat(String seatId) {
        ShowSeat ss = showSeats.get(seatId);
        if (ss == null) throw new IllegalArgumentException("No seat " + seatId + " in this show");
        return ss;
    }

    public synchronized List<Seat> getAvailableSeats() {
        return showSeats.values().stream()
                .filter(ShowSeat::isFree)
                .map(ShowSeat::getSeat)
                .collect(Collectors.toList());
    }

    public double getPrice(SeatType type) { return prices.get(type); }

    // [ ] free   [L] locked   [X] booked
    public synchronized String seatMap() {
        StringBuilder sb = new StringBuilder();
        String currentRow = null;
        for (ShowSeat ss : showSeats.values()) {
            if (!ss.getSeat().getRow().equals(currentRow)) {
                currentRow = ss.getSeat().getRow();
                sb.append("\n    ").append(String.format("%-9s", ss.getSeat().getType()));
            }
            String mark = ss.isFree() ? " " : ss.getStatus() == SeatStatus.BOOKED ? "X" : "L";
            sb.append(ss.getSeat().getId()).append("[").append(mark).append("] ");
        }
        return sb.toString();
    }

    public String getId()              { return id; }
    public Movie getMovie()            { return movie; }
    public Cinema getCinema()          { return cinema; }
    public Screen getScreen()          { return screen; }
    public LocalDateTime getStartTime(){ return startTime; }

    @Override public String toString() {
        return "Show#" + id + " " + movie.getTitle() + " @ " + cinema.getName() + "/" + screen.getName() + " " + startTime;
    }
}

// ===================== Seat locking =====================

// Temporarily holds seats while the customer pays. All checks are synchronized per show,
// so two customers can never lock/book the same seat of the same show.
class SeatLockService {
    private Duration lockTimeout = Duration.ofMinutes(5);

    // all-or-nothing: either every seat is locked, or none
    public boolean lockSeats(Show show, List<String> seatIds, String userId) {
        synchronized (show) {
            for (String id : seatIds) {
                if (!show.getShowSeat(id).isFree()) return false;
            }
            Instant expiresAt = Instant.now().plus(lockTimeout);
            for (String id : seatIds) show.getShowSeat(id).lock(userId, expiresAt);
            return true;
        }
    }

    public boolean isLockedBy(Show show, List<String> seatIds, String userId) {
        synchronized (show) {
            return seatIds.stream().allMatch(id -> show.getShowSeat(id).isLockedBy(userId));
        }
    }

    // only releases seats this user still holds (never someone else's new lock)
    public void unlockSeats(Show show, List<String> seatIds, String userId) {
        synchronized (show) {
            for (String id : seatIds) {
                ShowSeat ss = show.getShowSeat(id);
                if (ss.getStatus() == SeatStatus.LOCKED && userId.equals(ss.getLockedBy())) ss.release();
            }
        }
    }

    public void bookSeats(Show show, List<String> seatIds) {
        synchronized (show) {
            seatIds.forEach(id -> show.getShowSeat(id).book());
        }
    }

    public void releaseBookedSeats(Show show, List<String> seatIds) {
        synchronized (show) {
            seatIds.forEach(id -> show.getShowSeat(id).release());
        }
    }

    public void setLockTimeout(Duration timeout) { this.lockTimeout = timeout; }
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
    double calculate(Show show, Seat seat);
}

class NormalPricing implements PricingStrategy {
    public double calculate(Show show, Seat seat) {
        return show.getPrice(seat.getType());
    }
}

// Sat & Sun shows cost 1.25x
class WeekendPricing implements PricingStrategy {
    public double calculate(Show show, Seat seat) {
        DayOfWeek day = show.getStartTime().getDayOfWeek();
        boolean weekend = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
        return show.getPrice(seat.getType()) * (weekend ? 1.25 : 1.0);
    }
}

// Shows starting 6 PM - 10 PM cost 1.2x
class PrimeTimePricing implements PricingStrategy {
    public double calculate(Show show, Seat seat) {
        int hour = show.getStartTime().getHour();
        return show.getPrice(seat.getType()) * (hour >= 18 && hour < 22 ? 1.2 : 1.0);
    }
}

// ===================== Notification (Observer) =====================

interface NotificationObserver {
    void notify(Booking booking, String message);
}

class EmailNotifier implements NotificationObserver {
    public void notify(Booking b, String msg) {
        System.out.println("  [EMAIL to " + b.getCustomer().getEmail() + "] " + msg);
    }
}

class SmsNotifier implements NotificationObserver {
    public void notify(Booking b, String msg) {
        System.out.println("  [SMS to " + b.getCustomer().getPhone() + "] " + msg);
    }
}

// ===================== Booking =====================

class Booking {
    private final String id = UUID.randomUUID().toString().substring(0, 8);
    private final Customer customer;
    private final Show show;
    private final List<String> seatIds;
    private final double totalAmount;
    private BookingStatus status = BookingStatus.PENDING;
    private Payment payment;

    Booking(Customer customer, Show show, List<String> seatIds, double totalAmount) {
        this.customer = customer;
        this.show = show;
        this.seatIds = new ArrayList<>(seatIds);
        this.totalAmount = totalAmount;
    }

    // Allowed: PENDING -> CONFIRMED | CANCELLED | EXPIRED,  CONFIRMED -> CANCELLED
    public void confirm() {
        if (status != BookingStatus.PENDING) throw new IllegalStateException("Only PENDING can be confirmed");
        status = BookingStatus.CONFIRMED;
    }

    public void cancel() {
        if (status != BookingStatus.PENDING && status != BookingStatus.CONFIRMED)
            throw new IllegalStateException("Cannot cancel a " + status + " booking");
        status = BookingStatus.CANCELLED;
    }

    public void expire() {
        if (status != BookingStatus.PENDING) throw new IllegalStateException("Only PENDING can expire");
        status = BookingStatus.EXPIRED;
    }

    public void setPayment(Payment payment) { this.payment = payment; }

    public String getId()            { return id; }
    public Customer getCustomer()    { return customer; }
    public Show getShow()            { return show; }
    public List<String> getSeatIds() { return seatIds; }
    public double getTotalAmount()   { return totalAmount; }
    public BookingStatus getStatus() { return status; }
    public Payment getPayment()      { return payment; }

    @Override public String toString() {
        return "Booking#" + id + " " + show.getMovie().getTitle() + " " + seatIds
                + " amount=" + totalAmount + " status=" + status;
    }
}

// ===================== Services =====================

class SearchService {
    private final List<Cinema> cinemas = new ArrayList<>();
    private final List<Show> shows = new ArrayList<>();

    public void addCinema(Cinema cinema) { cinemas.add(cinema); }
    public void addShow(Show show)       { shows.add(show); }

    public List<Movie> searchMovies(String city) {
        return shows.stream()
                .filter(s -> s.getCinema().getCity().equalsIgnoreCase(city))
                .map(Show::getMovie)
                .distinct()
                .collect(Collectors.toList());
    }

    public List<Show> getShows(String city, Movie movie) {
        return shows.stream()
                .filter(s -> s.getCinema().getCity().equalsIgnoreCase(city))
                .filter(s -> s.getMovie().equals(movie))
                .sorted(Comparator.comparing(Show::getStartTime))
                .collect(Collectors.toList());
    }
}

class BookingService {
    private final Map<String, Booking> bookings = new ConcurrentHashMap<>();
    private final List<NotificationObserver> observers = new ArrayList<>();
    private final PaymentService paymentService = new PaymentService();
    private final SeatLockService seatLockService;
    private PricingStrategy pricingStrategy = new NormalPricing();

    BookingService(SeatLockService seatLockService) { this.seatLockService = seatLockService; }

    // Step 1: customer selects seats -> seats LOCKED, booking PENDING
    public Booking createBooking(Customer customer, Show show, List<String> seatIds) {
        if (!seatLockService.lockSeats(show, seatIds, customer.getId()))
            throw new IllegalStateException("Seats " + seatIds + " not available");

        double total = seatIds.stream()
                .mapToDouble(id -> pricingStrategy.calculate(show, show.getShowSeat(id).getSeat()))
                .sum();
        Booking booking = new Booking(customer, show, seatIds, total);
        bookings.put(booking.getId(), booking);
        return booking;
    }

    // Step 2: customer pays -> seats BOOKED, booking CONFIRMED
    public Booking confirmBooking(String bookingId, PaymentStrategy ps) {
        Booking b = getPendingBooking(bookingId);
        Show show = b.getShow();
        String userId = b.getCustomer().getId();

        if (!seatLockService.isLockedBy(show, b.getSeatIds(), userId)) {
            b.expire();
            throw new IllegalStateException("Seat lock expired, please select seats again");
        }

        Payment payment = paymentService.makePayment(b.getTotalAmount(), ps);
        b.setPayment(payment);
        if (payment.getStatus() != PaymentStatus.SUCCESS) {
            seatLockService.unlockSeats(show, b.getSeatIds(), userId);
            b.cancel();
            throw new IllegalStateException("Payment failed, seats released");
        }

        seatLockService.bookSeats(show, b.getSeatIds());
        b.confirm();
        b.getCustomer().addBooking(b);
        notifyAll(b, "Booking confirmed: " + b.getId() + " seats " + b.getSeatIds());
        return b;
    }

    public void cancelBooking(String bookingId) {
        Booking b = bookings.get(bookingId);
        if (b == null || b.getStatus() != BookingStatus.CONFIRMED)
            throw new IllegalStateException("Cannot cancel booking " + bookingId);
        seatLockService.releaseBookedSeats(b.getShow(), b.getSeatIds());
        paymentService.refund(b.getPayment(), b.getTotalAmount());
        b.cancel();
        notifyAll(b, "Booking cancelled: " + bookingId);
    }

    private Booking getPendingBooking(String bookingId) {
        Booking b = bookings.get(bookingId);
        if (b == null || b.getStatus() != BookingStatus.PENDING)
            throw new IllegalStateException("No pending booking " + bookingId);
        return b;
    }

    public void setPricingStrategy(PricingStrategy p) { this.pricingStrategy = p; }
    public void addObserver(NotificationObserver o)    { observers.add(o); }
    public Booking getBooking(String id)               { return bookings.get(id); }

    private void notifyAll(Booking b, String msg) { observers.forEach(o -> o.notify(b, msg)); }
}

// ===================== Facade + Singleton =====================

public class MovieTicketBookingSystem {
    private static volatile MovieTicketBookingSystem instance;
    private final SearchService searchService = new SearchService();
    private final SeatLockService seatLockService = new SeatLockService();
    private final BookingService bookingService = new BookingService(seatLockService);

    private MovieTicketBookingSystem() {}

    public static MovieTicketBookingSystem getInstance() {
        if (instance == null) {
            synchronized (MovieTicketBookingSystem.class) {
                if (instance == null) instance = new MovieTicketBookingSystem();
            }
        }
        return instance;
    }

    public void addCinema(Cinema cinema)                  { searchService.addCinema(cinema); }
    public void addShow(Show show)                        { searchService.addShow(show); }

    public List<Movie> searchMovies(String city)          { return searchService.searchMovies(city); }
    public List<Show> getShows(String city, Movie movie)  { return searchService.getShows(city, movie); }

    public Booking selectSeats(Customer c, Show show, List<String> seatIds) {
        return bookingService.createBooking(c, show, seatIds);
    }

    public Booking pay(String bookingId, PaymentStrategy ps) { return bookingService.confirmBooking(bookingId, ps); }
    public void cancel(String bookingId)                     { bookingService.cancelBooking(bookingId); }

    public SearchService getSearchService()     { return searchService; }
    public SeatLockService getSeatLockService() { return seatLockService; }
    public BookingService getBookingService()   { return bookingService; }

    // ===================== Demo =====================

    public static void main(String[] args) throws InterruptedException {
        MovieTicketBookingSystem system = MovieTicketBookingSystem.getInstance();
        system.getBookingService().addObserver(new EmailNotifier());
        system.getBookingService().addObserver(new SmsNotifier());
        system.getSeatLockService().setLockTimeout(Duration.ofSeconds(1));   // short, to demo expiry

        // Admin sets up cinema, screen, movie, shows
        Cinema pvr = new Cinema("PVR", "Chennai");
        Screen audi1 = new Screen("Audi 1");
        audi1.addRow("A", 5, SeatType.SILVER);
        audi1.addRow("B", 5, SeatType.GOLD);
        audi1.addRow("C", 3, SeatType.PLATINUM);
        pvr.addScreen(audi1);
        system.addCinema(pvr);

        Movie inception = new Movie("Inception", "English", "Sci-Fi", 148);
        Map<SeatType, Double> prices = Map.of(SeatType.SILVER, 150.0, SeatType.GOLD, 250.0, SeatType.PLATINUM, 400.0);
        Show thuShow = new Show(inception, pvr, audi1, LocalDateTime.of(2026, 11, 12, 18, 30), prices);   // Thursday
        Show satShow = new Show(inception, pvr, audi1, LocalDateTime.of(2026, 11, 14, 21, 0), prices);    // Saturday
        system.addShow(thuShow);
        system.addShow(satShow);

        Customer alice = new Customer("Alice", "alice@mail.com", "9111111111");
        Customer bob   = new Customer("Bob",   "bob@mail.com",   "9222222222");
        Customer carol = new Customer("Carol", "carol@mail.com", "9333333333");

        // 1. Search
        System.out.println("1. Movies in Chennai: " + system.searchMovies("Chennai"));
        system.getShows("Chennai", inception).forEach(s -> System.out.println("  " + s));

        // 2. Select seats + pay
        System.out.println("\n2. Alice selects B1, B2 and pays");
        Booking b1 = system.selectSeats(alice, thuShow, List.of("B1", "B2"));
        System.out.println("  " + b1);
        system.pay(b1.getId(), new UpiPayment());
        System.out.println("  " + b1);

        // 3. Booked seat -> rejected, all-or-nothing
        System.out.println("\n3. Bob tries B2, B3 (B2 already booked)");
        try {
            system.selectSeats(bob, thuShow, List.of("B2", "B3"));
        } catch (IllegalStateException e) {
            System.out.println("  Rejected: " + e.getMessage());
        }
        System.out.println("  B3 still free (nothing partially locked)? "
                + thuShow.getShowSeat("B3").isFree());

        // 4. Locked seat -> rejected for others
        System.out.println("\n4. Bob locks C1, Carol tries C1 while Bob is paying");
        Booking bobBooking = system.selectSeats(bob, thuShow, List.of("C1"));
        try {
            system.selectSeats(carol, thuShow, List.of("C1"));
        } catch (IllegalStateException e) {
            System.out.println("  Rejected: " + e.getMessage());
        }
        System.out.println("  Seat map:" + thuShow.seatMap());

        // 5. Lock expiry
        System.out.println("\n5. Bob waits too long, lock expires");
        Thread.sleep(1200);
        try {
            system.pay(bobBooking.getId(), new CardPayment());
        } catch (IllegalStateException e) {
            System.out.println("  Rejected: " + e.getMessage());
        }
        System.out.println("  " + bobBooking);
        Booking carolBooking = system.selectSeats(carol, thuShow, List.of("C1"));
        system.pay(carolBooking.getId(), new CardPayment());

        // 6. Payment failure releases seats
        System.out.println("\n6. Bob selects C2, C3 with low wallet balance");
        Booking failed = system.selectSeats(bob, thuShow, List.of("C2", "C3"));
        try {
            system.pay(failed.getId(), new WalletPayment(100));
        } catch (IllegalStateException e) {
            System.out.println("  Rejected: " + e.getMessage());
        }
        System.out.println("  C2, C3 free again? " + thuShow.getShowSeat("C2").isFree()
                + ", " + thuShow.getShowSeat("C3").isFree());

        // 7. Cancel
        System.out.println("\n7. Alice cancels " + b1.getId());
        system.cancel(b1.getId());
        System.out.println("  " + b1);
        System.out.println("  Seat map:" + thuShow.seatMap());

        // 8. Weekend pricing
        System.out.println("\n8. Switch to weekend pricing, Alice books B1 for the Saturday show");
        system.getBookingService().setPricingStrategy(new WeekendPricing());
        Booking b2 = system.selectSeats(alice, satShow, List.of("B1"));
        system.pay(b2.getId(), new UpiPayment());
        System.out.println("  " + b2 + "  (GOLD 250 x 1.25)");

        // 9. Concurrency: 10 threads race for the same seat, only 1 must win
        System.out.println("\n9. 10 concurrent customers race for A1 of the Saturday show");
        List<Thread> threads = new ArrayList<>();
        List<String> winners = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 10; i++) {
            Customer c = new Customer("C" + i, "c" + i + "@mail.com", "90000000" + i);
            Thread t = new Thread(() -> {
                try {
                    system.selectSeats(c, satShow, List.of("A1"));
                    winners.add(c.getName());
                } catch (IllegalStateException ignored) { }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join();
        System.out.println("  Winners: " + winners + " (expected exactly 1)");
    }
}
