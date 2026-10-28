import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

/**
 * Skeleton for the ATM System LLD: classes, fields and method signatures only.
 * Every class is nested here so this file can sit next to ATMSystem.java
 * without duplicate-class clashes. Getters/setters omitted.
 */
public class ATMSkeleton {

    // ===================== Enums =====================

    enum AccountType { SAVINGS, CURRENT }

    enum CardStatus { ACTIVE, BLOCKED }

    enum TransactionType { WITHDRAWAL, DEPOSIT, BALANCE_INQUIRY, TRANSFER }

    enum TransactionStatus { SUCCESS, FAILED }

    // ===================== Card & Account =====================

    static class Card {
        String cardNumber;
        String holderName;
        YearMonth expiry;

        boolean isExpired() {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }
    }

    static class Account {
        String accountNumber;
        String holderName;
        AccountType type;
        long balance;
        long dailyWithdrawLimit;
        long withdrawnToday;
        LocalDate limitDay;

        synchronized void withdraw(long amount) {
            // TODO: reset limit on new day, check balance + daily limit, debit
        }

        synchronized void debit(long amount) { /* TODO: for transfers, no daily limit */ }

        synchronized void deposit(long amount) { /* TODO */ }

        synchronized long getBalance() {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }
    }

    // Bank-side card record
    static class CardInfo {
        String cardNumber;
        String accountNumber;
        String pinHash;
        CardStatus status;
        int failedAttempts;

        void recordFailure(int maxAttempts) { /* TODO: attempts++, block at max */ }

        void resetFailures() { /* TODO */ }
    }

    static class Transaction {
        String id;
        TransactionType type;
        String accountNumber;
        long amount;
        TransactionStatus status;
        String atmId;
        LocalDateTime timestamp;
    }

    // ===================== Notification (Observer) =====================

    interface NotificationObserver {
        void notify(Account account, String message);
    }

    static class SmsNotifier implements NotificationObserver {
        public void notify(Account account, String message) { /* TODO */ }
    }

    static class EmailNotifier implements NotificationObserver {
        public void notify(Account account, String message) { /* TODO */ }
    }

    // ===================== Bank (Singleton) =====================

    static class BankService {
        private static volatile BankService instance;
        Map<String, Account> accounts;
        Map<String, CardInfo> cards;
        List<Transaction> transactions;
        List<NotificationObserver> observers;

        private BankService() {}

        static BankService getInstance() {
            // TODO: double-checked locking
            throw new UnsupportedOperationException("TODO");
        }

        void openAccount(Account a) { /* TODO */ }

        void issueCard(Card card, String accountNumber, String pin) { /* TODO: store PIN hash */ }

        void validateCard(Card card) { /* TODO: known, not blocked, not expired */ }

        boolean authenticate(Card card, String pin) {
            // TODO: compare hash; 3 failures -> BLOCKED
            throw new UnsupportedOperationException("TODO");
        }

        Account getAccount(Card card) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }

        Transaction withdraw(Account a, long amount, String atmId) {
            // TODO: account.withdraw, record SUCCESS/FAILED, notify
            throw new UnsupportedOperationException("TODO");
        }

