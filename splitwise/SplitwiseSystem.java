import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

// ===================== Enums =====================

enum SplitType { EQUAL, EXACT, PERCENT, SHARE }

// ===================== Money =====================

// all amounts are long paise (1 rupee = 100 paise) -> no floating point rounding errors
final class Money {
    private Money() {}

    static long of(double rupees) { return Math.round(rupees * 100); }

    static String fmt(long paise) {
        String sign = paise < 0 ? "-" : "";
        long abs = Math.abs(paise);
        return sign + "Rs " + (abs / 100) + "." + String.format("%02d", abs % 100);
    }
}

// ===================== User =====================

class User {
    private final String id;
    private final String name;
    private final String email;

    User(String id, String name, String email) {
        this.id = id;
        this.name = name;
        this.email = email;
    }

    public String getId()    { return id; }
    public String getName()  { return name; }
    public String getEmail() { return email; }

    @Override public String toString() { return name; }
}

// ===================== Split (Strategy + Factory) =====================

class Split {
    private final User user;
    private final long amount;   // paise this user owes for the expense

    Split(User user, long amount) {
        this.user = user;
        this.amount = amount;
    }

    public User getUser()   { return user; }
    public long getAmount() { return amount; }

    @Override public String toString() { return user + " " + Money.fmt(amount); }
}

interface SplitStrategy {
    // values: ignored for EQUAL, rupee amounts for EXACT, percents for PERCENT, weights for SHARE
    List<Split> split(long total, List<User> users, List<Double> values);
}

// shared by EQUAL / PERCENT / SHARE: split by weights, then hand out leftover paise one by one
abstract class WeightedSplitStrategy implements SplitStrategy {
    protected List<Split> byWeights(long total, List<User> users, List<Double> weights) {
        double sum = weights.stream().mapToDouble(Double::doubleValue).sum();
        long[] amounts = new long[users.size()];
        long assigned = 0;
        for (int i = 0; i < users.size(); i++) {
            amounts[i] = (long) Math.floor(total * weights.get(i) / sum);
            assigned += amounts[i];
        }
        long left = total - assigned;   // at most users.size() - 1 paise
        for (int i = 0; left > 0; i = (i + 1) % users.size()) {
            if (weights.get(i) > 0) {
                amounts[i]++;
                left--;
            }
        }
        List<Split> splits = new ArrayList<>();
        for (int i = 0; i < users.size(); i++) splits.add(new Split(users.get(i), amounts[i]));
        return splits;
    }

    protected void requireSize(List<User> users, List<Double> values) {
        if (values == null || values.size() != users.size())
            throw new IllegalArgumentException("Need one value per person");
        if (values.stream().anyMatch(v -> v < 0)) throw new IllegalArgumentException("Values can't be negative");
    }
}

class EqualSplitStrategy extends WeightedSplitStrategy {
    public List<Split> split(long total, List<User> users, List<Double> values) {
        return byWeights(total, users, Collections.nCopies(users.size(), 1.0));
    }
}

class PercentSplitStrategy extends WeightedSplitStrategy {
    public List<Split> split(long total, List<User> users, List<Double> values) {
        requireSize(users, values);
        double sum = values.stream().mapToDouble(Double::doubleValue).sum();
        if (Math.abs(sum - 100) > 0.001) throw new IllegalArgumentException("Percents add up to " + sum + ", expected 100");
        return byWeights(total, users, values);
    }
}

class ShareSplitStrategy extends WeightedSplitStrategy {
    public List<Split> split(long total, List<User> users, List<Double> values) {
        requireSize(users, values);
        if (values.stream().mapToDouble(Double::doubleValue).sum() <= 0) throw new IllegalArgumentException("Shares must add up to more than 0");
        return byWeights(total, users, values);
    }
}

class ExactSplitStrategy implements SplitStrategy {
    public List<Split> split(long total, List<User> users, List<Double> values) {
        if (values == null || values.size() != users.size()) throw new IllegalArgumentException("Need one amount per person");
        List<Split> splits = new ArrayList<>();
        long sum = 0;
        for (int i = 0; i < users.size(); i++) {
            long p = Money.of(values.get(i));
            if (p < 0) throw new IllegalArgumentException("Amounts can't be negative");
            splits.add(new Split(users.get(i), p));
            sum += p;
        }
        if (sum != total) throw new IllegalArgumentException("Amounts add up to " + Money.fmt(sum) + ", expected " + Money.fmt(total));
        return splits;
    }
}

