package com.imbet.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;

/**
 * Which way a piece of cloud faces the sun, for shading a storm's mass the way it is lit.
 *
 * <p>Photographs of supercells all say the same thing: in daylight the side of the tower toward the sun is close to white
 * with crisp, bright cauliflower edges, and the side away from it is a blue-grey; the anvil's top is bright and its
 * underside grey; the base is dark. A storm shaded only by height - dark at the foot, light at the crown - has none of
 * that and reads as a flat cut-out. The sun's direction is the game's own: it rises in the east, crosses overhead and
 * sets in the west.
 */
final class SunShading {
    private static long cachedTick = Long.MIN_VALUE;
    private static double sunX;
    private static double sunY = 1.0D;
    private static double sunZ;

    private SunShading() {
    }

    private static void refresh(ClientLevel level) {
        long tick = level.getGameTime();
        if (tick == cachedTick) {
            return;
        }
        cachedTick = tick;
        float angle = level.getSunAngle(1.0F);
        sunX = -Math.sin(angle);
        sunY = Math.cos(angle);
        sunZ = 0.0D;
    }

    /**
     * How lit a surface facing the given way is, from 0 for the side turned away from the sun to 1 for the side facing it.
     * At night, and with the sun low in the haze, the difference fades toward an even 0.5: the night's own darkening
     * does the rest there.
     */
    static float lit(ClientLevel level, double normalX, double normalY, double normalZ) {
        refresh(level);
        double length = Math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
        if (length < 1.0E-6D) {
            return 0.5F;
        }
        double facing = (normalX * sunX + normalY * sunY + normalZ * sunZ) / length;
        float daylight = daylight(level);
        return (float) Mth.lerp(daylight, 0.5D, Mth.clamp(facing * 0.5D + 0.5D, 0.0D, 1.0D));
    }

    /** How much the sun is up to light anything directionally: 0 at night, 1 once it is well clear of the horizon. */
    static float daylight(ClientLevel level) {
        refresh(level);
        return (float) Mth.clamp((sunY + 0.05D) / 0.30D, 0.0D, 1.0D);
    }

    /** How low and warm the sun is: 1 near sunrise and sunset, when lit cloud goes gold, and 0 from mid-morning on. */
    static float lowSun(ClientLevel level) {
        refresh(level);
        return (float) (daylight(level) * Mth.clamp(1.0D - sunY / 0.40D, 0.0D, 1.0D));
    }

    /**
     * A cloud colour for a grey of the given brightness, lit by the given share: near white toward the sun, warmed when it
     * is low, and blue-grey away from it. Returns {red, green, blue}.
     */
    static float[] cloudColour(ClientLevel level, float grey, float lit, float shadowShade) {
        float shade = Mth.lerp(lit, shadowShade, 1.0F);
        float value = Mth.clamp(grey * shade, 0.0F, 1.0F);
        float warm = lowSun(level) * lit;
        float cool = (1.0F - lit) * daylight(level);
        return new float[] {
                Mth.clamp(value * (1.0F + 0.06F * warm - 0.04F * cool), 0.0F, 1.0F),
                Mth.clamp(value * (1.0F + 0.01F * warm - 0.01F * cool), 0.0F, 1.0F),
                Mth.clamp(value * (1.0F - 0.05F * warm + 0.07F * cool), 0.0F, 1.0F)
        };
    }
}
