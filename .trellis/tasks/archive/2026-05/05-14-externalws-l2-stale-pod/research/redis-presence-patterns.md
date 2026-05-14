# Research: Redis-Based Presence / Registry Patterns for Failure Detection

- **Query**: Survey production-grade Redis idioms for "ephemeral membership / presence registry where stale entries auto-evict", in the context of the `external-ws:registry:{domain}` bug (Hash field for dead pod kept alive forever because pre-7.4 Redis has no per-field TTL and live-pod `EXPIRE` keeps the whole Hash alive).
- **Scope**: external (web research, breadth over depth)
- **Date**: 2026-05-14
- **Budget**: ~35 min, breadth-first

---

## TL;DR Up Front

For our exact constraints (single-instance Redis, Spring Boot + Lettuce, an `isInstanceAlive(id)` API already in place via `ss:internal:instance:{id}` TTL=30s heartbeat keys, ~10-50 pods, ~hundreds of domains):

1. **The "simple fix" — keep current Hash + filter by `isInstanceAlive` on read + lazy `HDEL` of dead fields — is genuinely fine and is exactly what the literature recommends for this class of bug.** It is what CrackingWalnuts' "MECHANISM 2: POD HEALTH REAPER" describes, and what rushsocket does in production (global presence in Hashes, stats in TTL'd keys per node; presence cleaned when no liveness exists).
2. **A meaningfully better migration target is "Sorted Set with timestamp score"** (the canonical pre-7.4 idiom; Svix, OneUptime, hjr265 all teach exactly this). It eliminates the "shared key TTL refreshes the dead entry" anti-pattern at the data-model level. Memory cost is similar, code is simpler, no Lua needed.
3. **`HEXPIRE` (Redis 7.4)** fully fixes the underlying primitive, AWS ElastiCache supports it, but Tencent Cloud (per their docs the team has been using — Redis 2.8/4.0/5.0/6.2/7.0 + ValKey 8.0; **no 7.4 line listed**) does not list it. ValKey has not implemented `HEXPIRE` yet (valkey-io/valkey#1070, open). **If we're on Tencent Cloud Redis or ValKey, `HEXPIRE` is not an option today.**

Rank for our case: **(A) lazy HDEL + isInstanceAlive filter (smallest diff)** → **(B) migrate to Sorted Set (cleanest model)** → (C) `HEXPIRE` (only if managed Redis supports it).

---

## The Anti-Pattern (Our Current Bug, Recapped)

```
HSET   external-ws:registry:{domain} {instanceId} {connCount}
EXPIRE external-ws:registry:{domain} 60        # refreshed by ANY live pod
```

Failure: if pod `B` dies holding `(domain=X, count=3)`, live pod `A`'s heartbeat keeps calling `EXPIRE external-ws:registry:X 60`, which renews the **whole key**, including `B`'s field. `B`'s field becomes immortal until somebody calls `HDEL`.

This is a known Redis idiom failure mode and is exactly what motivated `HEXPIRE` (redis/redis#6620, merged in 7.4). See the issue thread for the years-long discussion: https://github.com/redis/redis/issues/6620

---

## Pattern Survey

### Pattern A — Redis 7.4 `HEXPIRE` / `HPEXPIRE` (per-field TTL)

**How it works.** Native solution. Each field gets its own TTL; expired fields auto-deleted by active+lazy expiration cycle. `HEXPIRE key seconds [NX|XX|GT|LT] FIELDS n f1 f2 …`.

```redis
HSET    external-ws:registry:X  podA 3  podB 1
HEXPIRE external-ws:registry:X  60 FIELDS 2 podA podB
# podA heartbeat: HEXPIRE …:X 60 FIELDS 1 podA   (renews only its own field)
# podB dies; its field expires in <=60s automatically
```

- Available: Redis CE 7.4.0 GA, 2024-07-29 (https://github.com/redis/redis/releases/tag/7.4.0).
- **Managed availability:**
  - **AWS ElastiCache** — supports `HEXPIRE`/`HPEXPIRE`/`HEXPIREAT`/`HPEXPIREAT`/`HTTL`/`HPTTL`/`HPERSIST`/`HEXPIRETIME`/`HPEXPIRETIME` (https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/SupportedCommands.html).
  - **Redis Cloud / Redis Software** — supported (per command page "Redis Software and Redis Cloud compatibility: ✅ Standard / ✅ Active-Active").
  - **Tencent Cloud** — Hash command compatibility table lists Redis 2.8 / 4.0 / 5.0 / 6.2 / 7.0 + ValKey 8.0. **7.4 not listed**, no `HEXPIRE` family in the supported-commands list as of writing (https://www.tencentcloud.com/document/product/239/50392).
  - **ValKey (Amazon/Linux Foundation fork)** — `HEXPIRE` is **not yet implemented**; tracked in valkey-io/valkey#1070 (open since 2024-09-24). ElastiCache for ValKey ≤7.2.6 does **not** have it; ElastiCache for Redis OSS does.
  - **Alibaba/Azure** — not surveyed in this pass; both typically lag Redis OSS releases by quarters.
- **Gotchas:**
  - `HSET` of an already-expiring field **clears** its TTL (HEXPIRE doc note: "expirations cleared by HDEL/HSET"). So our `HSET ... <count>` heartbeat must be followed by `HEXPIRE` each cycle (or use the new `HSETEX` / `HGETEX` from 7.4).
  - RDB file format changed in 7.4 (#13391, #13438) — downgrade is one-way painful.
  - `O(N)` per field count.

**In-flight message during stale window:** if the pod died just now and its TTL is still ticking, a routing read sees it as alive. Solution is the same as today — verify per-message before publish.

**Verdict:** Cleanest model. Blocked for us unless we're 100% sure the prod Redis is 7.4+ AND not a ValKey-flavored cloud. Confirm before considering.

---

### Pattern B — Sorted Set with timestamp score (the canonical pre-7.4 idiom)

**How it works.** Per-instance entries are members; "last seen" timestamps are scores. Readers filter by `ZRANGEBYSCORE now-staleness +inf`. Periodic `ZREMRANGEBYSCORE -inf now-staleness` GC.

```redis
# heartbeat (any pod, every 15s):
ZADD external-ws:registry:X  <now-ms>  podA
# read (router): only pods seen in last 60s
ZRANGEBYSCORE external-ws:registry:X  <now-ms - 60000>  +inf
# GC (cron, every 5 min):
ZREMRANGEBYSCORE external-ws:registry:X  -inf  <now-ms - 60000>
```

Production examples teaching exactly this:
- Svix, "How to Track Online Users in Redis" — https://www.svix.com/resources/redis/presence-detection/ (recommends ZSET as the primary pattern; "stale entries age out naturally without explicit cleanup").
- hjr265, "Presence Tracking with Redis" — https://hjr265.me/blog/presence-tracking-with-redis/ (Go example, ZADD + ZRANGE BYSCORE).
- OneUptime, "Set with Expiration in Redis" — https://oneuptime.com/blog/post/2026-03-31-redis-set-with-expiration/view (explicit "per-member TTL semantics that native Redis sets lack").
- Redis Inc. glossary, "Sorted Set Time Series" — https://redis.io/glossary/sorted-set-time-series (Redis's own recommended idiom).

**Pros vs Hash+EXPIRE for our case:**
- Eliminates the "shared key TTL refreshes dead entry" failure mode by construction.
- Reader can serve "active pods only" directly without a second `isInstanceAlive` call.
- `ZRANGEBYSCORE` is `O(log N + M)`, fast enough for hundreds of pods.
- Memory: each member ~40-80 bytes (member sds + score). For ~50 pods × hundreds of domains, ≤ a few MB. Trivial.

**Cons:**
- Loses the "value = connectionCount per pod" affordance. Workaround options:
  1. Encode count into the member string: `"podA:3"` — ugly, requires parsing on read, and updates need ZREM old + ZADD new.
  2. Keep the count in a **separate** Hash with field TTL via Pattern E (lazy delete on read) — basically the same complexity as today.
  3. Drop the count from the registry entirely; if a router needs "least-loaded pod" decisions, query a separate stats key per pod (which already exists in our codebase pattern, mirroring `ss:internal:instance:{id}`).
- GC job adds operational responsibility (though `ZREMRANGEBYSCORE` is cheap).

**In-flight message during stale window:** member still in `ZRANGEBYSCORE` window until score ages out. Routing should still call `isInstanceAlive` for the chosen pod before publish. (Same as today.)

**Verdict:** This is the strictly-better default if we're willing to migrate. Same data model, cleaner semantics, no Lua, no Redis-version dependency.

---

### Pattern C — Per-instance key with TTL + SCAN/KEYS

**How it works.** One key per (domain, instance) with individual TTL. Readers `SCAN` the keyspace.

```redis
SET    external-ws:registry:X:podA  3  EX 60
# router:
SCAN 0 MATCH external-ws:registry:X:*
```

Used by: it's the implicit pattern behind a lot of "presence per user" demos (Witty Coder online-presence example uses `presence:{user_id}` with SETEX + per-server connection set). Pattern shows up frequently in chat-system tutorials.

**Pros:**
- Each entry has true per-entry TTL, on any Redis version.
- Conceptually simplest: "if key exists, instance is alive".

**Cons:**
- `SCAN` per read on the hot path is wrong. Either index in a separate set (back to a registry problem) or pay `O(keyspace)` per route.
- `KEYS` is forbidden in production. `SCAN` is incremental and may miss entries written mid-scan.
- Loses atomicity for "give me all pods for domain X".

**Verdict:** Not viable for routing hot path. Useful only as a backing store for non-latency-sensitive lookups.

---

### Pattern D — Lease / Token (Redisson `RLock`, `RSemaphore`)

**How it works.** Each instance holds a renewable lease key with TTL; instance ID stored as value or in a separate metadata key. Standard distributed-lock library territory.

```java
RLock lock = redisson.getLock("presence:X:podA");
lock.tryLock(0, 60, SECONDS);  // watchdog renews
```

Redisson watchdog (default 30s) auto-renews while the JVM is alive; on process death the lease lapses naturally.

**Pros:**
- Battle-tested, handles renewal correctly.
- Death of pod = immediate lease lapse (within renewal interval).

**Cons:**
- Heavyweight: one lock per (instance, domain) means thousands of locks.
- Cross-domain queries ("who's serving X?") aren't first-class — you'd still need a separate index.
- Adds Redisson dependency (we use Lettuce today per Spring Data Redis defaults).

**Verdict:** Overkill for an aggregate registry. Right tool for single-leader election (which is a separate concern from this bug).

---

### Pattern E — Lazy cleanup at read time (the hybrid the spec hints at)

**How it works.** Keep current `HSET` + `EXPIRE`-on-key model **but** every read does `isInstanceAlive(field)` before trusting it, and proactively `HDEL` dead fields it finds.

```java
List<String> instances = redisTemplate.opsForHash().keys("external-ws:registry:X");
List<String> alive = new ArrayList<>();
for (String id : instances) {
  if (instanceAlivenessService.isInstanceAlive(id)) {
    alive.add(id);
  } else {
    redisTemplate.opsForHash().delete("external-ws:registry:X", id);  // lazy GC
  }
}
return alive;
```

Production example matching this almost line-for-line:
- **CrackingWalnuts "100M Notifications/Second"** — https://crackingwalnuts.com/post/notification-system-design (Section 9.3). They run THREE mechanisms in parallel: (1) per-conn heartbeat, (2) "POD HEALTH REAPER" reactive every 30s that bulk-`HDEL`s dead pods' entries, (3) 24h TTL safety net. Their reaper is exactly our `isInstanceAlive` filter + `HDEL`.
- **rushsocket** — https://github.com/rushsocket/rushsocket. Global presence in Redis Hashes (`{prefix}:presence:{app}:{channel}` without TTL), node stats in `{prefix}:node_stats:{node_id}` with 15s TTL. "If a node crashes, its stats expire in 15 seconds." The presence Hash is cleaned via the application logic when a node loses all sockets — equivalent to our lazy-HDEL.

**Pros for our context:**
- Smallest diff to current code. We already have `ss:internal:instance:{id}` with 30s TTL and `isInstanceAlive(id)`.
- Read-path filtering is correct **by definition**: even if Hash still holds the dead field for hours, the router never sees it as alive.
- No data migration, no Redis version bump.

**Cons:**
- Memory creep: dead fields accumulate until a read happens. With ~hundreds of domains and ~tens of pods, worst case = thousands of stale fields. Each ~30 bytes. Negligible.
- Cosmetic only — Hash never reflects true state without a read, so `HLEN`/`HGETALL`-only observability is misleading. Need a periodic reaper job for cleanliness (CrackingWalnuts's MECHANISM 2).

**Verdict:** Right answer for the bug as stated, smallest blast radius. Add the periodic reaper if observability matters.

---

### Pattern F — Pub/Sub presence ping (query-on-demand)

**How it works.** No persistent registry. Routing publishes "who has X?" on a broadcast channel; pods holding X reply within a timeout window.

**Used in:** rushsocket's cross-node "request/response protocol" handles "cluster-wide queries that can't be served from Redis alone (e.g., listing socket IDs in a channel)" — but they explicitly use Hashes for presence and reserve pub/sub for things the Hashes can't answer.

**Pros:** zero storage staleness possible — answers come from currently-running pods.

**Cons:** latency on every route (10-50ms typical timeout); fan-out cost; doesn't degrade gracefully under Redis pub/sub message loss.

**Verdict:** Not suitable for our hot routing path. Useful as a complementary "verify before deliver" step (which `isInstanceAlive` already gives us cheaper).

---

## Comparison Table

| Pattern | Storage cost | Read cost | Write cost | Staleness bound | Code complexity | Redis version | HA story |
|---|---|---|---|---|---|---|---|
| A. `HEXPIRE` | O(pods × domains) | O(log N) per field | O(1) per heartbeat | ≤ TTL | Low | **7.4+**, varies in clouds | Native, no app GC |
| B. Sorted Set | O(pods × domains) | O(log N + M) range | O(log N) ZADD | ≤ staleness window | Low | Any | Periodic ZREMRANGEBYSCORE |
| C. Per-instance key | O(pods × domains) | O(keyspace) SCAN | O(1) SETEX | ≤ TTL | High (need index) | Any | TTL self-clean |
| D. Redisson lease | O(pods × domains × lock-overhead) | O(1) but indirect | watchdog | renewal interval | Medium | Any | Library-managed |
| E. Lazy HDEL + isAlive | O(pods × domains) | O(N) HKEYS + N×isAlive | O(1) HSET+EXPIRE | 0 at read time | Very low (current+filter) | Any | App filter + reaper |
| F. Pub/Sub ping | O(0) | RTT + timeout | — | 0 | Medium | Any | Always fresh |

---

## Libraries / Abstractions Worth Mirroring

- **Redisson** — no specific "presence service" abstraction, but `RBucket` + watchdog covers per-instance lease pattern. We could mimic the watchdog idea without taking the dependency.
- **Spring Data Redis** — no built-in presence abstraction. `RedisTemplate.opsForZSet()` is what we'd use for Pattern B with Lettuce.
- **Spring Session w/ Redis** — uses per-session keys with TTL (Pattern C variant) and a sorted set index for expiration events. Worth a read for the index pattern, but not a drop-in.
- **oalles/redis-presence** — https://github.com/oalles/redis-presence. Spring Boot + Redis Streams + key-space notifications. Different problem (user presence with SSE), but the keyspace-notification-driven cleanup is a pattern we could borrow if we want event-driven HDEL instead of polling.
- **rushsocket** — closest production match to our architecture (global presence Hash, TTL'd stats key per node).
- **CrackingWalnuts notification system writeup** — closest design match to our bug; their three-mechanism stale-cleanup is the textbook answer.

---

## Recommended Pattern for Our Case (Ranked)

### #1 — Lazy HDEL + `isInstanceAlive` filter at read time (Pattern E). Add a periodic reaper.

**Why first:**
- We already have all the pieces (`isInstanceAlive`, `ss:internal:instance:{id}` heartbeat keys, the Hash registry). Bug fix is a ~20-line change in the read path plus a scheduled task.
- Correctness is provable: the router can never act on a dead entry because every read filters via the authoritative liveness signal.
- Zero data migration, zero Redis version risk, zero new ops surface.
- This is **the** pattern the CrackingWalnuts large-scale design and rushsocket production codebase converge on, independently.

**Concretely:**
```java
// On read (routing/aggregation):
Set<String> instances = hashOps.keys("external-ws:registry:" + domain);
Set<String> alive = new HashSet<>();
List<String> deadFields = new ArrayList<>();
for (String id : instances) {
  if (instanceAlivenessService.isInstanceAlive(id)) alive.add(id);
  else deadFields.add(id);
}
if (!deadFields.isEmpty()) {
  hashOps.delete("external-ws:registry:" + domain, deadFields.toArray());
}
return alive;

// Add a Spring @Scheduled reaper (every 1-5 min) that iterates known domains
// and runs the same filter, to keep memory/observability clean even if no
// reads happen for a while.
```

### #2 — Migrate to Sorted Set (Pattern B), drop the per-pod count from the registry.

**When to prefer:** if we want a "cleaner" data model and are OK touching writer + reader code, with a managed migration. Sound long-term choice.

**Migration plan sketch:**
1. Dual-write phase: writers keep updating Hash AND `ZADD external-ws:registry:X <now> podA`.
2. Switch readers to `ZRANGEBYSCORE`.
3. Drop the Hash. Add `ZREMRANGEBYSCORE` cron.
4. If per-pod connection count matters for routing, move it to a separate key (`external-ws:conncount:{domain}:{podId}` with TTL, or a Hash that's only read after the ZSET filters live pods).

### #3 — `HEXPIRE` (Pattern A), **only if** ops confirms Redis 7.4+ on the actual managed instance and it's not ValKey.

**Block before pursuing:** verify with `INFO server` or `COMMAND INFO HEXPIRE` on the prod Redis. If Tencent Cloud, this is currently blocked (their published command matrix doesn't include 7.4). If ValKey-based, blocked (valkey-io/valkey#1070 still open).

---

## Honest Assessment: Is the Simple Fix Actually Fine?

**Yes.** The "keep current Hash + filter by isInstanceAlive + lazy HDEL + periodic reaper" approach is not a workaround — it's the same pattern large-scale production systems (CrackingWalnuts, rushsocket) deliberately ship. The Hash+EXPIRE failure mode exists for **every** read pattern that doesn't independently verify liveness; once you add the liveness filter, the Hash being briefly stale becomes irrelevant.

The Sorted Set migration is a **nicer** data model and would be the right greenfield choice, but the marginal benefit over the simple fix is:
- Slightly less code on the read path (no liveness loop).
- Slightly tighter memory bound (auto-aged out without a reaper).
- Cleaner observability (ZSET reflects truth without a read).

That's worth ~1-2 days of migration work, not a P0. **Recommendation: ship the simple fix now, schedule the ZSET migration as a follow-up if observability or memory becomes annoying.** Skip `HEXPIRE` unless ops independently wants the version bump.

---

## Caveats / Not Done in This Pass

- Did not verify the prod Redis version / vendor — assumed it could be Tencent Cloud or ValKey based on the project's other journal entries. **Please confirm.**
- Did not benchmark `isInstanceAlive` call latency under the proposed N-per-read loop. With ~50 pods max and Lettuce pipelining, this should be sub-ms, but if the call is sequential and round-trips per pod it could add up. Consider `MGET` of all `ss:internal:instance:*` keys in one shot.
- Did not survey Alibaba Cloud / Azure Cache for Redis 7.4 status.
- Did not deep-dive Redisson `RMapCacheNative` / `RMapCache` — Redisson 3.27+ wraps `HEXPIRE` natively (`RMapCacheNative`) and provides client-side TTL emulation in `RMapCache` for pre-7.4 servers. If we ever did adopt Redisson, this could be a drop-in for our exact use case. Worth a 30-min spike if we ever consider migrating off Lettuce.

---

## Source URLs (Inline-Cited)

- Redis 7.4.0 release: https://github.com/redis/redis/releases/tag/7.4.0
- `HEXPIRE` doc: https://redis.io/docs/latest/commands/hexpire/
- `HPEXPIRE` doc: https://redis.io/docs/latest/commands/HPEXPIRE/
- Redis 7.4 "What's new": https://redis.io/docs/latest/develop/whats-new/7-4/
- Hash field expiration KB: https://redis.io/kb/doc/29bj4ewcad/how-can-i-take-advantage-of-the-expiration-of-individual-hash-fields
- HFE PR #13303: https://github.com/redis/redis/pull/13303
- Original "insisting on hash field expiration" issue thread: https://github.com/redis/redis/issues/6620
- ValKey HEXPIRE tracking issue (still open): https://github.com/valkey-io/valkey/issues/1070
- AWS ElastiCache supported commands (lists HEXPIRE family): https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/SupportedCommands.html
- Tencent Cloud Hash command compatibility: https://www.tencentcloud.com/document/product/239/50392
- Svix presence guide (sorted-set canonical pattern): https://www.svix.com/resources/redis/presence-detection/
- hjr265 presence tracking: https://hjr265.me/blog/presence-tracking-with-redis/
- OneUptime "Set with Expiration": https://oneuptime.com/blog/post/2026-03-31-redis-set-with-expiration/view
- OneUptime presence detection: https://oneuptime.com/blog/post/2026-01-21-redis-presence-detection/view
- Redis sorted-set time-series glossary: https://redis.io/glossary/sorted-set-time-series
- CrackingWalnuts 100M notification system design (THE matching real-world architecture): https://crackingwalnuts.com/post/notification-system-design
- rushsocket production codebase: https://github.com/rushsocket/rushsocket
- oalles/redis-presence (Spring Boot reference impl): https://github.com/oalles/redis-presence
- Redisson Spring integration: https://redisson.org/docs/integration-with-spring/
- Kubernetes WS scaling overview: https://jorijn.com/en/knowledge-base/kubernetes/networking/load-balancing-long-lived-connections/
