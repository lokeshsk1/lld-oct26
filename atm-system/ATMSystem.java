import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

// ===================== Enums =====================

enum AccountType { SAVINGS, CURRENT }

enum CardStatus { ACTIVE, BLOCKED }

enum TransactionType { WITHDRAWAL, DEPOSIT, BALANCE_INQUIRY, TRANSFER }

enum TransactionStatus { SUCCESS, FAILED }

// ===================== Card & Account =====================

class Card {
    private final String cardNumber;
    private final String holderName;
    private final YearMonth expiry;

    Card(String cardNumber, String holderName, YearMonth expiry) {
        this.cardNumber = cardNumber;
        this.holderName = holderName;
        this.expiry = expiry;
    }

    public boolean isExpired() { return YearMonth.now().isAfter(expiry); }

    public String getCardNumber() { return cardNumber; }
    public String getHolderName() { return holderName; }
    public YearMonth getExpiry()  { return expiry; }

    // only last 4 digits are ever shown
    @Override public String toString() { return "XXXX-" + cardNumber.substring(cardNumber.length() - 4); }
}

class Account {
    private final String accountNumber;
    private final String holderName;
    private final AccountType type;
    private final long dailyWithdrawLimit;
    private long balance;
    private long withdrawnToday;
    private LocalDate limitDay = LocalDate.now();

    Account(String accountNumber, String holderName, AccountType type, long balance, long dailyWithdrawLimit) {
        this.accountNumber = accountNumber;
        this.holderName = holderName;
        this.type = type;
        this.balance = balance;
        this.dailyWithdrawLimit = dailyWithdrawLimit;
    }

    // check balance + daily limit and debit atomically
    public synchronized void withdraw(long amount) {
        resetLimitIfNewDay();
        if (amount > balance) throw new IllegalStateException("Insufficient balance");
        if (withdrawnToday + amount > dailyWithdrawLimit)
            throw new IllegalStateException("Daily limit exceeded (" + (dailyWithdrawLimit - withdrawnToday) + " left today)");
        balance -= amount;
        withdrawnToday += amount;
    }

    // transfers don't count against the ATM cash withdrawal limit
    public synchronized void debit(long amount) {
        if (amount > balance) throw new IllegalStateException("Insufficient balance");
        balance -= amount;
    }

    public synchronized void deposit(long amount) { balance += amount; }

    public synchronized long getBalance() { return balance; }

    private void resetLimitIfNewDay() {
        LocalDate today = LocalDate.now();
        if (!today.equals(limitDay)) {
            limitDay = today;
            withdrawnToday = 0;
        }
    }

    public String getAccountNumber() { return accountNumber; }
    public String getHolderName()    { return holderName; }
    public AccountType getType()     { return type; }

    @Override public String toString() { return holderName + " (" + accountNumber + ")"; }
}

// Bank-side card record: PIN hash, wrong attempts, status
class CardInfo {
    private final String cardNumber;
    private final String accountNumber;
    private final String pinHash;
    private CardStatus status = CardStatus.ACTIVE;
    private int failedAttempts;

    CardInfo(String cardNumber, String accountNumber, String pinHash) {
        this.cardNumber = cardNumber;
        this.accountNumber = accountNumber;
        this.pinHash = pinHash;
    }

    public void recordFailure(int maxAttempts) {
        failedAttempts++;
        if (failedAttempts >= maxAttempts) status = CardStatus.BLOCKED;
    }

    public void resetFailures() { failedAttempts = 0; }

    public String getCardNumber()    { return cardNumber; }
    public String getAccountNumber() { return accountNumber; }
    public String getPinHash()       { return pinHash; }
    public CardStatus getStatus()    { return status; }
    public int getFailedAttempts()   { return failedAttempts; }
}

// ===================== Transaction =====================

class Transaction {
    private final String id = UUID.randomUUID().toString().substring(0, 8);
    private final TransactionType type;
    private final String accountNumber;
    private final long amount;
    private final TransactionStatus status;
    private final String atmId;
    private final String note;
    private final LocalDateTime timestamp = LocalDateTime.now();

    Transaction(TransactionType type, String accountNumber, long amount, TransactionStatus status, String atmId, String note) {
        this.type = type;
        this.accountNumber = accountNumber;
        this.amount = amount;
        this.status = status;
        this.atmId = atmId;
        this.note = note;
    }

