package com.imbet.client;

import com.imbet.Config;
import com.imbet.weather.RainVolumeField;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

/**
 * The fog and the light the volumetric rain is drawn in, read once a frame for one storm.
 *
 * <p>Two things the volume needs that it cannot work out for itself, and one place to get them so its two
 * drawings - the program and the pack-safe mesh - cannot disagree about either.
 *
 * <p><b>The fog is the world's fog, not one of ours.</b> {@code FogRenderer} has already chosen it for
 * the frame by the time the rain is drawn: its colour comes from the sky, which this mod drives - the
 * storm tint, the tornado's pressure fog, morning fog, a mist bank - and its start, end and shape come
 * from the render distance, the player's effects, and whatever a shader pack has done with it. Reading
 * that back means the rain dissolves into the fog that is actually there instead of into a horizon this
 * class invented, and it is why a tornado's whiteout swallows the rain the same frame it swallows the
 * terrain. On top of it goes the storm's own mist, because rain is its own fog: the air inside a
 * supercell is not the air outside it.
 *
 * <p><b>The light is the sky the rain stands against.</b> {@code getSkyColor} is not just the sun - it is
 * the sun's angle on the day cycle, the rain and thunder levels, and the flash of a strike, and this mod
 * writes all of the last three. So one call answers "how bright, and what colour, is the light on this
 * storm right now", and the rain cannot be lit differently from the sky above it. What is not in it is
 * local light: a strike lights the water it is standing in more than it lights the storm a kilometre
 * behind, so that difference is added per fragment, from the channel's own position.
 */
final class RainVolumeLight {
    /** How much of a storm's own air the mist is, before the field's density scales it. */
    private final double mistPerBlock;

    private final boolean fogEnabled;
    private final boolean lightEnabled;
    private final double fogStart;
    private final double fogEnd;
    private final double fogRed;
    private final double fogGreen;
    private final double fogBlue;
    private final double fogAlpha;

    /** The sky over the storm, floored: the light on the rain, with no strike in it. */
    private final double skyRed;
    private final double skyGreen;
    private final double skyBlue;

    /** The strike carrying this frame's light, or null when none is, or when it is not knowable. */
    private final Vec3 flashCentre;
    /** How bright that strike is at its own channel, from nought to one. */
    private final double flashLevel;

    private RainVolumeLight(double mistPerBlock, boolean fogEnabled, boolean lightEnabled,
                            double fogStart, double fogEnd, double fogRed, double fogGreen, double fogBlue,
                            double fogAlpha, double skyRed, double skyGreen, double skyBlue,
                            Vec3 flashCentre, double flashLevel) {
        this.mistPerBlock = mistPerBlock;
        this.fogEnabled = fogEnabled;
        this.lightEnabled = lightEnabled;
        this.fogStart = fogStart;
        this.fogEnd = fogEnd;
        this.fogRed = fogRed;
        this.fogGreen = fogGreen;
        this.fogBlue = fogBlue;
        this.fogAlpha = fogAlpha;
        this.skyRed = skyRed;
        this.skyGreen = skyGreen;
        this.skyBlue = skyBlue;
        this.flashCentre = flashCentre;
        this.flashLevel = flashLevel;
    }

    /**
     * Reads the frame's fog and light for one storm.
     *
     * <p>{@code skyProbe} is where the light is measured, and it is the storm rather than the player on
     * purpose: rain is lit by the sky over the rain. A storm two hundred blocks away under a breaking
     * anvil really is lit differently from the one overhead, and that is most of what makes two storms in
     * one view look like two different storms.
     *
     * <p>{@code wetness} is how much of the storm's moisture is reaching the ground: a high-precipitation
     * supercell is a fog bank inside as well as out, an LP storm's air is nearly clear. It scales the
     * mist and nothing else.
     */
    static RainVolumeLight read(ClientLevel level, Vec3 skyProbe, double wetness, float partial) {
        boolean fog = Config.RAIN_VOLUME_FOG.get();
        boolean lit = Config.RAIN_VOLUME_LIGHT.get();
        float[] fogColour = RenderSystem.getShaderFogColor();

        double mist = 0.0D;
        if (fog) {
            mist = RainVolumeField.mistPerBlock(Config.RAIN_VOLUME_FOG_MIST.get())
                    * Math.max(0.0D, Math.min(1.0D, wetness));
        }
        // The sky over the storm, floored. The floor is a floor on how lit the rain is rather than a colour
        // of its own, so a night storm's rain is dark blue instead of grey - see RainVolumeField.light.
        // With lighting off the rain is its own light again, exactly as it was drawn before any of this: a
        // neutral multiplier.
        double[] sky;
        if (lit) {
            Vec3 skyAbove = level.getSkyColor(skyProbe, partial);
            sky = RainVolumeField.light(skyAbove.x, skyAbove.y, skyAbove.z,
                    Config.RAIN_VOLUME_LIGHT_FLOOR.get(), 0.0D, 0.0D);
        } else {
            sky = new double[] {1.0D, 1.0D, 1.0D};
        }

        LightningFlash.Strike strike = lit ? LightningFlash.brightestStrike(partial) : null;
        return new RainVolumeLight(mist, fog, lit,
                RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd(),
                fogColour[0], fogColour[1], fogColour[2], fogColour[3],
                sky[0], sky[1], sky[2],
                strike == null ? null : strike.centre(), strike == null ? 0.0D : strike.level());
    }

