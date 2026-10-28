import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

// ===================== Enums =====================

enum PartnerStatus { OFFLINE, AVAILABLE, BUSY }

enum PaymentStatus { PENDING, SUCCESS, FAILED, REFUNDED }

// Order lifecycle; allowed moves are defined in one place
enum OrderStatus {
    PLACED, ACCEPTED, PREPARING, READY_FOR_PICKUP, PICKED_UP, DELIVERED, CANCELLED;

    boolean canMoveTo(OrderStatus next) {
        switch (this) {
            case PLACED:           return next == ACCEPTED || next == CANCELLED;
            case ACCEPTED:         return next == PREPARING || next == CANCELLED;
            case PREPARING:        return next == READY_FOR_PICKUP;
            case READY_FOR_PICKUP: return next == PICKED_UP;
            case PICKED_UP:        return next == DELIVERED;
            default:               return false;   // DELIVERED, CANCELLED are final
        }
    }
}

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
        double dLat = Math.toRadians(o.lat - lat);
        double dLng = Math.toRadians(o.lng - lng);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(o.lat))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public String getName() { return name; }

    @Override public String toString() { return name; }
}

// ===================== Users =====================

abstract class User {
    private final String id = UUID.randomUUID().toString();
    private final String name;
    private final String phone;

    User(String name, String phone) {
        this.name = name;
        this.phone = phone;
    }

    public String getId()    { return id; }
    public String getName()  { return name; }
    public String getPhone() { return phone; }

    @Override public String toString() { return name; }
}

class Customer extends User {
    private final Location address;
    private final Cart cart = new Cart();
    private final List<Order> orders = new CopyOnWriteArrayList<>();

    Customer(String name, String phone, Location address) {
        super(name, phone);
        this.address = address;
    }

    public void addOrder(Order o)     { orders.add(o); }
    public List<Order> getOrders()    { return new ArrayList<>(orders); }
    public Location getAddress()      { return address; }
    public Cart getCart()             { return cart; }
}

class DeliveryPartner extends User {
    private volatile Location location;
    private PartnerStatus status = PartnerStatus.OFFLINE;
    private int deliveriesToday;

    DeliveryPartner(String name, String phone, Location location) {
        super(name, phone);
        this.location = location;
    }

    public synchronized void goOnline()  { if (status == PartnerStatus.OFFLINE) status = PartnerStatus.AVAILABLE; }

    public synchronized void goOffline() {
        if (status == PartnerStatus.BUSY) throw new IllegalStateException("Finish the delivery first");
        status = PartnerStatus.OFFLINE;
    }

    // check + reserve atomically -> a partner never gets two orders at once
    public synchronized boolean tryAssign() {
        if (status != PartnerStatus.AVAILABLE) return false;
        status = PartnerStatus.BUSY;
        return true;
    }

    public synchronized void release() { if (status == PartnerStatus.BUSY) status = PartnerStatus.AVAILABLE; }

    public synchronized void completeDelivery(Location at) {
        location = at;
        deliveriesToday++;
        release();
    }

    public synchronized boolean isAvailable()     { return status == PartnerStatus.AVAILABLE; }
    public synchronized PartnerStatus getStatus() { return status; }
    public synchronized int getDeliveriesToday()  { return deliveriesToday; }
    public Location getLocation()                 { return location; }
}

// ===================== Restaurant & Menu =====================

class MenuItem {
    public static final int UNLIMITED = Integer.MAX_VALUE;

    private final String id;
    private final String name;
    private final double price;
    private final boolean veg;
    private int stock;
    private boolean available = true;

    MenuItem(String id, String name, double price, boolean veg, int stock) {
        this.id = id;
        this.name = name;
        this.price = price;
        this.veg = veg;
        this.stock = stock;
    }

    // check + decrement atomically -> limited portions are never oversold
    public synchronized boolean tryReserve(int qty) {
        if (!available || stock < qty) return false;
        if (stock != UNLIMITED) stock -= qty;
        return true;
    }

    public synchronized void release(int qty) { if (stock != UNLIMITED) stock += qty; }

    public synchronized void setAvailable(boolean a) { this.available = a; }
    public synchronized boolean isAvailable()        { return available && stock > 0; }
    public synchronized int getStock()               { return stock; }

    public String getId()     { return id; }
    public String getName()   { return name; }
    public double getPrice()  { return price; }
    public boolean isVeg()    { return veg; }

