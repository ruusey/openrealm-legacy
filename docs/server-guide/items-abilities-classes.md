# Items, Abilities & Classes

[← Guide index](./README.md)

The RPG systems: the item model, rarity/gems/enchantments, the ability + passive
system, character classes and leveling, loot rolls, the forge/fame economy, and
the item-scripting SDK. Complements `../enchantment-rarity-system.md` and
`../class-kits.md`.

## Item model

`GameItem` (`game/entity/item/GameItem.java`) — template + per-instance state:

- Identity: `itemId` (template), `uid` (per-instance UUID), `name`, `description`.
- `stats` — flat bonuses; `damage` — weapon min/max + `projectileGroupId`.
- `effect` / `selfEffects` — on-hit / self status effects.
- Flags: `consumable`, `stackable` (+ `maxStack`, `stackCount`).
- `tier` (loot color), `rarity` (enchant slots) — **separate axes**.
- `targetSlot`: `0` weapon, `1` armor, `2` gauntlet, `3` boot, `4` ring, `-1` n/a.
- `itemClass` (`ItemClass`) + `archetypeId` (`WeaponArchetype`).
- `enchantments` (crystals), `attributeModifiers` (rolled affixes),
  `gemstoneType` (+ gem paint pixel/color).
- `scalingStat` (which stat damage scales off), `category`
  (`generic`/`shard`/`crystal`/`essence`/`gem`), `forgeStatId`, `forgeSlotId`,
  `socketSlots`.

### Item & weapon classes

`ItemClass`: `NONE(0)`, weapons `HEAVY(1)/LIGHT(2)/MAGIC(3)`, armor
`HEAVY(10)/LIGHT(11)/ROBE(12)`, `UNIVERSAL(99)` (gauntlets/boots/rings — any
class). `WeaponArchetype` (Sword/Axe/Hammer, Dagger/Bow/Chakram, Tome/Staff/Wand)
is data-driven via `WeaponArchetypeModel`: `scalingStat`, `attackSpeedMul`,
`damageMul`, `rangeMul`, `piercing`, `projectileCount`, `spreadRad`.

### Rarity

`Rarity` (ordinal → crystal slots, gem socket):

| Rarity | Crystal slots | Gem socket | Affix band |
|--------|---------------|------------|------------|
| MUNDANE | 0 | – | – |
| COMMON | 1 | – | ±1 |
| UNCOMMON | 2 | – | ±1..2 |
| RARE | 3 | – | ±1..3 |
| EPIC | 4 | ✅ | ±1..4 |
| LEGENDARY | 5 | ✅ | ±1..5 |

Crystal slots hold stat-delta enchantments; the gem socket (EPIC+) holds one
high-impact gem for the item's lifetime.

### Stats

`Stats` = 8 fields (HP, MP int; DEF/STR/SPD/DEX/VIT/WIS short). Index mapping used
everywhere in enchant/modifier code: `0=VIT 1=WIS 2=HP 3=MP 4=STR 5=DEF 6=SPD 7=DEX`.
`concat`/`subtract`/`clampTo`/`clone` compose stat layers.

### Computed stats (equip → effective)

`Player.getComputedStats()`:

```
1. clone base stats
2. per equipped item: concat item.stats; apply attributeModifiers; apply enchantments;
   gem.modifyStats(...)  (e.g. +10% of a stat)
3. status overrides: ARMOR_BROKEN→DEF 0, ARMORED→DEF×2, BRACED→DEF×1.5,
   PROTECTED→+5 VIT, EMPOWERED_STR/DEX→+magnitude
4. class-passive stat bonuses (PassiveModifierHelper)
```

## Gems & enchantments

- **Enchantment** (crystal): `statId` (0..7) + `deltaValue` (±1, or ±5 for HP/MP) +
  paint pixel/color. Count capped by rarity.
- **AttributeModifier**: a rolled affix `statId` + `deltaValue`, magnitude banded
  by rarity.
