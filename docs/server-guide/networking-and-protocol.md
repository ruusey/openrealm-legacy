# Networking & Protocol

[← Guide index](./README.md)

The server speaks a compact binary protocol over two transports. This chapter
covers both transports, the reflection-driven serialization framework, the
compression scheme, the complete packet catalog (IDs verified from source), and
the connection/handshake lifecycle.

## Transports

| Transport | Class | Port | Client |
|-----------|-------|------|--------|
| TCP / Java NIO | `NioServer` | **2222** | Native LibGDX client |
| WebSocket (binary) | `WebSocketGameServer` (java_websocket) | **2223** | Web (PixiJS) client |

Both are started in `RealmManagerServer.doSetup()` (`:313`, `:333`). They
converge on a common abstraction: every connection is a `ClientSession`
(`WebSocketClientSession extends ClientSession`) with a `packetQueue` the tick
thread drains. After login there is **no code-path distinction** between the two
client kinds.

Each connection gets a unique **clientKey** built from remote address plus a
monotonic `AtomicLong` sequence (`<ip>/<seq>` for TCP, `ws:<ip>/<seq>` for
WebSocket). The monotonic counter matters behind a reverse proxy where every web
client shares one source IP.

### NIO specifics

- Selector loop blocks on `select(50ms)`; handles accept + read.
- Per-session receive buffer: 655,360 bytes; read chunk 8192 bytes; `TCP_NODELAY` on.
- A dedicated **write thread** polls sessions (~1 ms), serializes + compresses
  queued packets, and handles partial writes via a `pendingWrite` buffer.
- A **timeout thread** (250 ms) closes pre-login connections idle beyond
  `GlobalConstants.SOCKET_READ_TIMEOUT = 15000 ms` (`NioServer.java:96`).

### WebSocket specifics

- Only **binary** frames are accepted; text frames are ignored.
- `onOpen` creates a `WebSocketClientSession`; framing is handled by the
  WebSocket layer, so there is no manual length-prefix parsing, but the same
  packet payload + compression is used.

## The serialization framework

Packets and wire entities are described **declaratively** and mapped by a
classpath scan at startup — no per-packet hand-written reader/writer for most
types.

### Defining a packet

```java
@PacketId(packetId = (byte) 1)          // wire id, unique across all Packet subclasses
public class PlayerMovePacket extends Packet {
    @SerializableField(order = 0, type = SerializableInt.class)
    private int seq;                     // input sequence number
    @SerializableField(order = 1, type = SerializableFloat.class)
    private float vx;
    @SerializableField(order = 2, type = SerializableFloat.class)
    private float vy;
}
```

- `@PacketId` — the one-byte wire identifier. `PacketType` (static block)
  scans all `Packet` subclasses, reads their `@PacketId`, and builds the
  `id → class` and `class → id` maps. A missing or duplicate id is logged
  CRITICAL; a duplicate is rejected. `BlankPacket` uses id `-1`.
- `@SerializableField(order, type, isCollection)` — one per persisted field.
  `order` fixes wire order; `type` is the `SerializableFieldType` used to
  read/write; `isCollection=true` writes a 4-byte length prefix then N elements.
- The base scalar types live in `net/core/nettypes`: `SerializableByte`,
  `SerializableBoolean`, `SerializableShort`, `SerializableInt`,
  `SerializableLong`, `SerializableFloat`, `SerializableString`.

### IOService

`IOService` builds, per streamable class, an ordered `PacketMappingInformation[]`
(field order + serializer + `VarHandle` for direct field access) and caches it.
Read/write then just walk that array:

- **Write** (`writeStream`): for each field, write single value or
  `[len][elements…]` for collections. `writePacket` wraps the payload in a frame.
- **Read** (`readStream`): mirror — read len for collections, set fields via `VarHandle`.

Hot-path wire entities (`NetBullet`, `NetObjectMovement`, `LoadPacket.from(...)`)
are additionally **hand-mapped** to avoid reflection/ModelMapper overhead during
ability spam.

### Frame format

```
[ packetId : 1 byte ][ totalLength : 4 bytes big-endian ][ payload … ]
```

`totalLength` includes the 5-byte header (`NetConstants.PACKET_HEADER_SIZE = 5`).

