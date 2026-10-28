import java.time.LocalDate;
import java.util.*;

/**
 * Skeleton for the Library Management System LLD: classes, fields and method signatures only.
 * Every class is nested here so this file can sit next to LibrarySystem.java
 * without duplicate-class clashes. Getters/setters omitted.
 */
public class LibrarySkeleton {

    // ===================== Enums =====================

    enum BookItemStatus { AVAILABLE, LOANED }

    // ===================== Book & BookItem =====================

    static class Book {
        String isbn;
        String title;
        String author;
    }

    static class BookItem {
        String barcode;
        Book book;
        BookItemStatus status;

        synchronized boolean tryCheckout() {
            // TODO: AVAILABLE -> LOANED, else false
            throw new UnsupportedOperationException("TODO");
        }

        synchronized void markAvailable() { /* TODO */ }
    }

    // ===================== Member =====================

    static class Member {
        String id;
        String name;
        String email;
        List<Loan> activeLoans;

        synchronized void addLoan(Loan l) { /* TODO */ }

        synchronized void removeLoan(Loan l) { /* TODO */ }
    }

    // ===================== Loan =====================

    static class Loan {
        String id;
        BookItem item;
        Member member;
        LocalDate issueDate;
        LocalDate dueDate;
        LocalDate returnDate;   // null while the book is out

        void close(LocalDate on) { /* TODO */ }

        boolean isActive() {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }
    }

    // ===================== Search (Strategy) =====================

    interface SearchStrategy {
        boolean matches(Book book, String query);
    }

    static class TitleSearch implements SearchStrategy {
        public boolean matches(Book b, String q) { throw new UnsupportedOperationException("TODO"); }
    }

    static class AuthorSearch implements SearchStrategy {
        public boolean matches(Book b, String q) { throw new UnsupportedOperationException("TODO"); }
    }

    static class IsbnSearch implements SearchStrategy {
        public boolean matches(Book b, String q) { throw new UnsupportedOperationException("TODO"); }
    }

    // ===================== Extension point (Observer) =====================

    interface LoanListener {
        void onBorrow(Loan loan);
        void onReturn(Loan loan);
    }

    static class ConsoleNotifier implements LoanListener {
        public void onBorrow(Loan l) { /* TODO */ }
        public void onReturn(Loan l) { /* TODO */ }
    }

    // ===================== Services =====================

    static class CatalogService {
        Map<String, Book> books;       // isbn -> book
        Map<String, BookItem> items;   // barcode -> copy

        void addBook(Book b) { /* TODO */ }

        void addItem(BookItem item) { /* TODO: book must exist */ }

        List<Book> search(SearchStrategy strategy, String query) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }

        List<BookItem> getAvailableCopies(Book b) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }

        BookItem getItem(String barcode) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }
    }

    static class MemberService {
        Map<String, Member> members;   // id -> member

        void register(Member m) { /* TODO */ }

        Member getMember(String id) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }
    }

    static class LoanService {
        Map<String, Loan> activeLoans;   // barcode -> loan
        List<Loan> history;
        List<LoanListener> listeners;

        Loan borrow(Member m, BookItem item) {
            // TODO: item.tryCheckout(), create Loan, notify listeners
            throw new UnsupportedOperationException("TODO");
        }

        Loan returnBook(BookItem item) {
            // TODO: close loan, item.markAvailable(), notify listeners
            throw new UnsupportedOperationException("TODO");
        }

        Loan getActiveLoan(String barcode) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }

        void addListener(LoanListener l) { /* TODO */ }
    }

    // ===================== Facade + Singleton =====================

    static class LibrarySystem {
        static volatile LibrarySystem instance;

        CatalogService catalog;
        MemberService members;
        LoanService loans;

        private LibrarySystem() {}

        static LibrarySystem getInstance() {
            // TODO: double-checked locking
            throw new UnsupportedOperationException("TODO");
        }

        void addBook(Book b) { /* TODO */ }

        void addCopy(BookItem item) { /* TODO */ }

        void registerMember(Member m) { /* TODO */ }

        List<Book> search(SearchStrategy s, String query) { throw new UnsupportedOperationException("TODO"); }

        Loan borrow(String memberId, String barcode) { throw new UnsupportedOperationException("TODO"); }

        Loan returnBook(String barcode) { throw new UnsupportedOperationException("TODO"); }

        List<Loan> loansOf(String memberId) { throw new UnsupportedOperationException("TODO"); }

        Loan whoHas(String barcode) { throw new UnsupportedOperationException("TODO"); }
    }
}
