import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Skeleton for the Parking Lot LLD: classes, fields and method signatures only.
 * Every class is nested here so this file can sit next to ParkingLotSystem.java
 * without duplicate-class clashes. Getters/setters omitted.
 */
public class ParkingLotSkeleton {

    // ===================== Enums =====================

    enum VehicleType { BIKE, CAR, TRUCK }

    enum SpotType {
        SMALL, MEDIUM, LARGE;

        static SpotType minimumFor(VehicleType v) {
            // TODO: BIKE -> SMALL, CAR -> MEDIUM, TRUCK -> LARGE
            throw new UnsupportedOperationException("TODO");
        }

        boolean fits(VehicleType v) {
            // TODO: this size >= minimum size
            throw new UnsupportedOperationException("TODO");
        }
    }

    // ===================== Vehicle & Spot =====================

    static class Vehicle {
        String number;
        VehicleType type;
    }

    static class ParkingSpot {
        String id;
        SpotType type;
        int floor;
        Vehicle vehicle;   // null = free

        synchronized boolean tryPark(Vehicle v) {
            // TODO: free and fits -> occupy
            throw new UnsupportedOperationException("TODO");
        }

        synchronized void free() { /* TODO */ }

        synchronized boolean isFree() {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }
    }

    static class ParkingFloor {
        int number;
        List<ParkingSpot> spots;

        ParkingFloor addSpot(String id, SpotType type) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }
    }

    // ===================== Ticket =====================

    static class Ticket {
        String id;
        Vehicle vehicle;
        ParkingSpot spot;
        LocalDateTime entryTime;
        LocalDateTime exitTime;   // null while parked
        long fee;

        void close(LocalDateTime at, long fee) { /* TODO */ }
    }

    // ===================== Strategies =====================

    interface SpotAllocationStrategy {
        List<ParkingSpot> rank(List<ParkingSpot> candidates, Vehicle v);
    }

    static class NearestFirstStrategy implements SpotAllocationStrategy {
        public List<ParkingSpot> rank(List<ParkingSpot> candidates, Vehicle v) {
            // TODO: lowest floor first
            throw new UnsupportedOperationException("TODO");
        }
    }

    static class BestFitStrategy implements SpotAllocationStrategy {
        public List<ParkingSpot> rank(List<ParkingSpot> candidates, Vehicle v) {
            // TODO: smallest spot type first, then floor
            throw new UnsupportedOperationException("TODO");
        }
    }

    interface FeeStrategy {
        long calculate(VehicleType type, Duration parked);
    }

    static class HourlyFee implements FeeStrategy {
        Map<VehicleType, Long> ratePerHour;

        public long calculate(VehicleType type, Duration parked) {
            // TODO: started hours (min 1) x rate
            throw new UnsupportedOperationException("TODO");
        }
    }

    // ===================== Extension point (Observer) =====================

    interface ParkingListener {
        void onPark(Ticket t);
        void onUnpark(Ticket t);
    }

    static class DisplayBoard implements ParkingListener {
        public void onPark(Ticket t)   { /* TODO: show availability */ }
        public void onUnpark(Ticket t) { /* TODO */ }
    }

    // ===================== Facade + Singleton =====================

    static class ParkingLotSystem {
        private static volatile ParkingLotSystem instance;
        List<ParkingFloor> floors;
        Map<String, Ticket> activeTickets;    // ticketId -> ticket
        Map<String, Ticket> parkedVehicles;   // vehicle number -> ticket
        List<ParkingListener> listeners;
        SpotAllocationStrategy allocation;
        FeeStrategy feeStrategy;

        private ParkingLotSystem() {}

        static ParkingLotSystem getInstance() {
            // TODO: double-checked locking
            throw new UnsupportedOperationException("TODO");
        }

        void addFloor(ParkingFloor f) { /* TODO */ }

        Ticket park(Vehicle v, LocalDateTime at) {
            // TODO: reject duplicate vehicle, free spots that fit, rank,
            //       tryPark each until one works, create ticket, notify
            throw new UnsupportedOperationException("TODO");
        }

        Ticket unpark(String ticketId, LocalDateTime at) {
            // TODO: remove ticket, fee, free spot, notify
            throw new UnsupportedOperationException("TODO");
        }

        Map<SpotType, Long> availability() {
            // TODO: free spots per type
            throw new UnsupportedOperationException("TODO");
        }

        Ticket findByVehicle(String number) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }
    }

    // ===================== Demo =====================

    public static void main(String[] args) {
        // TODO:
        // 1. Floors + spots; park a bike, car, truck
        // 2. No spot / duplicate vehicle -> rejected
        // 3. Switch allocation strategy
        // 4. Find vehicle, unpark with fee
        // 5. More cars than spots at once -> no spot given twice
    }
}