- **Gemstone** (`gem/` package): a coded effect socketed into EPIC+ items,
  registered in `GemstoneRegistry` by `typeId`. Hooks:
  `modifyStats`, `modifyShot(ShotContext)`, `onHitTarget`, `onPlayerHit`,
  `onEquip/onUnequip/onTick`, `onBasicAttack`, `onPlayerKilled`.

`ShotContext` is the per-shot mutable bag gems fill before a projectile spawns:
`extraProjectiles`, `damagePct`, `critChancePct`, `lifestealPct`, `extraSpread`,
`piercing`, `onHitStatuses[]`.

Gem implementations (`gem/impl/`):

| Gem | Type | Socket | Effect |
|-----|------|--------|--------|
| Lifesteal | 1 | weapon | Heal 8% of basic-attack damage (`onHitTarget`) |
| Crit | 2 | weapon | +15% crit chance (`modifyShot`) |
| Multishot | 3 | weapon | +1 fanned projectile |
| Venom | 4 | weapon | POISONED 4 s on hit |
| Frost | 5 | weapon | SLOWED 2 s on hit |
| Thorns | 6 | armor | Reflect 20% of damage taken (`onPlayerHit`) |
| Power | 7 | weapon | +15% basic-attack damage |
| Wis/Spd/Str/Def/Dex/Vit/Hp/Mp scaling | 8–15 | any | +10% of that stat (`modifyStats`) |

## Abilities & passives

`Ability` (`abilities.json`, ids 1–999): `mpCost`, `baseCooldownMs`, `baseCastMs`,
`castMovementSpeedMul`, `effects[]`, `scalings[]`, `baseDamage`, `baseRadius`,
`maxSkillPoints`, `cdReductionPerPointMs`, `maxCastRange`.

`AbilityEffect` is a flat, typed shape (`type` + type-specific fields):
`PROJECTILE_GROUP`, `STATUS_APPLY`, `HEAL`, `SHIELD`, `TELEPORT`,
`REFLECT_PROJECTILE`, `EMPOWER_NEXT_BASIC`.

