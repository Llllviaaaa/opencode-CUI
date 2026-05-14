# Research: Sticky Routing, Consistent Hashing, and Owner Election for Stateful Long-Connection Workloads

- **Query**: Survey routing-layer designs that avoid the "stale routing table" problem for `domain -> pod` ownership in a Spring Boot WebSocket fleet that already uses Redis pub/sub as transport.
- **Scope**: External (industry designs) with applicability analysis to our codebase.
- **Date**: 2026-05-14
- **Constraints**: Redis pub/sub transport is fixed. Vanilla Deployment (no StatefulSet). The question is whether to replace the *routing table* mechanism.

---

## Problem framing (one paragraph)

Today, a `domain -> pod` mapping lives in Redis as plain keys written by each pod when it accepts an external WS connection, and refreshed by liveness logic. If a pod dies abnormally (OOM kill, kubelet eviction, SIGKILL during drain), the mapping outlives the pod and other pods publish messages to a Redis channel that no one is subscribed to. The class of fix splits into two axes:

- **A. Make routing deterministic** (consistent hash of `domain` over the pod set) so there is no registry to go stale.
- **B. Make the registry self-cleaning** (TTL/lease/ephemeral semantics) so a dead pod loses ownership within bounded time, regardless of whether it deregistered politely.

The designs below sit on those two axes.

---

## 1. Consistent hashing (`domain -> pod`) — Discord / Slack / Twitter style

### How it works
A hash function maps each `domain` to a position on a ring; the pod owning that arc is the authoritative holder. No "table" — the assignment is a pure function of `(domain, current pod set)`. The only shared state is the *membership list*, which is much smaller than a per-domain registry and usually maintained by a coordinator (etcd, ZooKeeper) or by gossip.

### Production examples
- **Discord Guilds / Voice** (Elixir + etcd). Voice syncers and guild ownership are placed on nodes via a consistent hash ring whose membership is driven by etcd leases with a **60s TTL**. Health pings refresh the lease; if a node fails to ping, it falls out of the ring and the keys it owned re-hash to peers. The 2026-03 voice outage post-mortem is a candid description of how this works *and* how it can break when the same supervisor mailbox that handles RPCs also handles etcd keep-alives — sources: https://discord.com/blog/behind-the-scenes-of-the-3-25-26-voice-outage and https://discord.com/blog/how-discord-handles-two-and-half-million-concurrent-voice-users-using-webrtc
- **Slack Channel Servers (CS) + CHARM** (Consistent Hash Ring Manager). `channel_id` is hashed to a CS host; Gateway Servers subscribe to the relevant CS by ring lookup. When a CS goes unhealthy, CHARM reassigns its channels and a replacement CS is serving in **under 20 seconds** — that 20s window is the worst-case elevated-latency budget Slack pays for failover. Source: https://slack.engineering/real-time-messaging and https://snowan.gitbook.io/study-notes/ai-blogs/design-slack-messaging-system
- **Twitter / X — ShardLib + ZooKeeper-announced shards** for ads serving. ZooKeeper holds the shard placement; clients re-resolve dynamically; two sharding schemes can be simultaneously active during a reshard so deploys decouple. Source: https://blog.x.com/engineering/en_us/topics/infrastructure/2021/sharding-simplification-and-twitters-ads-serving-platform
- **Slack Flannel edge cache** uses consistent hashing to keep users from the same team on the same Flannel instance for cache locality. Source: https://slack.engineering/flannel-an-application-level-edge-cache-to-make-slack-scale-b8a6400e2f6b

### Rebalancing cost / in-flight semantics
- Plain modulo (`hash % N`) reshuffles ~all keys when N changes. Use **ring hashing or rendezvous (HRW) hashing** — only `1/N` of keys move per add/remove. Slack/Discord/Envoy/HAProxy/ingress-nginx all use ring variants. References: https://github.com/kubernetes/ingress-nginx/pull/9239 (consistent hashing with bounded loads), https://github.com/gravitational/redis/blob/master/ring.go (Redis-client-side rendezvous).
- During a remap, **in-flight messages targeted at the *old* owner are lost** unless the source pod also subscribes briefly to the new owner or re-publishes. Discord's pattern is "redirect + reconnect" — the SFU is reassigned, voice state is pushed to the new node, and the client tears down + reconnects (https://discord.com/blog/how-discord-handles-two-and-half-million-concurrent-voice-users-using-webrtc). Slack's CHARM accepts a ~20s gap as the design budget.
- Consistent-hash routing requires every routing decision-maker to see the *same* ring snapshot. When pod IPs change frequently in K8s, this is the hard part — see the ingress-nginx WebSocket question where two ingress replicas produced *different* upstream orderings and hashed the same key to different pods: https://stackoverflow.com/questions/79762856/ingress-nginx-consistent-hash-for-websocket-routing-across-multiple-controller-p
- **Envoy/Istio `consistentHash` LB** can do this at the proxy layer but Istio's own docs warn: "consistent hashing is less reliable at maintaining affinity than common sticky sessions … any host addition or removal can break affinity for 1/backends requests" — https://istio.io/latest/docs/reference/config/networking/destination-rule/

