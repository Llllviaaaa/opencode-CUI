# Research: Industry Approaches to Multi-Instance WebSocket Presence / Routing

- **Query**: How do mainstream realtime products route outbound messages to the right backend instance and detect/handle dead instances quickly?
- **Scope**: external
- **Date**: 2026-05-14

## Per-product findings

### 1. Socket.IO Redis Adapter

- **Routing primitive**: Pure **pub/sub fan-out**. Every server subscribes to `socket.io#<namespace>#<room>#` channels. When server A wants to emit to room R, it publishes once; every server (or every server subscribed to that sharded channel) receives it and decides locally whether it has matching clients. There is **no instance-targeted routing** — the adapter does not maintain "which node holds which socket id" in Redis. Each node keeps its own sid→room map in process memory.
- **Liveness detection**: **None**. The adapter has no concept of a node registry, no heartbeat, no node-death handling. A dead pod simply stops being subscribed to the Redis channels, so nobody notices.
- **In-flight messages on death**: For broadcast — silently dropped on the dead node only; other live nodes still deliver. For point-to-point (`socket.id` on another node, via `remoteJoin`/`remoteLeave` request-response), the request times out after `requestsTimeout` (default 5s) and the caller gets a Promise rejection or partial reply.
- **TTL / stale entries**: No Redis keys are stored. No staleness problem because state is in pub/sub, not in a registry.
- **Sticky sessions** are required at the LB layer (so a given client always lands on the same node); recovery across node restarts requires a different adapter (Redis Streams / Mongo).
- Sources: <https://socket.io/docs/v4/redis-adapter/>, <https://github.com/socketio/socket.io-redis>, <https://socket.io/docs/v4/connection-state-recovery>

### 2. Centrifugo (centrifuge library)

- **Routing primitive**: Mostly **PUB/SUB fan-out** per channel (subscribers receive messages by virtue of subscribing). Plus a dedicated **control channel** for node-to-node RPC (subscribe/unsubscribe/disconnect/survey/notification).
- **Liveness detection**: Explicit **node-info heartbeat over the control channel**. Each node publishes a `Node` control message every `nodeInfoPublishInterval = 3s`. Other nodes track the last-seen time; if no message arrives for `nodeInfoMaxDelay = 2*publish + 1s ≈ 7s` the node is considered stale and dropped during `nodeInfoCleanInterval = 9s`. So **dead-node detection ≤ ~9s**, no Redis TTL involved — it's purely in-memory gossip-via-pubsub.
- **Presence storage**: Channel-presence (which client is in which channel) lives in Redis under a hash + sorted set with Lua scripts, with `PresenceTTL` default 60s per client entry. Recently added `UseHashFieldTTL` option uses Redis 7.4 HEXPIRE so individual presence entries auto-expire without needing a separate sorted-set sweep.
- **In-flight messages on death**: For broadcast — fine, other subscribers still get it. Centrifugo does not try to redirect "I had a connection to that user" because it doesn't route to a specific node; any node holding that channel subscription receives the publish.
- **TTL / stale entries**: Hybrid. Node registry uses heartbeat-driven in-memory cleanup; presence entries use either sorted-set ZREMRANGEBYSCORE sweep or HEXPIRE.
- Sources: <https://github.com/centrifugal/centrifuge/blob/v0.38.0/node.go>, <https://github.com/centrifugal/centrifuge/blob/master/presence_redis.go>, <https://centrifugal.dev/docs/server/engines>

### 3. Microsoft SignalR (Redis backplane)

