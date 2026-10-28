import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

// ===================== Enums =====================

enum VoteType {
    UP(1), DOWN(-1);

    final int value;

    VoteType(int value) { this.value = value; }
}

enum QuestionStatus { OPEN, CLOSED }

enum Role { MEMBER, MODERATOR }

// what a user may do, unlocked by reputation (moderators can do everything)
enum Privilege {
    UPVOTE(15), COMMENT(50), DOWNVOTE(125);

    final int minReputation;

    Privilege(int minReputation) { this.minReputation = minReputation; }
}

enum Badge {
    STUDENT("First question with score of 1 or more"),
    TEACHER("First answer with score of 1 or more"),
    SCHOLAR("Accepted an answer"),
    NICE_ANSWER("Answer score of 10 or more");

    final String description;

    Badge(String description) { this.description = description; }
}

enum EventType { QUESTION_ASKED, ANSWER_POSTED, COMMENT_ADDED, VOTED, ANSWER_ACCEPTED, QUESTION_CLOSED, BADGE_AWARDED }

// ===================== User =====================

class User {
    private final String id;
    private final String name;
    private final Role role;
    private final Set<Badge> badges = EnumSet.noneOf(Badge.class);
    private int reputation;

    User(String id, String name, Role role, int reputation) {
        this.id = id;
        this.name = name;
        this.role = role;
        this.reputation = reputation;
    }

    // reputation never drops below 1
    public synchronized void changeReputation(int delta) { reputation = Math.max(1, reputation + delta); }

    public synchronized int getReputation() { return reputation; }

    public boolean can(Privilege p) { return role == Role.MODERATOR || getReputation() >= p.minReputation; }

    // true only the first time -> each badge is awarded once
    public synchronized boolean awardBadge(Badge b) { return badges.add(b); }

    public synchronized Set<Badge> getBadges() {
        Set<Badge> copy = EnumSet.noneOf(Badge.class);
        copy.addAll(badges);
        return copy;
    }

    public String getId()   { return id; }
    public String getName() { return name; }
    public Role getRole()   { return role; }

    @Override public String toString() { return name; }
}

// ===================== Posts =====================

class Comment {
    private final String id = UUID.randomUUID().toString().substring(0, 8);
    private final User author;
    private final String body;
    private final LocalDateTime createdAt = LocalDateTime.now();

    Comment(User author, String body) {
        this.author = author;
        this.body = body;
    }

    public String getId()               { return id; }
    public User getAuthor()             { return author; }
    public String getBody()             { return body; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    @Override public String toString() { return author + ": " + body; }
}

// common to questions and answers: votes + comments
abstract class Post {
    private static final AtomicLong SEQ = new AtomicLong();

    private final String id;
    private final User author;
    private final String body;
    private final LocalDateTime createdAt;
    private final Map<String, VoteType> votes = new HashMap<>();   // userId -> vote (one per user)
    private final List<Comment> comments = new CopyOnWriteArrayList<>();
    private int score;

    Post(String prefix, User author, String body) {
        this.id = prefix + SEQ.incrementAndGet();
        this.author = author;
        this.body = body;
        this.createdAt = LocalDateTime.now().plusNanos(SEQ.get());   // strictly increasing for sorting
    }

    // returns the voter's previous vote (null if none) so reputation can be adjusted
    public synchronized VoteType vote(User voter, VoteType type) {
        VoteType prev = votes.get(voter.getId());
        if (prev == type) throw new IllegalStateException(voter + " already " + type + "-voted " + id);
        votes.put(voter.getId(), type);
        score += type.value - (prev == null ? 0 : prev.value);
        return prev;
    }

    public synchronized VoteType removeVote(User voter) {
        VoteType prev = votes.remove(voter.getId());
        if (prev == null) throw new IllegalStateException(voter + " has not voted on " + id);
        score -= prev.value;
        return prev;
    }

    public void addComment(Comment c)          { comments.add(c); }

