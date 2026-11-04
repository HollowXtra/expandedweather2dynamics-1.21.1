package com.imbet.drone;

import com.imbet.Config;
import com.imbet.registry.ModContent;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/**
 * The drone in your hand: deploy it, fly it, and get it back with its log.
 *
 * <p>One item does all three because they are one action from the player's side - "the drone":
 *
 * <ul>
 *   <li><b>Use with no drone out</b> launches a new one two blocks ahead and links to it.</li>
 *   <li><b>Use while your drone is out</b> recalls it, and it comes home with the flight log its
 *       instruments recorded written onto this very item. Recall only works inside the link range, so a
 *       drone lost beyond that is lost for real until you walk to it - which is the interesting decision,
 *       because the storm is usually in the other direction.</li>
 * </ul>
 *
 * <p><b>The item never leaves your hand.</b> That is not a flourish, it is the whole mechanic: the link
 * between a pilot and their airframe is held by the pilot holding this item, and the server drops the link
 * the tick it sees an empty hand (see {@code WeatherDroneEntity#tick}). A launch that consumed the stack
 * therefore took the controls away in the same tick it handed them over, and only for players without
 * instabuild - which is exactly the shape of the bug, a drone that flew fine in creative and let go of a
 * survival player the instant it was launched. Nothing is spent by launching it, and nothing is minted by
 * recalling it: one item in the hand is one drone, in every game mode.
 *
 * <p>The log rides on the stack as custom data rather than as a component of its own, because it is a
 * handful of doubles with no behaviour: the item reads them back for a tooltip, and a probe's reading has
 * already established that pattern in this mod.
 */
public class WeatherDroneItem extends Item {
    /** Use a drone within this many blocks and the path is a link, not a launch. */
    private static final double RELINK_SEARCH_BLOCKS = 320.0D;
    private static final String LOG_KEY = "DroneLog";

    public WeatherDroneItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) {
            return InteractionResultHolder.success(stack);
        }
        WeatherDroneEntity existing = findDrone(level, player);
        if (existing != null) {
            // Recalling is the useful half: it brings the log home and saves walking.
            if (existing.linked() || existing.isPilotedBy(player)) {
                // The drone comes home onto the item that was flying it, rather than onto a fresh stack that
                // replaces it. Same rule, read from the other end: the stack holding the link is the stack
                // the log belongs on. It also keeps the arithmetic of the hand honest in every game mode -
                // one drone item went out, one drone item with a log is in your hand - where the old swap
                // added an item for a creative player and, with a full inventory, dropped the drone it had
                // just recovered on the ground.
                attachLog(stack, existing.flightLog());
                existing.discard();
                level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8F, 1.2F);
                player.displayClientMessage(Component.literal("Drone recalled - its flight log is on the drone in your hand"), false);
                return InteractionResultHolder.success(stack);
            }
            existing.setPilot(player);
            player.displayClientMessage(Component.literal("Re-linked to your drone"), true);
            return InteractionResultHolder.success(stack);
        }
        WeatherDroneEntity drone = spawn(level, player);
        if (drone == null) {
            return InteractionResultHolder.fail(stack);
        }
        // The stack stays where it is, and it has to: deploying the airframe is not spending the item, it is
        // handing the link to the airframe while the transmitter stays in your hand. Consuming it here - which
        // is what this did for anyone without instabuild, and only for them - left the pilot with an empty
        // hand, and WeatherDroneEntity drops the link the tick it sees that, so the drone released its pilot
        // one tick after launch. Creative never caught it because instabuild skipped the shrink.
        level.playSound(null, drone.getX(), drone.getY(), drone.getZ(), SoundEvents.BEE_LOOP, SoundSource.NEUTRAL, 0.5F, 1.6F);
        player.displayClientMessage(Component.literal("Drone launched and linked - use the item again to recall it"), false);
        return InteractionResultHolder.success(stack);
    }

    private WeatherDroneEntity spawn(Level level, Player player) {
        Vec3 look = player.getLookAngle();
        Vec3 at = player.position().add(look.x * 2.0D, 0.4D, look.z * 2.0D);
        WeatherDroneEntity drone = ModContent.WEATHER_DRONE_ENTITY.get().create(level);
        if (drone == null) {
            return null;
        }
        drone.moveTo(at.x, Math.max(level.getMinBuildHeight() + 2.0D, at.y), at.z, player.getYRot(), player.getXRot());
        drone.setDeltaMovement(0.0D, 0.06D, 0.0D);
        level.addFreshEntity(drone);
        drone.setPilot(player);
        return drone;
    }

    /**
     * The player's own drone, if one is out and near enough to link to.
     *
     * <p>Only ever the caller's own airframe. An unowned one nearby is deliberately not matched: a player
     * standing next to somebody else's parked drone would otherwise recall it into their inventory, and - the
     * quieter half of the same bug - a drone they had never launched would silently stop them launching one.
     * A drone whose owner is gone is still adoptable, but by walking up to it and using it, which is a
     * decision rather than an accident.
     */
    public static WeatherDroneEntity findDrone(Level level, Player player) {
        double range = Math.max(64.0D, Config.DRONE_LINK_RANGE_BLOCKS.get());
        AABB box = player.getBoundingBox().inflate(range, range, range);
        List<WeatherDroneEntity> found = level.getEntitiesOfClass(WeatherDroneEntity.class, box,
                drone -> drone.isPilotedBy(player));
        return found.isEmpty() ? null : found.get(0);
    }

    /** Writes a flight log onto a drone stack, whether the drone came home or crashed. */
    public static void attachLog(ItemStack stack, CompoundTag log) {
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(log));
    }

    public static CompoundTag logOf(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty() || !data.copyTag().contains(LOG_KEY)) {
            // Logs written by the entity are flat, so an older or hand-made tag is accepted as-is.
            return data == null ? new CompoundTag() : data.copyTag();
        }
        return data.copyTag().getCompound(LOG_KEY);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        CompoundTag log = logOf(stack);
        return (!log.isEmpty() && log.getDouble("PeakWindMph") > 0.0D) || super.isFoil(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        CompoundTag log = logOf(stack);
        if (log.isEmpty() || log.getDouble("SecondsAloft") <= 0.0D) {
            tooltip.add(Component.literal("No flight log").withStyle(ChatFormatting.DARK_GRAY));
            tooltip.add(Component.literal("Use to launch, then use again to recall").withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        tooltip.add(Component.literal("Flight log").withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.literal(String.format(Locale.ROOT, "%.1f minutes aloft, battery %.0f%% left",
                log.getDouble("SecondsAloft") / 60.0D, log.getFloat("Battery") * 100.0F)).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal(String.format(Locale.ROOT, "Peak wind %.0f mph over %.1f s in wind",
                log.getDouble("PeakWindMph"), log.getDouble("SecondsInWind"))).withStyle(ChatFormatting.GRAY));
        if (!Double.isNaN(log.getDouble("MinPressureHpa")) && log.getDouble("MinPressureHpa") != 0.0D) {
            tooltip.add(Component.literal(String.format(Locale.ROOT, "Lowest pressure %.1f hPa (%.1f below ambient)",
                    log.getDouble("MinPressureHpa"),
                    log.getDouble("AmbientHpa") - log.getDouble("MinPressureHpa"))).withStyle(ChatFormatting.GRAY));
        }
        if (log.getDouble("ClosestApproach") >= 0.0D) {
            tooltip.add(Component.literal(String.format(Locale.ROOT, "Closest approach to a storm: %.0f blocks",
                    log.getDouble("ClosestApproach"))).withStyle(ChatFormatting.GRAY));
        }
    }
}
