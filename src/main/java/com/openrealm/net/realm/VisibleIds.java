package com.openrealm.net.realm;

import java.util.HashSet;
import java.util.Set;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Per-viewer visibility snapshot — the set of entity ids visible from a
 * given viewport-center / radius. Used by RealmManagerServer's ledger
 * diff (what to LOAD vs UNLOAD this tick) so we don't recompute the full
 * visible set per packet.
 *
 * Sets are mutable so callers can reuse a single instance across the
 * five entity buckets without re-allocating collections.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class VisibleIds {
    private Set<Long> players    = new HashSet<>();
    private Set<Long> enemies    = new HashSet<>();
    private Set<Long> bullets    = new HashSet<>();
    private Set<Long> containers = new HashSet<>();
    private Set<Long> portals    = new HashSet<>();
}
