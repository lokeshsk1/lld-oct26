import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

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

class Location {
    private final String name;
    private final double lat;
    private final double lng;

    Location(String name, double lat, double lng) {
        this.name = name;
        this.lat = lat;
        this.lng = lng;
    }

    // Haversine distance in km
    public double distanceKm(Location o) {
        double r = 6371;
        double dLat = Math.toRadians(o.lat - lat);
        double dLng = Math.toRadians(o.lng - lng);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(o.lat))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public String getName() { return name; }
    public double getLat()  { return lat; }
    public double getLng()  { return lng; }

    @Override public String toString() { return name; }
}

// ===================== Users =====================

abstract class User {
    private final String id = UUID.randomUUID().toString();
    private final String name;
    private final String phone;
    private double ratingSum;
    private int ratingCount;

    User(String name, String phone) {
        this.name = name;
        this.phone = phone;
    }

    public synchronized void addRating(int stars) {
        if (stars < 1 || stars > 5) throw new IllegalArgumentException("Rating must be 1-5");
        ratingSum += stars;
        ratingCount++;
    }

    public synchronized double getRating() {
        return ratingCount == 0 ? 5.0 : Math.round(ratingSum / ratingCount * 10) / 10.0;
    }

    public String getId()    { return id; }
    public String getName()  { return name; }
    public String getPhone() { return phone; }

    @Override public String toString() { return name; }
}

class Rider extends User {
    private final List<Ride> rides = new ArrayList<>();

    Rider(String name, String phone) { super(name, phone); }

    public synchronized void addRide(Ride r)  { rides.add(r); }
    public synchronized List<Ride> getRides() { return new ArrayList<>(rides); }
}

class Vehicle {
    private final String number;
    private final String model;
    private final VehicleType type;

    Vehicle(String number, String model, VehicleType type) {
        this.number = number;
        this.model = model;
        this.type = type;
    }

    public String getNumber()    { return number; }
    public String getModel()     { return model; }
    public VehicleType getType() { return type; }

    @Override public String toString() { return model + " " + number; }
}

class Driver extends User {
    private final Vehicle vehicle;
    private volatile Location location;
    private DriverStatus status = DriverStatus.OFFLINE;

    Driver(String name, String phone, Vehicle vehicle, Location location) {
        super(name, phone);
        this.vehicle = vehicle;
        this.location = location;
    }

    public synchronized void goOnline() {
        if (status == DriverStatus.OFFLINE) status = DriverStatus.AVAILABLE;
    }

    public synchronized void goOffline() {
        if (status == DriverStatus.ON_TRIP) throw new IllegalStateException("Cannot go offline during a trip");
        status = DriverStatus.OFFLINE;
    }

    // check + reserve atomically -> a driver can never get two rides at once
    public synchronized boolean tryAssign() {
        if (status != DriverStatus.AVAILABLE) return false;
        status = DriverStatus.ON_TRIP;
        return true;
    }

    public synchronized void release() {
        if (status == DriverStatus.ON_TRIP) status = DriverStatus.AVAILABLE;
    }

    public synchronized boolean isAvailable() { return status == DriverStatus.AVAILABLE; }

    public void updateLocation(Location l)  { this.location = l; }
    public Location getLocation()           { return location; }
    public Vehicle getVehicle()             { return vehicle; }
    public synchronized DriverStatus getStatus() { return status; }
}

// ===================== Payment (Strategy) =====================

interface PaymentStrategy {
    boolean pay(double amount);
    boolean refund(double amount);
}

class CashPayment implements PaymentStrategy {
    public boolean pay(double amount)    { System.out.println("  Collected " + amount + " in cash"); return true; }
    public boolean refund(double amount) { return false; }
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
}

// ===================== Fare (Strategy + Decorator) =====================

interface FareStrategy {
    double calculate(VehicleType type, double distanceKm);
}

class StandardFare implements FareStrategy {
    public double calculate(VehicleType type, double distanceKm) {
        return round2(type.baseFare + type.perKm * distanceKm);
    }

    static double round2(double x) { return Math.round(x * 100) / 100.0; }
}

// wraps any fare strategy and multiplies it (high demand / low supply)
class SurgeFare implements FareStrategy {
    private final FareStrategy base;
    private final double multiplier;

    SurgeFare(FareStrategy base, double multiplier) {
        this.base = base;
        this.multiplier = multiplier;
    }