### Variant — Discord shard formula
`shard_id = (guild_id >> 22) % num_shards`. Dead-simple, deterministic, but `num_shards` change is a coordinated event (Discord supports running two shard counts simultaneously during transitions). Source: https://github.com/discord/discord-api-docs/pull/6853

---

## 2. etcd / Consul / ZooKeeper — leases & ephemeral nodes

### How it works
Each pod creates an *ephemeral key* attached to a **lease**; the lease is renewed by a streaming keep-alive (etcd) or by a TCP-session heartbeat (ZK). When the pod dies — politely or not — the keep-alive stops, the lease expires, the key is auto-deleted. This is the strong-liveness primitive that Redis does not natively offer.

### Production examples
- **Kubernetes itself** uses etcd leases for kubelet node liveness (`kube-node-lease` namespace), apiserver leader election, and controller leader election. The K8s `Lease` resource is the idiomatic mechanism. Sources: https://www.youngju.dev/blog/etcd/etcd_watch_lease.en and https://crackingwalnuts.com/concurrency/realworld-heartbeat-election
- **Discord** (above) — etcd lease 60s TTL drives consistent-hash ring membership.
- **ZooKeeper ephemeral znodes** auto-delete on session close (session = TCP heartbeat). Used historically by Kafka, HBase, Twitter (Finagle/Serversets), Solr. Sources: https://sujeet.pro/articles/service-discovery-and-registry, https://oneuptime.com/blog/post/2026-03-31-redis-redis-vs-zookeeper-for-service-coordination
- **Consul** — Raft-based, supports sessions with TTLs and session-bound keys.

### Why this is "stronger" than Redis TTL
- The lease and the key delete are **linearizable, server-side** — there's no race where the client must remember to `DEL`. Lease revoke is in Raft; once it commits, every observer sees the deletion.
- Watch streams deliver the deletion event to all interested parties with low latency (etcd: gRPC stream; ZK: ephemeral-node watch).
- Natural fencing tokens (etcd revision, ZK sequence) prevent split-brain writes — see https://crackingwalnuts.com/concurrency/realworld-heartbeat-election.

### What it costs
- A **new infra dependency** with its own ops, upgrade paths, on-call burden, and Raft quorum (3 or 5 nodes minimum). etcd specifically OOM-balloons under read-heavy load and is sensitive to disk fsync latency. Source: https://stackoverflow.com/questions/51624598/why-use-etcd-can-i-use-redis-to-implement-configuration-management-service-disco
- Lease expiration is **lazy** — etcd's revoke sweep runs every 500ms after TTL, so there is a sub-second window where a key can outlive its lease. Mutual exclusion patterns must guard the critical section with a `Txn` asserting `ModRevision`, not just "I have a lease." Source: https://etcd.io/docs/v3.6/learning/why
- For most teams "we want stronger liveness" *does not* justify adding etcd alongside Redis. The honest answer in the literature is "use Kubernetes Leases if you're already on K8s." Source: https://crackingwalnuts.com/concurrency/realworld-heartbeat-election ("For Kubernetes-native services: Lightweight, integrated, [use K8s Lease]").

---

## 3. Kubernetes-native: StatefulSet + headless Service + pod-direct addressing

### How it works
A `StatefulSet` gives each pod a **stable ordinal hostname** (`skill-server-0`, `skill-server-1`, …) and per-pod DNS via a headless Service: `skill-server-0.skill-server-hs.ns.svc.cluster.local`. Other pods address each other directly by hostname. The membership list collapses to "ordinals 0..N-1" and DNS is the registry.

