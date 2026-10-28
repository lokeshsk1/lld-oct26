import java.time.LocalDateTime;
import java.util.*;

/**
 * Skeleton for the Food Delivery System (Swiggy / Zomato) LLD: classes, fields and method signatures only.
 * Every class is nested here so this file can sit next to FoodDeliverySystem.java
 * without duplicate-class clashes. Getters/setters omitted.
 */
public class FoodDeliverySkeleton {

    // ===================== Enums =====================

    enum PartnerStatus { OFFLINE, AVAILABLE, BUSY }

    enum PaymentStatus { PENDING, SUCCESS, FAILED, REFUNDED }

    enum OrderStatus {
        PLACED, ACCEPTED, PREPARING, READY_FOR_PICKUP, PICKED_UP, DELIVERED, CANCELLED;

        boolean canMoveTo(OrderStatus next) {
            // TODO: PLACED -> ACCEPTED/CANCELLED, ACCEPTED -> PREPARING/CANCELLED,
            //       PREPARING -> READY_FOR_PICKUP -> PICKED_UP -> DELIVERED
            throw new UnsupportedOperationException("TODO");
        }
    }

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
    }

    static class Customer extends User {
        Location address;
        Cart cart;
        List<Order> orders;
    }

    static class DeliveryPartner extends User {
        volatile Location location;
        PartnerStatus status;
        int deliveriesToday;

        synchronized void goOnline() { /* TODO */ }

        synchronized void goOffline() { /* TODO: not while BUSY */ }

        synchronized boolean tryAssign() {
            // TODO: atomic AVAILABLE -> BUSY
            throw new UnsupportedOperationException("TODO");
        }

        synchronized void release() { /* TODO: BUSY -> AVAILABLE */ }

        synchronized void completeDelivery(Location at) { /* TODO: move, count, release */ }
    }

    // ===================== Restaurant & Menu =====================

    static class MenuItem {
        String id;
        String name;
        double price;
        boolean veg;
        int stock;          // Integer.MAX_VALUE = unlimited
        boolean available;

        synchronized boolean tryReserve(int qty) {
            // TODO: atomic stock check + decrement
            throw new UnsupportedOperationException("TODO");
        }

        synchronized void release(int qty) { /* TODO */ }
    }

    static class Restaurant {
        String id;
        String name;
        String cuisine;
        Location location;
        Map<String, MenuItem> menu;
        volatile boolean open;

        void addMenuItem(MenuItem item) { /* TODO */ }

        MenuItem getMenuItem(String itemId) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }
    }

    // ===================== Cart =====================

    static class Cart {
        Restaurant restaurant;
        Map<MenuItem, Integer> items;

        synchronized void add(Restaurant r, MenuItem item, int qty) {
            // TODO: reject items from a second restaurant
        }

        synchronized void remove(MenuItem item) { /* TODO */ }

        synchronized void clear() { /* TODO */ }
    }

    // ===================== Order =====================

    static class OrderItem {
        String name;     // snapshot at order time
        double price;
        int qty;
    }

    static class Bill {
        double subtotal;
        double deliveryFee;
        double tax;
        double discount;
        double total;
    }

    static class Order {
        String id;
        Customer customer;
        Restaurant restaurant;
        List<OrderItem> items;
        Map<MenuItem, Integer> reserved;   // to restore stock on cancel
        Location deliveryAddress;
        Bill bill;
        OrderStatus status;
        DeliveryPartner partner;
        Payment payment;
        LocalDateTime placedAt;

        synchronized void moveTo(OrderStatus next) {
            // TODO: throw if !status.canMoveTo(next)
        }

        synchronized boolean assignPartner(DeliveryPartner p) {
            // TODO: false if cancelled or already assigned
            throw new UnsupportedOperationException("TODO");
        }
    }

    // ===================== Payment (Strategy) =====================

    interface PaymentStrategy {
        boolean pay(double amount);
        boolean refund(double amount);
    }

    static class UpiPayment implements PaymentStrategy {
        public boolean pay(double amount)    { throw new UnsupportedOperationException("TODO"); }
        public boolean refund(double amount) { throw new UnsupportedOperationException("TODO"); }
    }

    static class CardPayment implements PaymentStrategy {
        public boolean pay(double amount)    { throw new UnsupportedOperationException("TODO"); }
        public boolean refund(double amount) { throw new UnsupportedOperationException("TODO"); }
    }

    static class WalletPayment implements PaymentStrategy {
        double balance;

        public boolean pay(double amount)    { throw new UnsupportedOperationException("TODO"); }
        public boolean refund(double amount) { throw new UnsupportedOperationException("TODO"); }
    }

    static class CashOnDelivery implements PaymentStrategy {
        public boolean pay(double amount)    { throw new UnsupportedOperationException("TODO"); }
        public boolean refund(double amount) { throw new UnsupportedOperationException("TODO"); }
    }

    static class Payment {
        double amount;
        PaymentStatus status;
        PaymentStrategy strategy;

        boolean process() {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }

        void refund() { /* TODO: only if SUCCESS */ }
    }

    // ===================== Delivery fee (Strategy + Decorator) =====================

    interface DeliveryFeeStrategy {
        double calculate(double distanceKm, double subtotal);
    }

    static class StandardDeliveryFee implements DeliveryFeeStrategy {
        public double calculate(double distanceKm, double subtotal) {
            // TODO: 20 + 5/km
            throw new UnsupportedOperationException("TODO");
        }
    }

    static class FreeDeliveryAbove implements DeliveryFeeStrategy {
        double threshold;
        DeliveryFeeStrategy base;

        public double calculate(double distanceKm, double subtotal) {
            // TODO: 0 if subtotal >= threshold, else base
            throw new UnsupportedOperationException("TODO");
        }
    }

    // ===================== Coupons (Strategy) =====================

    interface Coupon {
        String getCode();
        double discount(double subtotal);
    }

    static class FlatCoupon implements Coupon {
        String code;
        double amount;
        double minOrder;

        public String getCode() { return code; }
        public double discount(double subtotal) { throw new UnsupportedOperationException("TODO"); }
    }

    static class PercentCoupon implements Coupon {
        String code;
        double percent;
        double maxDiscount;
        double minOrder;

        public String getCode() { return code; }
        public double discount(double subtotal) { throw new UnsupportedOperationException("TODO"); }
    }

    // ===================== Partner assignment (Strategy) =====================

    interface PartnerAssignmentStrategy {
        List<DeliveryPartner> rank(List<DeliveryPartner> partners, Location restaurant);
    }

    static class NearestPartnerStrategy implements PartnerAssignmentStrategy {
        public List<DeliveryPartner> rank(List<DeliveryPartner> partners, Location restaurant) {
            // TODO: by distance to restaurant
            throw new UnsupportedOperationException("TODO");
        }
    }

    static class FairDistributionStrategy implements PartnerAssignmentStrategy {
        public List<DeliveryPartner> rank(List<DeliveryPartner> partners, Location restaurant) {
            // TODO: fewest deliveries today, then distance
            throw new UnsupportedOperationException("TODO");
        }
    }

    // ===================== Notification (Observer) =====================

    interface NotificationObserver {
        void notify(String recipient, String message);
    }

    static class PushNotifier implements NotificationObserver {
        public void notify(String recipient, String message) { /* TODO */ }
    }

    static class SmsNotifier implements NotificationObserver {
        public void notify(String recipient, String message) { /* TODO */ }
    }

    static class NotificationService {
        List<NotificationObserver> observers;

        void addObserver(NotificationObserver o) { /* TODO */ }

        void notify(String to, String msg) { /* TODO */ }
    }

    // ===================== Services =====================

    static class RestaurantService {
        Map<String, Restaurant> restaurants;

        void addRestaurant(Restaurant r) { /* TODO */ }

        List<Restaurant> search(Location near, double radiusKm) {
            // TODO: open + within radius, nearest first
            throw new UnsupportedOperationException("TODO");
        }
    }

    static class DeliveryService {
        List<DeliveryPartner> partners;
        Deque<Order> waitingForPartner;
        NotificationService notifications;
        PartnerAssignmentStrategy strategy;

        void register(DeliveryPartner p) { /* TODO */ }

        synchronized void assign(Order order) {
            // TODO: rank available partners, tryAssign each; nobody -> waiting queue
        }

        synchronized void onPartnerAvailable(DeliveryPartner p) {
            // TODO: give the partner the oldest waiting (not cancelled) order
        }

        synchronized void removeWaiting(Order o) { /* TODO */ }
    }

    static class OrderService {
        Map<String, Order> orders;
        Map<String, Coupon> coupons;
        DeliveryService deliveryService;
        NotificationService notifications;
        DeliveryFeeStrategy feeStrategy;

        Order placeOrder(Customer c, String couponCode, PaymentStrategy ps) {
            // TODO: synchronized(cart): open? bill (fee, 5% tax, coupon), reserve all items
            //       (all-or-nothing), pay (release on failure, keep cart), clear cart, notify
            throw new UnsupportedOperationException("TODO");
        }

        void accept(String orderId) { /* TODO: -> ACCEPTED, deliveryService.assign */ }

        void reject(String orderId, String reason) { /* TODO: only PLACED -> cancel */ }

        void startPreparing(String orderId) { /* TODO */ }

        void markReady(String orderId) { /* TODO */ }

        void pickUp(DeliveryPartner p, String orderId) { /* TODO: assigned partner only */ }

        void deliver(DeliveryPartner p, String orderId) {
            // TODO: -> DELIVERED, partner.completeDelivery, onPartnerAvailable
        }

        void cancelByCustomer(Customer c, String orderId) {
            // TODO: only before PREPARING; restore stock, refund, free partner
        }

        void setFeeStrategy(DeliveryFeeStrategy s) { /* TODO */ }
    }

    // ===================== Facade + Singleton =====================

    static class FoodDeliverySystem {
        private static volatile FoodDeliverySystem instance;
        RestaurantService restaurantService;
        DeliveryService deliveryService;
        OrderService orderService;

        private FoodDeliverySystem() {}

        static FoodDeliverySystem getInstance() {
            // TODO: double-checked locking
            throw new UnsupportedOperationException("TODO");
        }

        List<Restaurant> searchRestaurants(Location near, double radiusKm) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }

        void addToCart(Customer c, Restaurant r, String itemId, int qty) { /* TODO */ }

        Order placeOrder(Customer c, String coupon, PaymentStrategy ps) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }

        void cancelOrder(Customer c, String orderId) { /* TODO */ }

        void acceptOrder(String orderId) { /* TODO */ }

        void rejectOrder(String orderId, String reason) { /* TODO */ }

        void pickUp(DeliveryPartner p, String orderId) { /* TODO */ }

        void deliver(DeliveryPartner p, String orderId) { /* TODO */ }
    }

    // ===================== Demo =====================

    public static void main(String[] args) {
        // TODO:
        // 1. Restaurants + menus, partners online, coupons
        // 2. Search nearby, cart from one restaurant only
        // 3. Place order with coupon -> bill breakdown
        // 4. Accept -> partner assigned -> cook -> pick up -> deliver (invalid moves rejected)
        // 5. Coupon min order, free delivery decorator
        // 6. Restaurant rejects, customer cancels -> refund, stock + partner restored
        // 7. Payment failure keeps cart; closed restaurant
        // 8. Last portions race; more orders than partners -> waiting queue
    }
}