    public String getId()                { return id; }
    public TransactionType getType()     { return type; }
    public String getAccountNumber()     { return accountNumber; }
    public long getAmount()              { return amount; }
    public TransactionStatus getStatus() { return status; }
    public String getAtmId()             { return atmId; }
    public LocalDateTime getTimestamp()  { return timestamp; }

    @Override public String toString() {
        return "Txn#" + id + " " + type + " " + amount + " " + status + (note.isEmpty() ? "" : " (" + note + ")");
    }
}

// ===================== Notification (Observer) =====================

interface NotificationObserver {
    void notify(Account account, String message);
}

class SmsNotifier implements NotificationObserver {
    public void notify(Account account, String message) {
        System.out.println("  [SMS to " + account.getHolderName() + "] " + message);
    }
}

class EmailNotifier implements NotificationObserver {
    public void notify(Account account, String message) {
        System.out.println("  [EMAIL to " + account.getHolderName() + "] " + message);
    }
}

// ===================== Bank (Singleton) =====================

class BankService {
    private static final int MAX_PIN_ATTEMPTS = 3;
    private static volatile BankService instance;

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final Map<String, CardInfo> cards = new ConcurrentHashMap<>();
    private final List<Transaction> transactions = new CopyOnWriteArrayList<>();
    private final List<NotificationObserver> observers = new CopyOnWriteArrayList<>();

    private BankService() {}

    public static BankService getInstance() {
        if (instance == null) {
            synchronized (BankService.class) {
                if (instance == null) instance = new BankService();
            }
        }
        return instance;
    }

    public void openAccount(Account a) { accounts.put(a.getAccountNumber(), a); }

    public void issueCard(Card card, String accountNumber, String pin) {
        cards.put(card.getCardNumber(), new CardInfo(card.getCardNumber(), accountNumber, hash(pin)));
    }

    public void validateCard(Card card) {
        CardInfo info = cardInfo(card);
        if (info.getStatus() == CardStatus.BLOCKED) throw new IllegalStateException("Card blocked, contact your bank");
        if (card.isExpired()) throw new IllegalStateException("Card expired");
    }

    // wrong PIN 3 times -> card BLOCKED
    public boolean authenticate(Card card, String pin) {
        CardInfo info = cardInfo(card);
        synchronized (info) {
            if (info.getStatus() == CardStatus.BLOCKED) throw new IllegalStateException("Card blocked, contact your bank");
            if (info.getPinHash().equals(hash(pin))) {
                info.resetFailures();
                return true;
            }
            info.recordFailure(MAX_PIN_ATTEMPTS);
            if (info.getStatus() == CardStatus.BLOCKED)
                notifyAll(accounts.get(info.getAccountNumber()), "Card " + card + " blocked after 3 wrong PINs");
            return false;
        }
    }

    public boolean isBlocked(Card card)       { return cardInfo(card).getStatus() == CardStatus.BLOCKED; }
    public int remainingAttempts(Card card)   { return MAX_PIN_ATTEMPTS - cardInfo(card).getFailedAttempts(); }
    public Account getAccount(Card card)      { return accounts.get(cardInfo(card).getAccountNumber()); }

    public Transaction withdraw(Account a, long amount, String atmId) {
        try {
            a.withdraw(amount);
        } catch (IllegalStateException e) {
            record(TransactionType.WITHDRAWAL, a, amount, TransactionStatus.FAILED, atmId, e.getMessage());
            throw e;
        }
        Transaction t = record(TransactionType.WITHDRAWAL, a, amount, TransactionStatus.SUCCESS, atmId, "");
        notifyAll(a, "Rs " + amount + " withdrawn at " + atmId + ". Balance Rs " + a.getBalance());
        return t;
    }

    public Transaction deposit(Account a, long amount, String atmId) {
        a.deposit(amount);
        Transaction t = record(TransactionType.DEPOSIT, a, amount, TransactionStatus.SUCCESS, atmId, "");
        notifyAll(a, "Rs " + amount + " deposited at " + atmId + ". Balance Rs " + a.getBalance());
        return t;
    }

    public long balanceInquiry(Account a, String atmId) {
        record(TransactionType.BALANCE_INQUIRY, a, 0, TransactionStatus.SUCCESS, atmId, "");
        return a.getBalance();
    }

