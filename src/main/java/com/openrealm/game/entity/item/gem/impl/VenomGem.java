package com.openrealm.game.entity.item.gem.impl;

import com.openrealm.game.contants.StatusEffectType;
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.entity.item.gem.Gemstone;
import com.openrealm.game.entity.item.gem.ShotContext;

public class VenomGem implements Gemstone {

    public static final byte TYPE_ID = 4;
    private static final int DURATION_MS = 4000;

    @Override public byte   typeId()       { return TYPE_ID; }
    @Override public String displayName()  { return "Venom Gem"; }
    @Override public String description()  { return "Basic attacks poison targets for " + (DURATION_MS / 1000) + "s."; }
    @Override public int    paintColor()   { return 0xFFA040FF; }

    @Override
    public boolean canSocketInto(GameItem item) {
        return item != null && item.getTargetSlot() == 0;
    }

    @Override
    public void modifyShot(ShotContext ctx, Player p, GameItem item) {
        ctx.addOnHitStatus(StatusEffectType.POISONED.effectId, DURATION_MS);
    }
}