    public synchronized int getScore()         { return score; }
    public String getId()                      { return id; }
    public User getAuthor()                    { return author; }
    public String getBody()                    { return body; }
    public LocalDateTime getCreatedAt()        { return createdAt; }
    public List<Comment> getComments()         { return new ArrayList<>(comments); }
}

class Tag {
    private final String name;
    private final AtomicInteger questionCount = new AtomicInteger();

    Tag(String name) { this.name = name; }

    public void increment()     { questionCount.incrementAndGet(); }
    public String getName()     { return name; }
    public int getQuestionCount() { return questionCount.get(); }

    @Override public String toString() { return "[" + name + "]"; }
}

class Question extends Post {
    private final String title;
    private final Set<Tag> tags;
    private final List<Answer> answers = new CopyOnWriteArrayList<>();
    private final AtomicInteger views = new AtomicInteger();
    private volatile LocalDateTime lastActivity;
    private Answer accepted;
    private QuestionStatus status = QuestionStatus.OPEN;
    private String closeReason;

    Question(User author, String title, String body, Set<Tag> tags) {
        super("Q", author, body);
        this.title = title;
        this.tags = Collections.unmodifiableSet(new LinkedHashSet<>(tags));
        this.lastActivity = getCreatedAt();
    }

    public synchronized void addAnswer(Answer a) {
        if (status == QuestionStatus.CLOSED) throw new IllegalStateException("Question is closed: " + closeReason);
        answers.add(a);
        touch();
    }

    // returns the previously accepted answer (null if none)
    public synchronized Answer accept(Answer a) {
        if (accepted == a) throw new IllegalStateException("Answer already accepted");
        Answer prev = accepted;
        accepted = a;
        touch();
        return prev;
    }

    public synchronized void close(String reason) {
        if (status == QuestionStatus.CLOSED) throw new IllegalStateException("Already closed");
        status = QuestionStatus.CLOSED;
        closeReason = reason;
    }

    public synchronized void reopen() {
        status = QuestionStatus.OPEN;
        closeReason = null;
    }

    public int view()                          { return views.incrementAndGet(); }
    public void touch()                        { lastActivity = LocalDateTime.now(); }

    public String getTitle()                   { return title; }
    public Set<Tag> getTags()                  { return tags; }
    public List<Answer> getAnswers()           { return new ArrayList<>(answers); }
    public synchronized Answer getAccepted()   { return accepted; }
    public synchronized QuestionStatus getStatus() { return status; }
    public int getViews()                      { return views.get(); }
    public LocalDateTime getLastActivity()     { return lastActivity; }

    @Override public String toString() {
        return getId() + " \"" + title + "\" " + tags + " score=" + getScore() + " answers=" + answers.size()
                + (getAccepted() != null ? " (accepted)" : "") + (getStatus() == QuestionStatus.CLOSED ? " [CLOSED]" : "");
    }
}

class Answer extends Post {
    private final Question question;

    Answer(User author, String body, Question question) {
        super("A", author, body);
        this.question = question;
    }

    public Question getQuestion() { return question; }
    public boolean isAccepted()   { return question.getAccepted() == this; }

    @Override public String toString() {
        return getId() + " by " + getAuthor() + " score=" + getScore() + (isAccepted() ? " (accepted)" : "");
    }
}

// ===================== Reputation (Strategy) =====================

interface ReputationPolicy {
    int authorDelta(Post post, VoteType type);   // what the post author gains / loses
    int voterDelta(Post post, VoteType type);    // what the voter pays
    int acceptedAnswerBonus();
    int acceptorBonus();
}

class DefaultReputationPolicy implements ReputationPolicy {
    public int authorDelta(Post post, VoteType type) { return type == VoteType.UP ? 10 : -2; }

    // downvoting answers costs the voter 1, so people don't downvote lightly
    public int voterDelta(Post post, VoteType type) {
        return type == VoteType.DOWN && post instanceof Answer ? -1 : 0;
    }

