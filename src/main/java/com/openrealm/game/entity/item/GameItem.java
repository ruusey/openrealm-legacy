package com.openrealm.game.entity.item;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.openrealm.account.dto.AttributeModifierDto;
import com.openrealm.account.dto.EnchantmentDto;
import com.openrealm.account.dto.GameItemRefDto;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.model.SpriteModel;
import com.openrealm.net.entity.NetGameItemRef;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

@Data
@AllArgsConstructor
@Builder
@Slf4j
@EqualsAndHashCode(callSuper = false)
@JsonIgnoreProperties(ignoreUnknown = true)
public class GameItem extends SpriteModel {
    private int itemId;
    @Builder.Default
    private String uid = UUID.randomUUID().toString();
    private String name;
    private String description;
    private Stats stats;
    private Damage damage;
    private Effect effect;
    private List<Effect> selfEffects;
    private boolean consumable;
    private byte tier;
    private byte targetSlot;
    private byte targetClass;
    private byte fameBonus;
    // Stackable items (shards, essence) merge in inventory up to maxStack
    @Builder.Default
    private boolean stackable = false;
    @Builder.Default
    private int maxStack = 1;
    // Item category — used by forge & stacking. "generic", "shard", "crystal", "essence", "gem"
    @Builder.Default
    private String category = "generic";
    // For shards/crystals: 0=VIT 1=WIS 2=HP 3=MP 4=STR 5=DEF 6=SPD 7=DEX. -1 if N/A.
    @Builder.Default
    private byte forgeStatId = -1;
    // For essence: 0=weapon 1=ability 2=armor 3=ring. -1 if N/A.
    @Builder.Default
    private byte forgeSlotId = -1;
    // Per-instance: current quantity in this stack. 1 for non-stackable items.
    @Builder.Default
    private int stackCount = 1;
    // Per-instance: applied enchantments (max controlled by rarity)
    @Builder.Default
    private List<Enchantment> enchantments = new ArrayList<>();
    // Per-instance: rarity tier (orthogonal to power-tier). Drives crystal-slot
    // count, gem-socket presence, and tooltip color. 0=MUNDANE .. 5=LEGENDARY.
    // See Rarity enum.
    @Builder.Default
    private byte rarity = 0;
    // Per-instance: random attribute affixes rolled on drop.
    @Builder.Default
    private List<AttributeModifier> attributeModifiers = new ArrayList<>();
    // Template field. High-level equipment family. Used together with
    // CharacterClassModel.allowedWeaponClasses / allowedArmorClasses to gate
    // equip.
    @Builder.Default
    private byte itemClass = 0;
    // Template field. Foreign key into weapon-archetypes.json — the byte id
    // of the WeaponArchetype this weapon uses.
    @Builder.Default
    private byte archetypeId = 0;
    // Dual-purpose:
    //   * gem items (category="gem"): identifies which gemstone forging this
    //     consumable produces, keyed against GemstoneRegistry.
    //   * equipment instance: id of the gem socketed into this item (set by
    //     the forge), or 0 for an empty socket. Only EPIC+ items can socket.
    @Builder.Default
    private byte gemstoneType = 0;
    // Per-instance: pixel paint coords for the socketed gem. Only meaningful
    // when gemstoneType > 0 on an equipment instance.
    @Builder.Default
    private byte gemPixelX = 0;
    @Builder.Default
    private byte gemPixelY = 0;
    @Builder.Default
    private int gemPixelColor = 0;
    // Template field. Which player stat this item's damage scales off of.
    // 0=VIT 1=WIS 2=HP 3=MP 4=STR 5=DEF 6=SPD 7=DEX. Defaults to STR.
    @Builder.Default
    private byte scalingStat = 4;

