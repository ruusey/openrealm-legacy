# Game Loop & Realms

[← Guide index](./README.md)

The heart of the server: a fixed 64 Hz tick that advances every realm, decides
what each client can see, and emits delta snapshots. This chapter covers the tick
structure and sub-tick scheduling, realm lifecycle, entity storage, the spatial
grid, visibility/area-of-interest, load/unload, dead reckoning, realm
transitions, and persistence cadence.

## The tick

`RealmManagerServer` runs its `tick()` on a `TimedWorkerThread(this::tick, 64)`
(`:405`) → **64 Hz, 15.625 ms/tick**. A tick exceeding `TICK_BUDGET_NANOS`
(16 ms, `:277`) is logged as slow but never kills the loop (exceptions are caught
per-tick).

Per-tick order (`:414-438`):

1. `processPendingJoins()` — add newly-authenticated players to their realms.
2. `processPendingTransitions()` — attach realms whose async generation finished.
3. `processServerPackets()` — drain each session's queue; priority packets first, then up to `MAX_PACKETS_PER_TICK = 200`.
4. `update(0)` — advance all realms (players → enemies → bullets → global).
5. `enqueueGameData()` — per player, build Load/Update/Move/Unload deltas.
6. `sendGameData()` — hand packets to sessions for the write thread.

Steps 1–2 run before input so no packet touches a half-joined player; step 4 runs
before 5 so newly spawned bullets are already in the spatial grid when snapshots
are built.

## Sub-tick scheduling (divisors)

Not every subsystem runs at 64 Hz. Power-of-two **divisors** gate work using the
branch-free idiom `(tickCounter & (DIVISOR - 1)) == 0` (`:231-238`):

| Divisor const | Value | Effective rate | Gates |
|---------------|-------|----------------|-------|
| `MOVE_TICK_DIVISOR` | 2 | 32 Hz | Movement broadcast |
| `MOVE_FULL_TICK_DIVISOR` | 4 | 16 Hz | Wider-radius full movement send |
| `LOAD_TICK_DIVISOR` | 2 | 32 Hz | Load/unload reconcile |
| `UPDATE_TICK_DIVISOR` | 8 | 8 Hz | Heavy `UpdatePacket` (inventory/stats) |
| `LOADMAP_TICK_DIVISOR` | 12 | ~5.3 Hz | Tile delta send |
| `ENEMY_UPDATE_TICK_DIVISOR` | 4 | 16 Hz | Enemy state update |
| `ENEMY_AI_TICK_DIVISOR` | 2 | 32 Hz | Enemy AI decisions / attack-cooldown alignment |
| `ENEMY_MOVE_FAR_DIVISOR` | 4 | 16 Hz | Far (non-visible) enemy movement |

Light state (`PlayerStatePacket`) can go out every tick on change; the expensive
things (full inventory, tiles) are throttled hard.

## Realm lifecycle

A **Realm** is one instance of a map: its own tile grid, entity maps, spatial
grid, and short-id allocator. Realms live in a `ConcurrentHashMap<Long, Realm>`
keyed by a random long realm id.

Kinds of realm (distinguished by map id / dungeon-graph node):

- **Overworld / Nexus** — shared, persistent, entry-flagged nodes. The entry
  realm is created at startup, seeded with enemies and set pieces.
- **Dungeons** — created **lazily and asynchronously** when a player uses a
  portal. Party size (and thus difficulty bonus) is locked at entry. Single-party
  dungeons are per-group; shared nodes are reused.
- **Vault** — the personal storage realm (hardcoded map id 30).

**Creation:** dungeon generation runs on the worker pool
(`ServerGameLogic` portal flow → `WorkerThread.doAsync`), producing terrain +
enemies + boss off the tick thread. When done it is enqueued as a
`PendingRealmTransition` and attached on the next tick.

**Teardown/GC:** empty non-persistent realms are swept periodically
(`tickEmptyRealmCleanup`, every ~128 ticks ≈ 2 s); vault realms are removed
immediately on exit. Shared realms stay resident even when empty, but their
enemies are **parked** (velocity work skipped) via `parkedEmptyRealms` so the
server doesn't walk every enemy 64×/s in an empty shared world.

## Realm update sequence

`update()` iterates realms with players and, per realm:

1. **Players** — bullet-hit resolution, `player.update()`, expire effects,
   collision-tested movement, cast resolution.
2. **Enemies** — classify by proximity (DORMANT / AWAKE / VISIBLE), run AI on the
   AI cadence, move (every tick if visible, else far divisor), tick enemy scripts.
   Awake candidates are gathered within `MAX_AWAKE_RADIUS = 720 px`.
3. **Bullets** — homing steer, anchored re-position, integrate motion.
4. **Global** (`tickGlobal`) — cross-cutting: remove expired bullets/loot/portals;
   tick DoTs, traps, decoys, clones; passive auras; realm events; PvP; parties;
   minimap broadcast (~every 64 ticks); periodic overworld respawn (~every 384
   ticks ≈ 6 s).

See [Enemy AI & Scripting](./enemy-ai-and-scripting.md) and
[Projectiles & Combat](./projectiles-and-combat.md) for the inner loops.

## Entity storage & ids

Each realm holds `ConcurrentHashMap`s for players, enemies, bullets, loot
containers, and portals. Two id spaces:

- **Long ids** — every entity's real id, `Realm.RANDOM.nextLong()` (often
  negative — never gate logic on `id >= 0`).
