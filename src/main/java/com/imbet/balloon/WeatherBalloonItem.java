package com.imbet.balloon;

import com.imbet.Config;
import com.imbet.weather.WeatherBalloonEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Releases a weather balloon (radiosonde). The sonde is flown for its telemetry: it radios
 * temperature, dewpoint, pressure and wind back to the launcher as it climbs, reports storms it
 * passes near, and parachutes its instrument package home, which is why the item comes back.
 */
public class WeatherBalloonItem extends Item {
    private static final int LAUNCH_COOLDOWN_TICKS = 20;

    public WeatherBalloonItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!Config.WEATHER_BALLOON_ENABLED.get()) {
            if (!level.isClientSide()) {
                player.sendSystemMessage(Component.literal("Weather balloons are disabled in the config.").withStyle(ChatFormatting.GRAY));
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }

        int limit = Config.WEATHER_BALLOON_MAX_ACTIVE_PER_PLAYER.get();
        if (countOwnBalloons(serverLevel, player) >= limit) {
            player.sendSystemMessage(Component.literal("You already have " + limit + " balloon" + (limit == 1 ? "" : "s") + " in the air.").withStyle(ChatFormatting.GRAY));
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }

        Vec3 spawn = new Vec3(player.getX(), player.getY() + 2.2D, player.getZ());
        WeatherBalloonEntity balloon = new WeatherBalloonEntity(serverLevel, spawn.x, spawn.y, spawn.z, player.getUUID());
        serverLevel.addFreshEntity(balloon);
        serverLevel.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 0.9F, 1.2F);

        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.getCooldowns().addCooldown(this, LAUNCH_COOLDOWN_TICKS);
        }
        player.swing(hand, true);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    /** Sondes already in the air for this player, so one player cannot flood the sky with them. */
    private static int countOwnBalloons(ServerLevel level, Player player) {
        int count = 0;
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof WeatherBalloonEntity balloon && player.getUUID().equals(balloon.launcherId())) {
                count++;
            }
        }
        return count;
    }
}
