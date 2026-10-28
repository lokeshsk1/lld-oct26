# Stack Overflow / Q&A System — LLD

| File | What |
|---|---|
| [README.md](README.md) | Steps: requirements → entities → relationships → patterns → flow → talking points → DB schema |
| [class-diagram.puml](class-diagram.puml) | UML class diagram |
| [qa-flow.puml](qa-flow.puml) | Ask → answer → vote → accept sequence diagram |
| [db-schema.puml](db-schema.puml) | Database schema (ER diagram) |
| [StackOverflowSystem.java](StackOverflowSystem.java) | Full implementation + runnable demo |
| [StackOverflowSkeleton.java](StackOverflowSkeleton.java) | Skeleton only: classes, fields, method signatures with TODOs |

**Run:**

```bash
javac StackOverflowSystem.java && java StackOverflowSystem
```

---

## Step 1: Requirements

**Functional**
1. Users ask questions with a title, body and 1–5 tags.
2. Users answer questions and comment on questions and answers.
3. Users upvote / downvote questions and answers — one vote per user per post; can change or retract it.
4. The asker accepts one answer (and can switch to another later).
5. Reputation goes up and down with votes and accepts; actions are unlocked by reputation (upvote 15, comment 50, downvote 125).
6. Badges are awarded automatically (e.g. first upvoted answer, answer score 10).
7. Search by tag, keyword, author, or unanswered; sort by newest, votes or activity.
8. Moderators close and reopen questions; closed questions take no new answers.
9. Users are notified when someone answers, comments, accepts, or when they earn a badge.

**Non-functional**
- **Correct counts under load.** Many people voting on the same post at once must give the exact score and reputation.
- **Each badge once.** Concurrent events must not award the same badge twice.
- **Extensible.** New badges, reputation rules or search filters without changing existing code.

---

## Step 2: Core entities

| Entity | Responsibility |
|---|---|
| `User` | Name, role, reputation, badges; `can(Privilege)` |
| `Post` (abstract) | Shared by questions and answers: author, body, votes, score, comments |
| `Question` | Title, tags, answers, accepted answer, status, views |
| `Answer` | Belongs to one question |
| `Comment` | Short text on a post |
| `Tag` | Name + question count |
| `VoteType` (enum) | UP (+1), DOWN (−1) |
| `Privilege` (enum) | UPVOTE (15), COMMENT (50), DOWNVOTE (125) |
| `Badge` (enum) | STUDENT, TEACHER, SCHOLAR, NICE_ANSWER |
| `ReputationPolicy` | How many points each action gives / costs |
| `SearchStrategy` + `SortOrder` | Filter and order questions |
| `EventBus`, `Event`, `EventListener` | Publish events; listeners award badges and send notifications |
| `PostService`, `VoteService`, `ReputationService`, `SearchService` | Business logic |
| `StackOverflowSystem` | Single entry point (Singleton + Facade) |

**Why a `Post` base class:** questions and answers are both voted on and commented on in exactly the same way. Putting votes, score and comments in `Post` means `VoteService` and comments work on either without `if (question) ... else ...`.

---

## Step 3: Relationships

- `Question` and `Answer` **are** `Post`s (inheritance).
- `Post` **is written by** one `User` and **has** many `Comment`s — composition.
- `Post` **holds** at most one vote per user (`Map<userId, VoteType>`).
- `Question` **has** many `Answer`s — composition; **points to** zero or one accepted `Answer`.
- `Question` **is tagged with** 1–5 `Tag`s; a `Tag` is on many questions (many-to-many).
- `User` **has** many `Badge`s.
- `VoteService` and `PostService` **use** `ReputationService` (→ `ReputationPolicy`) and **publish to** `EventBus`.
- `EventBus` **notifies** `BadgeListener` and `NotificationListener`; `BadgeListener` **publishes** `BADGE_AWARDED` back to the bus.

---

## Step 4: Design patterns

| Pattern | Where | Why |
|---|---|---|
| **Singleton** | `StackOverflowSystem` | One shared system instance |
| **Facade** | `StackOverflowSystem` | One simple API over all services |
| **Observer** | `EventBus` + `BadgeListener`, `NotificationListener` | Posting and voting don't know about badges or notifications. New badge rules or channels = new listener |
| **Strategy** | `ReputationPolicy`, `SearchStrategy`, `SortOrder` | Swap point rules (e.g. beta site) and search / sort without touching callers |
| **Template / inheritance** | `Post` → `Question`, `Answer` | Shared voting and comment logic in one place |

---

## Step 5: Class diagram

See [class-diagram.puml](class-diagram.puml).

---

## Step 6: Flow

See [qa-flow.puml](qa-flow.puml).

**Vote** → `VoteService.vote(voter, postId, type)`:
1. Not your own post; you have the privilege (UPVOTE 15 / DOWNVOTE 125).
2. `post.vote(voter, type)` (synchronized): rejects the same vote twice; `score += new − previous`; returns the previous vote.
3. `ReputationService.onVote(prev, now)`: **undo the previous vote's effect, apply the new one**. So changing a downvote to an upvote on an answer gives the author +12 (undo −2, add +10) and gives the voter their 1 point back.
4. Publish `VOTED` → `BadgeListener` checks badge rules.

**Reputation rules** (`DefaultReputationPolicy`):