final class SplitStrategyFactory {
    private static final Map<SplitType, SplitStrategy> STRATEGIES = new EnumMap<>(SplitType.class);

    static {
        STRATEGIES.put(SplitType.EQUAL, new EqualSplitStrategy());
        STRATEGIES.put(SplitType.EXACT, new ExactSplitStrategy());
        STRATEGIES.put(SplitType.PERCENT, new PercentSplitStrategy());
        STRATEGIES.put(SplitType.SHARE, new ShareSplitStrategy());
    }

    private SplitStrategyFactory() {}

    static SplitStrategy get(SplitType type) { return STRATEGIES.get(type); }
}

// ===================== Expense & Settlement =====================

class Expense {
    private final String id = UUID.randomUUID().toString().substring(0, 8);
    private final String description;
    private final long total;
    private final User paidBy;
    private final List<Split> splits;
    private final SplitType type;
    private final Group group;   // null = expense between friends, outside any group
    private final LocalDateTime createdAt = LocalDateTime.now();
    private boolean deleted;

    Expense(String description, long total, User paidBy, List<Split> splits, SplitType type, Group group) {
        this.description = description;
        this.total = total;
        this.paidBy = paidBy;
        this.splits = List.copyOf(splits);
        this.type = type;
        this.group = group;
    }

    public synchronized void markDeleted() {
        if (deleted) throw new IllegalStateException("Expense already deleted");
        deleted = true;
    }

    public String getId()               { return id; }
    public String getDescription()      { return description; }
    public long getTotal()              { return total; }
    public User getPaidBy()             { return paidBy; }
    public List<Split> getSplits()      { return splits; }
    public SplitType getType()          { return type; }
    public Group getGroup()             { return group; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public synchronized boolean isDeleted() { return deleted; }

    @Override public String toString() {
        return "'" + description + "' " + Money.fmt(total) + " paid by " + paidBy + " " + type + " " + splits;
    }
}

class Settlement {
    private final String id = UUID.randomUUID().toString().substring(0, 8);
    private final User from;
    private final User to;
    private final long amount;
    private final Group group;
    private final LocalDateTime createdAt = LocalDateTime.now();

    Settlement(User from, User to, long amount, Group group) {
        this.from = from;
        this.to = to;
        this.amount = amount;
        this.group = group;
    }

    public String getId()   { return id; }
    public User getFrom()   { return from; }
    public User getTo()     { return to; }
    public long getAmount() { return amount; }
    public Group getGroup() { return group; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}

// suggested payment produced by simplify()
class Transfer {
    private final User from;
    private final User to;
    private final long amount;

    Transfer(User from, User to, long amount) {
        this.from = from;
        this.to = to;
        this.amount = amount;
    }

    public User getFrom()   { return from; }
    public User getTo()     { return to; }
    public long getAmount() { return amount; }

    @Override public String toString() { return from + " pays " + to + " " + Money.fmt(amount); }
}

// ===================== Balance sheet =====================

// owedTo[a][b] > 0  means  b owes a that much.  Always kept antisymmetric: owedTo[b][a] = -owedTo[a][b]
class BalanceSheet {
    private final Map<User, Map<User, Long>> owedTo = new LinkedHashMap<>();

    public synchronized void record(User creditor, User debtor, long amount) {
        if (creditor == debtor || amount == 0) return;
        adjust(creditor, debtor, amount);
        adjust(debtor, creditor, -amount);
    }

    private void adjust(User a, User b, long delta) {
        Map<User, Long> row = owedTo.computeIfAbsent(a, k -> new LinkedHashMap<>());
        long v = row.getOrDefault(b, 0L) + delta;
        if (v == 0) row.remove(b);
        else row.put(b, v);
    }

    // how much debtor owes creditor (<= 0 means nothing)
    public synchronized long owes(User debtor, User creditor) {
        return owedTo.getOrDefault(creditor, Map.of()).getOrDefault(debtor, 0L);
    }

    // positive: they owe u; negative: u owes them
    public synchronized Map<User, Long> balancesOf(User u) {
        return new LinkedHashMap<>(owedTo.getOrDefault(u, Map.of()));
    }

    // positive: u gets back overall; negative: u owes overall
    public synchronized long net(User u) {
        return owedTo.getOrDefault(u, Map.of()).values().stream().mapToLong(Long::longValue).sum();
    }

    public synchronized Map<User, Long> netAll() {
        Map<User, Long> out = new LinkedHashMap<>();
        for (User u : owedTo.keySet()) {
            long n = net(u);
            if (n != 0) out.put(u, n);
        }
        return out;
    }

