import java.time.LocalDate;
import java.util.*;

/**
 * Skeleton for the Hotel Booking LLD: classes, fields and method signatures only.
 * Every class is nested here so this file can sit next to HotelBookingSystem.java
 * without duplicate-class clashes. Getters/setters omitted.
 */
public class HotelBookingSkeleton {

    // ===================== Enums =====================

    enum RoomType { SINGLE, DOUBLE, DELUXE, SUITE }

    enum BookingStatus { PENDING, CONFIRMED, CANCELLED, COMPLETED }

    enum PaymentStatus { PENDING, SUCCESS, FAILED, REFUNDED }

    // ===================== Users =====================

    static abstract class User {
        String id;
        String name;
        String email;
        String phone;
    }

    static class Guest extends User {
        List<Booking> bookings;

        void addBooking(Booking b) { /* TODO */ }
    }

    static class Admin extends User {
        void addRoom(Hotel hotel, Room room) { /* TODO */ }
    }

    // ===================== DateRange =====================

    static class DateRange {
        LocalDate checkIn;
        LocalDate checkOut;   // exclusive

        DateRange(LocalDate checkIn, LocalDate checkOut) {
            // TODO: validate checkOut > checkIn
        }

        boolean overlaps(DateRange other) {
            // TODO: a < d && c < b
            throw new UnsupportedOperationException("TODO");
        }

        long nights() {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }
    }

    // ===================== Hotel & Room =====================

    static class Room {
        String id;
        String roomNumber;
        RoomType type;
        double basePrice;
        int capacity;
        List<DateRange> bookedRanges;

        synchronized boolean isAvailable(DateRange range) {
            // TODO: no booked range overlaps
            throw new UnsupportedOperationException("TODO");
        }

        synchronized boolean tryBook(DateRange range) {
            // TODO: atomic check + book
            throw new UnsupportedOperationException("TODO");
        }

        synchronized void release(DateRange range) { /* TODO */ }
    }

    static class Hotel {
        String id;
        String name;
        String city;
        String address;
        List<Room> rooms;

        void addRoom(Room room) { /* TODO */ }

        List<Room> getAvailableRooms(DateRange range, RoomType type) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }
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
        double calculate(Room room, DateRange range);
    }

    static class NormalPricing implements PricingStrategy {
        public double calculate(Room room, DateRange range) { throw new UnsupportedOperationException("TODO"); }
    }

    static class WeekendPricing implements PricingStrategy {
        public double calculate(Room room, DateRange range) { throw new UnsupportedOperationException("TODO"); }
    }

    static class SeasonalPricing implements PricingStrategy {
        public double calculate(Room room, DateRange range) { throw new UnsupportedOperationException("TODO"); }
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
        Guest guest;
        Hotel hotel;
        List<Room> rooms;
        DateRange dateRange;
        double totalAmount;
        BookingStatus status;
        Payment payment;

        void confirm()  { /* TODO: PENDING -> CONFIRMED */ }

        void cancel()   { /* TODO: PENDING/CONFIRMED -> CANCELLED */ }

        void complete() { /* TODO: CONFIRMED -> COMPLETED */ }
    }

    // ===================== Services =====================

    static class SearchService {
        List<Hotel> hotels;

        void addHotel(Hotel hotel) { /* TODO */ }

        List<Hotel> search(String city, DateRange range, int guests) {
            // TODO: filter by city + enough free capacity
            throw new UnsupportedOperationException("TODO");
        }
    }

    static class BookingService {
        Map<String, Booking> bookings;
        List<NotificationObserver> observers;
        PaymentService paymentService;
        PricingStrategy pricingStrategy;

        Booking createBooking(Guest guest, Hotel hotel, List<Room> rooms,
                              DateRange range, PaymentStrategy ps) {
            // TODO:
            // 1. tryBook each room, roll back on failure
            // 2. calculate price
            // 3. make payment; on failure release rooms + cancel
            // 4. confirm, save, notify
            throw new UnsupportedOperationException("TODO");
        }

        void cancelBooking(String bookingId) {
            // TODO: release rooms, refund, cancel, notify
        }

        void setPricingStrategy(PricingStrategy p) { /* TODO */ }

        void addObserver(NotificationObserver o) { /* TODO */ }

        private void notifyAll(Booking b, String message) { /* TODO */ }
    }

    // ===================== Facade + Singleton =====================

    static class HotelBookingSystem {
        private static volatile HotelBookingSystem instance;
        SearchService searchService;
        BookingService bookingService;

        private HotelBookingSystem() {}

        static HotelBookingSystem getInstance() {
            // TODO: double-checked locking
            throw new UnsupportedOperationException("TODO");
        }

        void addHotel(Hotel hotel) { /* TODO: delegate to searchService */ }

        List<Hotel> searchHotels(String city, DateRange range, int guests) {
            // TODO: delegate to searchService
            throw new UnsupportedOperationException("TODO");
        }

        Booking bookRooms(Guest g, Hotel h, List<Room> rooms, DateRange range, PaymentStrategy ps) {
            // TODO: delegate to bookingService
            throw new UnsupportedOperationException("TODO");
        }

        void cancel(String bookingId) { /* TODO: delegate to bookingService */ }
    }

    // ===================== Demo =====================

    public static void main(String[] args) {
        // TODO:
        // 1. Admin adds hotel + rooms
        // 2. Guest searches
        // 3. Guest books -> CONFIRMED
        // 4. Overlapping booking -> rejected
        // 5. Payment failure -> rooms released
        // 6. Cancel -> refund
    }
}