    public double calculate(VehicleType type, double distanceKm) {
        return StandardFare.round2(base.calculate(type, distanceKm) * multiplier);
    }
}

// ===================== Driver matching (Strategy) =====================

interface DriverMatchingStrategy {
    // order nearby drivers by preference; RideService tries them in this order
    List<Driver> rank(List<Driver> candidates, Location pickup);
}

class NearestDriverStrategy implements DriverMatchingStrategy {
    public List<Driver> rank(List<Driver> candidates, Location pickup) {
        return candidates.stream()
                .sorted(Comparator.comparingDouble(d -> d.getLocation().distanceKm(pickup)))
                .collect(Collectors.toList());
    }
}

class HighestRatedStrategy implements DriverMatchingStrategy {
    public List<Driver> rank(List<Driver> candidates, Location pickup) {
        return candidates.stream()
                .sorted(Comparator.comparingDouble(Driver::getRating).reversed()
                        .thenComparingDouble(d -> d.getLocation().distanceKm(pickup)))
                .collect(Collectors.toList());
    }
}

// ===================== Notification (Observer) =====================

interface NotificationObserver {
    void notify(User recipient, String message);
}

class SmsNotifier implements NotificationObserver {
    public void notify(User recipient, String message) {
        System.out.println("  [SMS to " + recipient.getName() + "] " + message);
    }
}

class PushNotifier implements NotificationObserver {
    public void notify(User recipient, String message) {
        System.out.println("  [PUSH to " + recipient.getName() + "] " + message);
    }
}

// ===================== Ride =====================

class Ride {
    private final String id = UUID.randomUUID().toString().substring(0, 8);
    private final Rider rider;
    private final Location pickup;
    private final Location drop;
    private final VehicleType vehicleType;
    private final double distanceKm;
    private final double fare;                       // upfront price, locked at request time
    private final Set<String> rejectedDriverIds = new HashSet<>();
    private Driver driver;
    private String otp;
    private RideStatus status = RideStatus.REQUESTED;
    private Payment payment;

    Ride(Rider rider, Location pickup, Location drop, VehicleType vehicleType, double distanceKm, double fare) {
        this.rider = rider;
        this.pickup = pickup;
        this.drop = drop;
        this.vehicleType = vehicleType;
        this.distanceKm = distanceKm;
        this.fare = fare;
    }

    // REQUESTED -> DRIVER_ASSIGNED -> IN_PROGRESS -> COMPLETED
    // REQUESTED / DRIVER_ASSIGNED -> CANCELLED;  DRIVER_ASSIGNED -> REQUESTED (driver cancelled)
    public synchronized void assign(Driver d, String otp) {
        require(RideStatus.REQUESTED);
        this.driver = d;
        this.otp = otp;
        status = RideStatus.DRIVER_ASSIGNED;
    }

    public synchronized Driver unassign() {
        require(RideStatus.DRIVER_ASSIGNED);
        Driver d = driver;
        rejectedDriverIds.add(d.getId());
        driver = null;
        otp = null;
        status = RideStatus.REQUESTED;
        return d;
    }

    public synchronized void start(String enteredOtp) {
        require(RideStatus.DRIVER_ASSIGNED);
        if (!otp.equals(enteredOtp)) throw new IllegalStateException("Wrong OTP");
        status = RideStatus.IN_PROGRESS;
    }

    public synchronized void complete(Payment p) {
        require(RideStatus.IN_PROGRESS);
        this.payment = p;
        status = RideStatus.COMPLETED;
    }

    public synchronized void cancel() {
        if (status != RideStatus.REQUESTED && status != RideStatus.DRIVER_ASSIGNED)
            throw new IllegalStateException("Cannot cancel a " + status + " ride");
        status = RideStatus.CANCELLED;
    }

    private void require(RideStatus expected) {
        if (status != expected) throw new IllegalStateException("Ride is " + status + ", expected " + expected);
    }

    public String getId()                   { return id; }
    public Rider getRider()                 { return rider; }
    public Location getPickup()             { return pickup; }
    public Location getDrop()               { return drop; }
    public VehicleType getVehicleType()     { return vehicleType; }
    public double getDistanceKm()           { return distanceKm; }
    public double getFare()                 { return fare; }
    public synchronized Driver getDriver()  { return driver; }
    public synchronized String getOtp()     { return otp; }
    public synchronized RideStatus getStatus() { return status; }
    public synchronized Set<String> getRejectedDriverIds() { return new HashSet<>(rejectedDriverIds); }
    public Payment getPayment()             { return payment; }