    @Override public String toString() { return name + " Rs" + (int) price; }
}

class Restaurant {
    private final String id = UUID.randomUUID().toString().substring(0, 8);
    private final String name;
    private final String cuisine;
    private final Location location;
    private final Map<String, MenuItem> menu = new LinkedHashMap<>();
    private volatile boolean open = true;

    Restaurant(String name, String cuisine, Location location) {
        this.name = name;
        this.cuisine = cuisine;
        this.location = location;
    }

    public void addMenuItem(MenuItem item) { menu.put(item.getId(), item); }

    public MenuItem getMenuItem(String itemId) {
        MenuItem m = menu.get(itemId);
        if (m == null) throw new IllegalArgumentException("No item " + itemId + " at " + name);
        return m;
    }

    public List<MenuItem> getMenu() { return new ArrayList<>(menu.values()); }

    public void setOpen(boolean open) { this.open = open; }
    public boolean isOpen()           { return open; }
    public String getId()             { return id; }
    public String getName()           { return name; }
    public String getCuisine()        { return cuisine; }
    public Location getLocation()     { return location; }

    @Override public String toString() { return name; }
}

// ===================== Cart =====================

// a cart holds items from ONE restaurant only
class Cart {
    private Restaurant restaurant;
    private final Map<MenuItem, Integer> items = new LinkedHashMap<>();

    public synchronized void add(Restaurant r, MenuItem item, int qty) {
        if (qty <= 0) throw new IllegalArgumentException("Quantity must be positive");
        if (restaurant != null && restaurant != r)
            throw new IllegalStateException("Cart has items from " + restaurant + ". Clear it to order from " + r);
        restaurant = r;
        items.merge(item, qty, Integer::sum);
    }

    public synchronized void remove(MenuItem item) {
        items.remove(item);
        if (items.isEmpty()) restaurant = null;
    }

    public synchronized void clear() {
        items.clear();
        restaurant = null;
    }

    public synchronized boolean isEmpty()              { return items.isEmpty(); }
    public synchronized Restaurant getRestaurant()     { return restaurant; }
    public synchronized Map<MenuItem, Integer> getItems() { return new LinkedHashMap<>(items); }
}

// ===================== Order =====================

// snapshot of name + price at order time (menu price changes later don't affect it)
class OrderItem {
    private final String name;
    private final double price;
    private final int qty;

    OrderItem(String name, double price, int qty) {
        this.name = name;
        this.price = price;
        this.qty = qty;
    }

    public double total() { return price * qty; }

    @Override public String toString() { return name + " x" + qty; }
}

class Bill {
    private final double subtotal;
    private final double deliveryFee;
    private final double tax;
    private final double discount;
    private final double total;

    Bill(double subtotal, double deliveryFee, double tax, double discount) {
        this.subtotal = subtotal;
        this.deliveryFee = deliveryFee;
        this.tax = tax;
        this.discount = discount;
        this.total = Money.round(subtotal + deliveryFee + tax - discount);
    }

    public double getTotal() { return total; }

    @Override public String toString() {
        return "items " + subtotal + " + delivery " + deliveryFee + " + tax " + tax
                + (discount > 0 ? " - coupon " + discount : "") + " = Rs " + total;
    }
}

class Order {
    private final String id = UUID.randomUUID().toString().substring(0, 8);
    private final Customer customer;
    private final Restaurant restaurant;
    private final List<OrderItem> items;
    private final Map<MenuItem, Integer> reserved;   // to give stock back on cancel
    private final Location deliveryAddress;
    private final Bill bill;
    private final LocalDateTime placedAt = LocalDateTime.now();
    private OrderStatus status = OrderStatus.PLACED;
    private DeliveryPartner partner;
    private Payment payment;

    Order(Customer customer, Restaurant restaurant, Map<MenuItem, Integer> cartItems, Location address, Bill bill) {
        this.customer = customer;
        this.restaurant = restaurant;
        this.reserved = new LinkedHashMap<>(cartItems);
        this.items = cartItems.entrySet().stream()
                .map(e -> new OrderItem(e.getKey().getName(), e.getKey().getPrice(), e.getValue()))
                .collect(Collectors.toList());
        this.deliveryAddress = address;
        this.bill = bill;
    }

    public synchronized void moveTo(OrderStatus next) {
        if (!status.canMoveTo(next)) throw new IllegalStateException("Order " + id + " cannot go from " + status + " to " + next);
        status = next;
    }