    // lock both accounts in a fixed order (by account number) -> no deadlock on A->B + B->A
    public Transaction transfer(Account from, String toAccountNumber, long amount, String atmId) {
        Account to = accounts.get(toAccountNumber);
        if (to == null) throw new IllegalArgumentException("Unknown account " + toAccountNumber);
        if (to == from) throw new IllegalArgumentException("Cannot transfer to the same account");

        Account first  = from.getAccountNumber().compareTo(to.getAccountNumber()) < 0 ? from : to;
        Account second = first == from ? to : from;
        synchronized (first) {
            synchronized (second) {
                try {
                    from.debit(amount);
                } catch (IllegalStateException e) {
                    record(TransactionType.TRANSFER, from, amount, TransactionStatus.FAILED, atmId, e.getMessage());
                    throw e;
                }
                to.deposit(amount);
            }
        }
        Transaction t = record(TransactionType.TRANSFER, from, amount, TransactionStatus.SUCCESS, atmId, "to " + to);
        notifyAll(from, "Rs " + amount + " sent to " + to.getHolderName() + ". Balance Rs " + from.getBalance());
        notifyAll(to, "Rs " + amount + " received from " + from.getHolderName() + ". Balance Rs " + to.getBalance());
        return t;
    }

    public List<Transaction> getTransactions(Account a) {
        List<Transaction> out = new ArrayList<>();
        for (Transaction t : transactions) if (t.getAccountNumber().equals(a.getAccountNumber())) out.add(t);
        return out;
    }

    public void addObserver(NotificationObserver o)    { observers.add(o); }
    public void removeObserver(NotificationObserver o) { observers.remove(o); }

    private Transaction record(TransactionType type, Account a, long amount, TransactionStatus s, String atmId, String note) {
        Transaction t = new Transaction(type, a.getAccountNumber(), amount, s, atmId, note);
        transactions.add(t);
        return t;
    }

    private CardInfo cardInfo(Card card) {
        CardInfo info = cards.get(card.getCardNumber());
        if (info == null) throw new IllegalStateException("Unknown card");
        return info;
    }

    // real banks use a salted slow hash (bcrypt) inside an HSM; SHA-256 keeps the demo simple
    private static String hash(String pin) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(pin.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void notifyAll(Account a, String msg) { observers.forEach(o -> o.notify(a, msg)); }
}

// ===================== Cash dispenser (Chain of Responsibility) =====================

// each handler pays out as many of its notes as it can, then passes the rest down the chain
class NoteDispenser {
    private final int denomination;
    private NoteDispenser next;

    NoteDispenser(int denomination) { this.denomination = denomination; }

    public NoteDispenser setNext(NoteDispenser next) {
        this.next = next;
        return next;
    }

    public boolean plan(long remaining, Map<Integer, Integer> inventory, Map<Integer, Integer> out) {
        int use = (int) Math.min(remaining / denomination, inventory.getOrDefault(denomination, 0));
        if (use > 0) out.put(denomination, use);
        remaining -= (long) use * denomination;
        if (remaining == 0) return true;
        return next != null && next.plan(remaining, inventory, out);
    }

    public int getDenomination() { return denomination; }
}

class CashDispenser {
    private final Map<Integer, Integer> notes = new TreeMap<>(Comparator.reverseOrder());   // denomination -> count
    private final NoteDispenser chain;

    CashDispenser() {
        chain = new NoteDispenser(500);
        chain.setNext(new NoteDispenser(200)).setNext(new NoteDispenser(100));
        for (int d : new int[]{500, 200, 100}) notes.put(d, 0);
    }

    public synchronized void load(int denomination, int count) {
        if (!notes.containsKey(denomination)) throw new IllegalArgumentException("Unsupported note " + denomination);
        notes.merge(denomination, count, Integer::sum);
    }

    // take the notes out of inventory now; returns null if this amount can't be made
    public synchronized Map<Integer, Integer> reserve(long amount) {
        Map<Integer, Integer> plan = new LinkedHashMap<>();
        if (!chain.plan(amount, notes, plan)) return null;
        plan.forEach((d, c) -> notes.merge(d, -c, Integer::sum));
        return plan;
    }

    // put reserved notes back (bank rejected the withdrawal)
    public synchronized void restore(Map<Integer, Integer> plan) {
        plan.forEach((d, c) -> notes.merge(d, c, Integer::sum));
    }

