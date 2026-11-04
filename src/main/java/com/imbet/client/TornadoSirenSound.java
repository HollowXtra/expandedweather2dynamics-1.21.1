package com.imbet.client;

import com.imbet.Config;
import com.imbet.alert.SirenTone;
import com.imbet.alert.TornadoSirenBlockEntity;
import com.imbet.weather.AlertLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The continuous tone a tornado siren makes while an alert is in force.
 *
 * <p>A siren is a machine left running, not a chime, so this is a looping sound held at the block's own
 * position: walk toward it and it gets louder, walk behind a hill and it is muffled, and it is still going
 * when you arrive. The tone recording already contains the sweep or the alternation, so the loop plays at
 * its natural pitch - a mechanical wail must not be transposed, or its rotor speed stops being the rotor
 * speed it was built from.
 *
 * <p>Two details are what make it read as real rather than as a music loop:
 *
 * <ul>
 *   <li><b>Spin-up.</b> Volume eases in over about a second and a half. A series motor driving a heavy
 *       rotor cannot start at full pitch or full loudness, and the fade-in is what tells the ear the siren
 *       <i>started</i> rather than that a recording was switched on.</li>
 *   <li><b>Distance rather than loudness.</b> The instance is positioned at the siren with linear
 *       attenuation and a large volume, so the audible radius is over a hundred blocks - which is what a
 *       siren is for. Close to the block the mix is simply loud; far from it, it thins out.</li>
 * </ul>
 *
 * <p>How far it carries is not a guess. Minecraft plays a positioned sound through OpenAL's linear
 * distance model with the reference distance at zero and the rolloff at one, and it sizes that model from
 * {@code max(volume, 1) * 16} blocks - the attenuation range. So the gain falls in a straight line from
 * full at the siren to nothing at {@code volume * 16} blocks, and the volume knob is really a radius: 150
 * blocks needs a volume of 9.4, not 150. Two consequences are easy to get wrong and are handled here: a
 * volume below one is still heard 16 blocks away rather than being quieter but just as far-reaching, and an
 * instance whose volume is <em>zero</em> when it is played is dropped by the engine outright, so the loop
 * starts at a hair above silence rather than at silence itself.
 */
public final class TornadoSirenSound extends AbstractTickableSoundInstance {
    /** How far a siren carries, in blocks. Converted to a volume below, because volume is the radius. */
    private static final float AUDIBLE_RADIUS_BLOCKS = 150.0F;
    /**
     * How many blocks of radius one unit of volume is worth.
     *
     * <p>Not a preference: {@code SoundEngine} computes the attenuation range as {@code max(volume, 1) *
     * sound.getAttenuationDistance()}, and a variable-range sound event's attenuation distance is sixteen.
     */
    private static final float BLOCKS_PER_VOLUME = 16.0F;
    /**
     * The volume the loop starts at, which must be above zero.
     *
     * <p>The engine refuses to play an instance whose calculated volume is zero - it logs "Skipped playing
     * sound, volume was zero" and drops it - so a spin-up that began at silence would never be heard at all.
     * A fiftieth of a unit is inaudible and, at an attenuation range of sixteen blocks, also barely
     * reaches past the siren itself.
     */
    private static final float MIN_VOLUME = 0.02F;
    /** Volume is eased toward its target at this rate per tick, which is the spin-up. */
    private static final float SPIN_UP = 0.055F;
    /**
     * Below this the loop is retired rather than left running.
     *
     * <p>Sits below {@link #MIN_VOLUME}, so the coast-down has a floor to stop at and the starting volume
     * is never mistaken for a finished one.
     */
    private static final float SILENCE = 0.012F;
    /** How often a running loop re-reads its siren's state to catch a missed sync, in ticks. */
    private static final int REFRESH_TICKS = 20;

    private static final Map<BlockPos, TornadoSirenSound> RUNNING = new HashMap<>();

    private final BlockPos sirenPos;
    private SirenTone tone;
    private float targetVolume;
    private boolean wanted;
    private int ticksSinceRefresh;

    private TornadoSirenSound(SirenTone tone, BlockPos pos) {
        super(tone.sound(), SoundSource.BLOCKS, RandomSource.create());
        this.tone = tone;
        this.sirenPos = pos.immutable();
        looping = true;
        delay = 0;
        attenuation = SoundInstance.Attenuation.LINEAR;
        // Starts at a hair above silence and eases up, which is the spin-up. Not at zero: the engine would
        // drop the instance, and the siren would never be heard.
        volume = MIN_VOLUME;
        pitch = 1.0F;
        x = pos.getX() + 0.5D;
        y = pos.getY() + 0.5D;
        z = pos.getZ() + 0.5D;
    }

