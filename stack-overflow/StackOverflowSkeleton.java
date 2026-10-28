import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Skeleton for the Stack Overflow / Q&A System LLD: classes, fields and method signatures only.
 * Every class is nested here so this file can sit next to StackOverflowSystem.java
 * without duplicate-class clashes. Getters/setters omitted.
 */
public class StackOverflowSkeleton {

    // ===================== Enums =====================

    enum VoteType {
        UP(1), DOWN(-1);

        final int value;

        VoteType(int value) { this.value = value; }
    }

    enum QuestionStatus { OPEN, CLOSED }

    enum Role { MEMBER, MODERATOR }

    enum Privilege {
        UPVOTE(15), COMMENT(50), DOWNVOTE(125);

        final int minReputation;

        Privilege(int minReputation) { this.minReputation = minReputation; }
    }

    enum Badge { STUDENT, TEACHER, SCHOLAR, NICE_ANSWER }

    enum EventType { QUESTION_ASKED, ANSWER_POSTED, COMMENT_ADDED, VOTED, ANSWER_ACCEPTED, QUESTION_CLOSED, BADGE_AWARDED }

    // ===================== User =====================

    static class User {
        String id;
        String name;
        Role role;
        int reputation;
        Set<Badge> badges;

        synchronized void changeReputation(int delta) { /* TODO: never below 1 */ }

        boolean can(Privilege p) {
            // TODO: moderator or reputation >= p.minReputation
            throw new UnsupportedOperationException("TODO");
        }

        synchronized boolean awardBadge(Badge b) {
            // TODO: true only the first time
            throw new UnsupportedOperationException("TODO");
        }
    }

    // ===================== Posts =====================

    static class Comment {
        String id;
        User author;
        String body;
        LocalDateTime createdAt;
    }

    static abstract class Post {
        String id;
        User author;
        String body;
        LocalDateTime createdAt;
        Map<String, VoteType> votes;   // userId -> vote
        int score;
        List<Comment> comments;

        synchronized VoteType vote(User voter, VoteType type) {
            // TODO: reject same vote twice, update score, return previous vote
            throw new UnsupportedOperationException("TODO");
        }

        synchronized VoteType removeVote(User voter) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }

        void addComment(Comment c) { /* TODO */ }
    }

    static class Tag {
        String name;
        AtomicInteger questionCount;
    }

    static class Question extends Post {
        String title;
        Set<Tag> tags;
        List<Answer> answers;
        Answer accepted;
        QuestionStatus status;
        AtomicInteger views;
        LocalDateTime lastActivity;

        synchronized void addAnswer(Answer a) { /* TODO: reject if CLOSED */ }

        synchronized Answer accept(Answer a) {
            // TODO: return previously accepted
            throw new UnsupportedOperationException("TODO");
        }

        synchronized void close(String reason) { /* TODO */ }

        synchronized void reopen() { /* TODO */ }

        int view() {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }
    }

    static class Answer extends Post {
        Question question;
    }

    // ===================== Reputation (Strategy) =====================

    interface ReputationPolicy {
        int authorDelta(Post post, VoteType type);
        int voterDelta(Post post, VoteType type);
        int acceptedAnswerBonus();
        int acceptorBonus();
    }

    static class DefaultReputationPolicy implements ReputationPolicy {
        public int authorDelta(Post post, VoteType type) { throw new UnsupportedOperationException("TODO: +10 / -2"); }
        public int voterDelta(Post post, VoteType type)  { throw new UnsupportedOperationException("TODO: -1 for downvoting answers"); }
        public int acceptedAnswerBonus()                 { return 15; }
        public int acceptorBonus()                       { return 2; }
    }

    // ===================== Search (Strategy) =====================

    interface SearchStrategy {
        boolean matches(Question q);
    }

    static class TagSearch implements SearchStrategy {
        String tag;

        public boolean matches(Question q) { throw new UnsupportedOperationException("TODO"); }
    }

    static class KeywordSearch implements SearchStrategy {
        String keyword;

        public boolean matches(Question q) { throw new UnsupportedOperationException("TODO"); }
    }

    static class AuthorSearch implements SearchStrategy {
        User author;

        public boolean matches(Question q) { throw new UnsupportedOperationException("TODO"); }
    }

    static class UnansweredSearch implements SearchStrategy {
        public boolean matches(Question q) { throw new UnsupportedOperationException("TODO"); }
    }

    enum SortOrder { NEWEST, VOTES, ACTIVE }   // TODO: each holds a Comparator<Question>

    // ===================== Events (Observer) =====================

    static class Event {
        EventType type;
        User actor;
        User recipient;
        Post post;
        String detail;
    }

    interface EventListener {
        void onEvent(Event e);
    }

    static class EventBus {
        List<EventListener> listeners;

        void subscribe(EventListener l) { /* TODO */ }

        void publish(Event e) { /* TODO */ }
    }

    static class NotificationListener implements EventListener {
        public void onEvent(Event e) { /* TODO: notify recipient (not yourself) */ }
    }

    static class BadgeListener implements EventListener {
        EventBus bus;

        public void onEvent(Event e) {
            // TODO: VOTED -> STUDENT / TEACHER / NICE_ANSWER, ANSWER_ACCEPTED -> SCHOLAR
        }
    }

    // ===================== Services =====================

    static class PostService {
        Map<String, Question> questions;
        Map<String, Answer> answers;
        Map<String, Tag> tags;
        EventBus bus;
        ReputationService reputation;

        Question ask(User author, String title, String body, List<String> tagNames) {
            // TODO: title >= 15 chars, 1-5 tags, publish QUESTION_ASKED
            throw new UnsupportedOperationException("TODO");
        }

        Answer answer(User author, String questionId, String body) {
            // TODO: question open, publish ANSWER_POSTED to asker
            throw new UnsupportedOperationException("TODO");
        }

        Comment comment(User author, String postId, String body) {
            // TODO: own post / answer on own question / COMMENT privilege
            throw new UnsupportedOperationException("TODO");
        }

        void accept(User asker, String answerId) {
            // TODO: asker only, question.accept, reputation.onAccept, publish
        }

        void close(User moderator, String questionId, String reason) { /* TODO: moderators only */ }

        void reopen(User moderator, String questionId) { /* TODO */ }

        int view(String questionId) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }
    }

    static class ReputationService {
        ReputationPolicy policy;

        void onVote(User voter, Post post, VoteType prev, VoteType now) {
            // TODO: undo prev effect, apply new effect (author + voter)
        }

        void onAccept(User asker, Answer accepted, Answer previous) {
            // TODO: previous -15, new +15, asker +2 first time; nothing for self-answers
        }
    }

    static class VoteService {
        PostService posts;
        ReputationService reputation;
        EventBus bus;

        void vote(User voter, String postId, VoteType type) {
            // TODO: not own post, privilege check, post.vote, reputation, publish VOTED
        }

        void retract(User voter, String postId) { /* TODO */ }
    }

    static class SearchService {
        PostService posts;

        List<Question> search(SearchStrategy filter, SortOrder order) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }
    }

    // ===================== Facade + Singleton =====================

    static class StackOverflowSystem {
        private static volatile StackOverflowSystem instance;
        EventBus bus;
        PostService posts;
        VoteService votes;
        ReputationService reputation;
        SearchService search;

        private StackOverflowSystem() {}

        static StackOverflowSystem getInstance() {
            // TODO: double-checked locking, subscribe BadgeListener
            throw new UnsupportedOperationException("TODO");
        }

        Question ask(User u, String title, String body, List<String> tags) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }

        Answer answer(User u, String questionId, String body) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }

        void upvote(User u, String postId) { /* TODO */ }

        void downvote(User u, String postId) { /* TODO */ }

        void accept(User asker, String answerId) { /* TODO */ }

        List<Question> search(SearchStrategy filter, SortOrder order) {
            // TODO
            throw new UnsupportedOperationException("TODO");
        }
    }

    // ===================== Demo =====================

    public static void main(String[] args) {
        // TODO:
        // 1. Users with different reputation (+ a moderator)
        // 2. Ask, answer -> asker notified
        // 3. Votes: privileges, no self-vote, no double vote, change vote, reputation math
        // 4. Comments gated by reputation
        // 5. Accept (asker only), switch accepted answer
        // 6. Search by tag / keyword / author / unanswered, sorted
        // 7. Moderator closes / reopens
        // 8. Many concurrent upvotes -> exact score + reputation, badge once
    }
}