    public synchronized boolean assignPartner(DeliveryPartner p) {
        if (status == OrderStatus.CANCELLED || partner != null) return false;
        partner = p;
        return true;
    }

    public void setPayment(Payment p)               { this.payment = p; }

    public String getId()                           { return id; }
    public Customer getCustomer()                   { return customer; }
    public Restaurant getRestaurant()               { return restaurant; }
    public List<OrderItem> getItems()               { return items; }
    public Map<MenuItem, Integer> getReserved()     { return reserved; }
    public Location getDeliveryAddress()            { return deliveryAddress; }
    public Bill getBill()                           { return bill; }
    public Payment getPayment()                     { return payment; }
    public LocalDateTime getPlacedAt()              { return placedAt; }
    public synchronized OrderStatus getStatus()     { return status; }
    public synchronized DeliveryPartner getPartner() { return partner; }

    @Override public synchronized String toString() {
        return "Order#" + id + " " + customer + " <- " + restaurant + " " + items + " Rs " + bill.getTotal()
                + " partner=" + (partner == null ? "-" : partner.getName()) + " " + status;
    }
}

class Money {
    static double round(double x) { return Math.round(x * 100) / 100.0; }
}

// ===================== Payment (Strategy) =====================

interface PaymentStrategy {
    boolean pay(double amount);
    boolean refund(double amount);
}

class UpiPayment implements PaymentStrategy {
    public boolean pay(double amount)    { System.out.println("  Paid " + amount + " via UPI");    return true; }
    public boolean refund(double amount) { System.out.println("  Refunded " + amount + " to UPI");  return true; }
}

class CardPayment implements PaymentStrategy {
    public boolean pay(double amount)    { System.out.println("  Paid " + amount + " via Card");   return true; }
    public boolean refund(double amount) { System.out.println("  Refunded " + amount + " to Card"); return true; }
}

class WalletPayment implements PaymentStrategy {
    private double balance;

    WalletPayment(double balance) { this.balance = balance; }

    public synchronized boolean pay(double amount) {
        if (amount > balance) { System.out.println("  Wallet: insufficient balance"); return false; }
        balance -= amount;
        return true;
    }

    public synchronized boolean refund(double amount) {
        balance += amount;
        return true;
    }
}

// pay at the door; refund isn't needed because nothing was collected yet
class CashOnDelivery implements PaymentStrategy {
    public boolean pay(double amount)    { System.out.println("  Cash on delivery: Rs " + amount); return true; }
    public boolean refund(double amount) { return true; }
}

class Payment {
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

    public void refund() {
        if (status == PaymentStatus.SUCCESS && strategy.refund(amount)) status = PaymentStatus.REFUNDED;
    }

    public PaymentStatus getStatus() { return status; }
}

// ===================== Delivery fee (Strategy + Decorator) =====================

interface DeliveryFeeStrategy {
    double calculate(double distanceKm, double subtotal);
}

class StandardDeliveryFee implements DeliveryFeeStrategy {
    public double calculate(double distanceKm, double subtotal) {
        return Money.round(20 + 5 * distanceKm);
    }
}

// wraps another fee rule: free delivery for big orders
class FreeDeliveryAbove implements DeliveryFeeStrategy {
    private final double threshold;
    private final DeliveryFeeStrategy base;

    FreeDeliveryAbove(double threshold, DeliveryFeeStrategy base) {
        this.threshold = threshold;
        this.base = base;
    }

    public double calculate(double distanceKm, double subtotal) {
        return subtotal >= threshold ? 0 : base.calculate(distanceKm, subtotal);
    }
}

// ===================== Coupons (Strategy) =====================

interface Coupon {
    String getCode();
    double discount(double subtotal);   // throws if not applicable
}

class FlatCoupon implements Coupon {
    private final String code;
    private final double amount;
    private final double minOrder;

    FlatCoupon(String code, double amount, double minOrder) {
        this.code = code;
        this.amount = amount;
        this.minOrder = minOrder;
    }

    public String getCode() { return code; }

    public double discount(double subtotal) {
        if (subtotal < minOrder) throw new IllegalStateException(code + " needs a minimum order of Rs " + (int) minOrder);
        return Math.min(amount, subtotal);
    }
}