    @Override public synchronized String toString() {
        return "Ride#" + id + " " + rider + " " + pickup + "->" + drop + " " + vehicleType
                + String.format(" %.1fkm", distanceKm) + " fare=" + fare
                + " driver=" + (driver == null ? "-" : driver.getName()) + " " + status;
    }
}

// ===================== Services =====================

class DriverService {
    private final Map<String, Driver> drivers = new ConcurrentHashMap<>();

    public void register(Driver d) { drivers.put(d.getId(), d); }

    // linear scan here; in production use a geo index (geohash / QuadTree / Redis GEO)
    public List<Driver> findNearbyDrivers(Location pickup, VehicleType type, double radiusKm) {
        return drivers.values().stream()
                .filter(Driver::isAvailable)
                .filter(d -> d.getVehicle().getType() == type)
                .filter(d -> d.getLocation().distanceKm(pickup) <= radiusKm)
                .collect(Collectors.toList());
    }
}

class RideService {
    private final Map<String, Ride> rides = new ConcurrentHashMap<>();
    private final List<NotificationObserver> observers = new ArrayList<>();
    private final PaymentService paymentService = new PaymentService();
    private final DriverService driverService;
    private DriverMatchingStrategy matchingStrategy = new NearestDriverStrategy();
    private FareStrategy fareStrategy = new StandardFare();
    private double searchRadiusKm = 10;

    RideService(DriverService driverService) { this.driverService = driverService; }

    public double estimateFare(Location pickup, Location drop, VehicleType type) {
        return fareStrategy.calculate(type, pickup.distanceKm(drop));
    }

    public Ride requestRide(Rider rider, Location pickup, Location drop, VehicleType type) {
        double distance = pickup.distanceKm(drop);
        Ride ride = new Ride(rider, pickup, drop, type, distance, fareStrategy.calculate(type, distance));
        rides.put(ride.getId(), ride);
        rider.addRide(ride);
        assignDriver(ride);
        return ride;
    }

    public void startRide(String rideId, String otp) {
        Ride ride = get(rideId);
        ride.start(otp);
        notify(ride.getRider(), "Ride started, heading to " + ride.getDrop());
    }

    // ride stays IN_PROGRESS if payment fails, so the rider can retry with another method
    public Ride completeRide(String rideId, PaymentStrategy ps) {
        Ride ride = get(rideId);
        if (ride.getStatus() != RideStatus.IN_PROGRESS) throw new IllegalStateException("Ride not in progress");

        Payment payment = paymentService.makePayment(ride.getFare(), ps);
        if (payment.getStatus() != PaymentStatus.SUCCESS)
            throw new IllegalStateException("Payment failed, try another method");

        ride.complete(payment);
        Driver d = ride.getDriver();
        d.updateLocation(ride.getDrop());
        d.release();
        notify(ride.getRider(), "Trip complete. Paid " + ride.getFare() + ". Rate " + d.getName());
        notify(d, "Trip complete. Earned " + ride.getFare());
        return ride;
    }

    public void cancelRide(String rideId, User by) {
        Ride ride = get(rideId);
        Driver d = ride.getDriver();

        if (d != null && d.getId().equals(by.getId())) {
            // driver cancelled -> free driver, find someone else for the rider
            ride.unassign();
            d.release();
            notify(ride.getRider(), d.getName() + " cancelled. Finding another driver...");
            assignDriver(ride);
        } else if (ride.getRider().getId().equals(by.getId())) {
            ride.cancel();
            if (d != null) {
                d.release();
                notify(d, ride.getRider() + " cancelled the ride");
            }
        } else {
            throw new IllegalStateException(by + " is not part of ride " + rideId);
        }
    }

    public void rateDriver(String rideId, int stars) {
        Ride ride = getCompleted(rideId);
        ride.getDriver().addRating(stars);
    }

    public void rateRider(String rideId, int stars) {
        Ride ride = getCompleted(rideId);
        ride.getRider().addRating(stars);
    }