- **Short ids** — a per-realm `ShortIdAllocator` hands out 16-bit ids (0
  reserved) used by `CompactMovePacket` to shrink the movement stream. Released on
  despawn; capacity 65,535 concurrent entities per realm.

Expiring entities are queued into `expired*` lists and removed at a safe point.

## Spatial grid & visibility

**`SpatialHashGrid`** buckets entities into cells of `10 × BASE_TILE_SIZE = 320 px`.
Cell key = `((long)cx << 32) | (cy & 0xFFFFFFFF)`. The grid is rebuilt each tick
and answers radius queries in ~O(1) per neighborhood.

**Area-of-interest** (`getVisibleIdsCircularFast`) computes, per viewer, the set
of entities to send, using per-type radii (squared for speed):

| Target | Radius |
|--------|--------|
| Enemies | viewport = 10 tiles (320 px) |
| Bullets (load) | viewport + 2 tiles |
| Players | viewport + 5 tiles |
| Loot / portals | viewport |

**Wall occlusion:** `VisibilityHelper.hasLineOfSight()` runs a grid DDA
(Amanatides–Woo) trace, max ~4096 steps. Only **wall** tiles occlude (not
decoration/collision). It hides enemies behind walls in PvE (except static
spawns) and rival players in PvP. Bullets are never occluded. Occluded enemies
also don't shoot.

## Load / unload deltas

`PlayerLoadLedger` records, per player, exactly which entity ids the client
currently has (players/enemies/bullets/containers/portals). Each load tick:

- **load** = desired − ledger, **unload** = ledger − desired.

Caps prevent snapshot storms: `MAX_NEW_ENEMIES_PER_LOAD = 500`,
`MAX_NEW_BULLETS_PER_LOAD = 1000` (cap-trimmed entities are picked up on later
ticks; the ledger keeps them from being falsely "unloaded").

**Bullet hysteresis:** bullets *load* within the narrow viewport radius but stay
loaded until they pass `BULLET_UNLOAD_RADIUS = 20 tiles`. The gap prevents the
"invisible shot" — a fast bullet skimming the load edge would otherwise unload and
then reach lethal range before the next load tick + RTT re-sent it (`:246-254`).

`LoadMapPacket` diffs tiles by packing `(tileId, layer, x, y)` into a 64-bit key
and set-diffing — O(N) instead of O(N²) — which is what let the server hold TPS
with ~40 players.

## Movement & dead reckoning

Movement broadcasts run at 32 Hz (`doMovement`) with a wider full send at 16 Hz.
`EntityMotionState` tracks the last sent `(pos, vel, tick)` per entity and only
emits a correction when:

- velocity changed beyond a squared threshold (direction/speed change), **or**
- position drifted past a squared error (~4 px²) from the client's predicted
  position, **or**
- the entity is stale (~48 ticks ≈ 750 ms since last send, unless stationary).

This is the main bandwidth lever for the movement stream: idle/constant-velocity
entities cost almost nothing.

## Realm transitions

Portal use (`UsePortalPacket` → `ServerGameLogic`):

1. Determine destination: vault / nexus / existing shared node / new dungeon.
2. For a new dungeon, generate **async** on the worker pool (terrain, enemies,
   boss), locking party size for difficulty.
3. Enqueue a `PendingRealmTransition`.
4. On the next tick, `processPendingTransitions()` atomically adds the realm,
   moves the player, and sends a fresh `LoadMapPacket` — running before
   `enqueueGameData()` so the delta logic sees a consistent world.

`PendingRealmJoin` (login) and `PendingRealmTransition` (portal) are the only ways
a player enters a realm, and both are applied at the top of the tick.

## Persistence

Two cadences write durable state to openrealm-data (all HTTP, off the tick thread):

- **Periodic (~12 s):** `beginPlayerSync()` loops every 12,000 ms and, per online
  player, refreshes the account, writes the character (`CharacterDto`: stats +
  items), and flushes the metrics delta.
- **On event:** disconnect, death, realm/vault exit, and server shutdown trigger
  an immediate async persist.

**`VaultSaveBarrier`** prevents item duplication when a player exits and
immediately re-enters the vault: it tracks in-flight save futures per
`accountUuid` and blocks a re-entry read until the prior exit-save settles (max
5 s). Full detail in [Data-Service Integration](./data-service-integration.md#persistence).

## Constants cheat-sheet

| Constant | Value | Meaning |
|----------|-------|---------|
| Tick rate | 64 Hz / 15.625 ms | Main loop |
| `TICK_BUDGET_NANOS` | 16 ms | Slow-tick warning |
| Spatial cell | 320 px (10 tiles) | Grid bucket |
| `VIEWPORT_RADIUS_SQ` | (10·32)² | Enemy visibility |
| `MAX_AWAKE_RADIUS` | 720 px | Enemy AI activation |
| `BULLET_UNLOAD_RADIUS` | 20 tiles | Bullet load hysteresis |
| `MAX_NEW_ENEMIES_PER_LOAD` | 500 | Per-tick load cap |
| `MAX_NEW_BULLETS_PER_LOAD` | 1000 | Per-tick load cap |
| `MAX_ENEMY_BULLETS_PER_REALM` | 10000 | Bullet ceiling |
| `MAX_PACKETS_PER_TICK` | 200 | Non-priority input budget |
| Vault map id | 30 | Personal storage realm |
| Empty-realm sweep | ~128 ticks | GC cadence |
| Overworld respawn | ~384 ticks | Respawn cadence |
| Persist cadence | ~12 s | Character save |
</content>
