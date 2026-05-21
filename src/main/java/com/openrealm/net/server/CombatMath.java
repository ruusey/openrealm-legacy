package com.openrealm.net.server;

import com.openrealm.game.contants.GlobalConstants;
import com.openrealm.game.entity.item.gem.ShotContext;

/**
 * Pure combat math — no realm/player/state access. Lives here so the
 * basic-attack path (ServerGameLogic) and the ability path (RealmManagerServer)
 * stop carrying duplicate implementations of the same formulas.
 *
 * Every method takes its inputs by value and returns a result. Anything that
 * touches realm state (status applies, broadcast packets, HP mutations) stays
 * with its caller.
 */
public final class CombatMath {

    private CombatMath() {}

    /**
     * Apply per-shot damage mods carried in {@link ShotContext}: damage-percent
     * uplift, crit-chance roll (doubles the result on success). Clamped to
     * [0, Short.MAX_VALUE] so a chain of bonuses can't underflow or overflow.
     */
    public static short applyShotDamageMods(short base, ShotContext ctx) {
        int dmg = base;
        if (ctx.getDamagePct() != 0) dmg = dmg + (dmg * ctx.getDamagePct()) / 100;
        if (ctx.getCritChancePct() > 0) {
            final int roll = (int) (Math.random() * 100);
            if (roll < ctx.getCritChancePct()) dmg = dmg * 2;
        }
        if (dmg < 0) dmg = 0;
        if (dmg > Short.MAX_VALUE) dmg = Short.MAX_VALUE;
        return (short) dmg;
    }

    /**
     * 15% minimum-damage floor. Used by both enemy-hit and player-hit paths so
     * weak shots still chip well-armored targets. Ceiling + min(1) so the
     * short cast doesn't truncate 0.75 to 0.
     */
    public static short minDamageFloor(short raw) {
        return (short) Math.max(1, Math.ceil(raw * 0.15));
    }

    /**
     * Enemy outgoing-damage multiplier as a function of realm difficulty.
     * Difficulty <= threshold (varies by overworld vs. dungeon instance) is
     * 1.0×; past the threshold it ramps linearly up to a knee, then a softer
     * slope continues to a cap. Tuning lives in {@link GlobalConstants}.
     */
    public static float difficultyDamageMult(final float difficulty, final boolean isDungeon) {
        final float threshold = isDungeon
                ? GlobalConstants.DAMAGE_SCALE_DUNGEON_MIN_DIFFICULTY
                : GlobalConstants.DAMAGE_SCALE_MIN_DIFFICULTY;
        if (difficulty <= threshold) return 1.0f;
        final float knee = GlobalConstants.DAMAGE_SCALE_KNEE_DIFFICULTY;
        final float slope = GlobalConstants.DAMAGE_SCALE_PER_LEVEL;
        final float slopeAfter = GlobalConstants.DAMAGE_SCALE_PER_LEVEL_AFTER_KNEE;
        float mult;
        if (difficulty <= knee) {
            mult = 1.0f + slope * (difficulty - threshold);
        } else {
            mult = 1.0f + slope * (knee - threshold) + slopeAfter * (difficulty - knee);
        }
        return Math.min(mult, GlobalConstants.DAMAGE_SCALE_CAP);
    }
}
