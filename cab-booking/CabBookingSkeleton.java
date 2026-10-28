import java.util.*;

/**
 * Skeleton for the Cab Booking (Uber/Ola) LLD: classes, fields and method signatures only.
 * Every class is nested here so this file can sit next to CabBookingSystem.java
 * without duplicate-class clashes. Getters/setters omitted.
 */
public class CabBookingSkeleton {

    // ===================== Enums =====================

    enum VehicleType {
        BIKE(20, 6), AUTO(30, 10), MINI(50, 12), SEDAN(70, 15), SUV(100, 20);

        final double baseFare;
        final double perKm;

        VehicleType(double baseFare, double perKm) {
            this.baseFare = baseFare;
            this.perKm = perKm;
        }
    }

    enum DriverStatus { OFFLINE, AVAILABLE, ON_TRIP }

    enum RideStatus { REQUESTED, DRIVER_ASSIGNED, IN_PROGRESS, COMPLETED, CANCELLED }

    enum PaymentStatus { PENDING, SUCCESS, FAILED, REFUNDED }

    // ===================== Location =====================

    static class Location {
        String name;
        double lat;
        double lng;

        double distanceKm(Location other) {
            // TODO: Haversine
            throw new UnsupportedOperationException("TODO");
        }
    }

    // ===================== Users =====================

    static abstract class User {
        String id;
        String name;
        String phone;
        double ratingSum;
        int ratingCount;

        synchronized void addRating(int stars) { /* TODO: validate 1-5 */ }

        synchronized double getRating() {
            // TODO: average, default 5.0
            throw new UnsupportedOperationException("TODO");
        }
    }

    static class Rider extends User {
        List<Ride> rides;

        void addRide(Ride r) { /* TODO */ }
    }

    static class Vehicle {
        String number;
        String model;
        VehicleType type;
    }

    static class Driver extends User {
        Vehicle vehicle;
        volatile Location location;
        DriverStatus status;

        synchronized void goOnline() { /* TODO: OFFLINE -> AVAILABLE */ }

        synchronized void goOffline() { /* TODO: not allowed ON_TRIP */ }

        synchronized boolean tryAssign() {
            // TODO: atomic AVAILABLE -> ON_TRIP
            throw new UnsupportedOperationException("TODO");
        }

        synchronized void release() { /* TODO: ON_TRIP -> AVAILABLE */ }

        synchronized boolean isAvailable() {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }

        void updateLocation(Location l) { /* TODO */ }
    }

    // ===================== Payment (Strategy) =====================

    interface PaymentStrategy {
        boolean pay(double amount);
        boolean refund(double amount);
    }

    static class CashPayment implements PaymentStrategy {
        public boolean pay(double amount)    { throw new UnsupportedOperationException("TODO"); }
        public boolean refund(double amount) { throw new UnsupportedOperationException("TODO"); }
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
    }