class PercentCoupon implements Coupon {
    private final String code;
    private final double percent;
    private final double maxDiscount;
    private final double minOrder;

    PercentCoupon(String code, double percent, double maxDiscount, double minOrder) {
        this.code = code;
        this.percent = percent;
        this.maxDiscount = maxDiscount;
        this.minOrder = minOrder;
    }

    public String getCode() { return code; }

    public double discount(double subtotal) {
        if (subtotal < minOrder) throw new IllegalStateException(code + " needs a minimum order of Rs " + (int) minOrder);
        return Money.round(Math.min(subtotal * percent / 100, maxDiscount));
    }
}

// ===================== Partner assignment (Strategy) =====================

interface PartnerAssignmentStrategy {
    // order available partners by preference; DeliveryService tries them in this order
    List<DeliveryPartner> rank(List<DeliveryPartner> partners, Location restaurant);
}

class NearestPartnerStrategy implements PartnerAssignmentStrategy {
    public List<DeliveryPartner> rank(List<DeliveryPartner> partners, Location restaurant) {
        return partners.stream()
                .sorted(Comparator.comparingDouble(p -> p.getLocation().distanceKm(restaurant)))
                .collect(Collectors.toList());
    }
}

// spreads work: fewest deliveries today first, then nearest
class FairDistributionStrategy implements PartnerAssignmentStrategy {
    public List<DeliveryPartner> rank(List<DeliveryPartner> partners, Location restaurant) {
        return partners.stream()
                .sorted(Comparator.comparingInt(DeliveryPartner::getDeliveriesToday)
                        .thenComparingDouble(p -> p.getLocation().distanceKm(restaurant)))
                .collect(Collectors.toList());
    }
}

// ===================== Notification (Observer) =====================

interface NotificationObserver {
    void notify(String recipient, String message);
}

class PushNotifier implements NotificationObserver {
    public void notify(String recipient, String message) {
        System.out.println("  [PUSH to " + recipient + "] " + message);
    }
}

class SmsNotifier implements NotificationObserver {
    public void notify(String recipient, String message) {
        System.out.println("  [SMS to " + recipient + "] " + message);
    }
}

class NotificationService {
    private final List<NotificationObserver> observers = new CopyOnWriteArrayList<>();

    public void addObserver(NotificationObserver o)    { observers.add(o); }
    public void removeObserver(NotificationObserver o) { observers.remove(o); }
    public void notify(String to, String msg)          { observers.forEach(o -> o.notify(to, msg)); }
}

// ===================== Services =====================

class RestaurantService {
    private final Map<String, Restaurant> restaurants = new ConcurrentHashMap<>();

    public void addRestaurant(Restaurant r) { restaurants.put(r.getId(), r); }

    // open restaurants within radius, nearest first
    public List<Restaurant> search(Location near, double radiusKm) {
        return restaurants.values().stream()
                .filter(Restaurant::isOpen)
                .filter(r -> r.getLocation().distanceKm(near) <= radiusKm)
                .sorted(Comparator.comparingDouble(r -> r.getLocation().distanceKm(near)))
                .collect(Collectors.toList());
    }
}

class DeliveryService {
    private final List<DeliveryPartner> partners = new CopyOnWriteArrayList<>();
    private final Deque<Order> waitingForPartner = new ArrayDeque<>();
    private final NotificationService notifications;
    private PartnerAssignmentStrategy strategy = new NearestPartnerStrategy();
    private double searchRadiusKm = 10;

    DeliveryService(NotificationService notifications) { this.notifications = notifications; }

    public void register(DeliveryPartner p) { partners.add(p); }

    // try ranked partners; nobody free -> wait in FIFO queue
    public synchronized void assign(Order order) {
        Location r = order.getRestaurant().getLocation();
        List<DeliveryPartner> nearby = partners.stream()
                .filter(DeliveryPartner::isAvailable)
                .filter(p -> p.getLocation().distanceKm(r) <= searchRadiusKm)
                .collect(Collectors.toList());
        for (DeliveryPartner p : strategy.rank(nearby, r)) {
            if (tryGive(order, p)) return;
        }
        waitingForPartner.addLast(order);
        notifications.notify(order.getCustomer().getName(), "Order " + order.getId() + ": looking for a delivery partner...");
    }

