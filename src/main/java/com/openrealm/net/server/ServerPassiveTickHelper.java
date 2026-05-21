package com.openrealm.net.server;

import com.openrealm.game.contants.EntityType;
import com.openrealm.game.contants.StatusEffectType;
import com.openrealm.game.contants.TextEffect;
import com.openrealm.game.entity.Enemy;
import com.openrealm.game.entity.Player;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.ability.PassiveAbility;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerServer;

/**
 * Per-tick aura refresh for class passives that pulse a status onto nearby
 * players or enemies. Extracted from {@link RealmManagerServer}'s main tick
 * loop so combat-adjacent passive behavior lives next to its peers in
 * {@code com.openrealm.net.server} instead of bloating the realm manager.
 *
 * The whole block was a single static-shape responsibility (no instance state),
 * so it slots into a static helper cleanly — same pattern as
 * {@link ServerCommandHandler} / {@link ServerItemHelper}.
 */
public final class ServerPassiveTickHelper {

    /** WIS / N — the per-stat divisor used by Guiding Light's EMPOWERED bonus. */
    private static final int GUIDING_LIGHT_WIS_DIVISOR = 5;

    /** Player-target aura radius in pixels (≈5 tiles). */
    private static final float AURA_RADIUS    = 320f;
    private static final float AURA_RADIUS_SQ = AURA_RADIUS * AURA_RADIUS;

    /** Necromancer Necrotic Aura uses a tighter radius (~1.6 tiles). */
    private static final float NECRO_AURA_R    = AURA_RADIUS / 3f;
    private static final float NECRO_AURA_SQ   = NECRO_AURA_R * NECRO_AURA_R;

    /** Refresh duration — short enough that the buff decays cleanly when the
     *  condition (in-range, low HP, etc.) stops holding. */
    private static final long REFRESH_MS = 800L;

    /** Called every server tick from {@link RealmManagerServer}. Internally
     *  gates to every 8 ticks (~125ms) — no need to refresh auras at 64Hz
     *  when statuses last 800ms. */
    public static void tick(final RealmManagerServer mgr, final long tickCounter) {
        if (tickCounter % 8 != 0) return;
        for (final Realm realm : mgr.getRealms().values()) {
            if (realm.getPlayers().isEmpty()) continue;
            for (final Player p : realm.getPlayers().values()) {
                final PassiveAbility pa = p.getClassPassive();
                if (pa == null) continue;
                final int pid = pa.getId();
                if (pid == 11003) {
                    refreshProtectiveAura(realm, p);
                } else if (pid == 11006) {
                    refreshHolyResolve(p);
                } else if (pid == 11015 || pid == 12006) {
                    refreshGuidingLight(mgr, realm, p);
                } else if (pid == 11008) {
                    refreshNecroticAura(realm, p);
                }
            }
        }
    }

    /** Priest Protective Aura (11003): PROTECTED on every player in range,
     *  including the priest themselves. */
    private static void refreshProtectiveAura(final Realm realm, final Player p) {
        final Vector2f pc = p.getPos().clone(p.getSize() / 2, p.getSize() / 2);
        for (final Player ally : realm.getPlayers().values()) {
            final Vector2f ac = ally.getPos().clone(ally.getSize() / 2, ally.getSize() / 2);
            final float dx = ac.x - pc.x, dy = ac.y - pc.y;
            if (dx * dx + dy * dy > AURA_RADIUS_SQ) continue;
            ally.addEffect(StatusEffectType.PROTECTED, REFRESH_MS);
        }
    }

    /** Paladin Holy Resolve (11006): BRACED while HP &lt; 50% of computed max.
     *  ARMORED/BRACED in Player.getComputedStats is mutually exclusive
     *  (ARMORED &gt; BRACED), so this won't downgrade a Brace cast. */
    private static void refreshHolyResolve(final Player p) {
        final int maxHp = p.getComputedStats() != null ? p.getComputedStats().getHp() : p.getHealth();
        if (maxHp > 0 && p.getHealth() * 2 < maxHp) {
            p.addEffect(StatusEffectType.BRACED, REFRESH_MS);
        }
    }

    /** "Guiding Light" — legacy Heavy Buffer (11015) and rewrite-era Paladin
     *  (12006) share the same mechanic: refresh EMPOWERED_STR and EMPOWERED_DEX
     *  on every party member within range INCLUDING the buffer itself.
     *  Magnitude = floor(caster.WIS / GUIDING_LIGHT_WIS_DIVISOR).
     *
     *  Two parallel statuses so the UI stacks two icons above the player
     *  ("Atk+10" and "Dex+10") instead of one combined label. */
    private static void refreshGuidingLight(final RealmManagerServer mgr, final Realm realm, final Player p) {
        final Vector2f pc = p.getPos().clone(p.getSize() / 2, p.getSize() / 2);
        final short bonus = (short) Math.max(0,
                p.getComputedStats().getWis() / GUIDING_LIGHT_WIS_DIVISOR);
        for (final Player ally : realm.getPlayers().values()) {
            final Vector2f ac = ally.getPos().clone(ally.getSize() / 2, ally.getSize() / 2);
            final float dx = ac.x - pc.x, dy = ac.y - pc.y;
            if (dx * dx + dy * dy > AURA_RADIUS_SQ) continue;
            // Floating "+N STR" / "+N DEX" text — only on the first
            // application (transition from no-EMPOWERED to has-EMPOWERED).
            // Without this guard we'd flood the screen with text every 125ms
            // tick while standing inside the aura.
            if (bonus > 0 && !ally.hasEffect(StatusEffectType.EMPOWERED_STR)) {
                mgr.broadcastTextEffect(realm, EntityType.PLAYER, ally,
                        TextEffect.HEAL, "+" + bonus + " STR");
                mgr.broadcastTextEffect(realm, EntityType.PLAYER, ally,
                        TextEffect.HEAL, "+" + bonus + " DEX");
            }
            ally.addEffect(StatusEffectType.EMPOWERED_STR, REFRESH_MS, bonus);
            ally.addEffect(StatusEffectType.EMPOWERED_DEX, REFRESH_MS, bonus);
        }
    }

    /** Necromancer Necrotic Aura (11008): refresh CURSED (1.25× dmg taken) on
     *  every enemy within the tighter necro radius. Forces the necro to kite
     *  into melee to apply it instead of camping a pack from across the screen. */
    private static void refreshNecroticAura(final Realm realm, final Player p) {
        final Vector2f pc = p.getPos().clone(p.getSize() / 2, p.getSize() / 2);
        for (final Enemy enemy : realm.getEnemies().values()) {
            if (enemy == null || enemy.getDeath()) continue;
            if (enemy.hasEffect(StatusEffectType.INVINCIBLE)) continue;
            final float dx = enemy.getPos().x - pc.x;
            final float dy = enemy.getPos().y - pc.y;
            if (dx * dx + dy * dy > NECRO_AURA_SQ) continue;
            enemy.addEffect(StatusEffectType.CURSED, REFRESH_MS);
        }
    }

    private ServerPassiveTickHelper() {}
}
