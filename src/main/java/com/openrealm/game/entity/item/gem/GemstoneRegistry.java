package com.openrealm.game.entity.item.gem;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.entity.item.gem.impl.CritGem;
import com.openrealm.game.entity.item.gem.impl.FrostGem;
import com.openrealm.game.entity.item.gem.impl.LifestealGem;
import com.openrealm.game.entity.item.gem.impl.MultishotGem;
import com.openrealm.game.entity.item.gem.impl.PowerGem;
import com.openrealm.game.entity.item.gem.impl.SpdScalingGem;
import com.openrealm.game.entity.item.gem.impl.ThornsGem;
import com.openrealm.game.entity.item.gem.impl.VenomGem;
import com.openrealm.game.entity.item.gem.impl.WisScalingGem;

/**
 * Static registry of all known {@link Gemstone} implementations, keyed by
 * their byte typeId. Concrete classes register themselves at class-load time.
 * gemstoneType=0 means "no gem" — {@link #get} returns null for it.
 */
public final class GemstoneRegistry {

    private static final Map<Byte, Gemstone> BY_ID = new HashMap<>();

    static {
        register(new LifestealGem());
        register(new CritGem());
        register(new MultishotGem());
        register(new VenomGem());
        register(new FrostGem());
        register(new ThornsGem());
        register(new PowerGem());
        register(new WisScalingGem());
        register(new SpdScalingGem());
    }

    private GemstoneRegistry() {}

    public static void register(Gemstone g) {
        if (g == null) return;
        if (g.typeId() == 0) {
            throw new IllegalArgumentException("Gemstone typeId 0 is reserved for 'no gem'");
        }
        BY_ID.put(g.typeId(), g);
    }

    /** Null if no gem (gemstoneType == 0) or unknown id. */
    public static Gemstone get(byte gemstoneType) {
        if (gemstoneType == 0) return null;
        return BY_ID.get(gemstoneType);
    }

    /** Convenience: resolves the gem socketed into an item. Null if empty. */
    public static Gemstone forItem(GameItem item) {
        if (item == null) return null;
        return get(item.getGemstoneType());
    }

    public static Collection<Gemstone> all() {
        return BY_ID.values();
    }
}