    public GameItem() {
        this.uid = UUID.randomUUID().toString();
        this.stackable = false;
        this.maxStack = 1;
        this.category = "generic";
        this.forgeStatId = -1;
        this.forgeSlotId = -1;
        this.stackCount = 1;
        this.enchantments = new ArrayList<>();
        this.rarity = 0;
        this.attributeModifiers = new ArrayList<>();
        this.itemClass = 0;
        this.archetypeId = 0;
        this.gemstoneType = 0;
        this.gemPixelX = 0;
        this.gemPixelY = 0;
        this.gemPixelColor = 0;
        this.scalingStat = 4;
    }

    @Override
    public GameItem clone() {
        GameItem.GameItemBuilder builder = GameItem.builder().itemId(this.itemId).uid(this.uid).name(this.name)
                .description(this.description).consumable(this.consumable).tier(this.tier).targetSlot(this.targetSlot)
                .targetClass(this.targetClass).fameBonus(this.fameBonus)
                .stackable(this.stackable).maxStack(this.maxStack).category(this.category)
                .forgeStatId(this.forgeStatId).forgeSlotId(this.forgeSlotId).stackCount(this.stackCount)
                .rarity(this.rarity)
                .itemClass(this.itemClass).archetypeId(this.archetypeId)
                .gemstoneType(this.gemstoneType)
                .gemPixelX(this.gemPixelX).gemPixelY(this.gemPixelY).gemPixelColor(this.gemPixelColor)
                .scalingStat(this.scalingStat);

        if (this.damage != null) {
            builder = builder.damage(this.damage.clone());
        }

        if (this.stats != null) {
            builder = builder.stats(this.stats.clone());
        }

        if (this.enchantments != null && !this.enchantments.isEmpty()) {
            final List<Enchantment> copy = new ArrayList<>(this.enchantments.size());
            for (Enchantment e : this.enchantments) {
                copy.add(e == null ? null : e.clone());
            }
            builder = builder.enchantments(copy);
        }

        if (this.attributeModifiers != null && !this.attributeModifiers.isEmpty()) {
            final List<AttributeModifier> copy = new ArrayList<>(this.attributeModifiers.size());
            for (AttributeModifier m : this.attributeModifiers) {
                copy.add(m == null ? null : m.clone());
            }
            builder = builder.attributeModifiers(copy);
        }

        GameItem itemFinal = builder.build();
        itemFinal.setAngleOffset(this.getAngleOffset());
        itemFinal.setRow(this.getRow());
        itemFinal.setCol(this.getCol());
        itemFinal.setSpriteKey(this.getSpriteKey());

        return itemFinal;
    }

    public void applySpriteModel(final SpriteModel model) {
        this.setRow(model.getRow());
        this.setCol(model.getCol());
        this.setAngleOffset(model.getAngleOffset());
        this.setSpriteKey(model.getSpriteKey());
    }

    public GameItemRefDto toGameItemRefDto(int idx) {
        final List<EnchantmentDto> enchDtos;
        if (this.enchantments != null && !this.enchantments.isEmpty()) {
            enchDtos = new ArrayList<>(this.enchantments.size());
            for (Enchantment e : this.enchantments) {
                enchDtos.add(new EnchantmentDto(
                        e.getStatId(), e.getDeltaValue(), e.getPixelX(), e.getPixelY(), e.getPixelColor()));
            }
        } else {
            enchDtos = null;
        }
        final List<AttributeModifierDto> modDtos;
        if (this.attributeModifiers != null && !this.attributeModifiers.isEmpty()) {
            modDtos = new ArrayList<>(this.attributeModifiers.size());
            for (AttributeModifier m : this.attributeModifiers) {
                modDtos.add(new AttributeModifierDto(m.getStatId(), m.getDeltaValue()));
            }
        } else {
            modDtos = null;
        }
        return GameItemRefDto.builder().itemId(this.itemId).slotIdx(idx).itemUuid(this.uid)
                .stackCount(this.stackCount).enchantments(enchDtos)
                .rarity(this.rarity).attributeModifiers(modDtos)
                .gemstoneType(this.gemstoneType)
                .gemPixelX(this.gemPixelX)
                .gemPixelY(this.gemPixelY)
                .gemPixelColor(this.gemPixelColor)
                .build();
    }

