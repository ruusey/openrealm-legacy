# PvP, Parties, Trading & Events

[← Guide index](./README.md)

The social and endgame systems: PvP matches, parties, the trade state machine,
realm events + purification, the chat-command system, and the transient
per-entity effect state that backs abilities.

## PvP

Party-based arena combat (1v1 up to 4v4). Design notes live in `pvp_impl.txt`;
implementation in the `Pvp*` classes. Key constants (`PvpMatchManager`):

- `PVP_ARENA_MAP_ID = 34` (has team spawns + `pvpLanesTeamA/B`).
- Minions: basic enemy `6`, strong enemy `8`; per wave 4 basic + 1 strong per
  lane × 3 lanes = 15/team; `WAVE_INTERVAL_MS = 30_000`, first wave at 10 s.
- `MATCH_START_INVULN_MS = 3_000`; `CHALLENGE_TTL_MS = 30_000`;
  `EMPTY_ARENA_GRACE_MS = 5_000`. Teams `TEAM_A = 1`, `TEAM_B = 2`.

**State machine:** `/pvp {name}` → pending challenge (30 s TTL) → `/pvpaccept` →
`startMatch()` creates the arena realm, teleports both to team spawns, applies 3 s
INVINCIBLE, registers `PvpMatch`. Minion waves spawn on the 30 s cadence and walk
lanes via `PvpMinionAi`. Match ends on HP 0 / disconnect / `/pvpforfeit`; both
players return to the nexus at full HP, `pvpTeamId` cleared.

**Damage path** (`PvpAbilityHandler`): PvP damage is scaled **×0.1**
(`PVP_ABILITY_DAMAGE_SCALE = 0.1`). Friendly fire is filtered — abilities skip
same-team minions/players; heals/buffs skip opposing players. AoE respects wall
line-of-sight. POISONED/BLEEDING route to `PvpEffectsManager`; lethal hits go
through `mgr.playerDeath()` → `PvpMatchManager.onPlayerHpZero()`.

**PvP DoTs** (`PvpEffectsManager`, `PvpDot`): 200 ms ticks; poison 1.5%/s, bleed
5%/s of max HP (bleed per-tick capped). Applying refreshes rather than stacks.

**Minion AI** (`PvpMinionAi`): priority = opposing player in range → opposing
minion in range → walk lane. Lane walking follows `MapModel` waypoints at 1.0
px/tick, advancing within 48 px (skip-ahead if stuck 12+ ticks). Minion-vs-minion
bullets are resolved in a dedicated pass; kills call `enemyDeath()`. Minions are
force-awake (no proximity gate) so lanes advance with no players nearby.

## Parties

`PartyManager`: `MAX_PARTY_SIZE = 4`, `INVITE_TIMEOUT_MS = 60_000`. State:
`rosters` (partyId → member set), `playerParty` (playerId → partyId),
`pendingInvites` (invitee → [inviter, expiry]).