    public int acceptedAnswerBonus() { return 15; }
    public int acceptorBonus()       { return 2; }
}

// a beta site rewards questions less
class BetaSiteReputationPolicy extends DefaultReputationPolicy {
    @Override public int authorDelta(Post post, VoteType type) {
        if (type == VoteType.UP && post instanceof Question) return 5;
        return super.authorDelta(post, type);
    }
}

// ===================== Search (Strategy) =====================

interface SearchStrategy {
    boolean matches(Question q);
}

class TagSearch implements SearchStrategy {
    private final String tag;

    TagSearch(String tag) { this.tag = tag.toLowerCase(); }

    public boolean matches(Question q) { return q.getTags().stream().anyMatch(t -> t.getName().equals(tag)); }
}

class KeywordSearch implements SearchStrategy {
    private final String keyword;

    KeywordSearch(String keyword) { this.keyword = keyword.toLowerCase(); }

    public boolean matches(Question q) {
        return q.getTitle().toLowerCase().contains(keyword) || q.getBody().toLowerCase().contains(keyword);
    }
}

class AuthorSearch implements SearchStrategy {
    private final User author;

    AuthorSearch(User author) { this.author = author; }

    public boolean matches(Question q) { return q.getAuthor() == author; }
}

class UnansweredSearch implements SearchStrategy {
    public boolean matches(Question q) { return q.getAnswers().isEmpty(); }
}

enum SortOrder {
    NEWEST(Comparator.comparing(Question::getCreatedAt).reversed()),
    VOTES(Comparator.comparingInt(Question::getScore).reversed()),
    ACTIVE(Comparator.comparing(Question::getLastActivity).reversed());

    final Comparator<Question> comparator;

    SortOrder(Comparator<Question> comparator) { this.comparator = comparator; }
}

// ===================== Events (Observer) =====================

class Event {
    final EventType type;
    final User actor;       // who did it
    final User recipient;   // who should hear about it (may be null)
    final Post post;
    final String detail;

    Event(EventType type, User actor, User recipient, Post post, String detail) {
        this.type = type;
        this.actor = actor;
        this.recipient = recipient;
        this.post = post;
        this.detail = detail;
    }
}

interface EventListener {
    void onEvent(Event e);
}

class EventBus {
    private final List<EventListener> listeners = new CopyOnWriteArrayList<>();

    public void subscribe(EventListener l)   { listeners.add(l); }
    public void unsubscribe(EventListener l) { listeners.remove(l); }
    public void publish(Event e)             { listeners.forEach(l -> l.onEvent(e)); }
}

class NotificationListener implements EventListener {
    public void onEvent(Event e) {
        if (e.recipient == null || e.recipient == e.actor || e.detail == null) return;   // don't notify yourself
        System.out.println("  [INBOX " + e.recipient + "] " + e.detail);
    }
}

// awards badges by watching events
class BadgeListener implements EventListener {
    private final EventBus bus;

    BadgeListener(EventBus bus) { this.bus = bus; }

    public void onEvent(Event e) {
        switch (e.type) {
            case VOTED:
                if (e.post.getScore() >= 1) {
                    award(e.post.getAuthor(), e.post instanceof Question ? Badge.STUDENT : Badge.TEACHER);
                }
                if (e.post instanceof Answer && e.post.getScore() >= 10) award(e.post.getAuthor(), Badge.NICE_ANSWER);
                break;
            case ANSWER_ACCEPTED:
                award(e.actor, Badge.SCHOLAR);
                break;
            default:
                break;
        }
    }

    private void award(User u, Badge b) {
        if (u.awardBadge(b)) {
            bus.publish(new Event(EventType.BADGE_AWARDED, null, u, null, "You earned the " + b + " badge: " + b.description));
        }
    }
}

// ===================== Services =====================

class PostService {
    private final Map<String, Question> questions = new ConcurrentHashMap<>();
    private final Map<String, Answer> answers = new ConcurrentHashMap<>();
    private final Map<String, Tag> tags = new ConcurrentHashMap<>();
    private final EventBus bus;
    private final ReputationService reputation;