    // a partner just became free: give them the oldest waiting order
    public synchronized void onPartnerAvailable(DeliveryPartner p) {
        while (!waitingForPartner.isEmpty() && p.isAvailable()) {
            Order next = waitingForPartner.pollFirst();
            if (next.getStatus() == OrderStatus.CANCELLED) continue;
            tryGive(next, p);
        }
    }

    public synchronized void removeWaiting(Order o) { waitingForPartner.remove(o); }
    public synchronized int waitingCount()          { return waitingForPartner.size(); }

    private boolean tryGive(Order order, DeliveryPartner p) {
        if (!p.tryAssign()) return false;
        if (!order.assignPartner(p)) {   // cancelled meanwhile
            p.release();
            return false;
        }
        notifications.notify(p.getName(), "Pick up order " + order.getId() + " at " + order.getRestaurant());
        notifications.notify(order.getCustomer().getName(), p.getName() + " will deliver order " + order.getId());
        return true;
    }

    public void setStrategy(PartnerAssignmentStrategy s) { this.strategy = s; }
}

class OrderService {
    private static final double TAX_RATE = 0.05;

    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final Map<String, Coupon> coupons = new ConcurrentHashMap<>();
    private final DeliveryService deliveryService;
    private final NotificationService notifications;
    private DeliveryFeeStrategy feeStrategy = new StandardDeliveryFee();

    OrderService(DeliveryService deliveryService, NotificationService notifications) {
        this.deliveryService = deliveryService;
        this.notifications = notifications;
    }

    public void addCoupon(Coupon c) { coupons.put(c.getCode(), c); }

    public Order placeOrder(Customer c, String couponCode, PaymentStrategy ps) {
        Cart cart = c.getCart();
        Order order;
        synchronized (cart) {   // same customer can't place the same cart twice
            if (cart.isEmpty()) throw new IllegalStateException("Cart is empty");
            Restaurant r = cart.getRestaurant();
            Map<MenuItem, Integer> items = cart.getItems();
            if (!r.isOpen()) throw new IllegalStateException(r + " is closed right now");
            Bill bill = buildBill(c, r, items, couponCode);   // validate coupon before touching stock
            reserveAll(items);

            order = new Order(c, r, items, c.getAddress(), bill);
            Payment payment = new Payment(bill.getTotal(), ps);
            order.setPayment(payment);
            if (!payment.process()) {
                releaseAll(items);   // cart is kept so the customer can retry
                throw new IllegalStateException("Payment failed, order not placed");
            }
            cart.clear();
        }
        Restaurant r = order.getRestaurant();
        Bill bill = order.getBill();
        orders.put(order.getId(), order);
        c.addOrder(order);
        notifications.notify(r.getName(), "New order " + order.getId() + ": " + order.getItems());
        notifications.notify(c.getName(), "Order " + order.getId() + " placed. " + bill);
        return order;
    }

    // ----- restaurant actions -----

    public void accept(String orderId) {
        Order o = get(orderId);
        o.moveTo(OrderStatus.ACCEPTED);
        notifications.notify(o.getCustomer().getName(), o.getRestaurant() + " accepted order " + o.getId());
        deliveryService.assign(o);   // send a partner now so they arrive when food is ready
    }

    public void reject(String orderId, String reason) {
        Order o = get(orderId);
        if (o.getStatus() != OrderStatus.PLACED) throw new IllegalStateException("Can only reject a new order");
        cancel(o, o.getRestaurant() + " rejected order " + o.getId() + ": " + reason);
    }

    public void startPreparing(String orderId) { get(orderId).moveTo(OrderStatus.PREPARING); }

    public void markReady(String orderId) {
        Order o = get(orderId);
        o.moveTo(OrderStatus.READY_FOR_PICKUP);
        if (o.getPartner() != null) notifications.notify(o.getPartner().getName(), "Order " + o.getId() + " is ready");
    }

    // ----- partner actions -----

    public void pickUp(DeliveryPartner p, String orderId) {
        Order o = get(orderId);
        requirePartner(o, p);
        o.moveTo(OrderStatus.PICKED_UP);
        notifications.notify(o.getCustomer().getName(), p.getName() + " picked up your order, on the way");
    }

    public void deliver(DeliveryPartner p, String orderId) {
        Order o = get(orderId);
        requirePartner(o, p);
        o.moveTo(OrderStatus.DELIVERED);
        p.completeDelivery(o.getDeliveryAddress());
        notifications.notify(o.getCustomer().getName(), "Order " + o.getId() + " delivered. Enjoy!");
        deliveryService.onPartnerAvailable(p);
    }

