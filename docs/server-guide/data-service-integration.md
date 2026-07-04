# Data-Service Integration

[← Guide index](./README.md)

The game server owns **no database**. Every durable read/write and all game
content go over HTTP to **openrealm-data**. This chapter documents the HTTP
client, every endpoint the server calls, the DTOs, the auth flow, game-data
loading, persistence cadence, metrics, and failure handling.

## The HTTP client

`OpenRealmServerDataService` (exposed as `ServerGameLogic.DATA_SERVICE`) wraps
Java 11 `java.net.http.HttpClient` + Jackson `ObjectMapper`. All requests set
`Content-Type: application/json`. Sync (`executeGet/Post/Put/Delete`) and async
(`executeGetAsync/PostAsync`) variants exist; hot paths use async so the tick
thread never blocks. Non-200 responses throw `IOException`. Calls are timed and
logged `[DATA-CALL]`, WARN at ≥250 ms. **No custom timeout** is configured (Java
default) — a hung data service can stall the calling worker.

**Base URL:** the first CLI arg (default `127.0.0.1`) → `http://<addr>/`.
There is currently **no mutual-TLS or API-key** between game server and data
service; the network is assumed trusted.

## Endpoints called

### Account / auth / persistence

| Method | Path | Purpose | When |
|--------|------|---------|------|
| GET | `/ping` | Health check | Startup (fatal if down) |
| POST | `/admin/account/login` | Email/password auth → `SessionTokenDto` | Login |
| GET | `/admin/account/token/resolve` | Token → `AccountDto` (Authorization header) | Token login |
| GET | `/data/account/{accountUuid}` | Full account + characters → `PlayerAccountDto` | Login, each persist |
| POST | `/data/account/character/{charUuid}` | Save character (`CharacterDto`) | ~12 s, disconnect |
| POST | `/data/account/character/{charUuid}/metrics/delta` | Flush metrics (`MetricsDeltaDto`) | ~12 s, disconnect |
| POST | `/data/account/{accountUuid}/chest` | Save vault chests (`List<ChestDto>`) | Disconnect, vault exit |
| POST | `/data/account/{accountUuid}/potion-storage` | Save potion storage | Disconnect, exit |
| DELETE | `/data/account/character/{charUuid}?bankFame=true&fameAmount=N` | Permadeath: soft-delete + bank fame | Death |
| POST | `/data/account/{accountUuid}/fame/spend?amount=N` | Spend fame → new balance | Fame-store buy |
| POST | `/data/account/{accountUuid}/fame/grant?amount=N` | Grant fame | Admin command |
| GET | `/admin/account/{accountUuid}` | Provisions | Restricted-command check |
| PUT | `/admin/account` | Update provisions | Admin op |
| POST | `/admin/account/register` | Create account | `/spambot` |
| POST | `/data/account/{accountUuid}/character?classId=N` | Create character | Setup/admin |

### Game content

All fetched once at startup into static `GameDataManager` maps from
`/game-data/<name>.json`:

`loot-groups`, `loot-tables`, `character-classes`, `exp-levels`, `portals`,
`dungeon-graph`, `terrains`, `enemy-hordes`, `maps`, `tiles`, `enemies`,
`projectile-groups`, `animations`, `setpieces`, `realm-events`, `abilities`,
`passives`, `game-items`, `weapon-archetypes`.

## Auth flow

```
Client → LoginRequestMessage { email, password, characterUuid, token }
  token present → GET /admin/account/token/resolve (Authorization: token) → AccountDto
  else          → POST /admin/account/login { email, password }          → SessionTokenDto
Server → GET /data/account/{accountGuid} → PlayerAccountDto (characters, vault, potion storage)
       → validate characterUuid exists → build Player → session established
```

**Provisions** (`AccountProvision`): `OPENREALM_SYS_ADMIN` ⊃ `OPENREALM_ADMIN` ⊃
`OPENREALM_MODERATOR`/`OPENREALM_EDITOR`; plus `OPENREALM_PLAYER`, `OPENREALM_DEMO`
(guest: 1 char, 1 chest, no trading). Cached per session; re-fetched from
`/admin/account/{uuid}` when a restricted command runs.

Client→server auth after login is by the `playerId` bound to the connection's
remote address; there is no per-packet token.

## Game-data loading

`GameDataManager.loadGameData(remote)`:

- **remote=true** (production): each resource via `GET /game-data/<name>.json`.
- **remote=false** (offline/testing): from `openrealm.dataDir` /
  `OPENREALM_DATA_DIR` filesystem dir, else classpath `data/<name>.json`.

Load order matters (dependencies): loot groups → loot tables → classes → exp →
portals → dungeon graph → terrains → hordes → maps → tiles → enemies →
projectile-groups → animations → setpieces → realm-events → abilities → passives →
game-items → weapon-archetypes. Afterward, items inheriting the default STR
scaling adopt their archetype's `scalingStat`.

**`{{PI}}` angle parsing:** `RadianAngleDeserializer` + `evalPiExpression()`
resolve `{{ n*PI/m }}` strings in projectile/animation angle fields at load time.
The native client needs the equivalent deserializer or its enemies/projectile
parse fails outright (zero enemies render).

> Because clients fetch this same JSON from openrealm-data at runtime, **data-only
> changes need no native EXE release** — push the data and redeploy the service.

## Persistence

`CharacterDto` (what a save writes): `characterUuid`, `characterClass`, `stats`
(`CharacterStatsDto`), `items` (`Set<GameItemRefDto>`), temporal fields.

- `CharacterStatsDto`: `xp`, `classId`, the 8 stats, `hpPotions`/`mpPotions`,
  `dyeId`, `availableSkillPoints`, `abilitySkillPoints` (abilityId → invested level).
- `GameItemRefDto`: `itemId`, `slotIdx`, `itemUuid`, `stackCount`, `enchantments`,
  `rarity`, `attributeModifiers`, `gemstoneType` + gem paint fields.
- `ChestDto`: `chestUuid`, `ordinal`, `items` — vault chests and potion storage.

**Save flow (per ~12 s tick, per player):** GET the account (fresh character
list) → locate character by UUID → skip if soft-deleted → set serialized
stats+items → POST `CharacterDto` → POST metrics delta. Disconnect/exit also POST
vault + potion storage.

**`VaultSaveBarrier`** prevents dupes on fast exit→re-enter: it merges in-flight
exit-save futures per `accountUuid` and blocks a re-entry read until they settle
(max 5 s). `PlayerLoadLedger` is unrelated to persistence — it's the visibility
bookkeeping from [Game Loop & Realms](./game-loop-and-realms.md#load--unload-deltas).

Concurrency model: last-write-wins with a fresh read before each save; assumes a
single game-server instance (no distributed locks).

## Metrics

Per `Player`, a lazily-allocated `PlayerMetrics` accumulates **deltas** since the
last flush using `LongAdder`s (lock-free, safe from the tick + bullet threads):
combat (projectiles/damage/kills incl. `killsByEnemyId`), items (potions,
pickups), progression (XP, skill points), abilities (casts by id), social
(trades, chat). See `../player-metrics-design.md` for the full taxonomy.

Flush (`drainAndReset()` → `MetricsDelta` → `MetricsDeltaDto` with stringified map
keys → POST `/metrics/delta`) happens on the ~12 s tick, disconnect, death, and
shutdown. **Empty deltas skip the HTTP call.** On failure the delta is merged back
into `PlayerMetrics` and retried next window — no double count, at most one
~12 s window lost on a crash.

## Failure handling

| Failure | Behavior |
|---------|----------|
| Data service unreachable at startup | `/ping` fails → log FATAL, `System.exit(-1)` |
| Login-time data call fails | Login errors back to client; pre-login socket reaped on timeout |
| Character save fails | Logged; player keeps playing; retried next window (stale on next login only if all fail) |
| Metrics flush fails | Delta re-merged, retried next window |
| Vault/storage save fails | Tracked by `VaultSaveBarrier`; re-entry waits ≤5 s then proceeds (stale-data risk) |

No exponential backoff or circuit breaker exists; retries ride the natural ~12 s
cadence.

## Environment / config

| Key | Purpose | Default |
|-----|---------|---------|
| CLI arg 1 | Data-service address | `127.0.0.1` |
| `OPENREALM_ADMIN_PORT` | Admin HTTP port | 8088 |
| `openrealm.dataDir` / `OPENREALM_DATA_DIR` | Local game-data dir (offline mode) | classpath |
| `OPENREALM_RELOAD_TOKEN` | Authorizes `POST /admin/reloadGameData` | – |
</content>