    PostService(EventBus bus, ReputationService reputation) {
        this.bus = bus;
        this.reputation = reputation;
    }

    public Question ask(User author, String title, String body, List<String> tagNames) {
        if (title.trim().length() < 15) throw new IllegalArgumentException("Title must be at least 15 characters");
        if (tagNames.isEmpty() || tagNames.size() > 5) throw new IllegalArgumentException("Use 1 to 5 tags");
        Set<Tag> qTags = new LinkedHashSet<>();
        for (String n : tagNames) {
            Tag t = tags.computeIfAbsent(n.toLowerCase(), Tag::new);
            t.increment();
            qTags.add(t);
        }
        Question q = new Question(author, title, body, qTags);
        questions.put(q.getId(), q);
        bus.publish(new Event(EventType.QUESTION_ASKED, author, null, q, null));
        return q;
    }

    public Answer answer(User author, String questionId, String body) {
        Question q = getQuestion(questionId);
        Answer a = new Answer(author, body, q);
        q.addAnswer(a);   // throws if closed
        answers.put(a.getId(), a);
        bus.publish(new Event(EventType.ANSWER_POSTED, author, q.getAuthor(), a,
                author + " answered your question \"" + q.getTitle() + "\""));
        return a;
    }

    // own post, or answer on your own question -> always allowed; otherwise needs the COMMENT privilege
    public Comment comment(User author, String postId, String body) {
        Post p = getPost(postId);
        boolean own = p.getAuthor() == author;
        boolean onMyQuestion = p instanceof Answer && ((Answer) p).getQuestion().getAuthor() == author;
        if (!own && !onMyQuestion && !author.can(Privilege.COMMENT))
            throw new IllegalStateException(author + " needs " + Privilege.COMMENT.minReputation + " reputation to comment");
        Comment c = new Comment(author, body);
        p.addComment(c);
        bus.publish(new Event(EventType.COMMENT_ADDED, author, p.getAuthor(), p, author + " commented on " + p.getId() + ": " + body));
        return c;
    }

    public void accept(User asker, String answerId) {
        Answer a = getAnswer(answerId);
        Question q = a.getQuestion();
        if (q.getAuthor() != asker) throw new IllegalStateException("Only the asker can accept an answer");
        Answer prev = q.accept(a);
        reputation.onAccept(asker, a, prev);
        bus.publish(new Event(EventType.ANSWER_ACCEPTED, asker, a.getAuthor(), a,
                asker + " accepted your answer on \"" + q.getTitle() + "\""));
    }

    public void close(User moderator, String questionId, String reason) {
        if (moderator.getRole() != Role.MODERATOR) throw new IllegalStateException("Only moderators can close questions");
        Question q = getQuestion(questionId);
        q.close(reason);
        bus.publish(new Event(EventType.QUESTION_CLOSED, moderator, q.getAuthor(), q,
                "Your question \"" + q.getTitle() + "\" was closed: " + reason));
    }

    public void reopen(User moderator, String questionId) {
        if (moderator.getRole() != Role.MODERATOR) throw new IllegalStateException("Only moderators can reopen questions");
        getQuestion(questionId).reopen();
    }

    public int view(String questionId) { return getQuestion(questionId).view(); }

    public Post getPost(String id) {
        Post p = id.startsWith("Q") ? questions.get(id) : answers.get(id);
        if (p == null) throw new IllegalArgumentException("No post " + id);
        return p;
    }

    public Question getQuestion(String id) {
        Question q = questions.get(id);
        if (q == null) throw new IllegalArgumentException("No question " + id);
        return q;
    }

    public Answer getAnswer(String id) {
        Answer a = answers.get(id);
        if (a == null) throw new IllegalArgumentException("No answer " + id);
        return a;
    }

    public Collection<Question> allQuestions() { return questions.values(); }

    public List<Tag> popularTags(int n) {
        return tags.values().stream()
                .sorted(Comparator.comparingInt(Tag::getQuestionCount).reversed().thenComparing(Tag::getName))
                .limit(n).collect(Collectors.toList());
    }
}

class ReputationService {
    private ReputationPolicy policy = new DefaultReputationPolicy();

