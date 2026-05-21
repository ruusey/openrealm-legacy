package com.openrealm.game.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data-driven gameplay shape for a weapon archetype (Sword / Axe / Hammer /
 * Dagger / Bow / Chakram / Tome / Staff / Wand). Loaded from
 * weapon-archetypes.json and looked up by archetype id (matches
 * {@link com.openrealm.game.entity.item.WeaponArchetype#id}).
 *
 * All multiplier fields are relative to a baseline Sword (1.0). Item authors
 * set only the {@code weaponArchetype} byte on a {@link com.openrealm.game.entity.item.GameItem};
 * the rest of the gameplay shape (scaling stat, attack speed, range, piercing,
 * fan, etc.) lives on this model so balance tweaks land via push-data.bat
 * rather than a server redeploy.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class WeaponArchetypeModel {
    /** Matches {@link com.openrealm.game.entity.item.WeaponArchetype#id}. */
    private byte id;
    /** Display name (Sword, Axe, etc.). Also used for tooltip captions. */
    private String name;
    /** "HEAVY" / "LIGHT" / "MAGIC" / "NONE" — informational; equip gating
     *  still goes through ItemClass + CharacterClassModel. */
    private String family;

    /** Default scaling stat for weapons of this archetype. 0..7, see
     *  {@link com.openrealm.game.entity.item.GameItem#scalingStat}. */
    @Builder.Default
    private byte scalingStat = 4;

    /** Fire-rate multiplier vs Sword (1.00 baseline). Larger = faster. */
    @Builder.Default
    private float attackSpeedMul = 1.0f;
    /** Base damage roll multiplier vs the item's declared damage range. */
    @Builder.Default
    private float damageMul = 1.0f;
    /** Projectile range multiplier vs the projectile-group declared range. */
    @Builder.Default
    private float rangeMul = 1.0f;
    /** Whether bullets pierce — applied to every shot of this archetype.
     *  Layers on top of per-projectile flags from the projectile-group. */
    @Builder.Default
    private boolean piercing = false;
    /** Extra bullets fanned around the cursor per shot. 1 = standard single
     *  shot. 3 = three-shot fan. Combines additively with the
     *  PROJECTILE_COUNT gem on top. */
    @Builder.Default
    private int projectileCount = 1;
    /** Fan-spread in radians applied when projectileCount > 1. */
    @Builder.Default
    private float spreadRad = 0.10f;
}
