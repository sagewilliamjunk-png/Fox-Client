package dev.kitsune.client.module.combat;

import dev.kitsune.client.hud.NotificationManager;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.component.ItemAttributeModifiers;

import java.util.Set;

/**
 * Flashes a one-shot toast when the player enters combat (takes damage from
 * another entity) while holding a non-weapon item. Settings let the user
 * decide whether to warn once per combat bout (default) or every tick while
 * the condition holds, and tune how sensitive the "combat" trigger is.
 */
public class WeaponSwapReminderModule extends Module {

    /**
     * Swords no longer have their own {@code SwordItem} class in 1.21 — they're
     * materially data-driven. Hard-coding the vanilla sword set is the
     * pragmatic check; modded swords would need to tag themselves via
     * whatever future {@code DataComponents.WEAPON} shape lands.
     */
    private static final Set<Item> SWORDS = Set.of(
            Items.WOODEN_SWORD, Items.STONE_SWORD, Items.IRON_SWORD,
            Items.GOLDEN_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD);

    private final BooleanSetting onceMode    = addSetting(new BooleanSetting("Once Per Combat", true));
    private final SliderSetting  graceTicks  = addSetting(new SliderSetting("Combat Window (ticks)", 80, 20, 200, 10));
    // Half-hearts threshold — only warn when the hit took at least N HP. Default 1
    // (any damage) preserves the v1.1 behaviour; raising it filters out tiny
    // poison ticks / 1-damage projectile grazes from quiet AFK damage.
    private final SliderSetting  damageThreshold = addSetting(new SliderSetting("Damage Threshold (HP)", 1, 0, 20, 1));
    private final BooleanSetting warnFood    = addSetting(new BooleanSetting("Warn on Food",   true));
    private final BooleanSetting warnTool    = addSetting(new BooleanSetting("Warn on Tools",  true));
    private final BooleanSetting warnEmpty   = addSetting(new BooleanSetting("Warn on Empty Hand", false));

    private int prevHurtTime = 0;
    private float prevHealth = -1f;
    private int inCombatTicks = 0; // counts down; 0 = not in combat
    private boolean warnedThisBout = false;

    public WeaponSwapReminderModule() {
        super("Weapon Swap Reminder",
              "Reminds you to swap to a weapon when you take damage holding a tool/food",
              Category.COMBAT);
    }

    @Override
    protected void onDisable() {
        prevHurtTime = 0;
        prevHealth = -1f;
        inCombatTicks = 0;
        warnedThisBout = false;
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return;

        // Detect hurt edge + measured damage. Without a damage measurement,
        // poison/wither ticks would qualify; with one we can ignore them.
        int ht = p.hurtTime;
        boolean justHurt = ht > prevHurtTime;
        prevHurtTime = ht;
        float health = p.getHealth();
        float dmg = (prevHealth >= 0f && health < prevHealth) ? (prevHealth - health) : 0f;
        prevHealth = health;

        int threshold = damageThreshold.get().intValue();
        boolean qualifyingHit = justHurt && dmg >= threshold;
        // If lastHurtByMob is set (entity attack), accept it regardless of
        // measured damage — that signal is fully reliable from the server.
        boolean inCombatTrigger = qualifyingHit || p.getLastHurtByMob() != null;

        if (inCombatTrigger) {
            // Reset (or start) combat window
            if (inCombatTicks == 0) warnedThisBout = false;
            inCombatTicks = graceTicks.get().intValue();
        } else if (inCombatTicks > 0) {
            inCombatTicks--;
            if (inCombatTicks == 0) warnedThisBout = false;
        }

        if (inCombatTicks <= 0) return;
        if (onceMode.get() && warnedThisBout) return;

        ItemStack held = p.getMainHandItem();
        if (!shouldWarn(held)) return;

        NotificationManager.show("\u2694 Switch to a weapon?",
                NotificationManager.Type.WARNING);
        warnedThisBout = true;
    }

    private boolean shouldWarn(ItemStack held) {
        if (held == null || held.isEmpty()) return warnEmpty.get();
        var item = held.getItem();
        // Real weapons — never warn
        if (SWORDS.contains(item))        return false;
        if (item instanceof AxeItem)      return false;
        if (item instanceof TridentItem)  return false;
        if (item instanceof BowItem)      return false;
        if (item instanceof CrossbowItem) return false;
        if (item == Items.MACE)           return false;
        if (item == Items.SHIELD)         return false;

        // Modded weapons — if the item declares an ATTACK_DAMAGE attribute
        // modifier, treat it as a weapon even though we can't match its
        // class. Catches modded swords, spears, daggers, halberds, etc.
        if (hasAttackDamageModifier(held)) return false;

        // Consumables (food / potion) — warn if setting on
        if (held.has(DataComponents.FOOD)) {
            return warnFood.get();
        }
        if (item == Items.POTION || item == Items.SPLASH_POTION || item == Items.LINGERING_POTION) {
            return warnFood.get();
        }

        // Tools (anything with a digger behaviour) — warn if setting on.
        // Covers pickaxe, shovel, hoe, fishing rod, shears, flint+steel, etc.
        // We keep the check simple: anything that has a max damage and isn't
        // a real weapon we already excluded above counts as a tool.
        if (held.getMaxDamage() > 0) return warnTool.get();

        // Anything else (blocks, materials) treated as "non-weapon"
        return warnTool.get();
    }

    /**
     * True if the stack declares a positive ATTACK_DAMAGE attribute modifier
     * for any slot. Wrapped in try/catch because some modded items have been
     * known to ship malformed component data and we'd rather not warn.
     */
    private static boolean hasAttackDamageModifier(ItemStack stack) {
        try {
            ItemAttributeModifiers mods = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
            if (mods == null) return false;
            for (ItemAttributeModifiers.Entry e : mods.modifiers()) {
                if (e.attribute() == Attributes.ATTACK_DAMAGE
                        && e.modifier().amount() > 0.0) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }
}
