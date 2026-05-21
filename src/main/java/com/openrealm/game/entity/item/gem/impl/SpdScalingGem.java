package com.openrealm.game.entity.item.gem.impl;

import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.entity.item.Stats;
import com.openrealm.game.entity.item.gem.Gemstone;

public class SpdScalingGem implements Gemstone {

    public static final byte TYPE_ID = 9;
    private static final int PCT = 10;

    @Override public byte   typeId()       { return TYPE_ID; }
    @Override public String displayName()  { return "Swift Scaling Gem"; }
    @Override public String description()  { return "+" + PCT + "% Speed while equipped."; }
    @Override public int    paintColor()   { return 0xFF5FD06F; }

    @Override
    public boolean canSocketInto(GameItem item) {
        return item != null && item.getTargetSlot() >= 0;
    }

    @Override
    public void modifyStats(Stats out, Player p, GameItem item) {
        out.setSpd((short) (out.getSpd() + (out.getSpd() * PCT) / 100));
    }
}
