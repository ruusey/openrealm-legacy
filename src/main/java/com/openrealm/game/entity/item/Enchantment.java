package com.openrealm.game.entity.item;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A stat-delta crystal painted onto a sprite pixel. Rarity-gated: each rarity
 * permits N enchantments per item ({@link Rarity#slotsFor}). Behavioral
 * (non-stat) effects live on {@link com.openrealm.game.entity.item.gem.Gemstone}
 * implementations, not here — see GameItem.gemstoneType.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Enchantment {
    public static final byte STAT_VIT = 0;
    public static final byte STAT_WIS = 1;
    public static final byte STAT_HP = 2;
    public static final byte STAT_MP = 3;
    public static final byte STAT_STR = 4;
    public static final byte STAT_DEF = 5;
    public static final byte STAT_SPD = 6;
    public static final byte STAT_DEX = 7;

    /** 0..7 — see constants. */
    private byte statId;
    /** Signed magnitude applied to the named stat. */
    private byte deltaValue;
    /** Painted pixel coords on the item sprite. */
    private byte pixelX;
    private byte pixelY;
    /** ARGB color painted at (pixelX, pixelY). */
    private int pixelColor;

    public Enchantment clone() {
        return new Enchantment(this.statId, this.deltaValue, this.pixelX, this.pixelY, this.pixelColor);
    }
}
