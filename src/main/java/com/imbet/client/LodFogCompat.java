package com.imbet.client;

import com.imbet.Config;
import net.minecraft.world.level.material.FogType;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Keeps vanilla fog from hiding terrain the LOD renderer is drawing.
 *
 * <p>Raising how far the weather particles are drawn is only half of a LOD compatibility fix: vanilla
 * terrain fog still ends at the vanilla render distance, so with an LOD renderer drawing thousands of
 * blocks further a tornado can be drawn and simultaneously fogged to nothing. This pushes the far fog
 * plane out to the LOD distance whenever one is drawing.
 *
 * <p>Three deliberate limits. It only ever moves fog <em>further away</em>, so it cannot make
 * anything harder to see than vanilla does. It leaves fluid fogs alone - underwater, in lava and in
 * powder snow keep their own short fog. And it yields entirely to the tornado pressure/whiteout fog,
 * the morning fog and the mist haze, which are the mod's own deliberate short-range fog effects: those are checked
 * by asking the effect handlers rather than by relying on listener order, so a whiteout stays a
 * whiteout whatever order the two systems end up running in.
 */
public final class LodFogCompat {

    private LodFogCompat() {
    }

    public static void onRenderFog(ViewportEvent.RenderFog event) {
        try {
            if (event == null || !Config.LOD_COMPAT_ENABLED.get() || !Config.LOD_COMPAT_EXTEND_FOG.get()) {
                return;
            }
            if (event.getType() != FogType.NONE) {
                return;
            }
            if (TornadoFearClient.fogPressureActive() || MorningFogClient.fogActive() || MistField.hazeActive()) {
                return;
            }
            float lod = LodViewDistance.activeDistanceBlocks();
            if (lod > event.getFarPlaneDistance()) {
                event.setFarPlaneDistance(lod);
            }
        } catch (Throwable ignored) {
            // Fog is cosmetic: a compat probe must never take the frame down with it.
        }
    }
}