- **Routing primitive**: **PUB/SUB fan-out**, like Socket.IO. The backplane has no "which server holds which connection" map — every server is subscribed to the relevant channels (per group / per user / per connection-id), so publish is fan-out and each server filters locally.
- **Liveness detection**: **None at the application layer**. SignalR relies on (a) sticky sessions at the LB so a given connection-id always lands on the same node, and (b) the assumption that if Redis itself is up, broadcast still works. There is no "is this node alive" registry.
- **In-flight messages on death**: Lost. Docs explicitly: *"SignalR doesn't buffer messages to send them when the server comes back up. Any messages sent while the Redis server is down are lost."* When a SignalR pod dies, its clients reconnect (LB stickiness reshuffles them onto a live pod). Messages targeted at a connection-id whose owning node is dead are simply not delivered — the target connection no longer exists once the client reconnects with a new id.
- **TTL / stale entries**: N/A — no registry.
- The recommended alternative is **Azure SignalR Service**, which is a managed proxy that owns all client connections and removes the backplane problem from the app servers entirely.
- Sources: <https://learn.microsoft.com/en-us/aspnet/core/signalr/redis-backplane>, <https://learn.microsoft.com/en-us/aspnet/core/signalr/scale>

### 4. Phoenix Channels / Elixir (`Phoenix.PubSub` + `Phoenix.Tracker`)

- **Routing primitive**: **Direct process messaging on the BEAM**. Each channel is a process with a globally-unique PID; broadcasts use a `:pg2`/`pg` process group local to each node, and node-to-node fan-out rides on Erlang distribution. Targeted send is also possible — `send(pid, msg)` works transparently across nodes.
- **Liveness detection**: Two layers. (a) Erlang `net_kernel` monitors emit `:nodedown` immediately on a TCP disconnect or `kill -9`. (b) `Phoenix.Tracker` is a **CRDT-based gossip**: each node heartbeats every `broadcast_period = 1500ms`, and a replica is flagged "down" after `down_period = broadcast_period * max_silent_periods * 2 = 30s`. Permanent removal at `permdown_period = 20min` (configurable to immediate on graceful shutdown).
- **In-flight messages on death**: Messages already enqueued in a dead process's mailbox are gone. Broadcasts via `:pg` only target live members, so there is no "publish to dead node" scenario.
- **TTL / stale entries**: Tracker uses CRDT joins/leaves so stale state is converged away automatically; no Redis TTL involved.
- Sources: <https://hexdocs.pm/phoenix_pubsub/Phoenix.Tracker.html>, <https://hexdocs.pm/phoenix/presence.html>, <https://supabase.com/docs/guides/realtime/architecture>

### 5. Discord gateway

- **Routing primitive**: **Targeted routing via consistent hashing**. Every Discord "Guild" maps to exactly one Elixir process on one node (consistent hash). Gateway nodes subscribe to the guilds whose members they hold. Fan-out path: client → its gateway → guild process (on whichever node owns it) → pub/sub to gateways that have subscribed members → each gateway local-fans-out to its WebSockets.
- **Liveness detection**: **etcd-backed service discovery with TTL leases** (60s TTL per service registration, re-registered periodically). Each instance reports health to etcd; consumers (the consistent-hash ring) refresh from etcd. The 2026 voice-outage postmortem describes exactly this: when an instance's etcd heartbeat is blocked, it drops out of the ring after 60s and the ring rebalances.
- **In-flight messages on death**: Sessions are re-established by clients on a new gateway; ongoing in-flight messages targeted at the dead node are lost. Discord puts heavy effort into session-resume (`:DOWN` message → client reconnect → fast resume in same zone).
- **TTL / stale entries**: 60s lease in etcd; not Redis.
- Sources: <https://discord.com/blog/how-discord-handles-two-and-half-million-concurrent-voice-users-using-webrtc>, <https://discord.com/blog/behind-the-scenes-of-the-3-25-26-voice-outage>

### 6. Slack real-time

- **Routing primitive**: **Targeted routing via consistent hashing of channel-id → Channel Server (CS)**. Gateway Servers (GS) own client websockets; on connect, the GS subscribes to all relevant CSs (by hash). Outbound flow: API → Admin Server → hash(channel) → CS → fan-out to all subscribed GSs → fan-out to WebSockets. Presence is a separate sharded service (Presence Servers, hash users to PS).
- **Liveness detection**: Not explicitly documented in public posts. Public blog says they have a "draining mechanism for region failures" and the recent Envoy migration discusses LB-level health checks. CS/GS are stateful, in-memory, with consistent hashing — implying the same ring-rebalance-on-failure pattern as Discord.
- **In-flight messages on death**: Slack's posts don't disclose detail. Clients reconnect to nearest healthy region via DNS / Envoy.
- **TTL / stale entries**: Not disclosed.
- Sources: <https://slack.engineering/real-time-messaging/>, <https://slack.engineering/flannel-an-application-level-edge-cache-to-make-slack-scale/>, <https://slack.engineering/migrating-millions-of-concurrent-websockets-to-envoy/>