    /**
     * How much of a fragment is fog, at a distance and at the rain's own density there.
     *
     * <p>The world's fog and the storm's own, on terms that multiply, with the mist following the rain's
     * density: the clear slot stays a slot and the core becomes the blindest place in the storm. The world's
     * half is written for the frame by the shader loader rather than chosen here, so this method is not what
     * the program calls - the program composes the same two halves in its own fragments - and it is written
     * down all the same, as the arithmetic the program's fog is measured against. Two paths describing one
     * fog is the thing that has to not happen; a statement of what that fog is, held to the program by
     * {@code RainFogLightSim}, is how it does not.
     */
    double fogValue(double distance, double density) {
        if (!fogEnabled) {
            return 0.0D;
        }
        return RainVolumeField.clamp01(1.0D
                - (1.0D - RainVolumeField.worldFog(distance, fogStart, fogEnd)) * (1.0D - mistFog(distance, density)));
    }

    /**
     * The storm's own mist on its own: the fog the rain makes of itself.
     *
     * <p>This is the half the pack-safe mesh applies by hand, and the reason it is separate is the pack.
     * That path is drawn through a vanilla render type, which the pack replaces with its own program - and
     * that program applies the pack's fog to the rain exactly as it does to everything else. Adding the
     * world's fog on the CPU as well would apply it twice. What no program can know is the mist a storm
     * makes of its own air, so that half is baked into the vertices and the world's half is left alone.
     */
    double mistFog(double distance, double density) {
        if (!fogEnabled) {
            return 0.0D;
        }
        double mist = mistPerBlock * RainVolumeField.mistScale(density, mistDensity());
        return RainVolumeField.clamp01(1.0D - Math.exp(-mist * Math.max(0.0D, distance)));
    }

    /** How much the mist follows the rain's own density, or nothing when there is no fog to follow it with. */
    double mistDensity() {
        return fogEnabled ? Config.RAIN_VOLUME_FOG_DENSITY.get() : 0.0D;
    }

    /**
     * The colour a piece of rain is lit by: the sky over the storm, plus the extra any strike gives it for
     * being nearer the channel than the eye is.
     *
     * <p>The subtraction is deliberate and is the whole of the local term. The sky colour already carries
     * the strike's light on everything, so what a fragment is owed on top of that is only the difference
     * between the light where it is and the light where the player is watching from - which is why rain the
     * strike is standing in glows while the storm behind it does not, and why nothing happens at all when
     * the player is the one nearest the channel.
     */
    double[] lightAt(Vec3 position, Vec3 camera) {
        return position == null
                ? lightAtPlain()
                : lightAt(position.x, position.y, position.z, camera);
    }

    /** The same, for a caller that has the coordinates rather than a vector: the pack-safe mesh, per vertex. */
    double[] lightAt(double x, double y, double z, Vec3 camera) {
        if (!lightEnabled) {
            return new double[] {1.0D, 1.0D, 1.0D};
        }
        double extra = 0.0D;
        if (flashCentre != null && flashLevel > 0.0D && camera != null) {
            double here = RainVolumeField.flashFalloff(flashCentre.distanceTo(new Vec3(x, y, z)));
            double eye = RainVolumeField.flashFalloff(camera.distanceTo(flashCentre));
            extra = Math.max(0.0D, here - eye) * flashLevel;
        }
        return RainVolumeField.light(skyRed, skyGreen, skyBlue,
                Config.RAIN_VOLUME_LIGHT_FLOOR.get(), extra, Config.RAIN_VOLUME_FLASH_LIGHT.get());
    }

    private double[] lightAtPlain() {
        return lightEnabled
                ? RainVolumeField.light(skyRed, skyGreen, skyBlue, Config.RAIN_VOLUME_LIGHT_FLOOR.get(), 0.0D, 0.0D)
                : new double[] {1.0D, 1.0D, 1.0D};
    }

    boolean fogEnabled() {
        return fogEnabled;
    }

    boolean lightEnabled() {
        return lightEnabled;
    }

    double fogStart() {
        return fogStart;
    }

    double fogEnd() {
        return fogEnd;
    }

    double fogRed() {
        return fogRed;
    }

    double fogGreen() {
        return fogGreen;
    }

    double fogBlue() {
        return fogBlue;
    }

    double fogAlpha() {
        return fogAlpha;
    }

    double mistPerBlock() {
        return mistPerBlock;
    }

    /** The sky's own light on the rain, with no strike in it: what the program's fragments start from. */
    double skyRed() {
        return skyRed;
    }

    double skyGreen() {
        return skyGreen;
    }

    double skyBlue() {
        return skyBlue;
    }

    /** The strike whose channel lights this frame, or null. */
    Vec3 flashCentre() {
        return flashCentre;
    }

    /** How bright that strike is at its own channel. */
    double flashLevel() {
        return flashLevel;
    }
}
