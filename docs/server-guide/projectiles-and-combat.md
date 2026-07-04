# Projectiles & Combat

[← Guide index](./README.md)

This chapter is the reference for how bullets are described, spawned, moved, and
resolved into damage — including the **projectile position modes and their
formulas**, projectile flags, the damage formula, ability casting, scaling
curves, and status effects. It complements the design-level
`../projectile-simulation-and-authority.md`.

## Projectile group model

`projectile-groups.json` → `ProjectileGroup` (`game/model/ProjectileGroup.java`),
which extends `SpriteModel`:

- `projectileGroupId` — key referenced by weapons, abilities, attack patterns, scripts.
- `projectiles` — `List<Projectile>`; each entry can have distinct flags, angle,
  speed, and effects even within one visual group.
- sprite metadata from `SpriteModel` (`spriteSize`, default 8 px, sheet row/col).

A single `Projectile` (`game/model/Projectile.java`):

| Field | Meaning |
|-------|---------|
| `positionMode` | Spawn origin: `TARGET_PLAYER` (from player, active), `ABSOLUTE` (at target), `RELATIVE` (defined, unused) |
| `angle` | Firing direction; **stored as String** so JSON can use the `{{PI}}`/`{{PI/2}}` placeholder (see below) |
| `range` | Max travel distance (px) |
| `magnitude` | Speed (px/tick) |
| `size` | Collision half-width |
| `damage` | Base damage |
| `amplitude` | PARAMETRIC wave peak / ORBITAL orbit radius |
| `frequency` | PARAMETRIC oscillation (deg/tick) / ORBITAL angular speed / SPEED curve `k` / LINE spin |
| `length` | LINE_SEGMENT wall extent perpendicular to angle |
| `lifetimeTicks` | Forced expiry (0 ⇒ range/10 s cap) |
| `spawnOffsetX/Y` | Offset from source center (rotated by firing angle) |
| `spawnDelayMs` | Stagger before this projectile spawns within its group |
| `flags` | `List<Short>` `ProjectileFlag` ids |
| `effects` | On-hit `ProjectileEffect`s (status id + duration) |

> **`{{PI}}` placeholder parsing.** Angle fields may be strings like `"{{PI/2}}"`.
> `RadianAngleDeserializer` + `GameDataManager.evalPiExpression()` resolve
> `{{ n*PI/m }}` forms at load time. The native client needs the equivalent
> deserializer or the whole enemies/projectile parse dies — this is a real,
> historically-bitten gotcha.

## Position modes & formulas

### Spawn origin (`ProjectilePositionMode`)

Applied in `ServerAbilityHelper` at spawn:

- **`TARGET_PLAYER`** — origin pushed **60 px forward** along the aim line:

  ```
  aim   = (destX - playerX, destY - playerY)
  source = playerCenter + (aim / |aim|) * 60      // SPAWN_FWD = 60
  ```

- **`ABSOLUTE`** (and the unused `RELATIVE`) — `source = dest` (spawn at the target point).

### Multi-shot fan

For `1 + extraProjectiles` bullets centered on the aim line
(`ServerAbilityHelper`, `SPREAD = 0.10 rad ≈ 5.7°` between adjacent shots):

```
baseA      = aimAngle + parse(projectile.angle)
for i in 0 .. total-1:
    deltaA = (i - (total-1)/2) * 0.10
    fire at angle = baseA + deltaA
```

The center shot stays on `baseA`; flanks offset symmetrically.

### Ring (radial burst)

N projectiles equally spaced around a point (used by grenade shrapnel, RING attacks):

```
angle_i = i * 2π / N            // i = 0 .. N-1
```

### Orbital projectiles (`ORBITAL` flag)

Bullet circles a center at fixed radius (`Bullet.updateOrbital`):

```
orbitPhase += toRadians(frequency * bulletScale)
pos = (orbitCenterX + orbitRadius*cos(orbitPhase),
       orbitCenterY + orbitRadius*sin(orbitPhase))
range -= orbitRadius * |toRadians(frequency * bulletScale)|   // arc-length spend
```

- `orbitRadius` = `amplitude`; angular speed = `frequency` (deg/tick).
- Ring members get evenly spaced starting phases `2πi/N`; the orbit center is
  shifted by half the bullet size so the ring traces around the source center.

### Parametric (sine-wave) projectiles (`PARAMETRIC` / `INVERTED_PARAMETRIC`)

Straight travel plus a perpendicular sinusoidal offset (`Bullet.updateParametric`):

