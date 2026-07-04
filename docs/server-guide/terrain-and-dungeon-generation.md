# Terrain & Dungeon Generation

[← Guide index](./README.md)

How the server builds worlds: the tile system, overworld terrain by concentric
zones, the procedural room-and-corridor dungeon generator, set pieces, decorators,
portals, and the map model that ties them together.

## Tile system

Three layers of representation:

- **`TileModel`** (`tiles.json`, extends `SpriteModel`) — the *definition*:
  `tileId`, `name`, `size`, a `TileData`, and optional `interactionType`
  (`"forge"`, `"fame_store"`, `"potion_storage"`).
- **`TileData`** — immutable physics flags: `hasCollision`, `slows`, `damaging`,
  `isWall`.
- **`Tile`** — a placed grid cell: `tileId`, `row`, `col`, and a packed **flags
  byte** encoding the four `TileData` booleans:

  ```
  bit0 = collision   bit1 = slows   bit2 = damaging   bit3 = isWall
  ```

  16 shared `TileData` instances (one per flag combo) are pooled and referenced by
  `flags & 0xF`, so tiles allocate no per-cell data.

`TileMap` is a `Tile[height][width]` grid with `fill`, `setTileAt`, and `append`
(stamp another map at an offset, skipping void tiles). Maps use two layers:
**base** (`"0"`) and **collision/decoration** (`"1"`). `tileId == 0` is *void*
(transparent — leaves whatever is underneath).

### On the wire

`NetTile` carries only `tileId`, `layer`, `xIndex`, `yIndex`. Collision/physics is
**not** transmitted — the client reconstructs it from its own `tiles.json`. This
is why you can add per-tile visual flags (e.g. `noBlend`) without a protocol
change: the flags ride in `tiles.json`, resolved on both ends, packed into the
`Tile.flags` byte (bit4 = noBlend, bits5–7 free). `LoadMapPacket` sends tile
deltas, packing `(tileId, layer, x, y)` into a 64-bit key for O(N) set-diffing.

## Overworld terrain generation

Overworld maps are generated from `TerrainGenerationParameters`
(`terrains.json`): `width/height`, `tileSize`, `tileGroups`, `enemyGroups`,
`zones`, `setPieces`, `hordeSpawns`, `difficulty`, `enemyDensity`.

### Zones

`OverworldZone` defines a **concentric radial band** around the map center by
normalized distance (0 at center → 1 at the far corner):

- `minRadius`/`maxRadius` — the band.
- `tileGroupOrdinal`/`enemyGroupOrdinal` — which `TileGroup`/`EnemyGroup` to use.
- `difficulty` — scales enemy HP/XP in the band.
- `spawnZone` — new players prefer this band (e.g. the safe beach ring).
- `portalDrops` — zone-specific portal drop weights.

`TileManager.getZoneForPosition()` computes `normalizedDist = dist(center)/maxDist`
and picks the first zone whose band contains it (outermost zone as fallback).

### Placement

`TileManager.getLayersFromTerrain()`:

- **Zoned**: per tile, find the zone → its `TileGroup`, pick a random base tile
  (with per-tile `rarities` filtering), place in base layer; repeat for
  `decorationTileIds` into the collision layer.
- **Unzoned**: fill the whole map from a single `TileGroup`.

`TileGroup`: `ordinal`, `tileIds` (base), `decorationTileIds`, `rarities`
(per-tile probability, default 1.0). Overworld generation seeds from wall-clock
time, so the same terrain id regenerates differently each run (no fixed seed).

## Procedural dungeon generation

`DungeonGenerator.generateDungeon()` builds a room-and-corridor dungeon into base
+ collision layers. Parameters come from `DungeonGenerationParams`: room
count/size ranges, `shapeTemplates`, floor/wall tile ids + rarities,
`hallwayStyles`, `bossEnemyId`. Randomness uses the shared `Realm.RANDOM` (no
seed param).

### Algorithm

1. **Init** — fill both layers with void; roll `numRooms ∈ [minRooms, maxRooms]`.
2. **Rooms** (i = 0 … numRooms−1):
   - Size from the width/height ranges; the **boss room** (last) is scaled 1.2–1.5×.
   - Shape from `RoomShapeTemplate`: `RECTANGLE`, `OVAL` (radial),
     `DIAMOND` (Manhattan), `CROSS` (arms), `L_SHAPE` (pivot split),
     `TRIANGLE` (interpolated), `IRREGULAR` (cellular-automata: 45% seed, 4
     iterations B5678/S45678, forced-clear center).
   - Placement: room 0 is the fixed small **spawn** room near a corner;
     subsequent rooms try ≤30 random gaps with a **directional bias** toward the
     opposite quadrant, clamped in-bounds, rejecting overlaps (±2 buffer). The
     boss room additionally must be ≥40% of the map diagonal from spawn.
   - Stamp via `TileMap.append`; add a vertex/edge to a `Graph<TileMap>` tracking connectivity.
