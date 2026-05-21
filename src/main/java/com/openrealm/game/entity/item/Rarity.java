package com.openrealm.game.entity.item;

/**
 * Per-instance item rarity. Drives crystal-enchantment slot count, gem-socket
 * presence, tooltip color, and the magnitude / count bands for randomly-rolled
 * AttributeModifier prefixes at drop time.
 *
 * Ordinal order is part of the wire format and the persisted character JSON —
 * if you change the order, write a migration in {@code GameItem.fromGameItemRef}.
 *
 * 2026-05-18: replaced legacy COMMON..MYTHICAL with MUNDANE..LEGENDARY per
 * the new class/item system design doc. MUNDANE accepts no crystal slots;
 * EPIC+ also receives a (separate) gem socket.
 */
public enum Rarity {
    MUNDANE  (0, "Mundane",   0xFF8A8A8A),
    COMMON   (1, "Common",    0xFFB8B8B8),
    UNCOMMON (2, "Uncommon",  0xFF4FC85A),
    RARE     (3, "Rare",      0xFF3F8CFF),
    EPIC     (4, "Epic",      0xFFB04FE0),
    LEGENDARY(5, "Legendary", 0xFFFF9418);

    /** Number of crystal-enchantment slots (separate from the gem socket). */
    public final int crystalSlots;
    public final String displayName;
    public final int color;

    Rarity(int crystalSlots, String displayName, int color) {
        this.crystalSlots = crystalSlots;
        this.displayName = displayName;
        this.color = color;
    }

    public static Rarity fromOrdinal(int ord) {
        final Rarity[] vals = Rarity.values();
        if (ord < 0) return MUNDANE;
        if (ord >= vals.length) return vals[vals.length - 1];
        return vals[ord];
    }

    public static int slotsFor(int ord) {
        return fromOrdinal(ord).crystalSlots;
    }

    /** Only EPIC+ items have the high-impact gem socket (per design doc). */
    public boolean hasGemSocket() {
        return this == EPIC || this == LEGENDARY;
    }

    public static boolean hasGemSocket(int ord) {
        return fromOrdinal(ord).hasGemSocket();
    }
}