### 7. Pusher / Ably

- **Pusher**: closed-source; very little public architecture detail. Skipping — no honest data.
- **Ably**: published a fairly detailed architecture overview.
  - **Routing primitive**: peer-to-peer between cluster nodes; messages flow node-to-node directly rather than via a central broker. Frontend nodes are stateless connection terminators; channel-level state has a "place-of-record" node.
  - **Liveness detection**: a **gossip layer** maintains a "netmap" — list of all nodes plus health. Gossip protocol with periodic heartbeats, eventually consistent. Acts as the service discovery for higher layers. Self-healing and tolerant to gossip-node failure.
  - **In-flight messages on death**: SDK side has connection-state machine with `disconnected → suspended` after 2 minutes; messages published while disconnected are queued locally and resent. Server side claims 100% delivery via multi-region redundancy.
  - **TTL**: not Redis-based; gossip eviction.
- Sources: <https://ably.com/docs/platform/architecture>, <https://www.ably.io/docs/platform/architecture/fault-tolerance>, <https://www.ably.io/docs/platform/architecture/edge-network>

## Patterns observed

| Product | Routing model | Liveness mechanism | Failure handling |
|---|---|---|---|
| Socket.IO Redis Adapter | Pub/sub fan-out, no instance routing | None | Dead node silently drops; LB stickiness re-homes clients |
| Centrifugo | Pub/sub + control-channel RPC | Heartbeat over control channel, ~9s detection | Node dropped from in-memory registry; presence has Redis TTL |
| SignalR (Redis BP) | Pub/sub fan-out | None (app-layer); LB stickiness | Messages lost; clients reconnect |
| Phoenix / Tracker | BEAM PID-targeted send | `:net_kernel` (instant) + Tracker CRDT heartbeat (~30s) | Dead PID's mail dropped; CRDT converges away |
| Discord | Consistent hash → owner node + targeted RPC | etcd lease (60s TTL) | Ring rebalance; clients resume on new node |
| Slack | Consistent hash → CS, GS subscribes | LB / region health checks (not detailed) | Drain & reroute |
| Ably | P2P between nodes, channel place-of-record | Gossip-protocol heartbeats, eventually consistent netmap | Local SDK queue; multi-region redundancy |

## Synthesis

**Dominant pattern**: when there is **any cross-instance routing** at all (not pure broadcast), the system uses **explicit heartbeats with a short TTL** to maintain a registry of live instances, and treats the registry — not the per-resource state — as the source of truth for "is this owner reachable". The actual routing is then either:

- (a) pure pub/sub fan-out and let each node self-filter (Socket.IO, SignalR), or
- (b) consistent-hash a key → owner-instance and target it directly (Discord, Slack), or
- (c) gossip a netmap and route P2P (Ably, Phoenix).

The "dead owner is filtered out by an `isAlive(instance)` check before send" pattern your code already uses (`SkillInstanceRegistry` heartbeat + `findInstanceWithConnection` + `isInstanceAlive`) is **structurally identical to Discord's etcd approach and Centrifugo's control-channel approach**, just with Redis as the heartbeat store instead of etcd / pub/sub control plane. The bug is not the design — it's that you have two independent sources of truth (the heartbeat key and the registry hash) and only one of them expires when a pod dies.

**Contrarian pattern**: Socket.IO / SignalR sidestep the problem entirely by **never routing to a specific instance**. They publish to a logical channel; every subscriber sees it; whoever holds the connection delivers it; everyone else ignores. There is no "wrong instance" because there is no targeting. Cost: every message goes to every node (or every node subscribed to the room/channel), which is wasteful at high cardinality but trivially correct under node death.