### Where this is real
- **Kafka brokers, NATS JetStream, etcd members, PostgreSQL primary/replica, Elasticsearch nodes, Redis Cluster** — all run as StatefulSets specifically because the brokers gossip member addresses and need them stable. NATS Helm chart explicitly uses a StatefulSet + headless Service for peer discovery: https://nats.io/blog/nats-helm-chart-1.0.0-rc/ and https://docs.nats.io/running-a-nats-service/nats-kubernetes
- **Headless services for stateful peer-to-peer** — https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/ and https://jorijn.com/en/knowledge-base/kubernetes/networking/kubernetes-statefulsets/
- **The pod-index label** (`apps.kubernetes.io/pod-index`, stable since K8s 1.32) makes a per-pod Service-selector possible — you can address a specific replica via L4 Service if needed.

### What you'd actually do for our case
- Migrate `skill-server` Deployment → StatefulSet.
- Pods derive ownership of `domain` via consistent hash over `ordinal mod N` (so the ring is "0..N-1", not pod IPs that churn).
- Source pod publishes to the *target ordinal's* Redis channel (e.g. `skill-server:pod:7:msg`), and pod 7 always knows it's pod 7.
- Stale-pod problem mostly disappears: pod 7 *is* pod 7 across restarts. If pod 7 is down, no one consumes — but a fresh pod-7 will boot and resume the same ownership share.

### Caveats
- StatefulSet **scale-up is sequential by default**, slower than Deployment.
- StatefulSet **scale-down terminates highest-ordinal first**, which interacts with consistent hashing: shrinking from 8→7 only moves keys from ordinal 7, which is exactly the ring property you want, but only if every routing decision uses "current N" consistently.
- Migration to StatefulSet for a long-lived WS fleet is non-trivial: rolling update semantics differ (one-at-a-time), and you must publish a headless Service.
- Vanilla Deployment hostnames are *not* stable today — this is a real migration, not a config flip.

### Sidestepping Redis routing?
The literature is firm: even with stable pod identities, the practical pattern for "shared state across pods serving long-lived WS" is still **a shared-state backplane (Redis/NATS/Kafka)**. Source: https://jorijn.com/en/knowledge-base/kubernetes/networking/load-balancing-long-lived-connections/ ("At production scale … the resilient pattern is a shared-state backplane"). So StatefulSet helps make the *routing table* deterministic; it does not let you delete the *transport*.

---

## 4. Service mesh routing for WebSockets (Envoy / Istio / ingress-nginx)

### How it works
The edge proxy terminates the client TCP, then picks a backend pod via cookie hash (sticky session), header hash, or source-IP hash. The proxy maintains its own pod set and consistent ring.

