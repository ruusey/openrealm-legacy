package com.openrealm.game.entity.item.gem.impl;

import com.openrealm.game.entity.Bullet;
import com.openrealm.game.entity.Entity;
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.entity.item.gem.Gemstone;

public class LifestealGem implements Gemstone {

    public static final byte TYPE_ID = 1;
    private static final int HEAL_PCT = 8;

    @Override public byte   typeId()       { return TYPE_ID; }
    @Override public String displayName()  { return "Vampiric Gem"; }
    @Override public String description()  { return "Heals " + HEAL_PCT + "% of damage dealt on basic attack hits."; }
    @Override public int    paintColor()   { return 0xFF40FF80; }

    @Override
    public boolean canSocketInto(GameItem item) {
        return item != null && item.getTargetSlot() == 0;
    }

    @Override
    public void onHitTarget(Player p, Entity target, Bullet b, int damageDealt, GameItem item) {
        if (damageDealt <= 0) return;
        final int heal = Math.max(1, (damageDealt * HEAL_PCT) / 100);
        final int max = p.getComputedStats().getHp();
        p.setHealth(Math.min(max, p.getHealth() + heal));
    }
}