    // number of "X owes Y" pairs right now
    public synchronized int debtCount() {
        return (int) owedTo.values().stream().flatMap(m -> m.values().stream()).filter(v -> v > 0).count();
    }

    // greedy min cash flow: biggest debtor pays biggest creditor; at most (people - 1) payments
    public synchronized List<Transfer> simplify() {
        Comparator<Map.Entry<User, Long>> byAmount = Comparator.comparingLong(e -> Math.abs(e.getValue()));
        Comparator<Map.Entry<User, Long>> order = byAmount.reversed().thenComparing(e -> e.getKey().getName());
        PriorityQueue<Map.Entry<User, Long>> creditors = new PriorityQueue<>(order);
        PriorityQueue<Map.Entry<User, Long>> debtors = new PriorityQueue<>(order);
        for (Map.Entry<User, Long> e : netAll().entrySet()) {
            if (e.getValue() > 0) creditors.add(new AbstractMap.SimpleEntry<>(e));
            else debtors.add(new AbstractMap.SimpleEntry<>(e));
        }
        List<Transfer> transfers = new ArrayList<>();
        while (!creditors.isEmpty() && !debtors.isEmpty()) {
            Map.Entry<User, Long> c = creditors.poll();
            Map.Entry<User, Long> d = debtors.poll();
            long x = Math.min(c.getValue(), -d.getValue());
            transfers.add(new Transfer(d.getKey(), c.getKey(), x));
            if (c.getValue() - x > 0) creditors.add(new AbstractMap.SimpleEntry<>(c.getKey(), c.getValue() - x));
            if (d.getValue() + x < 0) debtors.add(new AbstractMap.SimpleEntry<>(d.getKey(), d.getValue() + x));
        }
        return transfers;
    }
}

// ===================== Group =====================

class Group {
    private final String id = UUID.randomUUID().toString().substring(0, 8);
    private final String name;
    private final Set<User> members = new LinkedHashSet<>();
    private final List<Expense> expenses = new CopyOnWriteArrayList<>();
    private final BalanceSheet sheet = new BalanceSheet();

    Group(String name) { this.name = name; }

    public synchronized void addMember(User u)     { members.add(u); }
    public synchronized void removeMember(User u)  { members.remove(u); }
    public synchronized boolean isMember(User u)   { return members.contains(u); }
    public synchronized List<User> getMembers()    { return new ArrayList<>(members); }

    public void addExpense(Expense e)              { expenses.add(e); }
    public List<Expense> getExpenses()             { return expenses.stream().filter(e -> !e.isDeleted()).collect(Collectors.toList()); }
    public BalanceSheet getSheet()                 { return sheet; }
    public String getId()                          { return id; }
    public String getName()                        { return name; }

    @Override public String toString() { return name; }
}

// ===================== Notification (Observer) =====================

interface NotificationObserver {
    void notify(User user, String message);
}

class EmailNotifier implements NotificationObserver {
    public void notify(User u, String message) { System.out.println("  [EMAIL to " + u.getEmail() + "] " + message); }
}

class PushNotifier implements NotificationObserver {
    public void notify(User u, String message) { System.out.println("  [PUSH to " + u + "] " + message); }
}

class NotificationService {
    private final List<NotificationObserver> observers = new CopyOnWriteArrayList<>();

    public void addObserver(NotificationObserver o)    { observers.add(o); }
    public void removeObserver(NotificationObserver o) { observers.remove(o); }
    public void notify(User u, String msg)             { observers.forEach(o -> o.notify(u, msg)); }
}

// ===================== Services =====================

class GroupService {
    private final Map<String, Group> groups = new ConcurrentHashMap<>();

    public Group create(String name, List<User> members) {
        Group g = new Group(name);
        members.forEach(g::addMember);
        groups.put(g.getId(), g);
        return g;
    }

    public void addMember(Group g, User u) { g.addMember(u); }

    public Group get(String groupId) {
        Group g = groups.get(groupId);
        if (g == null) throw new IllegalArgumentException("No group " + groupId);
        return g;
    }

    // can't walk away from a group while you owe or are owed money in it
    public void leave(Group g, User u) {
        synchronized (g.getSheet()) {
            long net = g.getSheet().net(u);
            if (net != 0) throw new IllegalStateException(u + " has an unsettled balance of " + Money.fmt(net) + " in " + g);
            g.removeMember(u);
        }
    }
}

class ExpenseService {
    private final Map<String, Expense> expenses = new ConcurrentHashMap<>();
    private final List<Settlement> settlements = new CopyOnWriteArrayList<>();
    private final BalanceSheet global = new BalanceSheet();   // all balances, across groups and friends
    private final NotificationService notifications;

