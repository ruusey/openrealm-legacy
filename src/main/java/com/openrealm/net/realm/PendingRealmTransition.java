package com.openrealm.net.realm;

import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.Portal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Async realm-transition handoff.
 *
 * Realm generation (terrain, enemies, dungeon layout) happens on a worker
 * thread. When the generation completes, the worker enqueues one of these
 * onto {@link RealmManagerServer#enqueuePendingTransition} and the tick
 * thread drains the queue in {@code processPendingTransitions}: adds the
 * realm, transfers the player, sends map/load packets — all atomically on
 * the tick thread so the per-tick delta logic sees a consistent world.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = false)
public class PendingRealmTransition {
    private Realm generatedRealm;
    private Player player;
    private Realm sourceRealm;
    private Portal usedPortal;
}