```
prevOffset = amplitude * sin(toRadians(timeStep))
timeStep   = (timeStep + frequency * bulletScale) mod 360
currOffset = amplitude * sin(toRadians(timeStep))
perpDelta  = (currOffset - prevOffset) * (invert ? -1 : +1)

forward = (sinAngle, cosAngle) * magnitude * bulletScale
perp    = (cosAngle, -sinAngle)                 // 90° to travel
velocity = forward + perp * perpDelta
range   -= magnitude * bulletScale              // range spent on forward motion only
```

`INVERTED_PARAMETRIC` flips `invert` (opposite-side wave).

### `bulletScale`

All per-tick motion multiplies by `bulletScale = dt * 64` so movement is
normalized to the 64 Hz tick regardless of client frame rate — the property that
lets clients re-simulate deterministically.

## Projectile flags (`ProjectileFlag`)

| ID | Flag | Behavior |
|----|------|----------|
| 10 | `PLAYER_PROJECTILE` | Marks a player-sourced bullet (targeting/authority) |
| 12 | `PARAMETRIC` | Straight + sine perpendicular oscillation |
| 13 | `INVERTED_PARAMETRIC` | Parametric, opposite side |
| 20 | `ORBITAL` | Circles a center point |
| 23 | `ARMOR_PIERCING` | Ignores target DEF (full base damage) |
| 24 | `PASS_THROUGH_TERRAIN` | Not destroyed by wall/tile collision |
| 25 | `PASS_THROUGH_ENEMIES` | Pierces enemies; damages each once (per-bullet de-dup), keeps flying |
| 30 | `LINE_SEGMENT` | Capsule/wall: extends `length` perpendicular; point-to-segment collision; persistent hazard damaging on a ~600 ms interval; may spin via `frequency` |
| 31 | `ANCHORED` | Re-positions to its source each tick (preserves spawn offset) — used to attach LINE cages to moving bosses |
| 32 | `SPEED_DECAY` | Speed eases `magnitude → 0` over `lifetimeTicks` (exp curve, `k = frequency`, default 4) |
| 33 | `SPEED_RAMP` | Speed eases `0 → magnitude` (inverse curve) |
| 34 | `HOMING` | Steers toward `targetEntityId`, turn capped by `frequency` (deg/turn); streamed to clients, not re-simulated |

Speed-curve math (`Bullet.speedCurveMult`, `p = age/lifetime`):

```
SPEED_DECAY: mult = (exp(-k·p) - exp(-k)) / (1 - exp(-k))
SPEED_RAMP : mult = (exp( k·p) - 1)       / (exp(k) - 1)
```

## Bullet lifecycle (server)

1. **Spawn** (`RealmManagerServer.addProjectile`): create `Bullet` with pos,
   angle, magnitude, range, damage, flags, `srcEntityId`; stamp `createdTick`
   (tick-based expiry avoids `nanoTime` syscalls); set up orbital/anchor if flagged.
2. **Per-tick** (`Bullet.update(bulletScale)`): dispatch by flag — orbital /
   parametric / straight (with optional speed curve); spin + re-anchor as needed;
   spend `range`. Trig is cached (`sinAngle`/`cosAngle`) and recomputed only on
   direction change.
3. **Collision** (`RealmManagerServer.processBulletHit`): circle-vs-body for
   normal bullets, point-to-segment for LINE_SEGMENT. Damage via
   `ServerCombatHelper`; piercing bullets record hits and continue, others expire.
4. **Expiry** (`Bullet.remove`): `range <= 0`, or lifetime exceeded
   (`currentTick - createdTick > lifetimeTicks`), or the 10 s legacy wall-clock cap.

`NetBullet` serializes the full spawn parameter set once; the client
re-simulates from it (see [Client Integration](./client-integration.md)).

## The damage formula

Resolved in `ServerCombatHelper` using `CombatMath`:

```
raw        = bullet.damage * difficultyMult(difficulty)

afterStatus = raw
            * (attacker.DAMAGING  ? 1.50 : 1)
            * (attacker.WEAKEN    ? 0.65 : 1)
            * (target.CURSED      ? 1.25 : 1)
            * (target.VULNERABLE  ? 1.40 : 1)
            * (target.WITHER      ? 1.10 : 1)

minDmg     = ceil(raw * 0.15)                 // 15% floor

final      = (armorPiercing || target.ARMOR_BROKEN)
               ? afterStatus
               : max(minDmg, afterStatus - targetDEF)
```

- **Difficulty multiplier** (`CombatMath.difficultyDamageMult`): flat 1.0 up to a
  threshold, then a two-segment slope up to a cap (separate tuning for dungeon vs
  overworld) — this is what makes deep enemies hit harder.