    public NetGameItemRef asNetGameItemRef(int idx) {
        return new NetGameItemRef(itemId, idx, this.uid);
    }

    public static GameItem fromGameItemRef(final GameItemRefDto gameItem) {
        final GameItem template = GameDataManager.GAME_ITEMS.get(gameItem.getItemId());
        if (template == null) return null;
        // Clone the template so per-instance state (uid, stackCount, enchantments)
        // does not leak between players sharing the same item definition.
        final GameItem item = template.clone();
        item.setUid(gameItem.getItemUuid());
        if (gameItem.getStackCount() != null) {
            item.setStackCount(gameItem.getStackCount());
        }
        if (gameItem.getEnchantments() != null && !gameItem.getEnchantments().isEmpty()) {
            final List<Enchantment> loaded = new ArrayList<>(gameItem.getEnchantments().size());
            for (EnchantmentDto e : gameItem.getEnchantments()) {
                loaded.add(new Enchantment(
                        e.getStatId() == null ? 0 : e.getStatId(),
                        e.getDeltaValue() == null ? 0 : e.getDeltaValue(),
                        e.getPixelX() == null ? 0 : e.getPixelX(),
                        e.getPixelY() == null ? 0 : e.getPixelY(),
                        e.getPixelColor() == null ? 0 : e.getPixelColor()));
            }
            item.setEnchantments(loaded);
        } else {
            item.setEnchantments(new ArrayList<>());
        }
        if (gameItem.getAttributeModifiers() != null && !gameItem.getAttributeModifiers().isEmpty()) {
            final List<AttributeModifier> mods = new ArrayList<>(gameItem.getAttributeModifiers().size());
            for (AttributeModifierDto m : gameItem.getAttributeModifiers()) {
                mods.add(new AttributeModifier(
                        m.getStatId() == null ? 0 : m.getStatId(),
                        m.getDeltaValue() == null ? 0 : m.getDeltaValue()));
            }
            item.setAttributeModifiers(mods);
        } else {
            item.setAttributeModifiers(new ArrayList<>());
        }
        // Rarity migration: persisted records from the legacy COMMON..MYTHICAL
        // scale (pre-2026-05-18) may carry more enchantments than the new
        // rarity slot count permits — bump rarity so the slot cap fits.
        byte rarity = gameItem.getRarity() == null ? 0 : gameItem.getRarity();
        final int enchCount = item.getEnchantments().size();
        if (enchCount > Rarity.slotsFor(rarity)) {
            int newOrd = rarity;
            while (newOrd < Rarity.values().length - 1
                    && Rarity.slotsFor(newOrd) < enchCount) {
                newOrd++;
            }
            rarity = (byte) newOrd;
            log.info("[Rarity] Migrating legacy item {} ({} crystal enchantments) to {}",
                    item.getName(), enchCount, Rarity.fromOrdinal(rarity).displayName);
        }
        item.setRarity(rarity);
        // Per-instance socketed gem state.
        if (gameItem.getGemstoneType() != null) {
            item.setGemstoneType(gameItem.getGemstoneType());
        }
        if (gameItem.getGemPixelX() != null)     item.setGemPixelX(gameItem.getGemPixelX());
        if (gameItem.getGemPixelY() != null)     item.setGemPixelY(gameItem.getGemPixelY());
        if (gameItem.getGemPixelColor() != null) item.setGemPixelColor(gameItem.getGemPixelColor());
        return item;
    }

    public static GameItem fromGameItemRef(final NetGameItemRef gameItem) {
        final GameItem template = GameDataManager.GAME_ITEMS.get(gameItem.getItemId());
        if (template == null) return null;
        final GameItem item = template.clone();
        item.setUid(gameItem.getItemUuid());
        return item;
    }

    /** Convenience: max enchantments allowed by current rarity. */
    public int getMaxEnchantments() {
        return Rarity.slotsFor(this.rarity);
    }

}