**HEXPIRE (Redis 7.4)**: adopted by Centrifugo (opt-in `UseHashFieldTTL`) for presence. It does sidestep the exact bug you have — if each `instanceId → connectionCount` field in `external-ws:registry:{domain}` had its own TTL refreshed by the *owner* pod only, dead pods' fields would auto-expire. But: (a) requires Redis ≥ 7.4, (b) requires owning pod to do the refresh, (c) does not eliminate the race where a publish lands in the gap between TTL expiry and the field actually being removed, so you still need the "filter dead pods at lookup time" guard. So HEXPIRE is a defense-in-depth layer, not a replacement for the alive-check.

## Applicability to our codebase — candidate designs

All three keep the current "registry + relay" topology; the differences are how staleness is detected and prevented.

### Design A: keep current shape, fix the bug by single-writer invariant

Treat `external-ws:registry:{domain}` as **owner-writable-only**. Only the pod holding the connection writes/refreshes its own field; other pods may read but never `HSET`/`EXPIRE`. Combine with a per-field heartbeat (either `HEXPIRE` or, on Redis <7.4, a sibling key `external-ws:registry-field:{domain}:{instance}` with key-level TTL refreshed every 10s by the owner). At lookup time, additionally filter via existing `SkillInstanceRegistry.isInstanceAlive(id)`. After a successful relay, owner refreshes the field; on graceful shutdown, owner `HDEL`s its own field.

- Pros: minimal change; reuses `SkillInstanceRegistry`; aligns with Centrifugo's presence model.
- Cons: relies on owner refresh cadence — there is still a 10–30s window where a dead pod's field looks alive. Caller must always intersect registry with `isInstanceAlive`.

### Design B: drop the registry, do pure pub/sub fan-out (Socket.IO model)

Replace `external-ws:registry:{domain}` with a single domain-keyed pub/sub channel `ss:external-domain:{domain}`. Every pod subscribes to channels for the domains whose connections it holds. To send outbound, any pod publishes to `ss:external-domain:{domain}` — only the owning pod has a local handler that actually writes to the WebSocket. No instance-targeted relay channel; no registry to go stale.

- Pros: no stale-pod bug class exists, by construction. Simplest mental model.
- Cons: all pods receive every domain message they have subscribed to — fine if a domain lives on exactly one pod (current invariant), wasteful if domains are sharded across multiple pods. Subscribe/unsubscribe lifecycle must be reliable on connect/disconnect.

### Design C: registry as cache, alive-check as authority

Keep the existing registry as a hint, but make `findInstanceWithConnection` *always* intersect with `isInstanceAlive` and treat any entry whose instance is dead as automatically deletable. On detection, `HDEL` the stale field (best-effort, no coordination needed since it's idempotent). Optionally also publish a `ss:registry-cleanup` event so any node can race-free remove dead fields.

- Pros: zero new infra, fixes today's bug without touching write paths. Already half-implemented (`isInstanceAlive` exists).
- Cons: still two sources of truth; cleanup is lazy; if every node forgets to cleanup, the hash grows unbounded for very-long-lived dead pods. Mitigate with a periodic sweeper on one elected node.

**Recommendation context (not your call to make, just for the parent agent)**: Design C is the cheapest correct fix; Design A is the "do it right" upgrade if HEXPIRE is available; Design B is the architectural simplification worth considering if you ever decide the domain↔pod mapping invariant lets you do it.

## Caveats / not found

- Pusher: no public architecture detail — intentionally skipped.
- Slack: blog posts describe topology but not the specific dead-instance-detection timing or the registry data model. Treat the Slack row as "consistent hashing with LB-level health" — the rest is inference.
- Redis 7.4 HEXPIRE deployment availability in your cluster is unverified by this research.
- Discord's etcd-lease scheme is read off the 2026 voice-outage postmortem; primary architecture posts don't state the 60s number explicitly, but the postmortem does.