- **Per-shot mods** (`CombatMath.applyShotDamageMods`): `base·(1 + damagePct/100)`,
  then on a crit roll `dmg *= 2`, clamped to `[0, Short.MAX_VALUE]`.
- **AoE hit-test** uses the enemy **body**, not center: `reach = aoeRadius +
  enemy.size/2` (circle-vs-body). Comparing to the center made large bosses
  (size 64) skip slow/armor-break/stun at most positions — a fixed bug worth
  remembering when adding AoE.

### Damage-over-time

- **Poison**: total = `hitDmg * 0.5`, spread over the duration in 200 ms ticks.
- **Bleed**: total = `targetMaxHP * 0.15`, tick-capped (~50/tick).
- **Imbue Poison** (Assassin): `baseDot = 80 + attacker.DEX` on every basic hit.

Enemy DoTs are tracked per realm (`DotState`); PvP DoTs use a separate `PvpDot`
(see [PvP, Parties, Trading & Events](./pvp-parties-trading-events.md)).

## Ability casting

Two fire paths converge on the same spawn/collision/damage code:

- **`PlayerShootPacket` (6)** — legacy basic attack: carries the weapon's
  projectile group + target.
- **`UseAbilityPacket` (11)** — ability system: carries hotbar slot + cursor; the
  ability id is resolved server-side.

`ServerAbilityHelper.useAbility()` validates binding, not-already-casting,
cooldown, MP, and range-clamp, then:

- **Instant** (`baseCastMs == 0`): spawn projectiles / apply effects immediately.
- **Cast** (`baseCastMs > 0`): schedule resolution, apply `SLOWED` for the cast
  window, broadcast `AbilityCastStartPacket` (38) so clients draw the cast bar,
  and set cooldown to `now + castMs + cooldownMs`. On resolution the projectiles
  spawn (no second cooldown gate).

Cooldown/cast scaling (skill points invested in the ability):

```
effectiveCd   = max(500, baseCooldownMs - invested * cdReductionPerPointMs)
effectiveCast = max(150, baseCastMs      - invested * cdReductionPerPointMs / 2)
```

Projectile damage for an ability = `baseDamage + Σ(scaling.coeff * stat)` over its
`DAMAGE`-target scalings, then gem/shot-context mods (crit, damage%, extra
projectiles) — see [Items, Abilities & Classes](./items-abilities-classes.md).

## Scaling curves

`AbilityScaling` maps a stat onto an effect target via a curve
(`ScalingCurve`); `target` is a `ScalingTarget` (DAMAGE, RADIUS,
STATUS_DURATION_MS, COOLDOWN_REDUCTION_MUL, …):

| Curve | Contribution |
|-------|--------------|
| `LINEAR` | `min(cap, stat * coeff)` (cap ignored if 0) |
| `DIMINISHING` | `cap * (1 - exp(-stat * coeff / cap))` — saturates toward `cap` (used for CDR / cast speed) |
| `THRESHOLD` | `coeff` if `stat ≥ cap` else 0 (discrete unlock) |
| `LINEAR_THRESHOLD` | `coeff * max(0, stat - cap)` (+N per point past a floor) |

`SKILL_POINTS` is a synthetic stat resolving to the player's invested level in the
parent ability.

## Status effects

`StatusEffectType` enumerates ~40 effects (id → mechanic). The combat-relevant ones:

| ID | Name | Effect |
|----|------|--------|
| 2 | PARALYZED | Movement + attack lock (velocity zeroed) |
| 3 | STUNNED | Can't act (hard-capped ~3000 ms) |
| 4 | SPEEDY | +50% move speed |
| 6 | INVINCIBLE | Damage immunity |
| 15 | STASIS | Frozen + invulnerable |
| 17 | POISONED | DoT (`hitDmg*0.5` over 200 ms ticks) |
| 21 | SLOWED | −50% move speed |
| 22 | ARMOR_BROKEN | Full damage (ignores DEF) |
| 27 | WEAKEN | Outgoing damage ×0.65 |
| 28 | BLIND | Client render distance shrinks |
| 31 | VULNERABLE | Incoming damage ×1.40 |
| 36 | BLEEDING | DoT (`maxHP*0.15`, tick-capped) |
| 38 | WITHER | Incoming damage ×1.10 (source-scoped) |
| 39 | IMBUED_POISON | Attacker: basic hits apply POISONED + DoT |
| 40 | DODGE | High chance to negate hits |

The full list (auras, buffs, transient markers) and the per-entity state classes
(`DotState`, `TrapState`, `DecoyState`, `CloneState`, `PoisonThrowState`) are
documented in [PvP, Parties, Trading & Events](./pvp-parties-trading-events.md#status-effects).
</content>