### Compression (`PacketCompression`)

- Threshold: payloads at/under ~133 bytes (128 + header) are sent raw.
- Above threshold: zlib **Deflate** at `BEST_SPEED`. If the compressed result is
  not smaller, the raw frame is sent instead.
- The compression flag is the **`0x80` bit set on the packet-id byte**.
- Compressed frame carries the original payload size as a 4-byte prefix so the
  reader can size the inflate buffer.
- Uses `ThreadLocal` `Deflater`/`Inflater`/scratch buffers (no locking).
- Typical savings 40–60% on bulk game state.

## Packet catalog

IDs below are the `@PacketId` values verified from source. "Dir" is C→S
(client to server / input) or S→C (server to client / state). A handful (e.g.
`TextPacket`) travel both ways.

### Client → Server (input)

| ID | Packet | Purpose |
|----|--------|---------|
| 1 | `PlayerMovePacket` | Movement vector `(seq, vx, vy)`; `(0,0)` = stop |
| 4 | `TextPacket` | Chat / system text (bidirectional) |
| 5 | `HeartbeatPacket` | Keep-alive (`timestamp`) |
| 6 | `PlayerShootPacket` | Basic-attack fire (projectile group + src/dest) |
| 7 | `CommandPacket` | Login + chat commands (`playerId, commandId, command`) |
| 11 | `UseAbilityPacket` | Activate hotbar ability (`posX, posY, abilityIndex`) |
| 12 | `MoveItemPacket` | Inventory/loot move (target/from slot, drop, consume) |
| 13 | `UsePortalPacket` | Enter portal (`portalId, fromRealmId, toVault, toNexus`) |
| 20 | `DeathAckPacket` | Client acknowledges death |
| 27 | `ConsumeShardStackPacket` | Forge: 10 shards → 1 crystal |
| 28 | `InteractTilePacket` | Interact with forge/fame/potion tile |
| 30 | `ForgeEnchantPacket` | Apply crystal/gem to item |
| 31 | `ForgeDisenchantPacket` | Strip enchantments/gem |
| 33 | `BuyFameItemPacket` | Fame-store purchase |
| 36 | `PotionStorageMovePacket` | Move item in/out of potion storage |
| 37 | `SplitStackPacket` | Split a stack |
| 39 | `HotbarSwapPacket` | Rebind a hotbar slot |
| 40 | `InvestSkillPointPacket` | Spend a skill point on an ability |

### Server → Client (state)

| ID | Packet | Purpose |
|----|--------|---------|
| 2 | `UpdatePacket` | Heavy per-player update: stats, inventory, XP, potions, skill points |
| 3 | `ObjectMovePacket` | Dead-reckoned positions/velocities for visible entities |
| 8 | `LoadMapPacket` | Tile terrain (delta) for the viewport on join / realm change |
| 9 | `LoadPacket` | Entity snapshot: players, enemies, bullets, containers, portals, difficulty |
| 10 | `UnloadPacket` | Entity-removal id lists (left viewport / despawned) |
| 14 | `TextEffectPacket` | Floating damage/heal numbers, popups |
| 15 | `PlayerDeathPacket` | Death notification |
| 16 | `RequestTradePacket` | Incoming trade request |
| 17 | `AcceptTradeRequestPacket` | Trade accepted; both inventories for display |
| 18 | `UpdatePlayerTradeSelectionPacket` | Trade item-selection change (also C→S) |
| 19 | `UpdateTradePacket` | Bundled trade selections + confirmation state |
| 21 | `CreateEffectPacket` | Spawn a visual effect (heal ring, chain arc, dash trail) |
| 22 | `LoginAckPacket` | Login success ack |
| 23 | `GlobalPlayerPositionPacket` | Minimap / global positions |
| 24 | `PlayerStatePacket` | Light HP/MP/effects (`playerId, health, mana, effectIds[], effectTimes[]`) |
| 25 | `CompactMovePacket` | Bandwidth-optimized movement using 2-byte short ids |
| 26 | `PlayerPosAckPacket` | Server position ack for reconciliation |
| 29 | `OpenForgePacket` | Open the forge UI |
| 32 | `OpenFameStorePacket` | Open the fame store UI (with balance) |
| 34 | `OpenPotionStoragePacket` | Open potion storage UI |
| 35 | `PotionStorageUpdatePacket` | Potion-storage contents |
| 38 | `AbilityCastStartPacket` | Begin cast bar (`playerId, abilityId, slot, durationMs, worldTargetX/Y`) |
| 41 | `PartyUpdatePacket` | Party roster snapshot (≤4 members) |
| 42 | `RealmPurificationPacket` | Realm purification progress bar |