    // walk the ranked drivers until one tryAssign succeeds (another ride may grab a driver first)
    private void assignDriver(Ride ride) {
        Set<String> rejected = ride.getRejectedDriverIds();
        List<Driver> nearby = driverService.findNearbyDrivers(ride.getPickup(), ride.getVehicleType(), searchRadiusKm)
                .stream().filter(d -> !rejected.contains(d.getId())).collect(Collectors.toList());

        for (Driver d : matchingStrategy.rank(nearby, ride.getPickup())) {
            if (d.tryAssign()) {
                String otp = String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
                try {
                    ride.assign(d, otp);
                } catch (IllegalStateException e) {   // rider cancelled meanwhile
                    d.release();
                    throw e;
                }
                double away = d.getLocation().distanceKm(ride.getPickup());
                notify(ride.getRider(), d.getName() + " (" + d.getVehicle() + ", " + d.getRating() + "*) is "
                        + String.format("%.1f", away) + " km away. OTP " + otp);
                notify(d, "New ride: pick up " + ride.getRider() + " at " + ride.getPickup());
                return;
            }
        }
        ride.cancel();
        notify(ride.getRider(), "No " + ride.getVehicleType() + " drivers available near " + ride.getPickup());
        throw new IllegalStateException("No drivers available");
    }

    private Ride get(String rideId) {
        Ride r = rides.get(rideId);
        if (r == null) throw new IllegalArgumentException("No ride " + rideId);
        return r;
    }

    private Ride getCompleted(String rideId) {
        Ride r = get(rideId);
        if (r.getStatus() != RideStatus.COMPLETED) throw new IllegalStateException("Can rate only completed rides");
        return r;
    }

    public void setMatchingStrategy(DriverMatchingStrategy s) { this.matchingStrategy = s; }
    public void setFareStrategy(FareStrategy f)               { this.fareStrategy = f; }
    public void setSearchRadiusKm(double km)                  { this.searchRadiusKm = km; }
    public void addObserver(NotificationObserver o)           { observers.add(o); }

    private void notify(User recipient, String msg) { observers.forEach(o -> o.notify(recipient, msg)); }
}

// ===================== Facade + Singleton =====================

public class CabBookingSystem {
    private static volatile CabBookingSystem instance;
    private final DriverService driverService = new DriverService();
    private final RideService rideService = new RideService(driverService);

    private CabBookingSystem() {}

    public static CabBookingSystem getInstance() {
        if (instance == null) {
            synchronized (CabBookingSystem.class) {
                if (instance == null) instance = new CabBookingSystem();
            }
        }
        return instance;
    }

    public void registerDriver(Driver d) { driverService.register(d); }

    public double estimateFare(Location pickup, Location drop, VehicleType type) {
        return rideService.estimateFare(pickup, drop, type);
    }

    public Ride requestRide(Rider r, Location pickup, Location drop, VehicleType type) {
        return rideService.requestRide(r, pickup, drop, type);
    }

    public void startRide(String rideId, String otp)                { rideService.startRide(rideId, otp); }
    public Ride completeRide(String rideId, PaymentStrategy ps)     { return rideService.completeRide(rideId, ps); }
    public void cancelRide(String rideId, User by)                  { rideService.cancelRide(rideId, by); }
    public void rateDriver(String rideId, int stars)                { rideService.rateDriver(rideId, stars); }
    public void rateRider(String rideId, int stars)                 { rideService.rateRider(rideId, stars); }

    public RideService getRideService() { return rideService; }

    // ===================== Demo =====================

