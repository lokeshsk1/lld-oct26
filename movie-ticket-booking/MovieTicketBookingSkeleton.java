import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Skeleton for the Movie Ticket Booking LLD: classes, fields and method signatures only.
 * Every class is nested here so this file can sit next to MovieTicketBookingSystem.java
 * without duplicate-class clashes. Getters/setters omitted.
 */
public class MovieTicketBookingSkeleton {

    // ===================== Enums =====================

    enum SeatType { SILVER, GOLD, PLATINUM }

    enum SeatStatus { AVAILABLE, LOCKED, BOOKED }

    enum BookingStatus { PENDING, CONFIRMED, CANCELLED, EXPIRED }

    enum PaymentStatus { PENDING, SUCCESS, FAILED, REFUNDED }

    // ===================== Users =====================

    static abstract class User {
        String id;
        String name;
        String email;
        String phone;
    }

    static class Customer extends User {
        List<Booking> bookings;

        void addBooking(Booking b) { /* TODO */ }
    }

    static class Admin extends User {
    }

    // ===================== Movie, Cinema, Screen, Seat =====================

    static class Movie {
        String id;
        String title;
        String language;
        String genre;
        int durationMins;
    }

    static class Seat {
        String id;        // e.g. "B3"
        String row;
        int number;
        SeatType type;
    }

    static class Screen {
        String id;
        String name;
        List<Seat> seats;

        void addSeat(Seat seat) { /* TODO */ }
    }

    static class Cinema {
        String id;
        String name;
        String city;
        List<Screen> screens;

        void addScreen(Screen screen) { /* TODO */ }
    }

    // ===================== Show & ShowSeat =====================

    // Seat state for one particular show
    static class ShowSeat {
        Seat seat;
        SeatStatus status;
        String lockedBy;
        Instant lockExpiresAt;

        boolean isFree() {
            // TODO: AVAILABLE, or LOCKED but lock expired
            throw new UnsupportedOperationException("TODO");
        }

        boolean isLockedBy(String userId) {
            // TODO: LOCKED by this user and not expired
            throw new UnsupportedOperationException("TODO");
        }

        void lock(String userId, Instant expiresAt) { /* TODO */ }

        void book() { /* TODO */ }

        void release() { /* TODO */ }
    }

    static class Show {
        String id;
        Movie movie;
        Cinema cinema;
        Screen screen;
        LocalDateTime startTime;
        Map<SeatType, Double> prices;
        Map<String, ShowSeat> showSeats;   // built from screen seats

        ShowSeat getShowSeat(String seatId) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }

        List<Seat> getAvailableSeats() {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }

        double getPrice(SeatType type) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }
    }

    // ===================== Seat locking =====================

    static class SeatLockService {
        Duration lockTimeout;

        boolean lockSeats(Show show, List<String> seatIds, String userId) {
            // TODO: synchronized(show), all-or-nothing lock with expiry
            throw new UnsupportedOperationException("TODO");
        }

        boolean isLockedBy(Show show, List<String> seatIds, String userId) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }

        void unlockSeats(Show show, List<String> seatIds, String userId) {
            // TODO: release only seats this user still holds
        }

        void bookSeats(Show show, List<String> seatIds) { /* TODO */ }

        void releaseBookedSeats(Show show, List<String> seatIds) { /* TODO */ }
    }

    // ===================== Payment (Strategy) =====================

    interface PaymentStrategy {
        boolean pay(double amount);
        boolean refund(double amount);
    }

    static class CardPayment implements PaymentStrategy {
        public boolean pay(double amount)    { throw new UnsupportedOperationException("TODO"); }
        public boolean refund(double amount) { throw new UnsupportedOperationException("TODO"); }
    }

    static class UpiPayment implements PaymentStrategy {
        public boolean pay(double amount)    { throw new UnsupportedOperationException("TODO"); }
        public boolean refund(double amount) { throw new UnsupportedOperationException("TODO"); }
    }

    static class WalletPayment implements PaymentStrategy {
        double balance;

        public boolean pay(double amount)    { throw new UnsupportedOperationException("TODO"); }
        public boolean refund(double amount) { throw new UnsupportedOperationException("TODO"); }
    }

    static class Payment {
        String id;
        double amount;
        PaymentStatus status;
        PaymentStrategy strategy;

        boolean process() {
            // TODO: call strategy.pay, set status
            throw new UnsupportedOperationException("TODO");
        }

        boolean refund(double amount) {
            // TODO: call strategy.refund, set status
            throw new UnsupportedOperationException("TODO");
        }
    }

    static class PaymentService {
        Payment makePayment(double amount, PaymentStrategy ps) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }

        boolean refund(Payment payment, double amount) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }
    }

    // ===================== Pricing (Strategy) =====================

    interface PricingStrategy {
        double calculate(Show show, Seat seat);
    }

    static class NormalPricing implements PricingStrategy {
        public double calculate(Show show, Seat seat) { throw new UnsupportedOperationException("TODO"); }
    }

    static class WeekendPricing implements PricingStrategy {
        public double calculate(Show show, Seat seat) { throw new UnsupportedOperationException("TODO"); }
    }

    static class PrimeTimePricing implements PricingStrategy {
        public double calculate(Show show, Seat seat) { throw new UnsupportedOperationException("TODO"); }
    }

    // ===================== Notification (Observer) =====================

    interface NotificationObserver {
        void notify(Booking booking, String message);
    }

    static class EmailNotifier implements NotificationObserver {
        public void notify(Booking booking, String message) { /* TODO */ }
    }

    static class SmsNotifier implements NotificationObserver {
        public void notify(Booking booking, String message) { /* TODO */ }
    }

    // ===================== Booking =====================

    static class Booking {
        String id;
        Customer customer;
        Show show;
        List<String> seatIds;
        double totalAmount;
        BookingStatus status;
        Payment payment;

        void confirm() { /* TODO: PENDING -> CONFIRMED */ }

        void cancel()  { /* TODO: PENDING/CONFIRMED -> CANCELLED */ }

        void expire()  { /* TODO: PENDING -> EXPIRED */ }
    }

    // ===================== Services =====================

    static class SearchService {
        List<Cinema> cinemas;
        List<Show> shows;

        void addCinema(Cinema cinema) { /* TODO */ }

        void addShow(Show show) { /* TODO */ }

        List<Movie> searchMovies(String city) {
            // TODO: distinct movies with shows in the city
            throw new UnsupportedOperationException("TODO");
        }

        List<Show> getShows(String city, Movie movie) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }
    }

    static class BookingService {
        Map<String, Booking> bookings;
        List<NotificationObserver> observers;
        PaymentService paymentService;
        SeatLockService seatLockService;
        PricingStrategy pricingStrategy;

        Booking createBooking(Customer customer, Show show, List<String> seatIds) {
            // TODO:
            // 1. lockSeats (all-or-nothing), throw if not available
            // 2. calculate price
            // 3. save booking as PENDING
            throw new UnsupportedOperationException("TODO");
        }

        Booking confirmBooking(String bookingId, PaymentStrategy ps) {
            // TODO:
            // 1. lock expired -> EXPIRED
            // 2. make payment; on failure unlock seats + CANCELLED
            // 3. bookSeats, CONFIRMED, notify
            throw new UnsupportedOperationException("TODO");
        }

        void cancelBooking(String bookingId) {
            // TODO: release seats, refund, cancel, notify
        }

        void setPricingStrategy(PricingStrategy p) { /* TODO */ }

        void addObserver(NotificationObserver o) { /* TODO */ }

        private void notifyAll(Booking b, String message) { /* TODO */ }
    }

    // ===================== Facade + Singleton =====================

    static class MovieTicketBookingSystem {
        private static volatile MovieTicketBookingSystem instance;
        SearchService searchService;
        SeatLockService seatLockService;
        BookingService bookingService;

        private MovieTicketBookingSystem() {}

        static MovieTicketBookingSystem getInstance() {
            // TODO: double-checked locking
            throw new UnsupportedOperationException("TODO");
        }

        void addCinema(Cinema cinema) { /* TODO: delegate to searchService */ }

        void addShow(Show show) { /* TODO: delegate to searchService */ }

        List<Movie> searchMovies(String city) {
            // TODO: delegate to searchService
            throw new UnsupportedOperationException("TODO");
        }

        List<Show> getShows(String city, Movie movie) {
            // TODO: delegate to searchService
            throw new UnsupportedOperationException("TODO");
        }

        Booking selectSeats(Customer c, Show show, List<String> seatIds) {
            // TODO: delegate to bookingService.createBooking
            throw new UnsupportedOperationException("TODO");
        }

        Booking pay(String bookingId, PaymentStrategy ps) {
            // TODO: delegate to bookingService.confirmBooking
            throw new UnsupportedOperationException("TODO");
        }

        void cancel(String bookingId) { /* TODO: delegate to bookingService */ }
    }

    // ===================== Demo =====================

    public static void main(String[] args) {
        // TODO:
        // 1. Admin adds cinema, screen, seats, movie, shows
        // 2. Customer searches movies + shows
        // 3. Customer selects seats -> PENDING, pays -> CONFIRMED
        // 4. Booked / locked seat -> rejected
        // 5. Lock expires -> EXPIRED, seat free for others
        // 6. Payment failure -> seats released
        // 7. Cancel -> refund
        // 8. Concurrent customers race for one seat -> exactly 1 wins
    }
}