### What it actually does for our case
- **It only solves the *first-hop* WS pinning problem** (client → some pod). It does *not* solve `domain X message published on pod A but X's WS is on pod B`, which is our actual problem. The mesh has no notion of "this WS handle is for `domain X`" unless the client tells it via a header/cookie — which our external WS clients do not control.
- Istio's own docs say consistent-hash LB is *weaker* than sticky cookies, and "any host addition or removal can break affinity for 1/backends requests." Source: https://istio.io/latest/docs/reference/config/networking/destination-rule/
- ingress-nginx hash-by-query-param is **only consistent within a single nginx instance** — multi-replica ingress controllers produce divergent upstream orderings. Source: https://stackoverflow.com/questions/79762856/ingress-nginx-consistent-hash-for-websocket-routing-across-multiple-controller-p
- Sticky-session cookie + a shared-state backplane is the dominant pattern (https://dev.to/deepak_mishra_35863517037/scaling-horizontally-kubernetes-sticky-sessions-and-redis-578o, https://jorijn.com/en/knowledge-base/kubernetes/networking/load-balancing-long-lived-connections/).

### Verdict for our case
**Not applicable as a replacement for the routing table.** The mesh only controls inbound HTTP/WS, not which pod owns a given domain after the connection is established. It could shave a sticky-session edge case but doesn't address stale-pod for outbound message delivery.

---

## 5. Erlang/OTP `pg` / `global` / `syn` / `gproc`

### How it works
Every BEAM node has a local copy of a process registry; registrations replicate via Erlang distribution. `pg`/`pg2` is AP (eventually consistent); `global` is CP (uses a distributed lock during registration). Net splits are handled by user-provided resolve functions. Process *monitors* automatically deregister dead PIDs. This is the "ideal" because the runtime itself owns liveness.

### Production examples
- **Discord Gateway/Guilds/Voice** is built on this exact stack — guilds are Elixir processes registered globally; routing is "send to PID at known address" with the BEAM cluster handling the lookup.
- Libraries: `syn` (https://github.com/ostinelli/syn/, AP, strong-eventual-consistency, Mnesia-backed), `gproc`, `global` (https://erlang.org/doc/apps/kernel/global), `pg2`.

### Why hard to replicate on JVM
- No equivalent of BEAM process monitors at the language level — JVM has no "PID died, here's a deathwatch message" primitive that crosses process boundaries.
- BEAM distribution gives you exactly-once node-up/node-down events. JVM clusters (Akka Cluster, Hazelcast, JGroups) reimplement this on top of failure detectors, but it's a *framework* choice, not a runtime one.
- Akka Cluster (now Pekko after Lightbend re-license) is the closest equivalent and has been used for similar problems, but adopting it for a Spring Boot fleet is essentially "rewrite the app in actors."

### Verdict for our case
**Not applicable as a migration**, but useful for context: every time you see "the BEAM way" referenced, what you're really seeing is "ephemeral registry tied to TCP session liveness with watch streams." That's exactly what etcd/ZK give you — just via an external service instead of the language runtime. The mental model maps cleanly.

---

## 6. Database-backed routing (Postgres LISTEN/NOTIFY, MySQL heartbeat table)

### How it works
Pods register themselves in a `pod_ownership` table with a `last_heartbeat` timestamp. Either pollers check `WHERE last_heartbeat > now() - interval '30s'` or `LISTEN/NOTIFY` (Postgres) signals other pods when ownership changes.

### Reality check
- **PostgreSQL LISTEN/NOTIFY**: fire-and-forget pub/sub built into Postgres. Used for cache invalidation and small signals. https://www.prisma.io/blog/you-dont-need-redis-postgres-already-has-pub-sub and https://goldlapel.com/grounds/replication-scaling-cloud/postgresql-listen-notify-pubsub
- **PgBouncer in transaction mode silently breaks LISTEN/NOTIFY** because LISTEN is session state — the connection returns to the pool and your listener is gone, with no error. https://www.michal-drozd.com/en/blog/pgbouncer-listen-notify-transaction-pooling/ This is a footgun.
- Database-backed registries are typically *strictly worse* than Redis for this — same TTL/heartbeat semantics, higher latency, scarce connections, and DB load you don't want from a control plane.

### Verdict for our case
**Not worth it.** Smaller systems pick this when they have a DB but no Redis. We already have Redis.

---

## 7. Redis-only consistent hashing (for completeness)

You can implement consistent hashing *with Redis as the membership store* and avoid adding etcd:

- Store ring membership as a Redis sorted set (`ZADD` with score = ring position). Each pod owns N replicas on the ring and refreshes the score (which doubles as an expiry) periodically. Reference implementation: https://github.com/closeio/redis-hashring
- A second project — https://github.com/zavitax/redis-replica-manager-go — does rendezvous hashing for slot assignment with Redis coordination, including a "missing slots / redundant slots" notification model that is exactly the shape of our problem.
- Redis service-discovery patterns with TTL + keyspace notifications: https://oneuptime.com/blog/post/2026-01-21-redis-service-discovery/view, https://medium.com/@ansujain/managing-node-lifecycle-in-distributed-systems-using-etcd-or-redis-a-comprehensive-guide-e44cc39520a4

### Honest caveats
- Redis TTL/keyspace-notification is **best-effort delivery**. Notifications are not durable; a pod that misses the `expired` event for ring member X will *not* re-deliver. You must reconcile periodically (e.g. every 5–10s scan the ring sorted-set against a known liveness set).
- Redis is single-leader replicated, not Raft. A failover can cause keyspace-notification loss. For ring membership of ~10s of pods, that is acceptable. For high-stakes leader election, it is not.
- The `redis-hashring` README explicitly warns about the rebalance race: "When nodes are added to the ring, multiple nodes might assume they're responsible for the same key until they are notified about the new state of the ring." → for our use case that means *both* the old and new owner pod can briefly think they own `domain X`. We'd need either a lock or idempotent message delivery.

---

## Applicability table

| Design | Migration cost | Ops complexity (added) | Perf | Failure semantics for stale-pod | Fits our constraints? |
|---|---|---|---|---|---|
| **1. Consistent hash, ring kept in Redis** (our current Redis + ring overlay) | Low–Med (only the routing layer changes; transport unchanged) | None (reuses Redis) | Same as today | Pod death → ring member's TTL expires in N seconds → re-hash automatic | Yes — matches Redis-only constraint |
| **1b. Consistent hash, ring kept in etcd** (Discord shape) | High (new infra) | High (new component, on-call, upgrades) | Slightly better liveness (server-side lease revoke) | Strong: lease revoked atomically | No — fails "etcd is not free" constraint unless we already need it |
| **2. etcd/Consul/ZK ephemeral registry, no consistent hash** (just replace Redis registry) | High | High | Same | Strong | No |
| **3a. StatefulSet + ordinal-based hashing** | Med-High (Deployment → StatefulSet migration) | Low–Med (StatefulSet rolling updates differ) | Same | Strong (pod identity is stable, ownership is a function of ordinal) | Possible — but is a bigger move than a routing-layer fix |
| **3b. StatefulSet + per-pod direct addressing (skip Redis routing)** | High | Med | Better (one less hop) | Strong | No — fails "Redis transport stays" constraint |
| **4. Service mesh (Envoy/Istio) sticky** | Low if already on mesh | Low | Same | Does not solve outbound `pod-to-pod` routing for our case | No — wrong layer |
| **5. BEAM `syn`/`global`** | N/A | N/A | N/A | N/A | No — wrong runtime |
| **6. Postgres LISTEN/NOTIFY or heartbeat table** | Low | Low | Worse | TTL-based, with PgBouncer footguns | No |
| **Targeted bug fix on current design** (TTL + reconcile loop + fencing token in the registry) | Lowest | None | Same | TTL-based; eventual cleanup within N seconds | Yes |

---

## Verdict (opinionated)

**Do the targeted bug fix first.** Adding etcd/StatefulSet/mesh to fix "Redis routing entries outlive dead pods" is a hammer–nail mismatch. The actual class of fix you need is *"give every registry entry a lease that the owner pod must renew, and have writers verify the lease is fresh (or fall back to a reconcile scan) before publishing."* That is two pieces:

1. **Lease the registry entry.** Replace plain `SET domain:X = podId` with `SET domain:X = "{podId}|{epoch}" EX 30` and have the pod refresh every ~10s. Add a single `WATCH`/Lua check-and-publish path on the sender side so messages never go to an expired holder.
2. **Background reconciler.** Every 5s each pod scans its claimed-but-unrenewed entries and re-asserts; deletes entries it owns but no longer has the WS for. This catches the "pod died holding a lease that hasn't expired yet" window — bounded by lease TTL, not unbounded.

This is the **Redis-with-lease pattern** documented in https://medium.com/@ansujain/managing-node-lifecycle-in-distributed-systems-using-etcd-or-redis-a-comprehensive-guide-e44cc39520a4 and effectively the same liveness model Discord uses with etcd, just on Redis with a wider window. For a fleet measured in tens of pods (not 25,000 SFUs), Redis's weaker semantics are fine if you accept a ~lease-TTL stale window.

**If that bug fix is not enough,** the next step up — in order of cost — is:

- **Consistent hashing with the ring in Redis** (option 7 above; `redis-hashring`-style). This eliminates the per-domain entry entirely. Cost is medium: every routing decision now reads ring membership instead of `domain:X`. Worth it if domains-per-pod is high and per-entry registry traffic is a bottleneck, *not* if you mostly want correctness — correctness comes from leases either way.
- **StatefulSet + ordinal-based ring** (option 3a). Worth it only if you separately want stable pod identities for *other* reasons (e.g. you also want a per-pod PVC for some local state, or you want to make rolling deploys observable per-pod). Migrating Deployment → StatefulSet to fix a 50-line routing bug is overkill.

**Do not:**
- Add etcd just for this. The TCO of running a 3-node etcd cluster for production WS routing is not justified at our scale unless you also adopt it as the platform's primary coordination store.
- Try to fix it at the Istio/Envoy layer — wrong abstraction; the mesh has no view of `domain X is owned by pod B`.
- Use Postgres LISTEN/NOTIFY — same semantics as Redis with worse ops profile and PgBouncer footguns.

The empirical pattern across Discord, Slack, Twitter is **the same one**: deterministic placement function (hash) + lease-driven membership (etcd/ZK) + accept a small failover window (Discord's 60s, Slack's 20s). You can replicate that exact pattern with Redis leases at the cost of weaker liveness during Redis failover events — which for a routing table is acceptable.

---

## Key external references

- Discord Voice architecture: https://discord.com/blog/how-discord-handles-two-and-half-million-concurrent-voice-users-using-webrtc
- Discord 3/25/26 voice outage (etcd + consistent hash + lease in production): https://discord.com/blog/behind-the-scenes-of-the-3-25-26-voice-outage
- Discord 200M-users architecture overview: https://techie007.substack.com/p/how-discord-handles-200-million-users
- Discord sharding formula PR: https://github.com/discord/discord-api-docs/pull/6853
- Slack real-time messaging (CS/GS/AS, CHARM): https://slack.engineering/real-time-messaging
- Slack design (CHARM 20s failover): https://snowan.gitbook.io/study-notes/ai-blogs/design-slack-messaging-system
- Twitter ShardLib (ZooKeeper-announced shards, dual scheme during reshard): https://blog.x.com/engineering/en_us/topics/infrastructure/2021/sharding-simplification-and-twitters-ads-serving-platform
- Twitter timeline fan-out architecture: https://sujeet.pro/articles/twitter-timeline-architecture
- ingress-nginx multi-replica consistent-hash divergence (the gotcha): https://stackoverflow.com/questions/79762856/ingress-nginx-consistent-hash-for-websocket-routing-across-multiple-controller-p
- ingress-nginx Consistent Hashing with Bounded Loads (PR): https://github.com/kubernetes/ingress-nginx/pull/9239
- WS L4-vs-L7 + shared-state backplane pattern: https://jorijn.com/en/knowledge-base/kubernetes/networking/load-balancing-long-lived-connections/
- Istio consistent-hash LB (and its limits): https://istio.io/latest/docs/reference/config/networking/destination-rule/
- etcd vs Consul vs ZK rationale: https://etcd.io/docs/v3.6/learning/why
- Service registry patterns (CP vs AP): https://sujeet.pro/articles/service-discovery-and-registry
- Heartbeat & leader election patterns: https://crackingwalnuts.com/concurrency/realworld-heartbeat-election
- etcd Watch + Lease deep dive: https://www.youngju.dev/blog/etcd/etcd_watch_lease.en
- StatefulSet (K8s docs): https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/
- StatefulSet vs Deployment for stateful peers: https://jorijn.com/en/knowledge-base/kubernetes/networking/kubernetes-statefulsets/
- NATS Helm chart (StatefulSet + headless): https://nats.io/blog/nats-helm-chart-1.0.0-rc/
- Sticky sessions + Redis pub/sub for Flask-SocketIO scaling: https://dev.to/deepak_mishra_35863517037/scaling-horizontally-kubernetes-sticky-sessions-and-redis-578o
- redis-hashring (Redis-only consistent hashing reference): https://github.com/closeio/redis-hashring
- redis-replica-manager-go (rendezvous hashing + slot ownership over Redis): https://github.com/zavitax/redis-replica-manager-go
- BEAM `global` registry: https://erlang.org/doc/apps/kernel/global
- `syn` (Erlang/Elixir registry, AP): https://github.com/ostinelli/syn/
- Postgres LISTEN/NOTIFY + PgBouncer footgun: https://www.michal-drozd.com/en/blog/pgbouncer-listen-notify-transaction-pooling/
- Node lifecycle: etcd vs Redis side-by-side: https://medium.com/@ansujain/managing-node-lifecycle-in-distributed-systems-using-etcd-or-redis-a-comprehensive-guide-e44cc39520a4

## Caveats / Not found

- I did not benchmark Redis ring membership refresh latency under realistic Redis-failover conditions. If you decide to pursue Redis-only consistent hashing, run a chaos test that kills the Redis primary mid-rebalance.
- No first-party numbers from Discord on what fraction of voice-syncer keys re-hash during pod churn — the 2026-03 outage post mentions only that a single survivor was assigned "3/15 share of global syncer traffic" which is consistent with `(primary + secondary + tertiary)/N=15` triple-replica placement.
- I did not survey Hazelcast/Pekko (Akka Cluster)-based JVM solutions in depth; the report's "wrong-runtime" verdict for BEAM-style registries also applies in spirit to those — they are *framework adoptions*, not routing-table swaps.
