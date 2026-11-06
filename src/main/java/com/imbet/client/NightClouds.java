package com.imbet.client;

import com.imbet.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;

/**
 * How much of the sky's light a storm's own particles are lit by.
 *
 * <p>Every cloud, rain sheet, dust puff and funnel puff a storm draws is lit the way a block in the open is: by the
 * sky light at its position through the game's light map. That is right for the ground, and wrong for a cloud at
 * night. The light map's night still lights anything under open sky to a third or so of its daytime brightness, which
 * is what lets a player see the grass at midnight, and a pale cloud lit that way stands out against a sky that is
 * nearly black: the storm glowed in the dark. A real storm at night is a darker shape against a dark sky, and is seen
 * properly only when lightning lights it. So the sky light its particles are lit by is turned down as the sun goes,
 * and handed back for the length of a flash.
 *
 * <p>It works on the light value rather than on a colour or a program, so it holds wherever the particle is drawn:
 * with the soft puffs, without them, and under a shader pack, which reads the same light value.
 */
public final class NightClouds {
    /** How little of its sky light a storm's particle keeps at the darkest of the night, before the config's dial. */
    private static final float MIDNIGHT_SHARE = 0.30F;
    /** The light a particle is given where the game has none to give it: open sky, as Weather2's own default. */
    private static final int OPEN_SKY = 15 << 20;

    /** How far above sea level cloud starts to stand clear of a storm's own shadow and take the sun, in blocks. */
    private static final double SUNLIT_FROM_ABOVE_SEA = 180.0D;
    /** And over how many blocks above that it comes fully into the sun. */
    private static final double SUNLIT_SPAN = 120.0D;

    private static long cachedTick = Long.MIN_VALUE;
    private static float cachedShare = 1.0F;
    private static float cachedSunLift;
    private static double cachedSunlitFloor;

    private NightClouds() {
    }

    /** The light a storm particle is drawn with, given the light the game worked out for its position and its height. */
    public static int light(int packed, double y) {
        float share = skyShare();
        if (packed == 0) {
            // The game answers nothing for a particle over a chunk that is not loaded, and Weather2 then falls back on full
            // open sky. Answering open sky here instead, turned down like everything else, keeps a far cloud as dark as a
            // near one.
            packed = OPEN_SKY;
        }
        int sky = (packed >> 20) & 0xF;
        int block = (packed >> 4) & 0xF;
        if (share < 0.999F) {
            sky = Mth.clamp(Math.round(sky * share), 0, 15);
        }
        if (cachedSunLift > 0.0F && y > cachedSunlitFloor) {
            // The sun on the high cloud. The sky's light is turned down for everything when a supercell darkens the day,
            // and that is right under it, but the tower's crown and the anvil stand far above the storm's shadow, in full
            // sunshine. Block light is the one part of the light that darkening does not touch, and at full strength it is
            // white, so that is what gives it back; part way, as at dusk, it is warm, which is the colour a lit anvil is.
            double t = Mth.clamp((y - cachedSunlitFloor) / SUNLIT_SPAN, 0.0D, 1.0D);
            int sun = Math.round(cachedSunLift * (float) (t * t * (3.0D - 2.0D * t)));
            block = Math.max(block, Mth.clamp(sun, 0, 15));
        }
        return sky << 20 | block << 4;
    }

    /** The share of its sky light a storm's particle keeps right now: all of it by day, and less as the night comes. */
    private static float skyShare() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || !Config.VISUALS_ENABLED.get()) {
            return 1.0F;
        }
        long tick = level.getGameTime();
        if (tick == cachedTick) {
            return cachedShare;
        }
        cachedTick = tick;
        cachedSunLift = 0.0F;
        if (!level.dimensionType().hasSkyLight()) {
            cachedShare = 1.0F;
            return cachedShare;
        }
        // The game's own day: one with the sun up, nothing at night, and the same dawn and dusk the light map uses.
        float sunUp = Mth.clamp(Mth.cos(level.getTimeOfDay(1.0F) * Mth.TWO_PI) * 2.0F + 0.2F, 0.0F, 1.0F);
        // A storm darkening the day takes the sky's light from everything; the sun still reaches cloud standing above it.
        // A shader pack lights its own sky, and reads block light as lamplight, so it is left to the pack.
        if (!RenderingCompat.isShaderPackActive()) {
            float stormShade = (float) Mth.clamp((1.0D - StormSkyClient.darkness()) / (1.0D - 0.20D), 0.0D, 1.0D);
            cachedSunLift = 15.0F * sunUp * stormShade;
            cachedSunlitFloor = level.getSeaLevel() + SUNLIT_FROM_ABOVE_SEA;
        }
        float darkness = Mth.clamp(Config.NIGHT_STORM_DARKNESS.get().floatValue(), 0.0F, 1.0F);
        if (darkness <= 0.0F) {
            cachedShare = 1.0F;
            return cachedShare;
        }
        // A strike lights a storm from inside, which is the one time a night storm is seen whole.
        float day = Math.max(sunUp, LightningFlash.level(1.0F));
        float midnight = Mth.lerp(darkness, 1.0F, MIDNIGHT_SHARE);
        cachedShare = Mth.lerp(day, midnight, 1.0F);
        return cachedShare;
    }
}