    /**
     * Keeps the loop for one siren in step with whether it should be sounding.
     *
     * <p>Called every client tick from the siren, and it is deliberately idempotent: playing a sound every
     * tick would stack hundreds of copies, so the instance is created once and then only re-targeted.
     */
    public static void set(TornadoSirenBlockEntity siren, boolean shouldSound) {
        BlockPos pos = siren.getBlockPos();
        TornadoSirenSound sound = RUNNING.get(pos);
        if (!shouldSound) {
            if (sound != null) {
                sound.wanted = false;
            }
            return;
        }
        if (sound != null && (sound.tone != siren.tone() || !Minecraft.getInstance().getSoundManager().isActive(sound))) {
            // Either the block was swapped for another siren type - the old tone has to stop, not transpose
            // - or the instance is no longer running at all. The second case is the one that matters for a
            // loop's lifetime: the sound engine stops every instance on a level change, and an entry left
            // behind by that would be handed out again as a sound nobody would ever hear.
            sound.retire();
            sound = null;
        }
        if (sound == null) {
            sound = new TornadoSirenSound(siren.tone(), pos);
            RUNNING.put(pos, sound);
            Minecraft.getInstance().getSoundManager().play(sound);
        }
        sound.wanted = true;
        sound.ticksSinceRefresh = 0;
        sound.applyLevel(siren.alertLevel());
    }

    private void applyLevel(AlertLevel level) {
        // How loud a siren is set to run is a property of the warning, not of the listener: a tornado
        // emergency sounds everything at full volume, a severe thunderstorm warning runs it below that.
        float severity = switch (level) {
            case TORNADO_EMERGENCY -> 1.0F;
            case TORNADO_WARNING, PDS_TORNADO_WARNING, HURRICANE_WARNING -> 0.92F;
            case SEVERE_STORM_WARNING -> 0.74F;
            default -> 0.0F;
        };
        targetVolume = severity * (AUDIBLE_RADIUS_BLOCKS / BLOCKS_PER_VOLUME)
                * (float) Config.ALERTS_SIREN_TONE_VOLUME.get().doubleValue();
    }

    @Override
    public void tick() {
        ticksSinceRefresh++;
        if (!wanted) {
            targetVolume = 0.0F;
        } else if (ticksSinceRefresh >= REFRESH_TICKS) {
            // A safety net for a sync that was missed: the siren itself is authoritative, so the loop asks
            // it directly rather than trusting a flag that arrived before the block's state did.
            ticksSinceRefresh = 0;
            if (Minecraft.getInstance().level != null
                    && Minecraft.getInstance().level.getBlockEntity(sirenPos) instanceof TornadoSirenBlockEntity siren) {
                if (!siren.sounding() || !Config.ALERTS_SIREN_TONES.get()) {
                    wanted = false;
                } else {
                    applyLevel(siren.alertLevel());
                }
            } else {
                wanted = false;
            }
        }
        volume = Mth.lerp(SPIN_UP, volume, targetVolume);
        // Relaxing after a stop: the rotor coasts down rather than cutting out, and once it is quiet the
        // loop leaves the table so a siren that ends does not keep a sound instance alive behind it. The
        // coast-down keeps its own volume above zero while it lasts - a fading loop that reaches exactly
        // zero would be dropped by the engine instead of fading, which is a click rather than a coast.
        if (!wanted && volume < SILENCE) {
            retire();
        }
    }

    /**
     * Ends this loop and drops it from the table.
     *
     * <p>{@code stop()} itself cannot be overridden - the base class owns it, so that the sound engine can
     * always shut an instance down - which is why the bookkeeping lives here and every path that finishes a
     * loop goes through it.
     */
    private void retire() {
        Iterator<Map.Entry<BlockPos, TornadoSirenSound>> entries = RUNNING.entrySet().iterator();
        while (entries.hasNext()) {
            if (entries.next().getValue() == this) {
                entries.remove();
            }
        }
        stop();
    }

    /** The client's sink: the only thing that ever starts or stops a siren's tone. */
    public static void install() {
        com.imbet.alert.SirenTonePlayback.install(TornadoSirenSound::set);
    }

    /** How many sirens are currently looping a tone, for diagnostics. */
    public static int runningCount() {
        return RUNNING.size();
    }
}
