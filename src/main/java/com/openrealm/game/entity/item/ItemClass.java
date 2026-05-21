package com.openrealm.game.entity.item;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * High-level equipment family. Pairs with {@link com.openrealm.game.model.CharacterClassModel}'s
 * allowed-class lists to decide whether a {@link GameItem} can be equipped by a
 * given character class. Stored on the item template (not per-instance).
 *
 * Armor families and weapon families share this enum so the model can express
 * "Cultist may wear ROBE_ARMOR + LIGHT_WEAPON" without two parallel lists.
 *
 * Wire format: stored on GameItem as a single byte (the enum's id). Keep ids
 * stable — they are persisted in character JSON and on the network.
 */
public enum ItemClass {
    NONE((byte) 0),
    HEAVY_WEAPON((byte) 1),
    LIGHT_WEAPON((byte) 2),
    MAGIC_WEAPON((byte) 3),
    HEAVY_ARMOR((byte) 10),
    LIGHT_ARMOR((byte) 11),
    ROBE_ARMOR((byte) 12),
    /**
     * Universal — any class can equip. Used for utility slots (gauntlets,
     * boots, rings) and any item where class-restriction would just be
     * friction (e.g. a fame-boost trinket). Distinct from NONE so the
     * canEquip path can tell "untagged legacy" (reject) from "intentionally
     * universal" (allow).
     */
    UNIVERSAL((byte) 99);

    public final byte id;

    ItemClass(byte id) {
        this.id = id;
    }

    public boolean isWeapon() {
        return this == HEAVY_WEAPON || this == LIGHT_WEAPON || this == MAGIC_WEAPON;
    }

    public boolean isArmor() {
        return this == HEAVY_ARMOR || this == LIGHT_ARMOR || this == ROBE_ARMOR;
    }

    @JsonCreator
    public static ItemClass fromId(byte id) {
        for (ItemClass c : ItemClass.values()) {
            if (c.id == id) return c;
        }
        return NONE;
    }
}