    // undo the previous vote's effect (if any), apply the new one
    public void onVote(User voter, Post post, VoteType prev, VoteType now) {
        int authorDelta = (now == null ? 0 : policy.authorDelta(post, now)) - (prev == null ? 0 : policy.authorDelta(post, prev));
        int voterDelta  = (now == null ? 0 : policy.voterDelta(post, now))  - (prev == null ? 0 : policy.voterDelta(post, prev));
        if (authorDelta != 0) post.getAuthor().changeReputation(authorDelta);
        if (voterDelta != 0) voter.changeReputation(voterDelta);
    }

    // self-answers can be accepted but earn nothing
    public void onAccept(User asker, Answer accepted, Answer previous) {
        int bonus = policy.acceptedAnswerBonus();
        if (previous != null && previous.getAuthor() != asker) previous.getAuthor().changeReputation(-bonus);
        if (accepted.getAuthor() != asker) {
            accepted.getAuthor().changeReputation(bonus);
            if (previous == null) asker.changeReputation(policy.acceptorBonus());
        }
    }

    public void setPolicy(ReputationPolicy p) { this.policy = p; }
}

class VoteService {
    private final PostService posts;
    private final ReputationService reputation;
    private final EventBus bus;

    VoteService(PostService posts, ReputationService reputation, EventBus bus) {
        this.posts = posts;
        this.reputation = reputation;
        this.bus = bus;
    }

    public void vote(User voter, String postId, VoteType type) {
        Post post = posts.getPost(postId);
        if (post.getAuthor() == voter) throw new IllegalStateException("You can't vote on your own post");
        Privilege needed = type == VoteType.UP ? Privilege.UPVOTE : Privilege.DOWNVOTE;
        if (!voter.can(needed))
            throw new IllegalStateException(voter + " needs " + needed.minReputation + " reputation to " + type + "-vote");

        VoteType prev = post.vote(voter, type);   // synchronized per post
        reputation.onVote(voter, post, prev, type);
        bus.publish(new Event(EventType.VOTED, voter, post.getAuthor(), post, null));
    }

    public void retract(User voter, String postId) {
        Post post = posts.getPost(postId);
        VoteType prev = post.removeVote(voter);
        reputation.onVote(voter, post, prev, null);
    }
}

class SearchService {
    private final PostService posts;

    SearchService(PostService posts) { this.posts = posts; }

    public List<Question> search(SearchStrategy filter, SortOrder order) {
        return posts.allQuestions().stream()
                .filter(filter::matches)
                .sorted(order.comparator)
                .collect(Collectors.toList());
    }
}

// ===================== Facade + Singleton =====================

public class StackOverflowSystem {
    private static volatile StackOverflowSystem instance;

    private final EventBus bus = new EventBus();
    private final ReputationService reputation = new ReputationService();
    private final PostService posts = new PostService(bus, reputation);
    private final VoteService votes = new VoteService(posts, reputation, bus);
    private final SearchService search = new SearchService(posts);

    private StackOverflowSystem() {
        bus.subscribe(new BadgeListener(bus));
    }

    public static StackOverflowSystem getInstance() {
        if (instance == null) {
            synchronized (StackOverflowSystem.class) {
                if (instance == null) instance = new StackOverflowSystem();
            }
        }
        return instance;
    }

    public Question ask(User u, String title, String body, List<String> tags) { return posts.ask(u, title, body, tags); }
    public Answer answer(User u, String questionId, String body)              { return posts.answer(u, questionId, body); }
    public Comment comment(User u, String postId, String body)                { return posts.comment(u, postId, body); }
    public void accept(User asker, String answerId)                           { posts.accept(asker, answerId); }
    public void close(User mod, String questionId, String reason)             { posts.close(mod, questionId, reason); }
    public void reopen(User mod, String questionId)                           { posts.reopen(mod, questionId); }
    public int view(String questionId)                                        { return posts.view(questionId); }