    ExpenseService(NotificationService notifications) { this.notifications = notifications; }

    public Expense addExpense(String description, long total, User paidBy, List<User> users,
                              SplitType type, List<Double> values, Group group) {
        if (total <= 0) throw new IllegalArgumentException("Amount must be positive");
        if (users.isEmpty()) throw new IllegalArgumentException("Add at least one person");
        if (new HashSet<>(users).size() != users.size()) throw new IllegalArgumentException("A person is listed twice");
        if (group != null) {
            if (!group.isMember(paidBy)) throw new IllegalArgumentException(paidBy + " is not in " + group);
            for (User u : users) if (!group.isMember(u)) throw new IllegalArgumentException(u + " is not in " + group);
        }

        List<Split> splits = SplitStrategyFactory.get(type).split(total, users, values);
        Expense e = new Expense(description, total, paidBy, splits, type, group);
        expenses.put(e.getId(), e);
        apply(e, 1);
        if (group != null) group.addExpense(e);

        for (Split s : splits) {
            if (s.getUser() != paidBy && s.getAmount() > 0) {
                notifications.notify(s.getUser(), paidBy + " added '" + description + "' (" + Money.fmt(total)
                        + "). You owe " + Money.fmt(s.getAmount()));
            }
        }
        return e;
    }

    // only the payer can delete; balances are reversed exactly
    public void deleteExpense(User by, String expenseId) {
        Expense e = expenses.get(expenseId);
        if (e == null) throw new IllegalArgumentException("No expense " + expenseId);
        if (e.getPaidBy() != by) throw new IllegalStateException("Only " + e.getPaidBy() + " can delete this expense");
        e.markDeleted();
        apply(e, -1);
        e.getSplits().stream().filter(s -> s.getUser() != by)
                .forEach(s -> notifications.notify(s.getUser(), by + " deleted '" + e.getDescription() + "'"));
    }

    // from pays to; can't pay more than owed in that sheet (group or friends)
    public Settlement settleUp(User from, User to, long amount, Group group) {
        if (amount <= 0) throw new IllegalArgumentException("Amount must be positive");
        BalanceSheet sheet = group == null ? global : group.getSheet();
        synchronized (sheet) {
            long owed = sheet.owes(from, to);
            if (owed <= 0) throw new IllegalStateException(from + " doesn't owe " + to + (group == null ? "" : " in " + group));
            if (amount > owed) throw new IllegalStateException(from + " only owes " + to + " " + Money.fmt(owed));
            if (group != null) group.getSheet().record(from, to, amount);
            global.record(from, to, amount);
        }
        Settlement s = new Settlement(from, to, amount, group);
        settlements.add(s);
        notifications.notify(to, from + " paid you " + Money.fmt(amount));
        return s;
    }

    // payer is owed each other person's share
    private void apply(Expense e, int sign) {
        for (Split s : e.getSplits()) {
            if (s.getUser() == e.getPaidBy()) continue;
            global.record(e.getPaidBy(), s.getUser(), sign * s.getAmount());
            if (e.getGroup() != null) e.getGroup().getSheet().record(e.getPaidBy(), s.getUser(), sign * s.getAmount());
        }
    }

    public BalanceSheet getGlobal()         { return global; }
    public List<Settlement> getSettlements() { return new ArrayList<>(settlements); }
}

// ===================== Facade + Singleton =====================

public class SplitwiseSystem {
    private static volatile SplitwiseSystem instance;

    private final Map<String, User> users = new ConcurrentHashMap<>();
    private final NotificationService notifications = new NotificationService();
    private final GroupService groups = new GroupService();
    private final ExpenseService expenses = new ExpenseService(notifications);

    private SplitwiseSystem() {}

    public static SplitwiseSystem getInstance() {
        if (instance == null) {
            synchronized (SplitwiseSystem.class) {
                if (instance == null) instance = new SplitwiseSystem();
            }
        }
        return instance;
    }

    public User register(String name, String email) {
        User u = new User("U" + (users.size() + 1), name, email);
        users.put(u.getId(), u);
        return u;
    }

    public Group createGroup(String name, List<User> members) { return groups.create(name, members); }
    public void addMember(Group g, User u)                    { groups.addMember(g, u); }
    public void leaveGroup(Group g, User u)                   { groups.leave(g, u); }