    public synchronized long totalCash() {
        long sum = 0;
        for (Map.Entry<Integer, Integer> e : notes.entrySet()) sum += (long) e.getKey() * e.getValue();
        return sum;
    }

    public synchronized Map<Integer, Integer> getNotes() { return new LinkedHashMap<>(notes); }
}

// ===================== ATM states (State) =====================

interface ATMState {
    String name();

    default void insertCard(ATM atm, Card card)                      { throw invalid("insert card"); }
    default void enterPin(ATM atm, String pin)                       { throw invalid("enter PIN"); }
    default long checkBalance(ATM atm)                               { throw invalid("check balance"); }
    default Map<Integer, Integer> withdraw(ATM atm, long amount)     { throw invalid("withdraw"); }
    default void deposit(ATM atm, long amount)                       { throw invalid("deposit"); }
    default void transfer(ATM atm, String toAccount, long amount)    { throw invalid("transfer"); }
    default void ejectCard(ATM atm)                                  { throw invalid("eject card"); }

    default IllegalStateException invalid(String op) {
        return new IllegalStateException("Cannot " + op + " in " + name() + " state");
    }
}

class IdleState implements ATMState {
    public String name() { return "IDLE"; }

    public void insertCard(ATM atm, Card card) {
        atm.getBank().validateCard(card);
        atm.setCurrentCard(card);
        atm.setState(new CardInsertedState());
        atm.display("Card " + card + " accepted. Enter PIN");
    }
}

class CardInsertedState implements ATMState {
    public String name() { return "CARD_INSERTED"; }

    public void enterPin(ATM atm, String pin) {
        BankService bank = atm.getBank();
        Card card = atm.getCurrentCard();
        if (bank.authenticate(card, pin)) {
            atm.setCurrentAccount(bank.getAccount(card));
            atm.setState(new AuthenticatedState());
            atm.display("PIN OK. Welcome " + atm.getCurrentAccount().getHolderName());
        } else if (bank.isBlocked(card)) {
            atm.endSession();
            throw new IllegalStateException("Wrong PIN. Card blocked and retained");
        } else {
            throw new IllegalStateException("Wrong PIN, " + bank.remainingAttempts(card) + " attempts left");
        }
    }

    public void ejectCard(ATM atm) { atm.endSession(); }
}

class AuthenticatedState implements ATMState {
    public String name() { return "AUTHENTICATED"; }

    public long checkBalance(ATM atm) {
        return atm.getBank().balanceInquiry(atm.getCurrentAccount(), atm.getId());
    }

    // reserve notes -> debit account -> dispense; restore notes if the bank says no
    public Map<Integer, Integer> withdraw(ATM atm, long amount) {
        if (amount <= 0 || amount % 100 != 0) throw new IllegalArgumentException("Amount must be a multiple of 100");
        Map<Integer, Integer> notes = atm.getDispenser().reserve(amount);
        if (notes == null) throw new IllegalStateException("ATM cannot dispense " + amount + " right now");
        try {
            atm.getBank().withdraw(atm.getCurrentAccount(), amount, atm.getId());
        } catch (IllegalStateException e) {
            atm.getDispenser().restore(notes);
            throw e;
        }
        return notes;
    }

    public void deposit(ATM atm, long amount) {
        if (amount <= 0 || amount % 100 != 0) throw new IllegalArgumentException("Amount must be a multiple of 100");
        atm.getBank().deposit(atm.getCurrentAccount(), amount, atm.getId());
    }

    public void transfer(ATM atm, String toAccount, long amount) {
        if (amount <= 0) throw new IllegalArgumentException("Amount must be positive");
        atm.getBank().transfer(atm.getCurrentAccount(), toAccount, amount, atm.getId());
    }

    public void ejectCard(ATM atm) { atm.endSession(); }
}

class OutOfServiceState implements ATMState {
    public String name() { return "OUT_OF_SERVICE"; }
}

// ===================== ATM (State context) =====================

class ATM {
    private final String id;
    private final String location;
    private final BankService bank;
    private final CashDispenser dispenser = new CashDispenser();
    private ATMState state = new IdleState();
    private Card currentCard;
    private Account currentAccount;
    private boolean displayOn = true;

    ATM(String id, String location, BankService bank) {
        this.id = id;
        this.location = location;
        this.bank = bank;
    }

