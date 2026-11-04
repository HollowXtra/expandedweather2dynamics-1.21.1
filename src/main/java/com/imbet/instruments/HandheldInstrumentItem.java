package com.imbet.instruments;

import com.imbet.Config;
import com.imbet.weather.TornadoWindField;
import com.imbet.weather.WeatherInstruments;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One of the three handheld instruments: a reading you can carry into a field.
 *
 * <p>Every measurement in this mod is already made - the wind field is sampled by the anemometer and the vane,
 * the pressure column by the barometer, the air by biome and height - and until now all of it was read standing
 * still at a block. This is the same reading in the hand, and the reason it is worth a device rather than a
 * chat message is what a person can do with it while walking: sample the wind on the way in, watch the
 * barometer fall as the sky darkens, and find out that the cold pool went past you rather than arriving.
 *
 * <p>Two of the three instruments remember something, and both are the device's own memory rather than the
 * world's:
 *
 * <ul>
 *   <li>The anemometer keeps its <b>peak since it was last cleared</b>, cleared by sneaking. That is what a
 *       real handheld anemometer is for: the gust worth knowing about has usually already gone past, and this
 *       is the number a chaser writes down.</li>
 *   <li>The barometer keeps its <b>last reading and when it was taken</b>, so the tendency it reports is the
 *       one this device measured rather than a second-hand number. A device that has not been read twice has no
 *       tendency and says so, which is the honest answer.</li>
 * </ul>
 *
 * <p>The memory is kept per player rather than in the stack, because it is a record of what the holder has
 * seen: dropping the instrument and picking it up again does not rewind the sky, and two barometers carried by
 * two players are two records. It is dropped when a player logs out, so a server does not accumulate them.
 *
 * <p>Both of the wind instruments need open sky. That is not decoration: a pressure or wind reading under a
 * roof is a reading of the room, and the model says so rather than letting the instrument invent one.
 */
public class HandheldInstrumentItem extends Item {
    private final HandheldInstrument.Kind kind;

    /**
     * What each holder's own instrument knows.
     *
     * <p>{@code hpa} and {@code tick} are the barometer's record, {@code peak} the anemometer's; an
     * instrument only ever uses its own two fields. Weak references would be tidier but these are removed on
     * logout, which is the event that actually matters.
     */
    private static final Map<UUID, Memory> MEMORY = new ConcurrentHashMap<>();

    private static final class Memory {
        private double hpa = Double.NaN;
        private long tick = Long.MIN_VALUE;
        private double peak = Double.NaN;
    }

    public HandheldInstrumentItem(HandheldInstrument.Kind kind, Properties properties) {
        super(properties);
        this.kind = kind;
    }