| Action | Post author | Actor |
|---|---|---|
| Upvote (question or answer) | +10 | 0 |
| Downvote | −2 | −1 if it's an answer |
| Answer accepted | +15 | asker +2 (first accept only) |
| Accept moved to another answer | old author −15, new author +15 | 0 |

Reputation never drops below 1. Self-answers can be accepted but earn nothing.

**Accept** → asker only; `question.accept(answer)` returns the previous accepted answer so reputation can be moved over.

**Comment** → always allowed on your own post or on answers to your own question; otherwise needs 50 reputation.

**Badges** (`BadgeListener`):
- STUDENT: your question reaches score ≥ 1.
- TEACHER: your answer reaches score ≥ 1.
- NICE_ANSWER: your answer reaches score ≥ 10.
- SCHOLAR: you accept an answer.

`User.awardBadge()` returns true only the first time, so each badge (and its notification) happens once.

---

## Step 7: Code

See [StackOverflowSystem.java](StackOverflowSystem.java). The `main` method demos:

1. Asking: short title rejected; 3 questions with tags
2. Two answers → asker notified each time
3. Votes: privilege checks (rep 1 can't upvote, rep 20 can't downvote), no self-vote, no double vote; downvote math; vote change (−2 → +10); badges STUDENT and TEACHER
4. Comments: rep 1 rejected; asker can comment on answers to her question
5. Accept: only the asker; switching the accept moves 15 points from Bob to Carol; SCHOLAR badge
6. Search by tag (by votes), keyword, unanswered, author; popular tags; view count
7. Moderation: non-moderator can't close; closed question rejects answers; reopen
8. Retracting a vote undoes its reputation
9. 100 users upvote the same answer at once → score +100, reputation +1000, NICE_ANSWER awarded exactly once

---

## Step 8: Interview talking points

1. **Concurrency**
   - `Post.vote()` is synchronized: one vote per user and an exact score even with many voters.
   - `User.changeReputation()` is synchronized: concurrent votes never lose points.
   - `User.awardBadge()` is an atomic "add if absent": a badge is awarded once even if many threads cross the threshold together.
   - In the DB: `PRIMARY KEY(post_id, user_id)` on `votes` makes double voting impossible (see below).
2. **Why events?** `VoteService` only says "a vote happened". Badges, notifications, a reputation audit log or a search re-index can each listen without changing voting code. In production the bus would be a queue (Kafka) and listeners would run asynchronously.
3. **Hot posts.** A viral question gets thousands of votes and views. Keep the score as a column updated with `score = score + :delta` (no read-modify-write), count views in Redis and flush every few minutes.
4. **Search.** In-memory filters are fine for the interview. In production: Elasticsearch on title + body + tags, kept up to date by an event listener.
5. **Reputation audit.** Store every change in `reputation_history`; the user's reputation is the sum. Lets you reverse fraud ("serial voting") and show the reputation timeline.
6. **Extensions:** bounties (offer reputation for an answer), edit history and suggested edits, close votes from high-rep users (3 votes at 3000 rep), duplicate linking, spam flags, favourites / follow tags, markdown rendering, rate limiting new users.

---

## Step 9: Database schema

See [db-schema.puml](db-schema.puml).

| Table | Maps to | Notes |
|---|---|---|
| `users` | `User` | Reputation stored for fast reads, `CHECK(reputation >= 1)` |
| `posts` | `Post` / `Question` / `Answer` | **One table** with `post_type`; answers point to their question via `parent_id` (same as the real Stack Overflow schema) |
| `tags`, `post_tags` | `Tag`, `Question.tags` | Many-to-many |
| `votes` | `Post.votes` | `PK(post_id, user_id)` → one vote per user per post |
| `comments` | `Comment` | On any post |
| `badges`, `user_badges` | `Badge`, `User.badges` | `PK(user_id, badge_id)` → each badge once |
| `reputation_history` | every reputation change | Audit + timeline |

**Vote** (insert or change, one transaction):

```sql
BEGIN;
SELECT vote_type FROM votes WHERE post_id = :p AND user_id = :u FOR UPDATE;   -- previous vote (may be none)

INSERT INTO votes (post_id, user_id, vote_type, created_at) VALUES (:p, :u, :type, NOW())
ON CONFLICT (post_id, user_id) DO UPDATE SET vote_type = EXCLUDED.vote_type;

UPDATE posts SET score = score + (:type - COALESCE(:prev, 0)) WHERE id = :p;
UPDATE users SET reputation = GREATEST(1, reputation + :authorDelta) WHERE id = :authorId;
INSERT INTO reputation_history (user_id, delta, reason, post_id, created_at)
VALUES (:authorId, :authorDelta, 'VOTE', :p, NOW());
COMMIT;
```

**Award a badge once:**

```sql
INSERT INTO user_badges (user_id, badge_id, awarded_at) VALUES (:u, :b, NOW())
ON CONFLICT DO NOTHING;   -- 1 row inserted -> send the notification
```

**Questions tagged `java`, highest score first:**

```sql
SELECT p.* FROM posts p
JOIN post_tags pt ON pt.post_id = p.id
JOIN tags t ON t.id = pt.tag_id
WHERE t.name = 'java' AND p.post_type = 'QUESTION'
ORDER BY p.score DESC
LIMIT 50;
```

**Unanswered questions:**

```sql
SELECT q.* FROM posts q
WHERE q.post_type = 'QUESTION'
  AND NOT EXISTS (SELECT 1 FROM posts a WHERE a.parent_id = q.id)
ORDER BY q.created_at DESC;
```