    // customer actions -> delegated to the current state (one customer at a time)
    public synchronized void insertCard(Card card)                  { state.insertCard(this, card); }
    public synchronized void enterPin(String pin)                   { state.enterPin(this, pin); }
    public synchronized long checkBalance()                         { return state.checkBalance(this); }
    public synchronized Map<Integer, Integer> withdraw(long amount) { return state.withdraw(this, amount); }
    public synchronized void deposit(long amount)                   { state.deposit(this, amount); }
    public synchronized void transfer(String toAccount, long amount){ state.transfer(this, toAccount, amount); }
    public synchronized void ejectCard()                            { state.ejectCard(this); }

    // operator actions
    public synchronized void setOutOfService() {
        if (currentCard != null) display("Returning card " + currentCard);
        currentCard = null;
        currentAccount = null;
        state = new OutOfServiceState();
    }

    public synchronized void refill(int denomination, int count) {
        if (!(state instanceof OutOfServiceState)) throw new IllegalStateException("Put ATM out of service before refilling");
        dispenser.load(denomination, count);
    }

    public synchronized void backInService() { state = new IdleState(); }

    // ATM screen
    void display(String msg) { if (displayOn) System.out.println("  [" + id + " screen] " + msg); }
    public void setDisplayOn(boolean on) { this.displayOn = on; }

    // used by states
    void endSession() {
        currentCard = null;
        currentAccount = null;
        state = new IdleState();
    }

    void setState(ATMState s)          { this.state = s; }
    void setCurrentCard(Card c)        { this.currentCard = c; }
    void setCurrentAccount(Account a)  { this.currentAccount = a; }

    public String getId()              { return id; }
    public String getLocation()        { return location; }
    public BankService getBank()       { return bank; }
    public CashDispenser getDispenser(){ return dispenser; }
    public Card getCurrentCard()       { return currentCard; }
    public Account getCurrentAccount() { return currentAccount; }
    public synchronized String getStateName() { return state.name(); }
}

// ===================== Demo =====================

public class ATMSystem {

    private static void attempt(String label, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            System.out.println("  " + label + " rejected: " + e.getMessage());
        }
    }

