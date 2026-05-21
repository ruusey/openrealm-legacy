package com.openrealm.game.entity.item.gem.impl;

import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.entity.item.gem.Gemstone;
import com.openrealm.game.entity.item.gem.ShotContext;

public class PowerGem implements Gemstone {

    public static final byte TYPE_ID = 7;
    private static final int DAMAGE_PCT = 15;

    @Override public byte   typeId()       { return TYPE_ID; }
    @Override public String displayName()  { return "Crushing Gem"; }
    @Override public String description()  { return "+" + DAMAGE_PCT + "% basic attack damage."; }
    @Override public int    paintColor()   { return 0xFFFF4040; }

    @Override
    public boolean canSocketInto(GameItem item) {
        return item != null && item.getTargetSlot() == 0;
    }

    @Override
    public void modifyShot(ShotContext ctx, Player p, GameItem item) {
        ctx.setDamagePct(ctx.getDamagePct() + DAMAGE_PCT);
    }
}
