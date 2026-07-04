# Client Integration

[← Guide index](./README.md)

Two clients connect to the server: the **native LibGDX** desktop client
(`openrealm-native-client` / openrealm-client-legacy) over TCP, and the **web**
(PixiJS) client bundled in openrealm-data over WebSocket. Their source lives in
other repos (the `openrealm-native-client` folder here is a nested repo whose
working tree is currently empty), so this chapter documents the **contract the
server defines and assumes** — which is authoritative from the server side — plus
the client-side prediction model the protocol is built around.

## The contract at a glance

| Concern | Native | Web |
|---------|--------|-----|
| Transport | TCP `2222` | WebSocket `2223` (binary) |
| Framing | `[id:1][len:4][payload]`, `0x80` id-bit = deflate | same payload, WS frames |
| Serialization | Mirror of `net/core` (`@PacketId`/`@SerializableField` order) | mirror in JS |
| Game data | Fetched from openrealm-data over HTTP at runtime | same JSON, in the data jar |
| Authority | Server-authoritative; client sends intent | same |

Because the protocol classes are **duplicated by hand** across repos (no shared
lib), any wire change — a new `@SerializableField`, a reordered field, a new
packet id — must be replicated in the native client and the web client or
deserialization breaks. Field **order** is the wire order.

> The web client reads longs as JavaScript `BigInt`; numeric long packet fields
> must be converted to `Number` (or JS throws "Cannot mix BigInt"). Per-import
> `?v=` cache-busting must cascade through importers when a wire type changes.

## Connection lifecycle (client view)

1. Connect TCP 2222 / WS 2223.
2. Send `CommandPacket(commandId=1)` with `LoginRequestMessage`
   `{ email, password, characterUuid, token }`.
3. Receive `LoginResponseMessage` (playerId, classId, spawn, token, account) on
   success, then the first-world burst: `LoadMapPacket` (tiles), `LoadPacket`
   (entities), `UpdatePacket` (own inventory/stats), `PlayerStatePacket`.
4. Steady state: send input packets (`PlayerMovePacket`, `PlayerShootPacket`,
   `UseAbilityPacket`, `MoveItemPacket`, `UsePortalPacket`, commands, …); receive
   `ObjectMovePacket`/`CompactMovePacket`, `Load`/`Unload`, `UpdatePacket`,
   `PlayerStatePacket`, effect/trade/party/purification packets.
5. Send `HeartbeatPacket` periodically to stay ahead of the 15 s pre-login
   timeout and keep the connection live.

Client game states (login → character select → in-game) map onto this: auth in
login/select, the world burst on entering in-game.

## Movement: prediction & reconciliation

- The client **predicts locally**: on WASD it applies the movement vector
  immediately and sends `PlayerMovePacket(seq, vx, vy)` with a monotonically
  increasing `seq`.
- The server validates (speed cap, collision) and is authoritative. It broadcasts
  dead-reckoned positions in `ObjectMovePacket`/`CompactMovePacket` and can ack
  with `PlayerPosAckPacket` for `seq` reconciliation.
- When the server position diverges from the prediction, the client snaps and
  keeps predicting. The server only sends corrections on meaningful divergence
  (see [Game Loop & Realms](./game-loop-and-realms.md#movement--dead-reckoning)).

Remote entities are rendered **interpolated slightly in the past** (buffer a few
snapshots, lerp between them, extrapolate by velocity) to smooth the ~32 Hz
movement stream over a variable client frame rate.

## Projectiles: deterministic re-simulation

The bandwidth-critical trick: bullet positions are **not** streamed per tick.
`NetBullet` carries the full spawn parameter set **once** (id, group, position,
angle, magnitude, range, flags, `amplitude`/`frequency`, `lifetimeTicks`, orbit
center/radius/phase, `srcEntityId`, `targetEntityId`, sprite override). The client
then runs the **same deterministic integrator** as the server, scaled by
`bulletScale = dt * 64`, for straight / parametric / orbital / speed-curve motion
(the formulas are in
[Projectiles & Combat](./projectiles-and-combat.md#position-modes--formulas)).

- On receipt the client can fast-forward a straight bullet by `~(ping/2)*64` ticks
  so it appears where the server currently has it.
- **Collision and damage are always server-side.** Client-side bullet motion is
  visual; the server runs `circleHit`/`lineHit` on its own positions and sends the
  resulting HP/effect changes.
- **Homing** (`HOMING` flag) is the exception: its path depends on a moving
  target, so it can't be re-simulated blind — the server streams its
  position/velocity on the snapshot cadence and the client interpolates.

This is the core of `../projectile-simulation-and-authority.md`; that doc has the
authority/edge-case design.

## Remote game-data loading

Clients fetch content JSON (`abilities.json`, `projectile-groups.json`,
`enemies.json`, `tiles.json`, `maps.json`, sprites, …) from **openrealm-data over
HTTP at runtime**, the same source the server loads from. Consequences:

- **Data-only edits require no native EXE release** — push the data + redeploy the
  data service and both clients pick it up (web instantly; native on next connect).
- The native client needs the `{{PI}}` angle deserializer (see
  [Data-Service Integration](./data-service-integration.md#game-data-loading)) or
  the enemies/projectile parse fails and nothing renders.
- Native binary changes (new packet types, new render code) **do** require a
  native release: bump the pom version, `mvn -Pinstaller-windows package verify`
  (jpackage + WiX), and publish the `.exe`; the auto-updater pulls it from the
  latest GitHub release.

## Rendering from network state

- **Tiles:** `NetTile.tileId` → the client's `TileModel` → sprite-atlas
  coordinates. Collision/physics are reconstructed client-side from its own
  `tiles.json` (only `tileId` is on the wire), so visual tile flags can be added
  without a protocol change.
- **Entities:** `NetPlayer`/`NetEnemy` carry position, stats, and animation state;
  animations resolve through `animations.json` (idle/walk/attack). Player dyes
  recolor via a pixel mask + dye color.
- **HUD/inventory:** both clients render inventory/equipment through an atlas HUD
  (45 slots: 5 equip + 40 backpack across Main/Backpack pages, plus loot and
  potion slots). Slot-layout changes are a protocol break requiring server + web +
  native shipped together.
- **Effects:** `CreateEffectPacket` and `TextEffectPacket` drive transient visuals
  (heal rings, chain arcs, dash trails, floating damage/heal numbers).

## Adding to the protocol — checklist

1. Add/modify the packet in `net/**/packet` with a unique `@PacketId` and ordered
   `@SerializableField`s (server).
2. Handle it: a `@PacketHandlerServer` method (or a registered callback for hot
   paths) for input; build + enqueue it in `enqueueGameData()` for output.
3. **Mirror the exact field order** in the native client and the web client
   deserializers.
4. If it carries longs, ensure the web client coerces `BigInt → Number`.
5. If it's data-shaped rather than wire-shaped, prefer adding it to the content
   JSON (no protocol/EXE change) over a new packet.
</content>