    public HandheldInstrument.Kind kind() {
        return kind;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!Config.HANDHELD_INSTRUMENTS_ENABLED.get()) {
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.sendSystemMessage(Component.literal(kind.label()
                        + ": switched off in the configuration.").withStyle(ChatFormatting.GRAY));
            }
            return InteractionResultHolder.success(stack);
        }
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.success(stack);
        }
        Memory memory = MEMORY.computeIfAbsent(player.getUUID(), ignored -> new Memory());
        // Sneaking with the anemometer clears its peak, which is the button a real one has: the gust worth
        // writing down has usually already gone past by the time a chaser arrives.
        if (kind == HandheldInstrument.Kind.ANEMOMETER && player.isShiftKeyDown()) {
            memory.peak = Double.NaN;
            serverPlayer.sendSystemMessage(Component.literal("Peak cleared.").withStyle(ChatFormatting.GRAY));
            serverPlayer.getCooldowns().addCooldown(this, Config.HANDHELD_READOUT_COOLDOWN_TICKS.get());
            return InteractionResultHolder.success(stack);
        }
        // A reading from under a roof is a reading of the roof, so the two field instruments refuse rather
        // than inventing one. The thermometer answers anywhere, because indoor air is still air.
        boolean underRoof = !serverLevel.canSeeSky(player.blockPosition());
        if (!HandheldInstrument.readsAt(kind, underRoof)) {
            serverPlayer.sendSystemMessage(Component.literal(kind.label()
                    + ": no reading under a roof - stand in the open.").withStyle(ChatFormatting.GRAY));
            serverPlayer.getCooldowns().addCooldown(this, Config.HANDHELD_READOUT_COOLDOWN_TICKS.get());
            return InteractionResultHolder.success(stack);
        }
        serverPlayer.getCooldowns().addCooldown(this, Config.HANDHELD_READOUT_COOLDOWN_TICKS.get());
        switch (kind) {
            case ANEMOMETER -> readWind(serverLevel, serverPlayer, memory);
            case THERMOMETER -> readTemperature(serverLevel, serverPlayer);
            case BAROMETER -> readPressure(serverLevel, serverPlayer, memory);
        }
        return InteractionResultHolder.success(stack);
    }

    /** Wind: the speed now, where it is coming from, and the highest this instrument has seen. */
    private void readWind(ServerLevel level, ServerPlayer player, Memory memory) {
        Vec3 where = player.getEyePosition();
        TornadoWindField.WindSample sample = TornadoWindField.sampleStrongest(level, where);
        double mph = Math.max(0.0D, sample.speedMph());
        memory.peak = HandheldInstrument.peakSince(memory.peak, mph);
        // The peak is only worth printing when it is higher than the air right now: a steady wind and its own
        // peak are the same number, and "gusting 18" beside an 18 mph reading is noise.
        double gust = memory.peak > mph + 0.5D ? memory.peak : 0.0D;
        player.sendSystemMessage(Component.literal(kind.label() + ": "
                + HandheldInstrument.reading(kind, mph, gust)).withStyle(ChatFormatting.AQUA));
        player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                "  From %s | %s | peak %s since cleared",
                WeatherInstruments.windFromLabel(sample.mphVector()),
                HandheldInstrument.gusty(memory.peak, mph) ? "gusty, stirred air" : "steady air",
                WeatherInstruments.mph(memory.peak))).withStyle(ChatFormatting.GRAY));
    }

    /** Air temperature: the biome's own number, on the scale the model states, less the height lapse. */
    private void readTemperature(ServerLevel level, ServerPlayer player) {
        float base = level.getBiome(player.blockPosition()).value().getBaseTemperature();
        double celsius = HandheldInstrument.celsius(base, player.getY());
        player.sendSystemMessage(Component.literal(kind.label() + ": "
                + HandheldInstrument.reading(kind, celsius, 0.0D)).withStyle(ChatFormatting.AQUA));
        player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                "  Biome base %.2f, read at y=%.0f | belt: %s",
                base, player.getY(), WeatherInstruments.beltLine(player.getZ()))).withStyle(ChatFormatting.GRAY));
    }

    /** Pressure: the column, the band, and the tendency this instrument's own two readings give. */
    private void readPressure(ServerLevel level, ServerPlayer player, Memory memory) {
        WeatherInstruments.PressureReading reading = WeatherInstruments.pressure(level, player.getEyePosition());
        double rate = HandheldInstrument.rateHpaPerHour(memory.hpa, reading.hpa(), level.getGameTime() - memory.tick);
        long age = memory.tick == Long.MIN_VALUE ? -1L : level.getGameTime() - memory.tick;
        memory.hpa = reading.hpa();
        memory.tick = level.getGameTime();
        player.sendSystemMessage(Component.literal(kind.label() + ": "
                + HandheldInstrument.reading(kind, reading.hpa(), 0.0D)).withStyle(ChatFormatting.AQUA));
        if (Double.isNaN(rate)) {
            player.sendSystemMessage(Component.literal("  " + HandheldInstrument.NO_RECORD
                    + (age < 0L ? "" : " (last read " + age + " ticks ago)")).withStyle(ChatFormatting.GRAY));
        } else {
            WeatherInstruments.Tendency tendency = WeatherInstruments.tendency(rate);
            player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                    "  %s (%+.1f hPa per in-game hour, over %d ticks)",
                    tendency.label(), tendency.hPaPerHour(), age))
                    .withStyle(tendency.rapidlyFalling() ? ChatFormatting.RED
                            : tendency.falling() ? ChatFormatting.GOLD : ChatFormatting.GRAY));
        }
        player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                "  Altitude base %.1f, low deficit %.1f, vortex deficit %.1f",
                reading.altitudeHpa(), reading.lowDeficitHpa(), reading.coreDeficitHpa()))
                .withStyle(ChatFormatting.GRAY));
        if (reading.lowDistanceBlocks() >= 0.0D) {
            player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                    "  Nearest low: %s bearing %s, %.0f blocks",
                    reading.lowLabel().isBlank() ? "Low" : reading.lowLabel(),
                    reading.lowBearing(), reading.lowDistanceBlocks())).withStyle(ChatFormatting.GRAY));
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(getDescriptionId() + ".desc").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.expandedweather2dynamics.handheld.usage." + kind.name().toLowerCase(Locale.ROOT))
                .withStyle(ChatFormatting.DARK_AQUA));
    }

    /**
     * Forgets what a player's instruments had recorded.
     *
     * <p>Called when they leave, so a long-lived server does not keep a memory per player who ever held one.
     */
    public static void forget(UUID player) {
        if (player != null) {
            MEMORY.remove(player);
        }
    }
}
