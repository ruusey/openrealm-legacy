package com.openrealm.game.entity.item.gem.impl;

import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.entity.item.gem.Gemstone;
import com.openrealm.game.entity.item.gem.ShotContext;

public class CritGem implements Gemstone {

    public static final byte TYPE_ID = 2;
    private static final int CRIT_PCT = 15;

    @Override public byte   typeId()       { return TYPE_ID; }
    @Override public String displayName()  { return "Crit Gem"; }
    @Override public String description()  { return "+" + CRIT_PCT + "% chance to deal double damage."; }
    @Override public int    paintColor()   { return 0xFFFFA000; }

    @Override
    public boolean canSocketInto(GameItem item) {
        return item != null && item.getTargetSlot() == 0;
    }

    @Override
    public void modifyShot(ShotContext ctx, Player p, GameItem item) {
        ctx.setCritChancePct(ctx.getCritChancePct() + CRIT_PCT);
    }
}
