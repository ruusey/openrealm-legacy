package com.openrealm.game.entity.item.gem.impl;

import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.entity.item.Stats;
import com.openrealm.game.entity.item.gem.Gemstone;

public class WisScalingGem implements Gemstone {

    public static final byte TYPE_ID = 8;
    private static final int PCT = 10;

    @Override public byte   typeId()       { return TYPE_ID; }
    @Override public String displayName()  { return "Wisdom Scaling Gem"; }
    @Override public String description()  { return "+" + PCT + "% Wisdom while equipped."; }
    @Override public int    paintColor()   { return 0xFF3F6CFF; }

    @Override
    public boolean canSocketInto(GameItem item) {
        return item != null && item.getTargetSlot() >= 0;
    }

    @Override
    public void modifyStats(Stats out, Player p, GameItem item) {
        out.setWis((short) (out.getWis() + (out.getWis() * PCT) / 100));
    }
}