`AbilityScaling` maps `stat` (incl. synthetic `SKILL_POINTS`) × `coeff` onto a
`target` (DAMAGE/RADIUS/STATUS_DURATION_MS/…) with a `curve` and optional `cap`.
Cooldown/cast scaling and the DAMAGE-scaling formula are in
[Projectiles & Combat](./projectiles-and-combat.md#ability-casting).

`AbilityTree` (per class): `pool[]` (bindable ability ids), `defaultHotbar[4]`,
`passive` (a passive id 1000+).

**Passives** (`PassiveAbility`, ids 1000+) fire on `PassiveTriggerEvent`s
(ON_BASIC_ATTACK, ON_KILL, ON_TAKE_DAMAGE, ON_TICK, ON_HP_THRESHOLD, …) via
`PassiveTrigger` (event + conditions + effects + scalings), and apply permanent
`PassiveModifier`s. Modifier types (all integer-truncating to match hand-tuned
originals): `STAT_BONUS`, `STAT_BONUS_FROM_BONUS_MP`, `MP_REGEN_STAT`,
`ARMOR_PEN`, `MP_ON_HIT`, `HEAL_AMP`, `DEBUFF_DURATION_ENEMY`,
`DEBUFF_RESIST_SELF`, `DODGE_WHEN_SAFE`, `AURA`.

`PassiveModifierHelper` reads these during stat computation and combat
(`armorPen`, `mpOnHit`, `healBonus`, `debuffResistPercent`, …).
`ServerPassiveTickHelper` applies `AURA` modifiers every 8 ticks (radius query;
ALLIES or ENEMIES) — e.g. a Priest's PROTECTED (+5 VIT) aura refreshed every
~0.8 s.

## Character classes & leveling

`CharacterClass` enum (12): Barbarian, Assassin, Wizard, Duelist, Trapper,
Necromancer, Paladin, Druid, Priest, Knight, Ninja, Cultist.

`CharacterClassModel` (`character-classes.json`): `baseStats`, `maxStats`
(level-cap), `startingEquipment` (slot → itemId), `abilityTree`, allowed
armor/weapon classes + archetypes. `getRandomLevelUpStats()` guarantees ≥⅓ of each
stat's per-level range so no level is dead. `canEquip()` validates ItemClass +
WeaponArchetype against the class model.

`ExperienceModel` (`exp-levels.json`): a `level → "minXp-maxXp"` map parsed into
ranges. `getLevel(xp)`, `maxLevel()`, `maxExperience()`; past the cap, XP converts
to fame (`(xp - maxExperience()) / 2500`).

## Loot

On enemy death, `LootTableModel` (`loot-tables.json`, keyed by enemy id) rolls
drop entries. Drop-key grammar:

| Key | Resolves to |
|-----|-------------|
| `item:N` | item id N |
| `group:N` | random item from `LootGroupModel` N |
| `shard:S[:a-b]` | stat-S shard (ids 800–807), qty range |
| `shardany[:a-b]` | random stat shard |
| `essence:S[:a-b]` / `essenceany` | slot-S essence (816–819, 851) |

Non-stackable equipment is decorated at drop by `LootRarityRoller`: roll a rarity
from tier-banded weight tables (higher enemy tier → better bands, LEGENDARY only
at tier 12+), then roll two opposing attribute modifiers within the rarity's
magnitude cap (HP/MP scaled ×5). Every drop is a fresh clone with a new UUID.

## Forge & fame store

- **`ServerForgeHelper`** — shards (800–807) combine 10→1 crystal (808–815) via
  `ConsumeShardStackPacket`; `ForgeEnchantPacket` applies a crystal (stat-delta
  enchant, ±1 or ±5 for HP/MP) or sockets a gem (EPIC+, one per item), consuming a
  matching essence (50). `ForgeDisenchantPacket` strips enchantments + gem. All
  gated by rarity slot caps and per-pixel paint uniqueness; persists async.
- **`ServerFameStoreHelper`** — sells dyes (821–828), crystals (808–815), gems
  (830–836, 854–859) at 500 fame each. Fame is the **data service's** source of
  truth: buy calls `POST /data/account/{uuid}/fame/spend?amount=…` atomically;
  the client never deducts fame and inventory slots are validated server-side.
- **`ServerSoulboundHelper`** — tracks per-player damage to each enemy; soulbound
  drops go only to players over a damage-share threshold (anti-leech).

Both UIs open via `InteractTilePacket` on a tile whose `interactionType` is
`forge`/`fame_store`/`potion_storage`.

## Item-scripting SDK

For active/consumable item behavior beyond stats, implement `UseableItemScript`:

```java
public interface UseableItemScript {
    void invokeUseItem(Realm realm, Player p, GameItem item);          // consumables
    void invokeItemAbility(Realm realm, Player p, GameItem item);       // active ability
    default void invokeItemAbility(Realm realm, Player p, GameItem item, Vector2f targetPos) { ... }
    int  getTargetItemId();
    default boolean handles(int itemId) { ... }                         // multi-item scripts
}
```

`UseableItemScriptBase` supplies the `RealmManagerServer`. Scripts are discovered
by reflection at startup (like enemy scripts) and keyed by target item id (or
`handles()` for tiered ranges). Representative implementations:

| Script | Items | Behavior |
|--------|-------|----------|
| `NinjaDashScript` | 298–304 | Collision-aware dash toward cursor (3–5 tiles), 400 ms INVINCIBLE, blade-fury AoE along the path |
| `HuntressTrapScript` | 288–294 | Lob a trap; on arm, detonates on enemy entry (SLOW + damage, 10 s lifetime) |
| `NecromancerSkullScript` | 200–206 | Cursor AoE damage + flat vampiric heal, tier-scaled |
| `SorcererScepterScript` | 270–276 | Chain lightning (12-tile initial, 8-tile chains, ×0.85 decay), true damage, targets scale with WIS |
| `PotionOfMaxStatsScript` | 295 | Sets stats to class max + XP to cap; consumes itself |

Tiered scripts derive tier from `itemId - MIN_ID` to interpolate range/damage and
tint visual effects.
</content>
