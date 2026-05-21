package com.openrealm.game.entity.item.gem;

import com.openrealm.game.entity.Bullet;
import com.openrealm.game.entity.Entity;
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.entity.item.Stats;

/**
 * A gemstone socketed into an EPIC+ item. Each gemstone is a Java class —
 * unique scripted behavior per design — registered with {@link GemstoneRegistry}
 * by its byte typeId. The item stores only {@code gemstoneType} + the paint
 * coords; all behavior lives here.
 *
 * Combat sites invoke the relevant hook per server tick. Defaults are no-ops
 * so each gem only implements what it cares about.
 */
public interface Gemstone {

    byte typeId();

    String displayName();

    String description();

    /** Whether this gem can be socketed into the given item. Used by the forge
     *  to gate (e.g. weapon-only gems, armor-only gems, archetype-specific). */
    boolean canSocketInto(GameItem item);

    /** Visual paint color for the socket pixel (ARGB). The forge uses this when
     *  the player picks a pixel to socket the gem at. */
    int paintColor();

    default void onEquip(Player p, GameItem item) {}

    default void onUnequip(Player p, GameItem item) {}

    /** Per server tick. {@code nowMs} is the realm's tick wall-clock. */
    default void onTick(Player p, GameItem item, long nowMs) {}

    /** Layer on passive stat modifications. Called inside Player.getComputedStats
     *  after additive deltas, before multiplicative scalars resolve. */
    default void modifyStats(Stats out, Player p, GameItem item) {}

    /** Populate the per-shot scratch context (extra projectiles, damage %,
     *  lifesteal %, crit %, piercing, on-hit statuses). Called once per shot
     *  before the projectile loop. */
    default void modifyShot(ShotContext ctx, Player p, GameItem item) {}

    /** Called when the player triggers a basic attack volley. Runs before
     *  projectiles spawn. */
    default void onBasicAttack(Player p, GameItem item) {}

    /** Called when a player projectile hits a target. {@code damageDealt} is
     *  the final damage applied (post mitigation). */
    default void onHitTarget(Player p, Entity target, Bullet b, int damageDealt, GameItem item) {}

    /** Called when the player takes damage. {@code damageTaken} is the final
     *  amount applied to HP after mitigation. {@code attacker} may be null. */
    default void onPlayerHit(Player p, int damageTaken, Entity attacker, GameItem item) {}

    /** Called when the player dies. */
    default void onPlayerKilled(Player p, GameItem item) {}
}
