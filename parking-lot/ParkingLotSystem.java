import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

// ===================== Enums =====================

enum VehicleType { BIKE, CAR, TRUCK }

// ordered small -> large; a vehicle fits its own size or bigger
enum SpotType {
    SMALL, MEDIUM, LARGE;

    static SpotType minimumFor(VehicleType v) {
        switch (v) {
            case BIKE:  return SMALL;
            case CAR:   return MEDIUM;
            default:    return LARGE;
        }
    }

    boolean fits(VehicleType v) { return this.ordinal() >= minimumFor(v).ordinal(); }
}

// ===================== Vehicle & Spot =====================

class Vehicle {
    private final String number;
    private final VehicleType type;

    Vehicle(String number, VehicleType type) {
        this.number = number;
        this.type = type;
    }

    public String getNumber()    { return number; }
    public VehicleType getType() { return type; }

    @Override public String toString() { return type + " " + number; }
}

class ParkingSpot {
    private final String id;
    private final SpotType type;
    private final int floor;
    private Vehicle vehicle;   // null = free

    ParkingSpot(String id, SpotType type, int floor) {
        this.id = id;
        this.type = type;
        this.floor = floor;
    }

    // check + occupy atomically -> two cars can never get the same spot
    public synchronized boolean tryPark(Vehicle v) {
        if (vehicle != null || !type.fits(v.getType())) return false;
        vehicle = v;
        return true;
    }

    public synchronized void free()           { vehicle = null; }
    public synchronized boolean isFree()      { return vehicle == null; }
    public synchronized Vehicle getVehicle()  { return vehicle; }

    public String getId()     { return id; }
    public SpotType getType() { return type; }
    public int getFloor()     { return floor; }

    @Override public String toString() { return id + "(" + type + ", floor " + floor + ")"; }
}

class ParkingFloor {
    private final int number;
    private final List<ParkingSpot> spots = new ArrayList<>();

    ParkingFloor(int number) { this.number = number; }

    public ParkingFloor addSpot(String id, SpotType type) {
        spots.add(new ParkingSpot(id, type, number));
        return this;
    }

    public int getNumber()             { return number; }
    public List<ParkingSpot> getSpots() { return Collections.unmodifiableList(spots); }
}

// ===================== Ticket =====================

class Ticket {
    private final String id = UUID.randomUUID().toString().substring(0, 8);
    private final Vehicle vehicle;
    private final ParkingSpot spot;
    private final LocalDateTime entryTime;
    private LocalDateTime exitTime;   // null while parked
    private long fee;

    Ticket(Vehicle vehicle, ParkingSpot spot, LocalDateTime entryTime) {
        this.vehicle = vehicle;
        this.spot = spot;
        this.entryTime = entryTime;
    }

    public void close(LocalDateTime at, long fee) {
        this.exitTime = at;
        this.fee = fee;
    }

    public boolean isActive()            { return exitTime == null; }
    public String getId()                { return id; }
    public Vehicle getVehicle()          { return vehicle; }
    public ParkingSpot getSpot()         { return spot; }
    public LocalDateTime getEntryTime()  { return entryTime; }
    public LocalDateTime getExitTime()   { return exitTime; }
    public long getFee()                 { return fee; }

    @Override public String toString() {
        return "Ticket#" + id + " " + vehicle + " at " + spot.getId() + (isActive() ? "" : " fee Rs " + fee);
    }
}

// ===================== Spot allocation (Strategy) =====================

interface SpotAllocationStrategy {
    // order the free spots that fit; the lot tries them in this order
    List<ParkingSpot> rank(List<ParkingSpot> candidates, Vehicle v);
}

// lowest floor first, then the order spots were added (closest to the entrance)
class NearestFirstStrategy implements SpotAllocationStrategy {
    public List<ParkingSpot> rank(List<ParkingSpot> candidates, Vehicle v) {
        List<ParkingSpot> out = new ArrayList<>(candidates);
        out.sort(Comparator.comparingInt(ParkingSpot::getFloor));
        return out;
    }
}

// smallest spot that fits first -> keeps big spots free for big vehicles
class BestFitStrategy implements SpotAllocationStrategy {
    public List<ParkingSpot> rank(List<ParkingSpot> candidates, Vehicle v) {
        List<ParkingSpot> out = new ArrayList<>(candidates);
        out.sort(Comparator.comparing(ParkingSpot::getType).thenComparingInt(ParkingSpot::getFloor));
        return out;
    }
}