    public static void main(String[] args) throws InterruptedException {
        BankService bank = BankService.getInstance();
        SmsNotifier sms = new SmsNotifier();
        bank.addObserver(sms);

        Account alice = new Account("ACC1001", "Alice", AccountType.SAVINGS, 20000, 10000);
        Account bob   = new Account("ACC1002", "Bob",   AccountType.SAVINGS, 5000, 10000);
        bank.openAccount(alice);
        bank.openAccount(bob);
        Card aliceCard = new Card("4111222233331001", "Alice", YearMonth.of(2029, 12));
        Card bobCard   = new Card("4111222233331002", "Bob",   YearMonth.of(2029, 12));
        bank.issueCard(aliceCard, "ACC1001", "1234");
        bank.issueCard(bobCard,   "ACC1002", "4321");

        ATM atm1 = new ATM("ATM-1", "T Nagar", bank);
        atm1.setOutOfService();
        atm1.refill(500, 20);
        atm1.refill(200, 20);
        atm1.refill(100, 20);
        atm1.backInService();
        System.out.println("Setup: ATM-1 cash " + atm1.getDispenser().getNotes() + " = Rs " + atm1.getDispenser().totalCash());

        // 1. Wrong state
        System.out.println("\n1. Withdraw before inserting a card");
        attempt("Withdraw", () -> atm1.withdraw(1000));

        // 2. Card + PIN
        System.out.println("\n2. Alice inserts card, wrong PIN once, then correct PIN");
        atm1.insertCard(aliceCard);
        attempt("PIN", () -> atm1.enterPin("0000"));
        atm1.enterPin("1234");
        System.out.println("  State: " + atm1.getStateName());

        // 3. Balance + withdraw
        System.out.println("\n3. Balance, then withdraw 2800");
        System.out.println("  Balance: Rs " + atm1.checkBalance());
        System.out.println("  Dispensed notes: " + atm1.withdraw(2800));

        // 4. Validation + limits
        System.out.println("\n4. Invalid amount, then over the daily limit (10000/day)");
        attempt("Withdraw 250", () -> atm1.withdraw(250));
        attempt("Withdraw 7500", () -> atm1.withdraw(7500));
        System.out.println("  ATM cash unchanged after failed withdrawal: Rs " + atm1.getDispenser().totalCash());

        // 5. Deposit + transfer
        System.out.println("\n5. Deposit 3000, transfer 1000 to Bob");
        atm1.deposit(3000);
        atm1.transfer("ACC1002", 1000);
        attempt("Transfer to unknown", () -> atm1.transfer("ACC9999", 100));

        // 6. Eject
        System.out.println("\n6. Alice ejects card");
        atm1.ejectCard();
        System.out.println("  State: " + atm1.getStateName());
        System.out.println("  Alice's transactions:");
        bank.getTransactions(alice).forEach(t -> System.out.println("    " + t));

        // 7. Card blocked after 3 wrong PINs
        System.out.println("\n7. Bob enters wrong PIN 3 times");
        atm1.insertCard(bobCard);
        for (int i = 0; i < 3; i++) attempt("PIN", () -> atm1.enterPin("1111"));
        System.out.println("  State: " + atm1.getStateName());
        attempt("Insert blocked card", () -> atm1.insertCard(bobCard));

        // 8. ATM low on cash -> out of service -> refill
        System.out.println("\n8. ATM-2 has only 5 x 100 notes");
        ATM atm2 = new ATM("ATM-2", "Adyar", bank);
        atm2.setOutOfService();
        atm2.refill(100, 5);
        atm2.backInService();
        atm2.insertCard(aliceCard);
        atm2.enterPin("1234");
        attempt("Withdraw 1000", () -> atm2.withdraw(1000));
        System.out.println("  Operator takes ATM-2 out of service");
        atm2.setOutOfService();
        attempt("Insert card", () -> atm2.insertCard(aliceCard));
        atm2.refill(500, 10);
        atm2.backInService();
        atm2.insertCard(aliceCard);
        atm2.enterPin("1234");
        System.out.println("  Dispensed notes: " + atm2.withdraw(1000));
        atm2.ejectCard();

        bank.removeObserver(sms);   // keep the stress tests quiet

        // 9. Same account, 10 ATMs at once -> balance never goes negative
        System.out.println("\n9. Carol (balance 10000) withdraws 2000 at 10 ATMs at the same time");
        Account carol = new Account("ACC1003", "Carol", AccountType.CURRENT, 10000, 50000);
        bank.openAccount(carol);
        Card carolCard = new Card("4111222233331003", "Carol", YearMonth.of(2029, 12));
        bank.issueCard(carolCard, "ACC1003", "5555");
        List<Thread> threads = new ArrayList<>();
        List<String> ok = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 10; i++) {
            ATM atm = new ATM("ATM-C" + i, "City", bank);
            atm.setDisplayOn(false);
            atm.setOutOfService();
            atm.refill(500, 10);
            atm.backInService();
            Thread t = new Thread(() -> {
                atm.insertCard(carolCard);
                atm.enterPin("5555");
                try {
                    atm.withdraw(2000);
                    ok.add(atm.getId());
                } catch (IllegalStateException ignored) { }
                atm.ejectCard();
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join();
        System.out.println("  Successful: " + ok.size() + " (expected 5), Carol's balance: Rs " + carol.getBalance());

        // 10. Opposite transfers at once -> no deadlock, money conserved
        System.out.println("\n10. 1000 transfers X->Y and 1000 transfers Y->X at the same time");
        Account x = new Account("ACC2001", "X", AccountType.CURRENT, 100000, 0);
        Account y = new Account("ACC2002", "Y", AccountType.CURRENT, 100000, 0);
        bank.openAccount(x);
        bank.openAccount(y);
        Thread t1 = new Thread(() -> { for (int i = 0; i < 1000; i++) bank.transfer(x, "ACC2002", 10, "NET"); });
        Thread t2 = new Thread(() -> { for (int i = 0; i < 1000; i++) bank.transfer(y, "ACC2001", 10, "NET"); });
        t1.start();
        t2.start();
        t1.join(5000);
        t2.join(5000);
        boolean deadlock = t1.isAlive() || t2.isAlive();
        System.out.println("  Deadlock: " + deadlock + ", X + Y = " + (x.getBalance() + y.getBalance()) + " (expected 200000)");
        if (deadlock) System.exit(1);
    }
}
