package com.openrealm.game.contants;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.entity.item.ItemClass;
import com.openrealm.game.entity.item.WeaponArchetype;
import com.openrealm.game.model.CharacterClassModel;

/**
 * 12 playable classes per the 2026-05-18 design doc rewrite. Three roles per
 * armor family (DPS / Debuffer / Healer) plus one Specialty per family.
 * Cultist is mixed: Robe armor + Light weapons (see character-classes.json).
 *
 * Equip-eligibility is fully data-driven via {@link CharacterClassModel#getAllowedArmorClasses()}
 * and {@link CharacterClassModel#getAllowedWeaponClasses()}. The {@link #canEquip}
 * entry point is the only check now — the legacy targetClass byte path is gone.
 */
public enum CharacterClass {
    // DPS
    BARBARIAN(0),
    ASSASSIN(1),
    WIZARD(2),
    // Debuffer
    DUELIST(3),
    TRAPPER(4),
    NECROMANCER(5),
    // Healer / Buffer
    PALADIN(6),
    DRUID(7),
    PRIEST(8),
    // Specialty
    KNIGHT(9),
    NINJA(10),
    CULTIST(11);

    public static final Map<Integer, CharacterClass> map = new HashMap<>();
    static {
        for (CharacterClass cc : CharacterClass.values()) {
            CharacterClass.map.put(cc.classId, cc);
        }
    }

    public final int classId;

    CharacterClass(int classId) {
        this.classId = classId;
    }

    public static List<CharacterClass> getCharacterClasses() {
        return Arrays.asList(CharacterClass.values()).stream()
                .filter(c -> c.classId >= 0)
                .collect(Collectors.toList());
    }

    public static CharacterClass getPlayerCharacterClass(Player p) {
        return CharacterClass.valueOf(p.getClassId());
    }

    public static CharacterClass valueOf(int classId) {
        return CharacterClass.map.get(classId);
    }

    /**
     * Modular equip check. Looks up the player's class model and tests the
     * item's {@link ItemClass} against the model's allowed lists. Items with
     * {@code itemClass == NONE} cannot be equipped — the legacy targetClass
     * fallback was removed 2026-05-18 per the design rewrite.
     */
    public static boolean canEquip(Player p, GameItem item) {
        if (item == null) return false;
        final ItemClass ic = ItemClass.fromId(item.getItemClass());
        if (ic == ItemClass.NONE) return false;
        if (GameDataManager.CHARACTER_CLASSES == null) return false;
        final CharacterClassModel model = GameDataManager.CHARACTER_CLASSES.get(p.getClassId());
        if (model == null) return false;
        if (!model.allowsItemClass(ic)) return false;
        if (ic.isWeapon()) {
            final WeaponArchetype arch = WeaponArchetype.fromId(item.getArchetypeId());
            if (!model.allowsWeaponArchetype(arch)) return false;
        }
        return true;
    }
}
