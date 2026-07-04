# Enemy AI & Scripting

[← Guide index](./README.md)

Enemies are **data-driven** (JSON → `EnemyModel`) with an escape hatch into
**code** (the `EnemyScript` SDK) for stateful or bespoke behavior. This chapter
covers the enemy data model, the AI tick, movement and attack patterns (with
math), the scripting SDK, the horde flocking system, and difficulty scaling.

## Data model

`enemies.json` → `EnemyModel` (`game/model/EnemyModel.java`). Key fields:

| Field | Meaning |
|-------|---------|
| `enemyId`, `name`, `size` | Identity + collision/sprite size (px) |
| `stats` | Base HP/MP/DEF/STR/SPD/DEX/VIT/WIS |
| `maxSpeed` | Movement speed (0 = stationary NPC) |
| `chaseRange` / `attackRange` | Pursue / fire distances (px) |
| `xp` | Reward on death (also drives loot tier via `xp/100`) |
| `attackId` | Legacy weapon for phase-less enemies |
| `phases` | `List<EnemyPhase>`; empty ⇒ legacy chase+attack |
| `permanentEffects` | Status ids applied on spawn, never expire (e.g. INVINCIBLE for static NPCs) |
| `rarityWeights` | Per-rarity loot weights; null ⇒ derived from tier |

### Phases

`EnemyPhase` partitions a fight by HP threshold, enabling multi-stage bosses:

- `hpThreshold` (0..1) — phase becomes active at/below this HP fraction.
- `movement` — a `MovementPattern` (null ⇒ no movement).
- `attacks` — `List<AttackPattern>` (null/empty ⇒ no attacks this phase).

`Enemy.getActivePhase()` scans phases in reverse and picks the first whose
threshold the current HP% satisfies. On a phase change the enemy gets **1.2 s
INVINCIBLE** (`PHASE_TRANSITION_DURATION_MS = 1200`), attack cooldowns reset, the
`phaseEpoch` increments (so in-flight bursts from the old phase abort), and
existing bullets from this source are cleared — keeping counter-rotating spirals
radially symmetric across the transition.

## The AI tick

Enemies run at 64 Hz but AI *decisions* are gated to 32 Hz
(`ENEMY_AI_TICK_DIVISOR = 2`). Core loop: `Enemy.update()` →
`updateAgainstTarget()`:

1. **Target acquisition** — nearest player within `chaseRange` (scripted NPCs
   also see HIDDEN players so admin-invisible players still get healed/targeted).
2. **HP/mana %** — drive phase selection and client bars.
3. **Phase transition** handling (invincibility, cooldown reset, epoch bump).
4. **Freeze** — phase-transition / PARALYZED / STASIS zero velocity + suppress attacks.
5. **Movement** — `applyMovement(player, phase)` if the phase defines one, else
   legacy `chase()`.
6. **Attacks** — `processAttacks()` unless in transition / STUNNED / STASIS.

### Attack scheduling

- Occluded (behind a wall) non-static enemies don't fire at all.
- A **script** attack (if registered for this enemy id and target in range) is
  gated by a DEX-derived fire rate and runs async:

  ```
  dex        = max(1, (6.5 * (stats.dex + 17.3)) / 75)
  canShoot   = (now - lastShotTick) > 1000 / dex   (ms)
  ```

  A non-additive script *replaces* JSON attacks; an additive one runs alongside.

- **JSON phase attacks** convert `cooldownMs` to ticks, snapped to the AI cadence
  so fire always lands on a tick the enemy's AI runs:

  ```
  ticks = round(cooldownMs * 64 / 1000)
  ticks = (ticks / 2) * 2          // snap to AI_TICK_CADENCE = 2
  ticks = max(2, ticks)
  ```

  All attacks in a phase are seeded to fire on the same tick (lockstep), so
  multi-arm patterns stay symmetric; a stun re-synchronizes them.

## Movement patterns

