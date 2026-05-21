package com.openrealm.game.entity.item;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Concrete weapon shape. Drives base damage / attack-speed / range curves and
 * tooltip wording. Layered on top of {@link ItemClass}: every weapon has both
 * (e.g. a Sword is HEAVY_WEAPON + SWORD).
 *
 * Three archetypes per armor family, mirroring the game-design doc:
 *   Heavy (str):  Sword, Axe, Hammer
 *   Light (dex):  Dagger, Bow, Chakram
 *   Magic (wis):  Tome, Staff, Wand
 *
 * Wire format: stored on GameItem as a single byte. NONE is the catch-all for
 * non-weapon items so the field can be unset cleanly.
 */
public enum WeaponArchetype {
    NONE((byte) 0),

    SWORD((byte) 1),
    AXE((byte) 2),
    HAMMER((byte) 3),

    DAGGER((byte) 10),
    BOW((byte) 11),
    CHAKRAM((byte) 12),

    TOME((byte) 20),
    STAFF((byte) 21),
    WAND((byte) 22);

    public final byte id;

    WeaponArchetype(byte id) {
        this.id = id;
    }

    public ItemClass itemClass() {
        switch (this) {
            case SWORD: case AXE: case HAMMER:    return ItemClass.HEAVY_WEAPON;
            case DAGGER: case BOW: case CHAKRAM:  return ItemClass.LIGHT_WEAPON;
            case TOME: case STAFF: case WAND:     return ItemClass.MAGIC_WEAPON;
            default: return ItemClass.NONE;
        }
    }

    @JsonCreator
    public static WeaponArchetype fromId(byte id) {
        for (WeaponArchetype a : WeaponArchetype.values()) {
            if (a.id == id) return a;
        }
        return NONE;
    }
}