    public Expense addExpense(Group g, String desc, double amount, User paidBy, List<User> users, SplitType type, List<Double> values) {
        return expenses.addExpense(desc, Money.of(amount), paidBy, users, type, values, g);
    }

    public Expense addEqualExpense(Group g, String desc, double amount, User paidBy, List<User> users) {
        return addExpense(g, desc, amount, paidBy, users, SplitType.EQUAL, null);
    }

    public void deleteExpense(User by, String expenseId)                      { expenses.deleteExpense(by, expenseId); }
    public Settlement settleUp(User from, User to, double amount, Group g)    { return expenses.settleUp(from, to, Money.of(amount), g); }

    public Map<User, Long> balancesOf(User u)  { return expenses.getGlobal().balancesOf(u); }
    public List<Transfer> simplify(Group g)    { return g.getSheet().simplify(); }

    public ExpenseService getExpenseService()       { return expenses; }
    public NotificationService getNotifications()   { return notifications; }

    // ===================== Demo =====================

    private static void attempt(String label, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            System.out.println("  " + label + " rejected: " + e.getMessage());
        }
    }

    // print each debt once, from the debtor's side
    private static void printDebts(BalanceSheet sheet, List<User> people) {
        boolean any = false;
        for (User u : people) {
            for (Map.Entry<User, Long> e : sheet.balancesOf(u).entrySet()) {
                if (e.getValue() < 0) {
                    System.out.println("    " + u + " owes " + e.getKey() + " " + Money.fmt(-e.getValue()));
                    any = true;
                }
            }
        }
        if (!any) System.out.println("    all settled up");
    }

    private static String nets(BalanceSheet sheet, List<User> people) {
        return people.stream().map(u -> u + " " + Money.fmt(sheet.net(u))).collect(Collectors.joining(", "));
    }

