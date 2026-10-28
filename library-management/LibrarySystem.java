import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// ===================== Enums =====================

enum BookItemStatus { AVAILABLE, LOANED }

// ===================== Book & BookItem =====================

// the title in the catalog (one per ISBN)
class Book {
    private final String isbn;
    private final String title;
    private final String author;

    Book(String isbn, String title, String author) {
        this.isbn = isbn;
        this.title = title;
        this.author = author;
    }

    public String getIsbn()   { return isbn; }
    public String getTitle()  { return title; }
    public String getAuthor() { return author; }

    @Override public String toString() { return "'" + title + "'"; }
}

// one physical copy (many per Book)
class BookItem {
    private final String barcode;
    private final Book book;
    private BookItemStatus status = BookItemStatus.AVAILABLE;

    BookItem(String barcode, Book book) {
        this.barcode = barcode;
        this.book = book;
    }

    // check + lend atomically -> a copy can never be lent twice
    public synchronized boolean tryCheckout() {
        if (status != BookItemStatus.AVAILABLE) return false;
        status = BookItemStatus.LOANED;
        return true;
    }

    public synchronized void markAvailable() { status = BookItemStatus.AVAILABLE; }

    public String getBarcode()                     { return barcode; }
    public Book getBook()                          { return book; }
    public synchronized BookItemStatus getStatus() { return status; }

    @Override public synchronized String toString() { return book + "[" + barcode + " " + status + "]"; }
}

// ===================== Member =====================

class Member {
    private final String id;
    private final String name;
    private final String email;
    private final List<Loan> activeLoans = new ArrayList<>();

    Member(String id, String name, String email) {
        this.id = id;
        this.name = name;
        this.email = email;
    }

    public synchronized void addLoan(Loan l)          { activeLoans.add(l); }
    public synchronized void removeLoan(Loan l)       { activeLoans.remove(l); }
    public synchronized List<Loan> getActiveLoans()   { return new ArrayList<>(activeLoans); }

    public String getId()    { return id; }
    public String getName()  { return name; }
    public String getEmail() { return email; }

    @Override public String toString() { return name; }
}

// ===================== Loan =====================

// who borrowed which copy, and when
class Loan {
    private static final int LOAN_DAYS = 14;

    private final String id = UUID.randomUUID().toString().substring(0, 8);
    private final BookItem item;
    private final Member member;
    private final LocalDate issueDate;
    private final LocalDate dueDate;
    private LocalDate returnDate;     // null while the book is out

    Loan(BookItem item, Member member, LocalDate issueDate) {
        this.item = item;
        this.member = member;
        this.issueDate = issueDate;
        this.dueDate = issueDate.plusDays(LOAN_DAYS);
    }

    public void close(LocalDate on) { this.returnDate = on; }
    public boolean isActive()       { return returnDate == null; }

    public String getId()            { return id; }
    public BookItem getItem()        { return item; }
    public Member getMember()        { return member; }
    public LocalDate getIssueDate()  { return issueDate; }
    public LocalDate getDueDate()    { return dueDate; }
    public LocalDate getReturnDate() { return returnDate; }

    @Override public String toString() {
        return item.getBook() + "[" + item.getBarcode() + "] " + member + " issued " + issueDate
                + (isActive() ? " due " + dueDate : " returned " + returnDate);
    }
}

// ===================== Search (Strategy) =====================

interface SearchStrategy {
    boolean matches(Book book, String query);
}

class TitleSearch implements SearchStrategy {
    public boolean matches(Book b, String q) { return b.getTitle().toLowerCase().contains(q.toLowerCase()); }
}

class AuthorSearch implements SearchStrategy {
    public boolean matches(Book b, String q) { return b.getAuthor().toLowerCase().contains(q.toLowerCase()); }
}

class IsbnSearch implements SearchStrategy {
    public boolean matches(Book b, String q) { return b.getIsbn().equals(q); }
}

// ===================== Extension point (Observer) =====================