Transitions: `invite` (auto-creates a party for a partyless inviter) → `accept`
(join if room) / `decline`; `leave` (auto-ejects a lone survivor — parties of 1
aren't allowed — and deletes an empty party); expired invites auto-disband solo
lobbies. Broadcast via **`PartyUpdatePacket` (41)** carrying `partyId` (0 = none)
and up to four `NetPartyMember`s (name, class, HP/MP, level, realmId, effect
icons, hotbar bindings + cooldowns + invested points, computed stats for
tooltips). Commands: `/party invite|accept|decline|leave|list`.

## Trading

`ServerTradeManager`: 15 s request TTL; guests can't trade; both players must be
in viewport (~3 tiles). State maps track active trades, requests, per-player
selections, confirmations, and TTLs.

State machine:

1. `/trade {name}` → `RequestTradePacket` (16) to target; both confirmations 0.
2. `/accept` → `AcceptTradeRequestPacket` (17) with both inventories; empty
   selections created; both enter the active-trade map.
3. Item selection → `UpdatePlayerTradeSelectionPacket` (18); **any change
   un-confirms both sides**; state rebroadcast via `UpdateTradePacket` (19).
4. `/confirm true` → sets confirmation; when **both** confirm, items are moved —
   each selected item is **cloned with a fresh UUID** (anti-dupe) — and both get
   "Trade completed!". `/confirm false` or `/decline` cancels.

Anti-scam: fresh-UUID clone, change-resets-confirmation, viewport distance check,
guest ban, server-side slot validation.

## Realm events

`RealmEventModel` (`realm-events.json`): boss + set-piece encounter with
`bossEnemyId`, `setPieceId`, allowed terrains/zones, `durationSeconds`, and
`minionWaves` (HP-gated). `ActiveRealmEvent` tracks live state incl. saved terrain
for rollback and per-wave trigger flags.

`ServerRealmEventHelper`: eligibility requires a zoned terrain realm with a human
player. A spawn check runs every ~6400 ticks (~100 s) with a 15% roll, max 1
concurrent event/realm. On spawn it finds a safe in-zone position, stamps the set
piece (saving original tiles), spawns the boss (HP × difficulty), and broadcasts
an `EVENT_MARKER ADD` via `TextPacket`. Minion waves spawn as boss HP crosses each
`triggerHpPercent`. On boss death: clean up minions, restore terrain, broadcast
`EVENT_MARKER REMOVE` + defeat message, and award purification. Timeout restores
terrain and removes the boss/minions.

## Realm purification

`RealmPurificationHelper`: shared, non-entry overworld nodes accumulate
purification "progress" toward a goal (`BASE_GOAL_PER_DIFFICULTY = 500_000` ×
difficulty ≈ 20–30 min). Sources: enemy kills (`baseXp × difficulty ×
groupMultiplier`), dungeon completion (`5_000 × diff`), event completion
(`15_000 × diff`). Group multiplier is **superlinear**: `size × (1 + 0.34·(size−1))`
→ ~2.68× (2p), ~5.04× (3p), ~8.08× (4p).

Progress broadcasts via **`RealmPurificationPacket` (42)** on change + heartbeat.
On first reaching the goal: announce, start a `TELEPORT_DELAY_TICKS = 640` (~10 s)
countdown, then teleport qualified players to the boss realm. Portal-summary UI
data (target node, difficulty, player count, progress) refreshes ~every 64 ticks.
Admin: `/purify {percent}`.

## Chat-command system

Commands are `public static` methods annotated `@CommandHandler(value, description)`;
`@AdminRestrictedCommand(provisions)` gates them. A reflection scan at startup
populates the name→handle and name→metadata maps. Dispatch (`ServerCommandHandler`):
resolve player from address → check admin-mode + provisions → invoke
`(mgr, player, message)`; errors return code 502, unknown commands 501. Provisions
are cached and re-checked from the data service on restricted use.

Representative commands (see `/help` for the live list):

- **Utility:** `/help`, `/about`, `/pos`, `/admin` (toggle), `/gmc`.
- **Moderator+:** `/heal`, `/cdreset`, `/stat`.
- **Spawning (admin+):** `/spawn`, `/spawnhorde`, `/item`, `/tierset`, `/portal`,
  `/spawnbots` + `/killbots` (sysadmin).
- **Combat/effects:** `/kill`, `/damage`, `/seteffect`, `/godmode`.
- **Movement:** `/tp`, `/visit`, `/summon`, `/world`, `/realm up|down`.
- **Progression/realm:** `/event`, `/purify`, `/sp`, `/fame`.
- **Item mod:** `/rarity`, `/modifier`, `/setench`, `/setgem`.
- **Cosmetic/terrain:** `/size`, `/hide`, `/tile`, `/clearspawn`.
- **Social:** `/party …`, `/trade` + `/accept`/`/decline`/`/confirm`,
  `/pvp` + `/pvpaccept`/`/pvpdecline`/`/pvpforfeit`.
- **Sysadmin:** `/op`, `/testplayers`.

## Status effects

`StatusEffectType` (~40) drives buffs, debuffs, CC, and markers. Combat-relevant
mechanics are tabulated in
[Projectiles & Combat](./projectiles-and-combat.md#status-effects). Notable
additional ones: `ARMORED`/`BRACED` (DEF ×2 / ×1.5), `PROTECTED` (+5 VIT aura),
`PHALANX_DOME` (bullet-blocking field), `WARDED` (drops new debuffs),
`MANA_FOUNT` (2× MP regen), `GROUNDED` (movement + dash veto),
`EMPOWERED_STR`/`EMPOWERED_DEX` (buffer auras), `TAUNT_TARGET`, `MARKED_FOR_LOOT`.

Transient per-entity state (ticked in `tickGlobal`, tracked per realm):

| Class | Backs | Notes |
|-------|-------|-------|
| `DotState` | Enemy poison/bleed | total damage, duration, source, last-tick |
| `PvpDot` | PvP player DoTs | clamped per-tick; bleed capped |
| `TrapState` | Ranger/Huntress trap | place → arm (`ARM_TIME_MS = 500`) → trigger radius/effect/damage |
| `DecoyState` | Trickster decoy | travels a direction until max distance / expiry |
| `CloneState` | Ninja clone | mirrors source movement for `durationMs` |
| `PoisonThrowState` | Assassin poison throw | delayed-land AoE poison cloud |

## Server-side UI flows

Opened via `InteractTilePacket` (28) on a tile whose `interactionType` matches;
each move/buy persists async and is guarded against races:

- **Potion storage** (`ServerPotionStorageHelper`) — 32 slots, stackables + gems
  only; open/move/quick-store/stack-merge; POST `/potion-storage`.
- **Forge** (`ServerForgeHelper`) — shard→crystal, enchant, socket gem,
  disenchant. See [Items, Abilities & Classes](./items-abilities-classes.md#forge--fame-store).
- **Fame store** (`ServerFameStoreHelper`) — dyes/crystals/gems at 500 fame, spent
  atomically through the data service.
</content>