    public void upvote(User u, String postId)   { votes.vote(u, postId, VoteType.UP); }
    public void downvote(User u, String postId) { votes.vote(u, postId, VoteType.DOWN); }
    public void retractVote(User u, String postId) { votes.retract(u, postId); }

    public List<Question> search(SearchStrategy filter, SortOrder order) { return search.search(filter, order); }
    public List<Tag> popularTags(int n)                                  { return posts.popularTags(n); }

    public EventBus getEventBus()                { return bus; }
    public ReputationService getReputation()     { return reputation; }

    // ===================== Demo =====================

    private static void attempt(String label, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            System.out.println("  " + label + " rejected: " + e.getMessage());
        }
    }

    private static String rep(User... users) {
        return Arrays.stream(users).map(u -> u + "=" + u.getReputation()).collect(Collectors.joining(", "));
    }

    public static void main(String[] args) throws InterruptedException {
        StackOverflowSystem so = StackOverflowSystem.getInstance();
        NotificationListener inbox = new NotificationListener();
        so.getEventBus().subscribe(inbox);

        User alice = new User("u1", "Alice", Role.MEMBER, 1);      // new user
        User bob   = new User("u2", "Bob",   Role.MEMBER, 200);    // can do everything except moderate
        User carol = new User("u3", "Carol", Role.MEMBER, 20);     // can upvote only
        User dave  = new User("u4", "Dave",  Role.MEMBER, 1);      // brand new
        User mod   = new User("u5", "Mia",   Role.MODERATOR, 5000);
        System.out.println("Start: " + rep(alice, bob, carol, dave));

        // 1. Ask
        System.out.println("\n1. Questions");
        attempt("Short title", () -> so.ask(alice, "HashMap?", "...", List.of("java")));
        Question q1 = so.ask(alice, "How does HashMap work internally in Java?",
                "What happens on put() when two keys have the same hash?", List.of("java", "hashmap"));
        Question q2 = so.ask(dave, "Difference between ArrayList and LinkedList?",
                "When should I use which?", List.of("java", "collections"));
        Question q3 = so.ask(carol, "How to center a div with flexbox?", "Tried margin auto.", List.of("css"));
        System.out.println("  " + q1 + "\n  " + q2 + "\n  " + q3);

        // 2. Answers
        System.out.println("\n2. Bob and Carol answer Alice");
        Answer bobAns = so.answer(bob, q1.getId(), "Buckets of linked lists, treeified after 8 entries...");
        Answer carolAns = so.answer(carol, q1.getId(), "It uses hashCode() and equals().");

        // 3. Votes + privileges
        System.out.println("\n3. Votes");
        so.upvote(carol, bobAns.getId());
        attempt("Dave upvotes (rep 1)", () -> so.upvote(dave, bobAns.getId()));
        attempt("Bob upvotes own answer", () -> so.upvote(bob, bobAns.getId()));
        attempt("Carol downvotes (rep 20)", () -> so.downvote(carol, bobAns.getId()));
        attempt("Carol upvotes again", () -> so.upvote(carol, bobAns.getId()));
        so.downvote(bob, carolAns.getId());
        System.out.println("  After Carol's upvote on Bob, Bob's downvote on Carol: " + rep(bob, carol));
        so.upvote(carol, q1.getId());
        System.out.println("  Carol upvoted Alice's question: " + rep(alice));
        System.out.println("  Bob changes his downvote to an upvote on Carol's answer");
        so.upvote(bob, carolAns.getId());
        System.out.println("  " + rep(bob, carol) + " (Carol: undo -2, +10; Bob: gets his 1 back)");

        // 4. Comments
        System.out.println("\n4. Comments");
        attempt("Dave comments (rep 1)", () -> so.comment(dave, bobAns.getId(), "+1"));
        so.comment(alice, bobAns.getId(), "What does treeified mean?");   // answer on her own question
        so.comment(bob, bobAns.getId(), "Converted to a red-black tree.");

        // 5. Accept
        System.out.println("\n5. Accepting an answer");
        attempt("Dave accepts on Alice's question", () -> so.accept(dave, bobAns.getId()));
        so.accept(alice, bobAns.getId());
        System.out.println("  " + rep(alice, bob) + "  Alice badges: " + alice.getBadges());
        System.out.println("  Alice switches the accept to Carol's answer");
        so.accept(alice, carolAns.getId());
        System.out.println("  " + rep(alice, bob, carol) + " (Bob -15, Carol +15, Alice keeps her +2)");

        // 6. Search
        System.out.println("\n6. Search");
        for (int i = 0; i < 3; i++) so.view(q1.getId());
        System.out.println("  [java] by votes: " + so.search(new TagSearch("java"), SortOrder.VOTES));
        System.out.println("  'flexbox':       " + so.search(new KeywordSearch("flexbox"), SortOrder.NEWEST));
        System.out.println("  Unanswered:      " + so.search(new UnansweredSearch(), SortOrder.NEWEST));
        System.out.println("  By Dave:         " + so.search(new AuthorSearch(dave), SortOrder.NEWEST));
        System.out.println("  Popular tags:    " + so.popularTags(3).stream()
                .map(t -> t + " x" + t.getQuestionCount()).collect(Collectors.toList()));
        System.out.println("  q1 views: " + q1.getViews());

        // 7. Moderation
        System.out.println("\n7. Moderation");
        attempt("Bob closes Dave's question", () -> so.close(bob, q2.getId(), "duplicate"));
        so.close(mod, q2.getId(), "Duplicate of Q42");
        attempt("Bob answers a closed question", () -> so.answer(bob, q2.getId(), "ArrayList is backed by an array"));
        so.reopen(mod, q2.getId());
        so.answer(bob, q2.getId(), "ArrayList is backed by an array; LinkedList by nodes.");

        // 8. Retract a vote
        System.out.println("\n8. Carol retracts her upvote on Alice's question");
        int before = alice.getReputation();
        so.retractVote(carol, q1.getId());
        System.out.println("  Alice " + before + " -> " + alice.getReputation() + ", q1 score " + q1.getScore());

        // 9. Swap reputation rules (Strategy)
        System.out.println("\n9. Beta site: question upvotes give +5 instead of +10");
        so.getReputation().setPolicy(new BetaSiteReputationPolicy());
        int carolBefore = carol.getReputation();
        so.upvote(bob, q3.getId());
        System.out.println("  Bob upvotes Carol's question: Carol " + carolBefore + " -> " + carol.getReputation());
        so.getReputation().setPolicy(new DefaultReputationPolicy());

        // 10. Concurrency
        so.getEventBus().unsubscribe(inbox);   // keep the stress test quiet, except badges below
        System.out.println("\n10. 100 users upvote Bob's answer at the same time");
        int bobBefore = bob.getReputation();
        int scoreBefore = bobAns.getScore();
        List<User> crowd = new ArrayList<>();
        for (int i = 0; i < 100; i++) crowd.add(new User("c" + i, "C" + i, Role.MEMBER, 20));
        AtomicInteger badgeEvents = new AtomicInteger();
        so.getEventBus().subscribe(e -> {
            if (e.type == EventType.BADGE_AWARDED && e.recipient == bob) badgeEvents.incrementAndGet();
        });
        List<Thread> threads = new ArrayList<>();
        for (User u : crowd) {
            Thread t = new Thread(() -> so.upvote(u, bobAns.getId()));
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join();
        System.out.println("  Answer score " + scoreBefore + " -> " + bobAns.getScore() + " (expected +" + crowd.size() + ")");
        System.out.println("  Bob reputation " + bobBefore + " -> " + bob.getReputation() + " (expected +" + crowd.size() * 10 + ")");
        System.out.println("  NICE_ANSWER badge events for Bob: " + badgeEvents.get() + " (expected 1), Bob badges: " + bob.getBadges());
    }
}
