package com.imbet.instruments;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;

import java.util.List;

/**
 * A field upgrade kit for a tornado probe: a reinforced housing or a sensor module.
 *
 * <p>It is fitted where the probe stands rather than assembled on a bench, which is both how a real
 * instrument is upgraded and what keeps a recovered probe's reading intact: a crafting recipe cannot carry a
 * recorded deployment through to its result, so routing the upgrade through the block means a probe that
 * already survived one tornado keeps its numbers while you bolt a heavier case onto it.
 *
 * <p>Each kit is consumed when it is fitted, and each refuses politely when it is not needed - a second
 * housing on an armoured probe, or a fourth module on a grade three head.
 */
public class ProbeUpgradeItem extends Item {
    /** Which kit this is. */
    public enum Kind {
        HOUSING,
        SENSOR
    }

    private final Kind kind;

    public ProbeUpgradeItem(Kind kind, Properties properties) {
        super(properties);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getLevel().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(context.getLevel().getBlockEntity(context.getClickedPos()) instanceof TornadoProbeBlockEntity probe)) {
            return InteractionResult.PASS;
        }
        return fit(context, probe);
    }

    private InteractionResult fit(UseOnContext context, TornadoProbeBlockEntity probe) {
        ProbeUpgrades current = probe.upgrades();
        ProbeUpgrades next = kind == Kind.HOUSING ? current.withHousing() : current.withSensorModule();
        if (next.equals(current)) {
            if (context.getPlayer() != null) {
                context.getPlayer().displayClientMessage(Component.literal(kind == Kind.HOUSING
                        ? "This probe already carries a reinforced housing."
                        : "This probe's sensor head is already at the top grade.").withStyle(ChatFormatting.GRAY), true);
            }
            return InteractionResult.CONSUME;
        }
        probe.fitUpgrades(next);
        context.getLevel().playSound(null, context.getClickedPos(), SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.7F, 1.35F);
        if (context.getPlayer() != null) {
            context.getPlayer().displayClientMessage(Component.literal("Tornado probe fitted: " + next.label()
                    + " (" + next.effectLabel() + ")").withStyle(ChatFormatting.AQUA), false);
        }
        if (context.getPlayer() == null || !context.getPlayer().getAbilities().instabuild) {
            context.getItemInHand().shrink(1);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        ProbeUpgrades sample = kind == Kind.HOUSING ? ProbeUpgrades.NONE.withHousing() : ProbeUpgrades.NONE.withSensorModule();
        tooltip.add(Component.literal(kind == Kind.HOUSING
                ? "Armours a placed tornado probe and anchors it"
                : "Sharpens a placed tornado probe's sensor head").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal(sample.effectLabel()).withStyle(ChatFormatting.DARK_AQUA));
        tooltip.add(Component.literal("Use on a deployed probe").withStyle(ChatFormatting.DARK_GRAY));
    }
}
