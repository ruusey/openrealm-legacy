package com.openrealm.net.realm;

import com.openrealm.game.entity.Player;
import com.openrealm.net.Packet;
import com.openrealm.net.server.ClientSession;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Deferred realm-join handoff. Worker threads create these after async
 * authentication completes; the tick thread drains and integrates them
 * before building LoadPackets, guaranteeing no race with the delta logic.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = false)
public class PendingRealmJoin {
    private Realm realm;
    private Player player;
    private String srcIp;
    private ClientSession session;
    private Packet loginResponse;
}