// ===================== Fee (Strategy) =====================

interface FeeStrategy {
    long calculate(VehicleType type, Duration parked);
}

// per started hour, minimum 1 hour
class HourlyFee implements FeeStrategy {
    private final Map<VehicleType, Long> ratePerHour = new EnumMap<>(VehicleType.class);

    HourlyFee() {
        ratePerHour.put(VehicleType.BIKE, 10L);
        ratePerHour.put(VehicleType.CAR, 30L);
        ratePerHour.put(VehicleType.TRUCK, 60L);
    }

    public long calculate(VehicleType type, Duration parked) {
        long minutes = Math.max(1, parked.toMinutes());
        long hours = (minutes + 59) / 60;
        return hours * ratePerHour.get(type);
    }
}

// ===================== Extension point (Observer) =====================

// display boards, payments, alerts plug in here without changing the lot
interface ParkingListener {
    void onPark(Ticket t);
    void onUnpark(Ticket t);
}

class DisplayBoard implements ParkingListener {
    private final ParkingLotSystem lot;

    DisplayBoard(ParkingLotSystem lot) { this.lot = lot; }

    public void onPark(Ticket t)   { show(); }
    public void onUnpark(Ticket t) { show(); }

    public void show() { System.out.println("  [BOARD] free: " + lot.availability()); }
}

// ===================== Facade + Singleton =====================

public class ParkingLotSystem {
    private static volatile ParkingLotSystem instance;

    private final List<ParkingFloor> floors = new CopyOnWriteArrayList<>();
    private final Map<String, Ticket> activeTickets = new ConcurrentHashMap<>();     // ticketId -> ticket
    private final Map<String, Ticket> parkedVehicles = new ConcurrentHashMap<>();    // vehicle number -> ticket
    private final List<ParkingListener> listeners = new CopyOnWriteArrayList<>();
    private volatile SpotAllocationStrategy allocation = new BestFitStrategy();
    private volatile FeeStrategy feeStrategy = new HourlyFee();

    private ParkingLotSystem() {}

    public static ParkingLotSystem getInstance() {
        if (instance == null) {
            synchronized (ParkingLotSystem.class) {
                if (instance == null) instance = new ParkingLotSystem();
            }
        }
        return instance;
    }

    public void addFloor(ParkingFloor f) { floors.add(f); }

    public Ticket park(Vehicle v) { return park(v, LocalDateTime.now()); }

    public Ticket park(Vehicle v, LocalDateTime at) {
        Ticket placeholder = new Ticket(v, null, at);
        if (parkedVehicles.putIfAbsent(v.getNumber(), placeholder) != null)   // same vehicle can't enter twice
            throw new IllegalStateException(v + " is already parked");

        List<ParkingSpot> candidates = allSpots().stream()
                .filter(s -> s.isFree() && s.getType().fits(v.getType()))
                .collect(Collectors.toList());
        for (ParkingSpot s : allocation.rank(candidates, v)) {
            if (s.tryPark(v)) {   // may lose to another thread -> try the next spot
                Ticket t = new Ticket(v, s, at);
                activeTickets.put(t.getId(), t);
                parkedVehicles.put(v.getNumber(), t);
                listeners.forEach(l -> l.onPark(t));
                return t;
            }
        }
        parkedVehicles.remove(v.getNumber());
        throw new IllegalStateException("No free spot for " + v);
    }

    public Ticket unpark(String ticketId) { return unpark(ticketId, LocalDateTime.now()); }

    public Ticket unpark(String ticketId, LocalDateTime at) {
        Ticket t = activeTickets.remove(ticketId);
        if (t == null) throw new IllegalStateException("No active ticket " + ticketId);
        long fee = feeStrategy.calculate(t.getVehicle().getType(), Duration.between(t.getEntryTime(), at));
        t.close(at, fee);
        t.getSpot().free();
        parkedVehicles.remove(t.getVehicle().getNumber());
        listeners.forEach(l -> l.onUnpark(t));
        return t;
    }

    // free spots per spot type
    public Map<SpotType, Long> availability() {
        Map<SpotType, Long> out = new EnumMap<>(SpotType.class);
        for (SpotType st : SpotType.values()) out.put(st, 0L);
        allSpots().stream().filter(ParkingSpot::isFree).forEach(s -> out.merge(s.getType(), 1L, Long::sum));
        return out;
    }

