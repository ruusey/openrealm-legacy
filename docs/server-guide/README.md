# OpenRealm Server Guide

A comprehensive, implementation-level guide to how the OpenRealm **game server**
works end to end: the process model, the network protocol, the game loop, enemy
AI and scripting, projectiles and combat, procedural world generation, the
item/ability/class systems, and how the server talks to its two dependencies —
the **openrealm-data** HTTP service and the **game clients** (native + web).

This guide documents the server as it exists in `src/main/java/com/openrealm`.
Constants, formulas, packet IDs, and tick rates are taken from source; where a
file and line are cited they are the authoritative reference.

## The three-repo ecosystem

OpenRealm is split across three repositories with **no shared library** — the
protocol classes are duplicated by hand in each:

| Repo | Role |
|------|------|
| **openrealm** (this repo) | Authoritative Java game server. Runs the 64 Hz simulation, owns all game state, speaks the binary packet protocol. |
| **openrealm-data** | Spring/HTTP service. Persists accounts/characters/vault to MongoDB (`jrealm` in prod), serves all game-content JSON (`enemies.json`, `abilities.json`, …), and hosts the web client. |
| **openrealm-native-client** / openrealm-client-legacy | LibGDX desktop client. Fetches game-data JSON from openrealm-data at runtime and speaks the packet protocol to the game server. |

The game server holds **no database connection of its own** — every durable read
and write goes over HTTP to openrealm-data (see
[Data-Service Integration](./data-service-integration.md)).

## Chapters

1. [Server Architecture Overview](./server-architecture-overview.md) — process model, bootstrap, threads, top-level data flow.
2. [Networking & Protocol](./networking-and-protocol.md) — transports, serialization framework, compression, the full packet catalog, connection/handshake lifecycle.
3. [Game Loop & Realms](./game-loop-and-realms.md) — the 64 Hz tick, sub-tick divisors, realm lifecycle, entity storage, spatial grid, visibility, load/unload, dead reckoning, persistence cadence.
4. [Enemy AI & Scripting](./enemy-ai-and-scripting.md) — enemy data model, phases, movement/attack patterns, the AI tick, the EnemyScript SDK, grenade archetype, hordes, difficulty scaling.
5. [Projectiles & Combat](./projectiles-and-combat.md) — projectile groups, **position modes + formulas**, flags, bullet lifecycle, the damage formula, ability casting, scaling curves, status effects.
6. [Terrain & Dungeon Generation](./terrain-and-dungeon-generation.md) — tiles, overworld zones, the procedural dungeon algorithm, set pieces, decorators, portals, map model.
7. [Items, Abilities & Classes](./items-abilities-classes.md) — item model, rarity, gems, enchantments, abilities/passives, character classes, loot rolls, forge/fame store, the item-scripting SDK.
8. [Data-Service Integration](./data-service-integration.md) — every HTTP endpoint the server calls, DTOs, auth, game-data loading, persistence, metrics, failure handling.
9. [PvP, Parties, Trading & Events](./pvp-parties-trading-events.md) — PvP matches, parties, the trade state machine, realm events + purification, the chat-command system, transient effect state.
10. [Client Integration](./client-integration.md) — the client contract: prediction, interpolation, deterministic projectile re-sim, remote data loading, rendering.

## Related design docs

The following pre-existing docs in `../` cover feature *design* (rationale and
tuning) and complement this runtime guide:

- `../combat-rework.md` — ability/hotbar combat design.
- `../class-kits.md` — per-class ability kits.
- `../enchantment-rarity-system.md` — rarity/enchant design.
- `../player-guide-enchanting.md` — player-facing enchanting.
- `../player-metrics-design.md` — lifetime metrics design.
- `../projectile-simulation-and-authority.md` — projectile determinism/authority design.

## Quick reference: the numbers that matter

| Constant | Value | Source |
|----------|-------|--------|
| Server tick rate | **64 Hz** (15.625 ms) | `RealmManagerServer.java:405` |
| Slow-tick budget | 16 ms | `RealmManagerServer.java:277` |
| Native TCP port | **2222** | `RealmManagerServer.java:313` |
| Web WebSocket port | **2223** | `RealmManagerServer.java:333` |
| Admin HTTP port | 8088 (`OPENREALM_ADMIN_PORT`) | `ServerLauncher.java` |
| Base tile size | 32 px | `GlobalConstants.java:32` |
| Pre-login socket timeout | 15000 ms | `GlobalConstants.java:71` |
| Max packets/tick/session | 200 | `RealmManagerServer.java:269` |
| Character persist cadence | ~12 s | `RealmManagerServer.beginPlayerSync()` |
| Data-service default URL | `http://127.0.0.1/` | `ServerLauncher.java` |
</content>
</invoke>