    // ----- customer actions -----

    // allowed only before the kitchen starts cooking
    public void cancelByCustomer(Customer c, String orderId) {
        Order o = get(orderId);
        if (o.getCustomer() != c) throw new IllegalStateException("Not your order");
        cancel(o, c + " cancelled order " + o.getId());
    }

    private void cancel(Order o, String message) {
        o.moveTo(OrderStatus.CANCELLED);   // throws if already PREPARING or later
        releaseAll(o.getReserved());
        o.getPayment().refund();
        deliveryService.removeWaiting(o);
        DeliveryPartner p = o.getPartner();
        if (p != null) {
            p.release();
            notifications.notify(p.getName(), "Order " + o.getId() + " cancelled, you're free");
            deliveryService.onPartnerAvailable(p);
        }
        notifications.notify(o.getCustomer().getName(), message);
    }

    private Bill buildBill(Customer c, Restaurant r, Map<MenuItem, Integer> items, String couponCode) {
        double subtotal = Money.round(items.entrySet().stream().mapToDouble(e -> e.getKey().getPrice() * e.getValue()).sum());
        double fee = feeStrategy.calculate(r.getLocation().distanceKm(c.getAddress()), subtotal);
        double tax = Money.round(subtotal * TAX_RATE);
        double discount = 0;
        if (couponCode != null) {
            Coupon coupon = coupons.get(couponCode);
            if (coupon == null) throw new IllegalArgumentException("Invalid coupon " + couponCode);
            discount = coupon.discount(subtotal);
        }
        return new Bill(subtotal, fee, tax, discount);
    }

    // all-or-nothing: if any item runs out, give back what was already taken
    private void reserveAll(Map<MenuItem, Integer> items) {
        Map<MenuItem, Integer> done = new LinkedHashMap<>();
        for (Map.Entry<MenuItem, Integer> e : items.entrySet()) {
            if (!e.getKey().tryReserve(e.getValue())) {
                releaseAll(done);
                throw new IllegalStateException(e.getKey().getName() + " is sold out");
            }
            done.put(e.getKey(), e.getValue());
        }
    }

    private void releaseAll(Map<MenuItem, Integer> items) { items.forEach(MenuItem::release); }

    private void requirePartner(Order o, DeliveryPartner p) {
        if (o.getPartner() != p) throw new IllegalStateException(p + " is not assigned to order " + o.getId());
    }

    private Order get(String id) {
        Order o = orders.get(id);
        if (o == null) throw new IllegalArgumentException("No order " + id);
        return o;
    }

    public void setFeeStrategy(DeliveryFeeStrategy s) { this.feeStrategy = s; }
}

// ===================== Facade + Singleton =====================

public class FoodDeliverySystem {
    private static volatile FoodDeliverySystem instance;

    private final NotificationService notifications = new NotificationService();
    private final RestaurantService restaurantService = new RestaurantService();
    private final DeliveryService deliveryService = new DeliveryService(notifications);
    private final OrderService orderService = new OrderService(deliveryService, notifications);

    private FoodDeliverySystem() {}

    public static FoodDeliverySystem getInstance() {
        if (instance == null) {
            synchronized (FoodDeliverySystem.class) {
                if (instance == null) instance = new FoodDeliverySystem();
            }
        }
        return instance;
    }

    public void addRestaurant(Restaurant r) { restaurantService.addRestaurant(r); }

    public void registerPartner(DeliveryPartner p) {
        deliveryService.register(p);
        p.goOnline();
        deliveryService.onPartnerAvailable(p);
    }

    public List<Restaurant> searchRestaurants(Location near, double radiusKm) { return restaurantService.search(near, radiusKm); }

    public void addToCart(Customer c, Restaurant r, String itemId, int qty) {
        MenuItem item = r.getMenuItem(itemId);
        if (!item.isAvailable()) throw new IllegalStateException(item.getName() + " is not available");
        c.getCart().add(r, item, qty);
    }

    public Order placeOrder(Customer c, String coupon, PaymentStrategy ps) { return orderService.placeOrder(c, coupon, ps); }
    public void cancelOrder(Customer c, String orderId)                    { orderService.cancelByCustomer(c, orderId); }