`MovementPattern.type` (default `CHASE`), with per-type parameters. Behaviors
(`Enemy.java`):

| Type | Behavior (summary) |
|------|--------------------|
| **CHASE** | Move toward player at phase speed with a `sin(orbitAngle*3)*0.4` wobble; strafe perpendicular at 0.3× when inside `attackRange` to avoid stacking on the player |
| **ORBIT** | Hold a fixed `radius`; blend radial correction with tangential motion (CW/CCW), radial weight `min(1, |dist-radius|/30)` |
| **STRAFE** | Keep `preferredRange`; approach/retreat at 0.6× outside a ±20 band, else strafe (toggles every 3 ticks) |
| **CHARGE** | Rush at 1.3× (capped at `MAX_ENEMY_SPEED = 5.0`) until within `chargeDistanceMin`, then `pauseMs` recovery |
| **FLEE** | Flee at full speed inside `fleeRange`; re-approach at 0.5× if player drifts out but stays within `chaseRange` |
| **WANDER** | Pick a random heading every 30–90 ticks; gentle sine curve between changes |
| **ANCHOR** | Defend spawn: return home past `anchorRadius` (1.2×), orbit spawn when player near, wander at 0.4× otherwise |
| **FIGURE_EIGHT** | Lissajous around player: `x = sin(a)·r`, `y = sin(2a)·r·0.5` |

Wall handling: X then Y axis attempted separately (slide along walls); if blocked
> 3 consecutive ticks, try 16-px perpendicular escape nudges.

Speed modifiers: `SLOWED` halves phase speed; everything is capped at
`MAX_ENEMY_SPEED = 5.0`. Multi-phase CHASE bosses are capped to 50% of their
phase-3 speed so they can't outrun their own bullet patterns.

## Attack patterns

`AttackPattern` describes one projectile emission rule. Selected fields:

| Field | Meaning |
|-------|---------|
| `projectileGroupId` | Which projectile group to emit |
| `cooldownMs` | Fire interval |
| `burstCount` / `burstDelayMs` | Volleys per trigger + spacing (async) |
| `shotCount` / `spreadAngle` | Shots per volley + fan width (radians) |
| `aimMode` | `PLAYER` (aimed), `RING` (360°), `FIXED` (world angle), `FIXED_RING` |
| `fixedAngle` | Angle for FIXED / FIXED_RING |
| `predictive` | Lead a moving target |
| `mirror` | Also fire a mirrored volley at the inverted angle |
| `minRange` / `maxRange` | Distance gate |
| `angleIncrementPerFiring`, `angleOffsetPerBurst` | Spiral accumulators |
| `speedCount` / `minSpeedMult` / `maxSpeedMult` | Fire N copies at interpolated speeds |
| `sourceNoise` | Random spawn jitter (px) |