// reservations, fines, notifications plug in here without changing LoanService
interface LoanListener {
    void onBorrow(Loan loan);
    void onReturn(Loan loan);
}

class ConsoleNotifier implements LoanListener {
    public void onBorrow(Loan l) { System.out.println("  [NOTIFY " + l.getMember().getEmail() + "] Borrowed " + l.getItem().getBook() + ", due " + l.getDueDate()); }
    public void onReturn(Loan l) { System.out.println("  [NOTIFY " + l.getMember().getEmail() + "] Returned " + l.getItem().getBook()); }
}

// ===================== Services =====================

class CatalogService {
    private final Map<String, Book> books = new ConcurrentHashMap<>();       // isbn -> book
    private final Map<String, BookItem> items = new ConcurrentHashMap<>();   // barcode -> copy

    public void addBook(Book b) { books.put(b.getIsbn(), b); }

    public void addItem(BookItem item) {
        if (!books.containsKey(item.getBook().getIsbn())) throw new IllegalArgumentException("Add the book first");
        items.put(item.getBarcode(), item);
    }

    public List<Book> search(SearchStrategy strategy, String query) {
        return books.values().stream()
                .filter(b -> strategy.matches(b, query))
                .sorted(Comparator.comparing(Book::getTitle))
                .collect(Collectors.toList());
    }

    public List<BookItem> getAvailableCopies(Book b) {
        return items.values().stream()
                .filter(i -> i.getBook() == b && i.getStatus() == BookItemStatus.AVAILABLE)
                .collect(Collectors.toList());
    }

    public BookItem getItem(String barcode) {
        BookItem i = items.get(barcode);
        if (i == null) throw new IllegalArgumentException("No copy " + barcode);
        return i;
    }
}

class MemberService {
    private final Map<String, Member> members = new ConcurrentHashMap<>();   // id -> member

    public void register(Member m) { members.put(m.getId(), m); }

    public Member getMember(String id) {
        Member m = members.get(id);
        if (m == null) throw new IllegalArgumentException("No member " + id);
        return m;
    }
}

class LoanService {
    private final Map<String, Loan> activeLoans = new ConcurrentHashMap<>();   // barcode -> loan
    private final List<Loan> history = Collections.synchronizedList(new ArrayList<>());
    private final List<LoanListener> listeners = new ArrayList<>();

    public Loan borrow(Member m, BookItem item) {
        if (!item.tryCheckout()) throw new IllegalStateException(item + " is not available");
        Loan loan = new Loan(item, m, LocalDate.now());
        activeLoans.put(item.getBarcode(), loan);
        history.add(loan);
        m.addLoan(loan);
        listeners.forEach(l -> l.onBorrow(loan));
        return loan;
    }

    public Loan returnBook(BookItem item) {
        Loan loan = activeLoans.remove(item.getBarcode());
        if (loan == null) throw new IllegalStateException(item.getBarcode() + " is not checked out");
        loan.close(LocalDate.now());
        loan.getMember().removeLoan(loan);
        item.markAvailable();
        listeners.forEach(l -> l.onReturn(loan));
        return loan;
    }

    public Loan getActiveLoan(String barcode) { return activeLoans.get(barcode); }
    public List<Loan> getHistory()            { return new ArrayList<>(history); }
    public void addListener(LoanListener l)   { listeners.add(l); }
}

// ===================== Facade + Singleton =====================

public class LibrarySystem {
    private static volatile LibrarySystem instance;

    private final CatalogService catalog = new CatalogService();
    private final MemberService members = new MemberService();
    private final LoanService loans = new LoanService();

    private LibrarySystem() {}

    public static LibrarySystem getInstance() {
        if (instance == null) {
            synchronized (LibrarySystem.class) {
                if (instance == null) instance = new LibrarySystem();
            }
        }
        return instance;
    }

    public void addBook(Book b)          { catalog.addBook(b); }
    public void addCopy(BookItem item)   { catalog.addItem(item); }
    public void registerMember(Member m) { members.register(m); }