    static class PaymentService {
        Payment makePayment(double amount, PaymentStrategy ps) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }
    }

    // ===================== Fare (Strategy + Decorator) =====================

    interface FareStrategy {
        double calculate(VehicleType type, double distanceKm);
    }

    static class StandardFare implements FareStrategy {
        public double calculate(VehicleType type, double distanceKm) {
            // TODO: base + perKm * distance
            throw new UnsupportedOperationException("TODO");
        }
    }

    static class SurgeFare implements FareStrategy {
        FareStrategy base;
        double multiplier;

        public double calculate(VehicleType type, double distanceKm) {
            // TODO: base.calculate(...) * multiplier
            throw new UnsupportedOperationException("TODO");
        }
    }

    // ===================== Driver matching (Strategy) =====================

    interface DriverMatchingStrategy {
        List<Driver> rank(List<Driver> candidates, Location pickup);
    }

    static class NearestDriverStrategy implements DriverMatchingStrategy {
        public List<Driver> rank(List<Driver> candidates, Location pickup) {
            // TODO: sort by distance to pickup
            throw new UnsupportedOperationException("TODO");
        }
    }

    static class HighestRatedStrategy implements DriverMatchingStrategy {
        public List<Driver> rank(List<Driver> candidates, Location pickup) {
            // TODO: sort by rating desc, then distance
            throw new UnsupportedOperationException("TODO");
        }
    }

    // ===================== Notification (Observer) =====================

    interface NotificationObserver {
        void notify(User recipient, String message);
    }

    static class SmsNotifier implements NotificationObserver {
        public void notify(User recipient, String message) { /* TODO */ }
    }

    static class PushNotifier implements NotificationObserver {
        public void notify(User recipient, String message) { /* TODO */ }
    }

    // ===================== Ride =====================

    static class Ride {
        String id;
        Rider rider;
        Driver driver;
        Location pickup;
        Location drop;
        VehicleType vehicleType;
        double distanceKm;
        double fare;                 // upfront price
        String otp;
        RideStatus status;
        Set<String> rejectedDriverIds;
        Payment payment;

        synchronized void assign(Driver d, String otp) { /* TODO: REQUESTED -> DRIVER_ASSIGNED */ }

        synchronized Driver unassign() {
            // TODO: DRIVER_ASSIGNED -> REQUESTED, remember rejected driver
            throw new UnsupportedOperationException("TODO");
        }

        synchronized void start(String enteredOtp) { /* TODO: check OTP, -> IN_PROGRESS */ }

        synchronized void complete(Payment p) { /* TODO: IN_PROGRESS -> COMPLETED */ }

        synchronized void cancel() { /* TODO: REQUESTED/DRIVER_ASSIGNED -> CANCELLED */ }
    }

    // ===================== Services =====================

    static class DriverService {
        Map<String, Driver> drivers;

        void register(Driver d) { /* TODO */ }

        List<Driver> findNearbyDrivers(Location pickup, VehicleType type, double radiusKm) {
            // TODO: available + same type + within radius
            throw new UnsupportedOperationException("TODO");
        }
    }

    static class RideService {
        Map<String, Ride> rides;
        List<NotificationObserver> observers;
        PaymentService paymentService;
        DriverService driverService;
        DriverMatchingStrategy matchingStrategy;
        FareStrategy fareStrategy;
        double searchRadiusKm;

        double estimateFare(Location pickup, Location drop, VehicleType type) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }

        Ride requestRide(Rider rider, Location pickup, Location drop, VehicleType type) {
            // TODO:
            // 1. upfront fare
            // 2. create ride REQUESTED
            // 3. assignDriver
            throw new UnsupportedOperationException("TODO");
        }

        void startRide(String rideId, String otp) { /* TODO */ }

        Ride completeRide(String rideId, PaymentStrategy ps) {
            // TODO: pay (ride stays IN_PROGRESS on failure), complete,
            //       move driver to drop, release driver, notify
            throw new UnsupportedOperationException("TODO");
        }

        void cancelRide(String rideId, User by) {
            // TODO: driver cancels -> unassign + re-match; rider cancels -> cancel + release driver
        }

        void rateDriver(String rideId, int stars) { /* TODO: COMPLETED only */ }

        void rateRider(String rideId, int stars) { /* TODO: COMPLETED only */ }

        private void assignDriver(Ride ride) {
            // TODO: nearby drivers minus rejected, rank, tryAssign each until one succeeds,
            //       generate OTP, notify; none -> cancel ride + throw
        }

        void setMatchingStrategy(DriverMatchingStrategy s) { /* TODO */ }

        void setFareStrategy(FareStrategy f) { /* TODO */ }

        void addObserver(NotificationObserver o) { /* TODO */ }
    }

    // ===================== Facade + Singleton =====================

    static class CabBookingSystem {
        private static volatile CabBookingSystem instance;
        DriverService driverService;
        RideService rideService;

        private CabBookingSystem() {}

        static CabBookingSystem getInstance() {
            // TODO: double-checked locking
            throw new UnsupportedOperationException("TODO");
        }

        void registerDriver(Driver d) { /* TODO */ }

        double estimateFare(Location pickup, Location drop, VehicleType type) {
            // TODO: delegate to rideService
            throw new UnsupportedOperationException("TODO");
        }

        Ride requestRide(Rider r, Location pickup, Location drop, VehicleType type) {
            // TODO: delegate to rideService
            throw new UnsupportedOperationException("TODO");
        }

        void startRide(String rideId, String otp) { /* TODO */ }

        Ride completeRide(String rideId, PaymentStrategy ps) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }

        void cancelRide(String rideId, User by) { /* TODO */ }

        void rateDriver(String rideId, int stars) { /* TODO */ }

        void rateRider(String rideId, int stars) { /* TODO */ }
    }

    // ===================== Demo =====================

    public static void main(String[] args) {
        // TODO:
        // 1. Register drivers (different vehicle types / locations), go online
        // 2. Fare estimate per vehicle type
        // 3. Request ride -> nearest driver, OTP
        // 4. Wrong OTP rejected, correct OTP starts ride
        // 5. Payment failure -> retry, complete, ratings
        // 6. No driver available; driver cancels -> re-match; rider cancels
        // 7. Surge pricing
        // 8. Concurrent requests -> no driver assigned twice
    }
}