3. **Corridors** — connect consecutive room centers with a random hallway style:
   `L_SHAPED`, `ZIGZAG`, `WIDE` (halfWidth 2), `WINDING` (70% greedy / 30% random
   walk), `CURVED` (quadratic Bézier), `S_BEND`.
4. **Walls** — `lineWithWalls()` rings floor tiles with wall tiles;
   `roughenWalls()` carves ~12% of thin walls for a craggy look; a second
   `lineWithWalls()` closes diagonal gaps.
5. **Boss entrance** — carve openings through the boss room's walls to adjacent floor.
6. **Side branches** — ~`max(2, numRooms/4)` short dead-end branches off corridor
   tiles, each ending in a 1–2 tile chamber.

`Graph`, `Partition`, and `Cardinality` (`util/`) are connectivity/utility helpers
used by generation and future spawn logic.

## Set pieces

Reusable multi-tile structures stamped onto generated terrain.

- **`SetPieceModel`** (`setpieces.json`) — the template: `width`, `height`, and
  `data` = layer maps (`"0"` base, `"1"` collision) of `int[][]` tile ids.
  `tileId == 0` cells are transparent.
- **`SetPiece`** (in a terrain definition) — a placement rule: `setPieceId`,
  `minCount`/`maxCount`, `allowedZones`.

`Realm.placeSetPieces()` rolls a count per piece and, per attempt: picks a random
position, rejects it if the center tile is void or the zone isn't allowed or it
overlaps an already-occupied tile (tracked in a `Set<Long>` of packed
coordinates), then `stampSetPiece()` writes non-transparent tiles onto both layers
and invalidates the wall cache (so line-of-sight recomputes if walls were added).

Overworld set pieces are stamped **on lazy terrain-realm creation**
(`placeSetPiecesIfTerrainMap`, after enemy spawn); non-terrain maps skip it.
Runtime tile stamps (from set pieces or admin edits) broadcast automatically via
the `LoadMapPacket` delta loop — no dedicated packet.

> **Falsy-id trap:** `setPieceId 0` is valid. Editor/parse code using
> `parseInt(x) || default` silently turns a real 0 id into the default and
> corrupts placements — use an explicit null/NaN check.

`StaticSpawn` (`enemyId`, `x`, `y`) places fixed enemies/objects at exact world
coordinates (e.g. NPCs, forge attendants).

## Decorators

`RealmDecorator` (`decorate(Realm)`, `getTargetMapId()`) layers extra content onto
a generated map, registered by target map id at startup. Examples:

| Decorator | Map | Effect |
|-----------|-----|--------|
| `Cave0Decorator` | 3 | 15–25 lava pools (center `LAVA_TILE1` ringed by `LAVA_TILE0`) on the base layer |
| `Grasslands0Decorator` | 4 | 15–25 tree clusters (center + cross) on the collision layer |
| `BossRoomDecorator` | 5 | Spawns boss (enemy 13, HP ×4) + cardinal minions (×2 HP); sets `dungeonBossEnemyId` so the exit portal drops on boss death |

`RealmDecoratorBase` holds the `RealmManagerServer` reference.

## Portals & the dungeon graph

- **`PortalModel`** (`portals.json`, extends `SpriteModel`): `portalId`,
  `portalName`, destination `mapId`, `targetNodeId` (graph link), world `x`/`y`,
  UI `label`.
- **`DungeonGraphNode`** (`dungeon-graph.json`): a node in the progression graph —
  `nodeId`, `mapId`, `difficulty`, `entryPoint`, `bossNode`, `shared`,
  `childNodes`, and `portalDropNodeMap` (which portal id unlocks which next node).

Portals are either static (placed in `MapModel.staticPortals`) or dropped on boss
death per the map/graph configuration.

## Map model

`MapModel` (`maps.json`) is the top-level realm definition. Selection priority:

1. `terrainId ≥ 0` → generate from `TerrainGenerationParameters`.
2. else `dungeonId ≥ 0` → generate from `DungeonGenerationParams`.
3. else → load static `data` layers.

Other fields: `tileSize` (32), `width`/`height`, `staticSpawns`, `spawnPoints`,
`staticPortals`, `difficulty`, PvP data (`isPvp`, team spawns, `pvpLanesTeamA/B`),
and `maxPartyCount` (≤0 unlimited, 1 = single-party dungeon).

A `Realm` builds its `TileManager(mapId)` at construction, which runs the
appropriate generation path and stores the base + collision layers.
</content>