    public List<Book> search(SearchStrategy s, String query) { return catalog.search(s, query); }
    public List<BookItem> availableCopies(Book b)            { return catalog.getAvailableCopies(b); }

    public Loan borrow(String memberId, String barcode) {
        return loans.borrow(members.getMember(memberId), catalog.getItem(barcode));
    }

    public Loan returnBook(String barcode)              { return loans.returnBook(catalog.getItem(barcode)); }
    public List<Loan> loansOf(String memberId)          { return members.getMember(memberId).getActiveLoans(); }
    public Loan whoHas(String barcode)                  { return loans.getActiveLoan(barcode); }
    public void addLoanListener(LoanListener l)         { loans.addListener(l); }

    // ===================== Demo =====================

    private static void attempt(String label, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            System.out.println("  " + label + " rejected: " + e.getMessage());
        }
    }

    public static void main(String[] args) throws InterruptedException {
        LibrarySystem lib = LibrarySystem.getInstance();

        Book cleanCode = new Book("978-0132350884", "Clean Code", "Robert C. Martin");
        Book patterns  = new Book("978-0201633610", "Design Patterns", "Erich Gamma");
        Book refactor  = new Book("978-0134757599", "Refactoring", "Martin Fowler");
        for (Book b : List.of(cleanCode, patterns, refactor)) lib.addBook(b);
        lib.addCopy(new BookItem("CC-1", cleanCode));
        lib.addCopy(new BookItem("CC-2", cleanCode));
        lib.addCopy(new BookItem("DP-1", patterns));
        lib.addCopy(new BookItem("RF-1", refactor));

        lib.registerMember(new Member("M1", "Alice", "alice@mail.com"));
        lib.registerMember(new Member("M2", "Bob",   "bob@mail.com"));

        // 1. Search
        System.out.println("1. Search");
        System.out.println("  Title 'code'     -> " + lib.search(new TitleSearch(), "code"));
        System.out.println("  Author 'martin'  -> " + lib.search(new AuthorSearch(), "martin"));
        System.out.println("  ISBN             -> " + lib.search(new IsbnSearch(), "978-0201633610"));

        lib.addLoanListener(new ConsoleNotifier());

        // 2. Borrow
        System.out.println("\n2. Alice borrows Clean Code [CC-1] and Design Patterns [DP-1]");
        lib.borrow("M1", "CC-1");
        lib.borrow("M1", "DP-1");
        System.out.println("  Alice's loans: " + lib.loansOf("M1"));

        // 3. Copy already out
        System.out.println("\n3. Bob wants Design Patterns [DP-1], then Clean Code");
        attempt("Bob borrows DP-1", () -> lib.borrow("M2", "DP-1"));
        System.out.println("  Free copies of Clean Code: " + lib.availableCopies(cleanCode));
        lib.borrow("M2", "CC-2");

        // 4. Who has it?
        System.out.println("\n4. Who has DP-1? " + lib.whoHas("DP-1"));

        // 5. Return
        System.out.println("\n5. Alice returns Design Patterns, Bob borrows it");
        lib.returnBook("DP-1");
        attempt("Return DP-1 again", () -> lib.returnBook("DP-1"));
        lib.borrow("M2", "DP-1");
        System.out.println("  Bob's loans: " + lib.loansOf("M2"));

        // 6. Concurrency: 10 members grab the last copy at once -> exactly 1 winner
        System.out.println("\n6. 10 members grab Refactoring [RF-1] at once");
        List<String> winners = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String id = "C" + i;
            lib.registerMember(new Member(id, id, id.toLowerCase() + "@mail.com"));
            Thread t = new Thread(() -> {
                try {
                    lib.borrow(id, "RF-1");
                    winners.add(id);
                } catch (IllegalStateException ignored) { }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join();
        System.out.println("  Winners: " + winners + " (expected exactly 1)");
    }
}