The geometry (ring vs fan, spawn offsets, orbital/anchored handling) is applied
in `Enemy.fireAttackPattern()` and shares the projectile-spawn math documented in
[Projectiles & Combat](./projectiles-and-combat.md#position-modes--formulas).
`DAZED` halves the projectiles emitted (skips every other) while preserving the
spread shape.

## The scripting SDK

For behavior JSON can't express (auras, lobbed grenades, custom targeting),
implement `EnemyScript`:

```java
public interface EnemyScript {
    int  getTargetEnemyId();                                   // which enemy id
    void attack(Realm realm, Enemy enemy, Player target) throws Exception;
    default boolean isAdditive()      { return false; }        // run alongside JSON attacks?
    default void tick(Realm realm, Enemy enemy) throws Exception {}  // every tick, no combat gate
}
```

- `attack()` is gated by the DEX fire-rate + `attackRange` + target acquisition.
- `tick()` runs **every tick regardless of combat state** — ideal for auras
  (a healer that works even when the player is HIDDEN or in STASIS).
- `EnemyScriptBase` provides `mgr`, `sleep(ms)`, and a `createProjectile(...)` helper.

### Registration (reflection)

At startup, `registerEnemyScripts()` scans for concrete `EnemyScriptBase`
subclasses and instantiates each via its `(RealmManagerServer)` constructor.
**Abstract classes are skipped** — this is why `GrenadeScriptBase` itself never
registers, only its subclasses do. Lookup is a linear scan matching
`getTargetEnemyId()` (the script set is tiny).

### Worked example: `GrenadeScriptBase`

A reusable lobbed-grenade archetype: **lob arc → landing telegraph → detonation
shrapnel ring**, all tunable via overridable methods:

- Pick a random landing cell offset from the boss (grid of ±192 / ±64 px).
- Paint the lob-arc line effect, then the landing warning ring.
- Async: after `throwDurationMs` paint the landed dot; after `landDelayMs` paint
  the impact splash and fan `shrapnelCount` projectiles in a 360° ring — each
  shrapnel carries the projectile group's status effects (damage + debuff in one).
- Per-enemy throttle keyed by entity id so multiple instances don't share a
  cooldown.

Concrete subclasses just override the tunables and the shrapnel group:

| Script | Enemy | Shrapnel group | Notes |
|--------|-------|----------------|-------|
| `Enemy26Script` | 26 (Inferno Demon testbed) | 301 | Defaults |
| `Enemy232GrenadeScript` | 232 | 1198 (SLOWED) | 3600 ms interval |
| `Enemy238GrenadeScript` | 238 (final boss) | 1199 (ARMOR_BROKEN) | 3200 ms, 10 shrapnel |
| `Enemy240GrenadeScript` | 240 | 1200 (POISONED) | 3000 ms, 12 shrapnel |

`Enemy67Script` (vault healer) is the `tick()`-based counter-example: heals
nearby players (radius 64 px, 69 HP, throttled 100 ms) with an empty `attack()`.

## Hordes

Hordes model organic packs: one **commander** the player chases, with
**followers** steered by flocking. Templates live in `enemy-hordes.json`
(`HordeTemplate`); runtime instances are `Horde`.

Template knobs: `commanderEnemyId`, `minionEnemyIds` (random draw),
`min/maxFollowers` (5–7), `spacing` (36 px), `cohesion` (0.7), `separation`
(1.2), `leash` (480 px), `wander` (0.35), and `onCommanderDeath` (`FLEE` /
`DISBAND`, with `fleeDurationMs`).

### Steering

Non-commander members run `Realm.steerHordeMember()` each update, summing three
vectors:

- **Cohesion** — pull toward the commander. Pull strength: `1.0` beyond `leash`,
  `0` inside the follow-band (`spacing·1.5`), else `cohesion`.
- **Separation** — push away from members closer than `spacing`, magnitude
  `(spacing - d)/spacing`.
- **Wander** — a private per-follower heading drifting ±0.35 rad/tick.

Composed velocity is normalized to a speed that eases up when trailing
(accelerate toward the leader, capped at 5.0) and moseys gently when in-band —
so the pack looks alive rather than rigid. On commander death, followers either
FLEE (scatter away from the nearest player for `fleeDurationMs`, then revert) or
DISBAND (revert to solo AI immediately).

Placement: zone `HordeSpawn` entries with count ranges and optional `density`
(hordes per 10,000 eligible tiles). Admins spawn on demand with
`/spawnhorde {hordeId}`.

## Difficulty scaling

Effective difficulty = zone difficulty + party bonus (locked at dungeon entry).
Applied on spawn:

```
effDiff = diff + partyDifficultyBonus
enemy.health          = (int)(baseHealth   * effDiff)   // explicit int cast
enemy.stats.hp        = (int)(baseHp        * effDiff)
```

The explicit `(int)` cast is load-bearing: a prior bug scaled max-HP as a `short`
and overflowed on high-difficulty bosses (summit ×8), corrupting `maxHP`. HP is
kept as clean integers to avoid fractional drift on subsequent damage math.
`Stats.mp` is a 32-bit int for the same overflow reason.
</content>