        Transaction deposit(Account a, long amount, String atmId) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }

        long balanceInquiry(Account a, String atmId) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }

        Transaction transfer(Account from, String toAccountNumber, long amount, String atmId) {
            // TODO: lock both accounts in account-number order (no deadlock), debit + deposit
            throw new UnsupportedOperationException("TODO");
        }

        void addObserver(NotificationObserver o) { /* TODO */ }
    }

    // ===================== Cash dispenser (Chain of Responsibility) =====================

    static class NoteDispenser {
        int denomination;
        NoteDispenser next;

        NoteDispenser setNext(NoteDispenser next) {
            // TODO: link and return next (for chaining)
            throw new UnsupportedOperationException("TODO");
        }

        boolean plan(long remaining, Map<Integer, Integer> inventory, Map<Integer, Integer> out) {
            // TODO: take min(remaining / denom, available), pass the rest to next
            throw new UnsupportedOperationException("TODO");
        }
    }

    static class CashDispenser {
        Map<Integer, Integer> notes;   // denomination -> count
        NoteDispenser chain;           // 500 -> 200 -> 100

        synchronized void load(int denomination, int count) { /* TODO */ }

        synchronized Map<Integer, Integer> reserve(long amount) {
            // TODO: plan via chain, deduct notes, null if impossible
            throw new UnsupportedOperationException("TODO");
        }

        synchronized void restore(Map<Integer, Integer> plan) { /* TODO */ }

        synchronized long totalCash() {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }
    }

    // ===================== ATM states (State) =====================

    interface ATMState {
        String name();

        default void insertCard(ATM atm, Card card)                   { throw new IllegalStateException("TODO"); }
        default void enterPin(ATM atm, String pin)                    { throw new IllegalStateException("TODO"); }
        default long checkBalance(ATM atm)                            { throw new IllegalStateException("TODO"); }
        default Map<Integer, Integer> withdraw(ATM atm, long amount)  { throw new IllegalStateException("TODO"); }
        default void deposit(ATM atm, long amount)                    { throw new IllegalStateException("TODO"); }
        default void transfer(ATM atm, String toAccount, long amount) { throw new IllegalStateException("TODO"); }
        default void ejectCard(ATM atm)                               { throw new IllegalStateException("TODO"); }
    }

    static class IdleState implements ATMState {
        public String name() { return "IDLE"; }

        public void insertCard(ATM atm, Card card) {
            // TODO: bank.validateCard, set card, -> CardInsertedState
        }
    }

    static class CardInsertedState implements ATMState {
        public String name() { return "CARD_INSERTED"; }

        public void enterPin(ATM atm, String pin) {
            // TODO: ok -> AuthenticatedState; blocked -> retain card, Idle; else attempts left
        }

        public void ejectCard(ATM atm) { /* TODO: end session */ }
    }

    static class AuthenticatedState implements ATMState {
        public String name() { return "AUTHENTICATED"; }

        public long checkBalance(ATM atm) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }

        public Map<Integer, Integer> withdraw(ATM atm, long amount) {
            // TODO: validate multiple of 100, reserve notes, bank.withdraw, restore notes on failure
            throw new UnsupportedOperationException("TODO");
        }

        public void deposit(ATM atm, long amount) { /* TODO */ }

        public void transfer(ATM atm, String toAccount, long amount) { /* TODO */ }

        public void ejectCard(ATM atm) { /* TODO: end session */ }
    }

    static class OutOfServiceState implements ATMState {
        public String name() { return "OUT_OF_SERVICE"; }
    }

    // ===================== ATM (State context) =====================

    static class ATM {
        String id;
        String location;
        BankService bank;
        CashDispenser dispenser;
        ATMState state;
        Card currentCard;
        Account currentAccount;

        synchronized void insertCard(Card card) { /* TODO: delegate to state */ }

        synchronized void enterPin(String pin) { /* TODO */ }

        synchronized long checkBalance() {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }

        synchronized Map<Integer, Integer> withdraw(long amount) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }

        synchronized void deposit(long amount) { /* TODO */ }

        synchronized void transfer(String toAccount, long amount) { /* TODO */ }

        synchronized void ejectCard() { /* TODO */ }

        synchronized void setOutOfService() { /* TODO: return card, -> OutOfServiceState */ }

        synchronized void refill(int denomination, int count) { /* TODO: only when out of service */ }

        synchronized void backInService() { /* TODO: -> IdleState */ }

        void endSession() { /* TODO: clear card/account, -> IdleState */ }
    }

    // ===================== Demo =====================

    public static void main(String[] args) {
        // TODO:
        // 1. Open accounts, issue cards, load ATM cash
        // 2. Operation in wrong state -> rejected
        // 3. Insert card, wrong PIN, correct PIN
        // 4. Balance, withdraw (note breakdown), invalid amount, daily limit
        // 5. Deposit, transfer, eject
        // 6. 3 wrong PINs -> card blocked
        // 7. ATM can't dispense -> out of service -> refill
        // 8. Same account at many ATMs at once -> no overdraft
        // 9. Opposite transfers at once -> no deadlock
    }
}
