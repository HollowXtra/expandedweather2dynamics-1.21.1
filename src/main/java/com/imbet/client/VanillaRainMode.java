package com.imbet.client;

import com.imbet.Config;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import weather2.config.ConfigParticle;

/**
 * Puts vanilla's own precipitation in charge of the rain, and keeps it in charge.
 *
 * <p>Two things have to be true before vanilla sheets are what you see, and one of them is not ours:
 * Weather2 hides vanilla precipitation outright behind its own {@code Particle_vanilla_precipitation}
 * flag (its {@code RenderParticlesOverride} mixin cancels the pass when the flag is off, and it ships
 * off because Weather2 draws its own rain particles instead). So while this mode is on that flag is
 * held open - the value Weather2 shipped with is remembered the first time it is overridden and put
 * back the moment the mode is switched off, so the user's own Weather2 config is not left rewritten.
 *
 * <p>The other thing is the mod's own rain: the volumetric volumes and Weather2's own rain and snow
 * particles, either of which would rain a second time over the same sky. The volumes stand down
 * through {@link VolumetricPrecipitationRenderer}, and the particles through a redirect on the two
 * predicates Weather2 spawns them from - a redirect rather than a cancelled method, because that
 * method also spawns the ambient, leaf and sand particles that have nothing to do with rain.
 */
public final class VanillaRainMode {

    /** Weather2's own flag as it was before this mode first overrode it. */
    private static Boolean weather2FlagOriginal;

    private VanillaRainMode() {
    }

    /** Whether vanilla's own precipitation is the rain: no volumetric volumes, no Weather2 particles. */
    public static boolean active() {
        return Config.VANILLA_RAIN_ENABLED.get();
    }

    /**
     * Holds Weather2's precipitation flag where this mode needs it, and puts it back when the mode ends.
     *
     * <p>Run every client tick rather than once at startup, because Weather2 reloads that field from its
     * own config screen and from its config file without telling anyone, and the flag has to be open
     * before the pass that reads it rather than eventually.
     */
    public static void onClientTick(ClientTickEvent.Post event) {
        if (Minecraft.getInstance().level == null) {
            return;
        }
        boolean wanted = active();
        if (wanted) {
            if (!ConfigParticle.Particle_vanilla_precipitation) {
                if (weather2FlagOriginal == null) {
                    // Read before writing: whatever Weather2 really wants is what gets restored later.
                    weather2FlagOriginal = ConfigParticle.Particle_vanilla_precipitation;
                }
                ConfigParticle.Particle_vanilla_precipitation = true;
            }
            return;
        }
        if (weather2FlagOriginal != null) {
            ConfigParticle.Particle_vanilla_precipitation = weather2FlagOriginal;
            weather2FlagOriginal = null;
        }
    }

    /** Whether Weather2's own rain and snow particles should be skipped this tick. */
    public static boolean suppressWeather2Precipitation() {
        return active();
    }
}