    public void acceptOrder(String orderId)                  { orderService.accept(orderId); }
    public void rejectOrder(String orderId, String reason)   { orderService.reject(orderId, reason); }
    public void startPreparing(String orderId)               { orderService.startPreparing(orderId); }
    public void markReady(String orderId)                    { orderService.markReady(orderId); }

    public void pickUp(DeliveryPartner p, String orderId)    { orderService.pickUp(p, orderId); }
    public void deliver(DeliveryPartner p, String orderId)   { orderService.deliver(p, orderId); }

    public OrderService getOrderService()             { return orderService; }
    public DeliveryService getDeliveryService()       { return deliveryService; }
    public NotificationService getNotifications()     { return notifications; }

    // ===================== Demo =====================

    private static void attempt(String label, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            System.out.println("  " + label + " rejected: " + e.getMessage());
        }
    }

    public static void main(String[] args) throws InterruptedException {
        FoodDeliverySystem app = FoodDeliverySystem.getInstance();
        PushNotifier push = new PushNotifier();
        app.getNotifications().addObserver(push);

        Location tNagar  = new Location("T Nagar",      13.0418, 80.2341);
        Location nungam  = new Location("Nungambakkam", 13.0569, 80.2425);
        Location adyar   = new Location("Adyar",        13.0012, 80.2565);
        Location guindy  = new Location("Guindy",       13.0067, 80.2206);
        Location airport = new Location("Airport",      12.9941, 80.1709);

        Restaurant a2b = new Restaurant("A2B", "South Indian", tNagar);
        a2b.addMenuItem(new MenuItem("DOSA", "Masala Dosa", 90, true, MenuItem.UNLIMITED));
        a2b.addMenuItem(new MenuItem("COFFEE", "Filter Coffee", 40, true, MenuItem.UNLIMITED));
        a2b.addMenuItem(new MenuItem("MEALS", "Mini Meals", 150, true, MenuItem.UNLIMITED));

        Restaurant biryani = new Restaurant("Biryani House", "Mughlai", nungam);
        biryani.addMenuItem(new MenuItem("CHB", "Chicken Biryani", 250, false, 3));   // only 3 portions left
        biryani.addMenuItem(new MenuItem("MTB", "Mutton Biryani", 350, false, MenuItem.UNLIMITED));
        biryani.addMenuItem(new MenuItem("RAITA", "Raita", 40, true, MenuItem.UNLIMITED));

        Restaurant pizza = new Restaurant("Airport Pizza", "Italian", airport);
        pizza.addMenuItem(new MenuItem("MARG", "Margherita", 300, true, MenuItem.UNLIMITED));
        for (Restaurant r : List.of(a2b, biryani, pizza)) app.addRestaurant(r);

        app.getOrderService().addCoupon(new FlatCoupon("WELCOME50", 50, 200));
        app.getOrderService().addCoupon(new PercentCoupon("FEAST20", 20, 120, 500));

        DeliveryPartner ravi  = new DeliveryPartner("Ravi",  "9000000001", guindy);
        DeliveryPartner kumar = new DeliveryPartner("Kumar", "9000000002", tNagar);
        app.registerPartner(ravi);
        app.registerPartner(kumar);

        Customer alice = new Customer("Alice", "9111111111", adyar);
        Customer bob   = new Customer("Bob",   "9222222222", adyar);

        // 1. Search
        System.out.println("1. Restaurants within 7 km of Adyar: " + app.searchRestaurants(adyar, 7));

        // 2. Cart: one restaurant only
        System.out.println("\n2. Alice adds 2 Masala Dosa + 2 Filter Coffee from A2B, then tries Biryani House");
        app.addToCart(alice, a2b, "DOSA", 2);
        app.addToCart(alice, a2b, "COFFEE", 2);
        attempt("Add biryani", () -> app.addToCart(alice, biryani, "MTB", 1));

        // 3. Place order with coupon
        System.out.println("\n3. Alice places the order with WELCOME50");
        Order o1 = app.placeOrder(alice, "WELCOME50", new UpiPayment());

        // 4. Full lifecycle
        System.out.println("\n4. A2B accepts -> nearest partner assigned -> cook -> pick up -> deliver");
        app.acceptOrder(o1.getId());
        attempt("Pick up before ready", () -> app.pickUp(kumar, o1.getId()));
        app.startPreparing(o1.getId());
        attempt("Customer cancels while cooking", () -> app.cancelOrder(alice, o1.getId()));
        app.markReady(o1.getId());
        attempt("Ravi picks up Kumar's order", () -> app.pickUp(ravi, o1.getId()));
        app.pickUp(kumar, o1.getId());
        app.deliver(kumar, o1.getId());
        System.out.println("  " + o1);
        System.out.println("  Kumar is now at " + kumar.getLocation() + ", " + kumar.getStatus());

        // 5. Coupon rules + free delivery decorator
        System.out.println("\n5. Bob: FEAST20 needs Rs 500; free delivery above Rs 300 is switched on");
        app.getOrderService().setFeeStrategy(new FreeDeliveryAbove(300, new StandardDeliveryFee()));
        app.addToCart(bob, biryani, "MTB", 1);
        attempt("FEAST20 on Rs 350", () -> app.placeOrder(bob, "FEAST20", new CardPayment()));
        Order o2 = app.placeOrder(bob, null, new CardPayment());
        app.getOrderService().setFeeStrategy(new StandardDeliveryFee());

        // 6. Restaurant rejects -> refund
        System.out.println("\n6. Biryani House rejects Bob's order");
        app.rejectOrder(o2.getId(), "kitchen closing");
        System.out.println("  " + o2);

        // 7. Customer cancels after accept -> partner freed
        System.out.println("\n7. Alice orders Mini Meals, A2B accepts, Alice cancels before cooking starts");
        app.addToCart(alice, a2b, "MEALS", 1);
        Order o3 = app.placeOrder(alice, null, new UpiPayment());
        app.acceptOrder(o3.getId());
        app.cancelOrder(alice, o3.getId());
        System.out.println("  " + o3);
        System.out.println("  Partners: Ravi " + ravi.getStatus() + ", Kumar " + kumar.getStatus());

        // 8. Payment failure / closed restaurant
        System.out.println("\n8. Payment fails; restaurant closed");
        app.addToCart(bob, biryani, "CHB", 1);
        attempt("Wallet with Rs 10", () -> app.placeOrder(bob, null, new WalletPayment(10)));
        System.out.println("  Chicken Biryani portions still left: " + biryani.getMenuItem("CHB").getStock()
                + " (expected 3), Bob's cart kept: " + !bob.getCart().isEmpty());
        biryani.setOpen(false);
        attempt("Order from closed restaurant", () -> app.placeOrder(bob, null, new CashOnDelivery()));
        bob.getCart().clear();
        biryani.setOpen(true);

        app.getNotifications().removeObserver(push);   // keep the stress test quiet

        // 9. Concurrency: last 3 portions, then 3 orders for 2 partners
        System.out.println("\n9. 10 customers order the last 3 Chicken Biryanis at once");
        List<Order> placed = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Customer c = new Customer("C" + i, "98000000" + i, adyar);
            Thread t = new Thread(() -> {
                try {
                    app.addToCart(c, biryani, "CHB", 1);
                    placed.add(app.placeOrder(c, null, new CashOnDelivery()));
                } catch (IllegalStateException ignored) { }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join();
        System.out.println("  Orders placed: " + placed.size() + " (expected 3), portions left: " + biryani.getMenuItem("CHB").getStock());

        System.out.println("  Restaurant accepts all 3 at once; only 2 partners online");
        threads.clear();
        for (Order o : placed) {
            Thread t = new Thread(() -> app.acceptOrder(o.getId()));
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join();
        long assigned = placed.stream().filter(o -> o.getPartner() != null).count();
        System.out.println("  Assigned: " + assigned + " (expected 2), waiting: " + app.getDeliveryService().waitingCount() + " (expected 1)");
        Set<DeliveryPartner> distinct = placed.stream().map(Order::getPartner).filter(Objects::nonNull).collect(Collectors.toSet());
        System.out.println("  Distinct partners: " + distinct.size() + " (expected 2, nobody has two orders)");

        Order first = placed.stream().filter(o -> o.getPartner() != null).findFirst().get();
        DeliveryPartner p = first.getPartner();
        app.startPreparing(first.getId());
        app.markReady(first.getId());
        app.pickUp(p, first.getId());
        app.deliver(p, first.getId());
        Order waiting = placed.stream().filter(o -> o != first && o.getPartner() == p).findFirst().orElse(null);
        System.out.println("  " + p.getName() + " delivered and was auto-assigned the waiting order: " + (waiting != null)
                + ", still waiting: " + app.getDeliveryService().waitingCount());
    }
}