    public long freeSpotsFor(VehicleType v) {
        return allSpots().stream().filter(s -> s.isFree() && s.getType().fits(v)).count();
    }

    public Ticket findByVehicle(String number) {
        Ticket t = parkedVehicles.get(number);
        return t == null || t.getSpot() == null ? null : t;
    }

    public void setAllocationStrategy(SpotAllocationStrategy s) { this.allocation = s; }
    public void setFeeStrategy(FeeStrategy f)                    { this.feeStrategy = f; }
    public void addListener(ParkingListener l)                   { listeners.add(l); }
    public void removeListener(ParkingListener l)                { listeners.remove(l); }

    private List<ParkingSpot> allSpots() {
        return floors.stream().flatMap(f -> f.getSpots().stream()).collect(Collectors.toList());
    }

    // ===================== Demo =====================

    private static void attempt(String label, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            System.out.println("  " + label + " rejected: " + e.getMessage());
        }
    }

    public static void main(String[] args) throws InterruptedException {
        ParkingLotSystem lot = ParkingLotSystem.getInstance();
        lot.addFloor(new ParkingFloor(0).addSpot("S1", SpotType.SMALL).addSpot("M1", SpotType.MEDIUM)
                .addSpot("M2", SpotType.MEDIUM).addSpot("L1", SpotType.LARGE));
        lot.addFloor(new ParkingFloor(1).addSpot("S2", SpotType.SMALL).addSpot("M3", SpotType.MEDIUM)
                .addSpot("M4", SpotType.MEDIUM));
        DisplayBoard board = new DisplayBoard(lot);
        LocalDateTime nine = LocalDateTime.of(2026, 10, 21, 9, 0);

        // 1. Park each vehicle type
        System.out.println("1. Park a bike, a car and a truck (best fit)");
        board.show();
        lot.addListener(board);
        Ticket bike = lot.park(new Vehicle("TN01-B1", VehicleType.BIKE), nine);
        Ticket car = lot.park(new Vehicle("TN01-C1", VehicleType.CAR), nine);
        Ticket truck = lot.park(new Vehicle("TN01-T1", VehicleType.TRUCK), nine);
        System.out.println("  " + bike + "\n  " + car + "\n  " + truck);

        // 2. Full / duplicate
        System.out.println("\n2. Second truck (only 1 large spot); same car again");
        attempt("Truck TN01-T2", () -> lot.park(new Vehicle("TN01-T2", VehicleType.TRUCK), nine));
        attempt("Car TN01-C1 again", () -> lot.park(new Vehicle("TN01-C1", VehicleType.CAR), nine));

        // 3. Strategy switch
        System.out.println("\n3. Nearest-first puts bike #2 in medium M2 on floor 0; best fit puts bike #3 in small S2 on floor 1");
        lot.setAllocationStrategy(new NearestFirstStrategy());
        System.out.println("  " + lot.park(new Vehicle("TN01-B2", VehicleType.BIKE), nine));
        lot.setAllocationStrategy(new BestFitStrategy());
        System.out.println("  " + lot.park(new Vehicle("TN01-B3", VehicleType.BIKE), nine));

        // 4. Find + unpark with fee
        System.out.println("\n4. Where is TN01-C1? " + lot.findByVehicle("TN01-C1").getSpot());
        System.out.println("  Car leaves after 2h30m (3 started hours x Rs 30)");
        Ticket paid = lot.unpark(car.getId(), nine.plusHours(2).plusMinutes(30));
        System.out.println("  " + paid);
        attempt("Same ticket again", () -> lot.unpark(car.getId(), nine.plusHours(3)));

        lot.removeListener(board);   // keep the stress test quiet

        // 5. Concurrency: more cars than spots -> no spot given twice
        long free = lot.freeSpotsFor(VehicleType.CAR);
        System.out.println("\n5. 20 cars arrive at once; " + free + " spots fit a car");
        List<Ticket> tickets = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Vehicle v = new Vehicle("KA05-" + i, VehicleType.CAR);
            Thread t = new Thread(() -> {
                try {
                    tickets.add(lot.park(v, nine));
                } catch (IllegalStateException ignored) { }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join();
        long distinct = tickets.stream().map(Ticket::getSpot).distinct().count();
        System.out.println("  Parked: " + tickets.size() + " (expected " + free + "), distinct spots: " + distinct);
        System.out.println("  Free now: " + lot.availability());
    }
}