    public static void main(String[] args) throws InterruptedException {
        CabBookingSystem system = CabBookingSystem.getInstance();
        system.getRideService().addObserver(new PushNotifier());

        Location tNagar     = new Location("T Nagar",     13.0418, 80.2341);
        Location nungam     = new Location("Nungambakkam", 13.0569, 80.2425);
        Location marina     = new Location("Marina",      13.0500, 80.2824);
        Location guindy     = new Location("Guindy",      13.0067, 80.2206);
        Location adyar      = new Location("Adyar",       13.0012, 80.2565);
        Location velachery  = new Location("Velachery",   12.9815, 80.2180);
        Location airport    = new Location("Airport",     12.9941, 80.1709);

        Driver ravi   = new Driver("Ravi",   "9000000001", new Vehicle("TN01AB1111", "WagonR", VehicleType.MINI),  guindy);
        Driver kumar  = new Driver("Kumar",  "9000000002", new Vehicle("TN01AB2222", "Swift",  VehicleType.MINI),  nungam);
        Driver suresh = new Driver("Suresh", "9000000003", new Vehicle("TN01AB3333", "Dzire",  VehicleType.SEDAN), adyar);
        List<Driver> all = new ArrayList<>(List.of(ravi, kumar, suresh));
        for (int i = 1; i <= 3; i++)
            all.add(new Driver("Biker" + i, "900000010" + i, new Vehicle("TN09BK000" + i, "Splendor", VehicleType.BIKE), velachery));
        all.forEach(d -> { system.registerDriver(d); d.goOnline(); });

        Rider alice = new Rider("Alice", "9111111111");
        Rider bob   = new Rider("Bob",   "9222222222");

        // 1. Fare estimate
        System.out.println("1. Fare estimates T Nagar -> Marina");
        for (VehicleType t : VehicleType.values())
            System.out.println("  " + String.format("%-5s", t) + " " + system.estimateFare(tNagar, marina, t));

        // 2. Request -> nearest driver
        System.out.println("\n2. Alice requests a MINI at T Nagar (Kumar 1.9 km, Ravi 4.1 km away)");
        Ride r1 = system.requestRide(alice, tNagar, marina, VehicleType.MINI);
        System.out.println("  " + r1);

        // 3. OTP check
        System.out.println("\n3. Driver enters OTP");
        String wrongOtp = "0000".equals(r1.getOtp()) ? "1111" : "0000";
        try {
            system.startRide(r1.getId(), wrongOtp);
        } catch (IllegalStateException e) {
            System.out.println("  Rejected: " + e.getMessage());
        }
        system.startRide(r1.getId(), r1.getOtp());

        // 4. Complete + payment failure retry + ratings
        System.out.println("\n4. Trip ends; wallet fails, UPI succeeds; both rate each other");
        try {
            system.completeRide(r1.getId(), new WalletPayment(50));
        } catch (IllegalStateException e) {
            System.out.println("  Rejected: " + e.getMessage() + " (ride still " + r1.getStatus() + ")");
        }
        system.completeRide(r1.getId(), new UpiPayment());
        system.rateDriver(r1.getId(), 5);
        system.rateRider(r1.getId(), 4);
        System.out.println("  " + r1);
        System.out.println("  Kumar now at " + kumar.getLocation() + ", " + kumar.getStatus() + ", rating " + kumar.getRating());

        // 5. No driver of that type
        System.out.println("\n5. Bob requests an SUV at Airport");
        try {
            system.requestRide(bob, airport, adyar, VehicleType.SUV);
        } catch (IllegalStateException e) {
            System.out.println("  Rejected: " + e.getMessage());
        }

        // 6. Driver cancels -> auto re-match; rider cancels -> driver freed
        System.out.println("\n6. Bob requests a MINI at Guindy; Ravi cancels, ride goes to the next driver");
        Ride r2 = system.requestRide(bob, guindy, adyar, VehicleType.MINI);
        system.cancelRide(r2.getId(), ravi);
        System.out.println("  " + r2);
        System.out.println("  Bob cancels too");
        system.cancelRide(r2.getId(), bob);
        System.out.println("  " + r2);
        System.out.println("  Ravi " + ravi.getStatus() + ", Kumar " + kumar.getStatus());

        // 7. Surge pricing (Decorator over the standard fare)
        System.out.println("\n7. Rain! Surge 1.5x");
        system.getRideService().setFareStrategy(new SurgeFare(new StandardFare(), 1.5));
        System.out.println("  MINI T Nagar -> Marina: " + system.estimateFare(tNagar, marina, VehicleType.MINI));
        system.getRideService().setFareStrategy(new StandardFare());

        // 8. Concurrency: 10 riders, 3 bikes -> exactly 3 rides, no bike assigned twice
        System.out.println("\n8. 10 riders request a BIKE at Velachery at the same time (3 bikes online)");
        List<Thread> threads = new ArrayList<>();
        List<String> winners = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 10; i++) {
            Rider rider = new Rider("R" + i, "98000000" + i);
            Thread t = new Thread(() -> {
                try {
                    Ride r = system.requestRide(rider, velachery, guindy, VehicleType.BIKE);
                    winners.add(rider.getName() + "->" + r.getDriver().getName());
                } catch (IllegalStateException ignored) { }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join();
        System.out.println("  Winners: " + winners + " (expected exactly 3, each biker once)");
    }
}
