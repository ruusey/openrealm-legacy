package com.openrealm.game.entity.item.gem;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * Mutable per-shot scratch struct. A Gemstone's {@link Gemstone#modifyShot}
 * hook mutates fields here; the firing site reads them once to drive the
 * projectile loop. Replaces the old CombatModifiers static-builder pattern —
 * gems now own their effect implementation directly.
 */
@Data
public class ShotContext {
    /** Extra projectiles fired in addition to the archetype's group. */
    private int extraProjectiles = 0;
    /** Damage scalar in percent (added to 100). 15 → ×1.15. */
    private int damagePct = 0;
    /** Crit chance (0..100). Damage doubles on a crit roll. */
    private int critChancePct = 0;
    /** Lifesteal percent of damage dealt. */
    private int lifestealPct = 0;
    /** Additional spread between fanned bullets (radians, additive). */
    private float extraSpread = 0f;
    /** Adds PASS_THROUGH_ENEMIES to spawned bullets. */
    private boolean piercing = false;
    /** On-hit statuses applied by every bullet from this shot. */
    private final List<OnHitStatus> onHitStatuses = new ArrayList<>();

    public void addOnHitStatus(short effectId, int durationMs) {
        this.onHitStatuses.add(new OnHitStatus(effectId, durationMs));
    }

    public boolean hasAny() {
        return extraProjectiles > 0 || damagePct != 0 || critChancePct > 0
                || lifestealPct > 0 || piercing || extraSpread != 0f
                || !onHitStatuses.isEmpty();
    }

    @Data
    public static final class OnHitStatus {
        private final short effectId;
        private final int durationMs;
    }
}
