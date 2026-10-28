import java.time.LocalDateTime;
import java.util.*;

/**
 * Skeleton for the Splitwise LLD: classes, fields and method signatures only.
 * Every class is nested here so this file can sit next to SplitwiseSystem.java
 * without duplicate-class clashes. Getters/setters omitted.
 */
public class SplitwiseSkeleton {

    // ===================== Enums =====================

    enum SplitType { EQUAL, EXACT, PERCENT, SHARE }

    // ===================== Money =====================

    static final class Money {
        static long of(double rupees) {
            // TODO: round to paise
            throw new UnsupportedOperationException("TODO");
        }

        static String fmt(long paise) {
            // TODO: "Rs 333.34"
            throw new UnsupportedOperationException("TODO");
        }
    }

    // ===================== User =====================

    static class User {
        String id;
        String name;
        String email;
    }

    // ===================== Split (Strategy + Factory) =====================

    static class Split {
        User user;
        long amount;   // paise
    }

    interface SplitStrategy {
        List<Split> split(long total, List<User> users, List<Double> values);
    }

    static abstract class WeightedSplitStrategy implements SplitStrategy {
        protected List<Split> byWeights(long total, List<User> users, List<Double> weights) {
            // TODO: floor(total * w / sum) each, then give leftover paise one by one
            throw new UnsupportedOperationException("TODO");
        }
    }

    static class EqualSplitStrategy extends WeightedSplitStrategy {
        public List<Split> split(long total, List<User> users, List<Double> values) {
            // TODO: all weights 1
            throw new UnsupportedOperationException("TODO");
        }
    }

    static class PercentSplitStrategy extends WeightedSplitStrategy {
        public List<Split> split(long total, List<User> users, List<Double> values) {
            // TODO: percents must add to 100
            throw new UnsupportedOperationException("TODO");
        }
    }

    static class ShareSplitStrategy extends WeightedSplitStrategy {
        public List<Split> split(long total, List<User> users, List<Double> values) {
            // TODO: shares are weights
            throw new UnsupportedOperationException("TODO");
        }
    }

    static class ExactSplitStrategy implements SplitStrategy {
        public List<Split> split(long total, List<User> users, List<Double> values) {
            // TODO: amounts must add up to total
            throw new UnsupportedOperationException("TODO");
        }
    }

    static final class SplitStrategyFactory {
        static Map<SplitType, SplitStrategy> STRATEGIES;

        static SplitStrategy get(SplitType type) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }
    }

    // ===================== Expense & Settlement =====================

    static class Expense {
        String id;
        String description;
        long total;
        User paidBy;
        List<Split> splits;
        SplitType type;
        Group group;   // null = between friends
        LocalDateTime createdAt;
        boolean deleted;

        synchronized void markDeleted() { /* TODO: throw if already deleted */ }
    }

    static class Settlement {
        String id;
        User from;
        User to;
        long amount;
        Group group;
        LocalDateTime createdAt;
    }

    static class Transfer {
        User from;
        User to;
        long amount;
    }

    // ===================== Balance sheet =====================

    static class BalanceSheet {
        Map<User, Map<User, Long>> owedTo;   // owedTo[a][b] > 0 -> b owes a

        synchronized void record(User creditor, User debtor, long amount) {
            // TODO: owedTo[c][d] += amount, owedTo[d][c] -= amount, drop zeros
        }

        synchronized long owes(User debtor, User creditor) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }

        synchronized Map<User, Long> balancesOf(User u) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }

        synchronized long net(User u) {
            // TODO: sum of u's row
            throw new UnsupportedOperationException("TODO");
        }

        synchronized List<Transfer> simplify() {
            // TODO: nets -> max-heap of creditors and debtors, match biggest with biggest
            throw new UnsupportedOperationException("TODO");
        }
    }

    // ===================== Group =====================

    static class Group {
        String id;
        String name;
        Set<User> members;
        List<Expense> expenses;
        BalanceSheet sheet;

        synchronized void addMember(User u) { /* TODO */ }

        synchronized void removeMember(User u) { /* TODO */ }

        synchronized boolean isMember(User u) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }
    }

    // ===================== Notification (Observer) =====================

    interface NotificationObserver {
        void notify(User user, String message);
    }

    static class EmailNotifier implements NotificationObserver {
        public void notify(User u, String message) { /* TODO */ }
    }

    static class PushNotifier implements NotificationObserver {
        public void notify(User u, String message) { /* TODO */ }
    }

    static class NotificationService {
        List<NotificationObserver> observers;

        void notify(User u, String msg) { /* TODO */ }
    }

    // ===================== Services =====================

    static class GroupService {
        Map<String, Group> groups;

        Group create(String name, List<User> members) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }

        void addMember(Group g, User u) { /* TODO */ }

        void leave(Group g, User u) { /* TODO: only with zero net balance in the group */ }
    }

    static class ExpenseService {
        Map<String, Expense> expenses;
        List<Settlement> settlements;
        BalanceSheet global;
        NotificationService notifications;

        Expense addExpense(String description, long total, User paidBy, List<User> users,
                           SplitType type, List<Double> values, Group group) {
            // TODO: validate, factory.get(type).split, apply to global + group sheet, notify
            throw new UnsupportedOperationException("TODO");
        }

        void deleteExpense(User by, String expenseId) {
            // TODO: payer only, markDeleted, apply with sign -1
        }

        Settlement settleUp(User from, User to, long amount, Group group) {
            // TODO: synchronized(sheet): must owe, amount <= owed, record on group + global
            throw new UnsupportedOperationException("TODO");
        }

        private void apply(Expense e, int sign) {
            // TODO: for each split except payer: record(payer, user, sign * amount)
        }
    }

    // ===================== Facade + Singleton =====================

    static class SplitwiseSystem {
        private static volatile SplitwiseSystem instance;
        Map<String, User> users;
        GroupService groups;
        ExpenseService expenses;

        private SplitwiseSystem() {}

        static SplitwiseSystem getInstance() {
            // TODO: double-checked locking
            throw new UnsupportedOperationException("TODO");
        }

        User register(String name, String email) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }

        Group createGroup(String name, List<User> members) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }

        Expense addExpense(Group g, String desc, double amount, User paidBy, List<User> users, SplitType type, List<Double> values) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }

        void deleteExpense(User by, String expenseId) { /* TODO */ }

        Settlement settleUp(User from, User to, double amount, Group g) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }

        List<Transfer> simplify(Group g) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }
    }

    // ===================== Demo =====================

    public static void main(String[] args) {
        // TODO:
        // 1. Users + group
        // 2. EQUAL (with paisa rounding), EXACT, PERCENT, SHARE expenses + validation errors
        // 3. Show who owes whom, nets sum to 0
        // 4. Simplify debts
        // 5. Settle up (no overpay), delete expense (payer only)
        // 6. Group rules: non-member rejected, can't leave with balance
        // 7. Friends expense outside a group
        // 8. Concurrent expenses -> balances exact
    }
}
