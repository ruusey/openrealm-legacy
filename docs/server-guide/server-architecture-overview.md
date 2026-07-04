# Server Architecture Overview

[← Guide index](./README.md)

This chapter is the map. It covers what starts the server, the process/thread
model, and the top-level flow of a request from a client keypress to a rendered
world update. Every subsystem named here has its own chapter.

## Package layout

```
com.openrealm
├── account            Account/character DTOs + the HTTP client to openrealm-data
│   ├── dto            AccountDto, CharacterDto, CharacterStatsDto, ChestDto, ...
│   └── service        OpenRealmServerDataService (the data-service HTTP client)
├── game
│   ├── contants       Enums + GlobalConstants (PacketType, StatusEffectType, ...)
│   ├── data           GameDataManager / GameDataLookup (loads game-content JSON)
│   ├── entity         Player, Enemy, Bullet, Entity, GameObject, Portal, item/*
│   ├── math           Vector2f, Rectangle
│   ├── metrics        PlayerMetrics + MetricsDelta (lifetime stat capture)
│   ├── model          Data POJOs: EnemyModel, ProjectileGroup, MapModel, ability/*
│   ├── script         Enemy + item scripting SDK
│   └── tile           TileMap, TileManager, DungeonGenerator, decorators/*
├── net
│   ├── core           Serialization framework (IOService, @PacketId, @SerializableField)
│   ├── client/packet  Server→Client packets
│   ├── server         The server: NioServer, RealmManagerServer, ServerGameLogic, helpers
│   ├── server/packet  Client→Server packets
│   ├── entity         Wire DTOs: NetPlayer, NetEnemy, NetBullet, NetTile, ...
│   ├── realm          Realm, SpatialHashGrid, ledgers, transient state
│   ├── party          PartyManager
│   └── messaging      Login/command JSON messages
└── util               Threads (TimedWorkerThread, WorkerThread), Graph, Partition
```

## Bootstrap

`ServerLauncher.main()` is the entry point. In order:

1. Parse the first non-flag CLI arg as the **openrealm-data address** (default
   `127.0.0.1`), build the base URL `http://<addr>/`, and construct the singleton
   `OpenRealmServerDataService` exposed as `ServerGameLogic.DATA_SERVICE`.
2. **Ping** the data service (`GET /ping`). If unreachable → log FATAL and
   `System.exit(-1)`. The server cannot run without its data backend.
3. `GameDataManager.loadGameData(true)` — pull **all** game-content JSON from the
   data service into static in-memory maps (enemies, items, abilities, tiles,
   maps, terrains, loot tables, …). See
   [Data-Service Integration](./data-service-integration.md#game-data-loading).
4. Start the **admin HTTP server** on `OPENREALM_ADMIN_PORT` (default 8088).
5. Construct `RealmManagerServer` and call `doRunServer()` (blocking).

`RealmManagerServer.doSetup()` then wires everything up:

- `new NioServer(2222)` — the native TCP transport.
- `startWebSocketServer()` → `new WebSocketGameServer(2223, …)` — the web transport.
- **Reflection registration** (Reflections classpath scan):
  - `registerRealmDecorators()` — map-id → `RealmDecorator`.
  - `registerEnemyScripts()` — concrete `EnemyScriptBase` subclasses (abstract bases skipped).
  - `registerItemScripts()` — `UseableItemScript` implementations.
  - `registerPacketCallbacksReflection()` — `@PacketHandlerServer` methods.
  - `registerCommandHandlersReflection()` — `@CommandHandler` methods.
- `beginPlayerSync()` — start the ~12 s character-persistence background thread.
- Create + register the **entry realm**, spawn its enemies, stamp set pieces.
- Install a shutdown hook that flushes all players.

## Process & thread model

The server is a single JVM. The important threads:

| Thread | Owner | Rate | Job |
|--------|-------|------|-----|
| **Game tick** | `TimedWorkerThread(this::tick, 64)` | 64 Hz | The authoritative simulation. Single-threaded — all realm mutation happens here. |
| **NIO selector** | `NioServer` | `select(50ms)` | Accept TCP connections, read bytes into per-session buffers. |
| **NIO write** | `NioServer` | ~1 ms poll | Serialize + compress queued packets, write to sockets. |
| **Timeout sweep** | `NioServer` | 250 ms | Kill pre-login connections idle > 15 s. |
| **WebSocket** | `WebSocketGameServer` (java_websocket) | event-driven | Web-client transport; feeds the same session pipeline. |
| **Player sync** | `beginPlayerSync()` | ~12 s | Persist every online character + flush metrics to openrealm-data. |
| **Worker pool** | `WorkerThread` | on-demand | Async dungeon generation, async persistence, async script attacks, HTTP calls off the tick thread. |
| **Admin HTTP** | `AdminHttpServer` | event-driven | Ops endpoints (e.g. `/admin/reloadGameData`). |

**Key invariant:** the tick thread is the only writer of realm state. Cross-thread
inputs arrive via `ConcurrentLinkedQueue`s (packet queues, `PendingRealmJoin`,
`PendingRealmTransition`) that are drained at the top of each tick. Realm entity
maps are `ConcurrentHashMap` so the NIO/write threads can read them safely to
build snapshots.

## The end-to-end data flow

A single frame of the world, from input to render:

```
             ┌──────────────┐   PlayerMovePacket / PlayerShootPacket / ...
   Client ───┤ TCP 2222 or  ├────────────────────────────────────────────►
             │ WS 2223      │                                    (per client)
             └──────────────┘
                                        NIO read thread
                                            │ parse frames → decompress
                                            ▼
                              ClientSession.packetQueue (per session)
                                            │
   ┌───────────────────────── GAME TICK (64 Hz) ──────────────────────────┐
   │ 1. processPendingJoins()        add logged-in players to realms       │
   │ 2. processPendingTransitions()  finish async realm/dungeon gen        │
   │ 3. processServerPackets()       drain queues; priority packets first  │
   │ 4. update(0)                    move players, run enemy AI, move       │
   │                                 bullets, collisions, damage, spawns    │
   │ 5. enqueueGameData()            per player: build Load/Update/Move/    │
   │                                 Unload deltas from visibility          │
   │ 6. sendGameData()               hand packets to sessions               │
   └───────────────────────────────────────────────────────────────────────┘
                                            │
                                        NIO write thread
                                            │ serialize → compress → socket
                                            ▼
             ┌──────────────┐   UpdatePacket / LoadPacket / ObjectMovePacket / ...
   Client ◄──┤ TCP 2222 or  ├────────────────────────────────────────────
             │ WS 2223      │
             └──────────────┘
```

Ordering in the tick is deliberate: joins/transitions are applied **before**
input processing (no races on a half-joined player), and `update()` runs
**before** `enqueueGameData()` so freshly spawned enemy bullets are in the
spatial grid before the visibility snapshot is built
(`RealmManagerServer.java:414-438`).

## Authority model

The server is **fully authoritative**. Clients send *intent* (a movement vector,
a shoot request, an ability index); the server validates and simulates. Position,
collision, damage, and loot are decided server-side. Clients predict locally for
responsiveness and reconcile against server snapshots — projectiles are
**deterministically re-simulated** on the client from spawn parameters rather
than streamed per-tick (except homing). See [Client Integration](./client-integration.md).

## Where to go next

- The wire format and connection lifecycle → [Networking & Protocol](./networking-and-protocol.md)
- The tick internals and realm management → [Game Loop & Realms](./game-loop-and-realms.md)
- How durable state is read/written → [Data-Service Integration](./data-service-integration.md)
</content>