    public static void main(String[] args) throws InterruptedException {
        SplitwiseSystem app = SplitwiseSystem.getInstance();
        PushNotifier push = new PushNotifier();
        app.getNotifications().addObserver(push);

        User alice = app.register("Alice", "alice@mail.com");
        User bob   = app.register("Bob",   "bob@mail.com");
        User carol = app.register("Carol", "carol@mail.com");
        User dave  = app.register("Dave",  "dave@mail.com");
        User eve   = app.register("Eve",   "eve@mail.com");
        List<User> four = List.of(alice, bob, carol, dave);
        Group goa = app.createGroup("Goa Trip", four);

        // 1. Equal split with rounding
        System.out.println("1. EQUAL: Alice pays Rs 1000 hotel for Alice, Bob, Carol");
        Expense hotel = app.addEqualExpense(goa, "Hotel", 1000, alice, List.of(alice, bob, carol));
        System.out.println("  " + hotel);
        System.out.println("  Shares add up to " + Money.fmt(hotel.getSplits().stream().mapToLong(Split::getAmount).sum())
                + " (the extra paisa goes to the first person)");

        // 2. Exact
        System.out.println("\n2. EXACT: Bob pays Rs 500 cab");
        attempt("Amounts 200+100+150", () -> app.addExpense(goa, "Cab", 500, bob, List.of(alice, bob, dave),
                SplitType.EXACT, List.of(200.0, 100.0, 150.0)));
        Expense cab = app.addExpense(goa, "Cab", 500, bob, List.of(alice, bob, dave), SplitType.EXACT, List.of(200.0, 100.0, 200.0));

        // 3. Percent
        System.out.println("\n3. PERCENT: Carol pays Rs 2000 dinner");
        attempt("Percents 40+30+20", () -> app.addExpense(goa, "Dinner", 2000, carol, List.of(alice, bob, carol),
                SplitType.PERCENT, List.of(40.0, 30.0, 20.0)));
        app.addExpense(goa, "Dinner", 2000, carol, four, SplitType.PERCENT, List.of(40.0, 30.0, 20.0, 10.0));

        // 4. Share
        System.out.println("\n4. SHARE: Dave pays Rs 1200 drinks, shares Alice 2 : Bob 1 : Dave 3");
        Expense drinks = app.addExpense(goa, "Drinks", 1200, dave, List.of(alice, bob, dave), SplitType.SHARE, List.of(2.0, 1.0, 3.0));
        System.out.println("  " + drinks);

        // 5. Balances
        System.out.println("\n5. Goa Trip balances");
        printDebts(goa.getSheet(), four);
        System.out.println("  Net: " + nets(goa.getSheet(), four));
        long sum = four.stream().mapToLong(u -> goa.getSheet().net(u)).sum();
        System.out.println("  Sum of nets: " + Money.fmt(sum) + " (always 0)");

        // 6. Simplify
        System.out.println("\n6. Simplify debts");
        List<Transfer> plan = app.simplify(goa);
        System.out.println("  " + goa.getSheet().debtCount() + " pairwise debts -> " + plan.size() + " payments:");
        plan.forEach(t -> System.out.println("    " + t));

        // 7. Settle
        System.out.println("\n7. Bob settles with Carol");
        long bobOwesCarol = goa.getSheet().owes(bob, carol);
        attempt("Bob pays Carol too much", () -> app.settleUp(bob, carol, bobOwesCarol / 100.0 + 100, goa));
        attempt("Carol pays Bob", () -> app.settleUp(carol, bob, 10, goa));
        app.settleUp(bob, carol, bobOwesCarol / 100.0, goa);
        System.out.println("  Bob owes Carol now: " + Money.fmt(goa.getSheet().owes(bob, carol)));

        // 8. Delete
        System.out.println("\n8. Bob deletes the cab expense");
        attempt("Alice deletes Bob's expense", () -> app.deleteExpense(alice, cab.getId()));
        long before = goa.getSheet().owes(bob, alice);
        app.deleteExpense(bob, cab.getId());
        System.out.println("  Bob owes Alice " + Money.fmt(before) + " -> " + Money.fmt(goa.getSheet().owes(bob, alice))
                + " (her Rs 200 cab share no longer offsets the hotel)");
        attempt("Delete again", () -> app.deleteExpense(bob, cab.getId()));

        // 9. Group rules
        System.out.println("\n9. Group rules");
        attempt("Expense with Eve (not in group)", () -> app.addEqualExpense(goa, "Snacks", 300, alice, List.of(alice, eve)));
        attempt("Dave leaves with a balance", () -> app.leaveGroup(goa, dave));

        // 10. Friends expense outside any group
        System.out.println("\n10. Alice and Eve split a movie outside any group");
        app.addEqualExpense(null, "Movie", 600, eve, List.of(alice, eve));
        System.out.println("  Alice overall (all groups + friends): ");
        app.balancesOf(alice).forEach((u, v) -> System.out.println("    " + (v > 0 ? u + " owes Alice " : "Alice owes " + u + " ") + Money.fmt(Math.abs(v))));
        System.out.println("  Alice net overall: " + Money.fmt(app.getExpenseService().getGlobal().net(alice)));

        app.getNotifications().removeObserver(push);   // keep the stress test quiet

        // 11. Concurrency
        System.out.println("\n11. 4 threads add 250 random expenses each to the same group at once");
        List<User> flat = new ArrayList<>();
        for (int i = 1; i <= 4; i++) flat.add(app.register("F" + i, "f" + i + "@mail.com"));
        Group flatGroup = app.createGroup("Flat", flat);
        Map<User, Long> expected = new ConcurrentHashMap<>();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            Thread th = new Thread(() -> {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                for (int i = 0; i < 250; i++) {
                    User payer = flat.get(rnd.nextInt(flat.size()));
                    long paise = rnd.nextLong(100, 500_000);
                    Expense e = app.getExpenseService().addExpense("x", paise, payer, flat, SplitType.EQUAL, null, flatGroup);
                    for (Split s : e.getSplits()) {
                        if (s.getUser() == payer) continue;
                        expected.merge(payer, s.getAmount(), Long::sum);
                        expected.merge(s.getUser(), -s.getAmount(), Long::sum);
                    }
                }
            });
            threads.add(th);
            th.start();
        }
        for (Thread th : threads) th.join();
        boolean groupOk = flat.stream().allMatch(u -> flatGroup.getSheet().net(u) == expected.getOrDefault(u, 0L));
        boolean globalOk = flat.stream().allMatch(u -> app.getExpenseService().getGlobal().net(u) == expected.getOrDefault(u, 0L));
        long flatSum = flat.stream().mapToLong(u -> flatGroup.getSheet().net(u)).sum();
        System.out.println("  Expenses: " + flatGroup.getExpenses().size() + " (expected 1000)");
        System.out.println("  Group nets match: " + groupOk + ", global nets match: " + globalOk + ", sum of nets: " + Money.fmt(flatSum));
        System.out.println("  Simplified to " + app.simplify(flatGroup).size() + " payments (at most 3 for 4 people)");
    }
}