`CommandPacket` (id 7) multiplexes login and command traffic via an inner
`commandId` (`CommandType`): `1 LOGIN_REQUEST`, `2 LOGIN_RESPONSE`,
`3 SERVER_COMMAND`, `4 SERVER_ERROR`, `5 PLAYER_ACCOUNT`. Login and command
payloads are JSON messages (`net/messaging/*`) carried inside the command string.

### Priority packets

Four input packets are processed **first** every tick, ahead of the 200-packet
budget for everything else, so bulk inventory/trade traffic can't starve
movement/combat (`RealmManagerServer.java:270`):
`PlayerShootPacket`, `PlayerMovePacket`, `HeartbeatPacket`, `CommandPacket`.

## Connection & handshake lifecycle

```
1. Socket accept
   TCP: NioServer.handleAccept → ClientSession (non-blocking, TCP_NODELAY)
   WS : WebSocketGameServer.onOpen → WebSocketClientSession
   → unique clientKey; connection timestamp recorded for the timeout sweep.

2. Login request (C→S)
   Client sends CommandPacket(commandId = 1) carrying a LoginRequestMessage
   { email, password, characterUuid, token }.

3. Authentication (async, off tick thread)
   ServerGameLogic.doLogin():
     • token present  → GET  /admin/account/token/resolve  (Authorization header)
       else           → POST /admin/account/login          → SessionTokenDto
     • GET /data/account/{accountGuid}                      → PlayerAccountDto
     • locate character by characterUuid; load equipment
     • force-disconnect any existing session for that account (ghost prevention)
     • build Player (random long id, spawn pos, class) and enqueue PendingRealmJoin

4. Join (tick thread)
   processPendingJoins() adds the Player to its realm, marks the session
   handshakeComplete, maps clientKey → playerId, and sends LoginResponseMessage.

5. First world (S→C), next tick
   • LoadMapPacket (8)  — viewport tiles
   • LoadPacket   (9)  — visible entity snapshot
   • UpdatePacket (2)  — own inventory/stats
   • PlayerStatePacket (24) — HP/MP/effects
   Thereafter the client receives Update/Load/Move/Unload deltas each tick group.
```

Failure: if the data service is unreachable or credentials are bad, login throws,
and a `LoginResponseMessage` with `success=false` (or a SERVER_ERROR) is returned;
the pre-login connection is reaped by the timeout thread if the client hangs.

## Heartbeat & disconnect

- The client periodically sends `HeartbeatPacket` (id 5). Any inbound packet
  refreshes a session's activity timestamp.
- Pre-login sockets idle > 15 s are closed by the timeout sweep.
- On disconnect (`shutdownProcessing`): the tick loop resolves the `playerId`,
  persists the player asynchronously (character + vault/potion storage + metrics
  flush), removes them from the realm, cleans up party/trade/PvP state, and closes
  the session. See [Game Loop & Realms](./game-loop-and-realms.md#persistence) and
  [Data-Service Integration](./data-service-integration.md).

## Bandwidth optimizations at a glance

- **Delta packets:** `LoadPacket`/`LoadMapPacket`/`UnloadPacket` diff against a
  per-player ledger and send only changes; `LoadMapPacket` packs each tile into a
  64-bit key for O(N) set-diffing instead of O(N²) compare.
- **CompactMovePacket:** 2-byte short entity ids in place of 8-byte longs for the
  high-frequency movement stream.
- **Split light/heavy state:** `PlayerStatePacket` (HP/MP/effects, frequent) is
  separate from `UpdatePacket` (inventory/stats, infrequent).
- **Dead reckoning:** movement is only re-sent on meaningful divergence (see next chapter).
</content>
