package com.imbet.client;

import com.imbet.drone.WeatherDroneEntity;
import com.imbet.registry.ModContent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * The noise a weather drone makes while its rotors are turning.
 *
 * <p>A drone is heard before it is seen, and the sound is the machine's own state rather than a decoration:
 *
 * <ul>
 *   <li><b>The pitch is the rotor speed.</b> The recording is the blade-pass tone of a rotor at 4650 rpm, so
 *       transposing it is not a style choice - it is what the machine does when the controller asks for more.
 *       The loop eases between an idling rotor and one at full bite, following the same throttle the
 *       airframe's own flight controller is commanding, so the whine rises as the drone accelerates and
 *       settles as it holds station.</li>
 *   <li><b>In a storm it goes to full pitch and stays there.</b> A drone that has lost its fight with the
 *       wind is still holding the throttle wide open - every motor is at its limit, which is exactly what it
 *       sounds like, and it is the last thing the pilot hears from the airframe before it is carried off.</li>
 *   <li><b>It is positioned at the airframe, not at the pilot.</b> Fly it 300 blocks into a storm and the
 *       sound goes with it: the pilot standing still hears it fade out, which is the honest cue that the
 *       drone has gone somewhere.</li>
 * </ul>
 *
 * <p>How far it carries is not a guess, and it is the same arithmetic the sirens use: Minecraft plays a
 * positioned sound through OpenAL's linear distance model, sized from {@code max(volume, 1) * 16} blocks, so
 * the volume knob is a radius. A small drone is audible about 56 blocks, which is 3.5 units of volume. Two
 * consequences the engine imposes are handled here: a volume of exactly zero at play time makes the engine
 * drop the instance outright, so the loop starts a hair above silence, and a fading loop that reaches exactly
 * zero would be dropped rather than faded, so it is retired before that.
 *
 * <p>There is no subtitle. Subtitles are for one-shot cues - a subtitle for a continuous two-second loop
 * would sit on the screen for as long as a drone hovers near you, which is not an accessibility win.
 */
public final class WeatherDroneSound extends AbstractTickableSoundInstance {
    /** How far a drone is audible, in blocks. Turned into a volume below, because volume is the radius. */
    private static final float AUDIBLE_RADIUS_BLOCKS = 56.0F;
    /** The engine sizes a variable-range sound's attenuation from this many blocks per unit of volume. */
    private static final float BLOCKS_PER_VOLUME = 16.0F;
    /** Above zero deliberately: the engine refuses to play an instance whose volume is zero. */
    private static final float MIN_VOLUME = 0.02F;
    /** Below this the loop is retired rather than left running. */
    private static final float SILENCE = 0.012F;
    /** How fast the loop comes up to speed and how fast a rotor changes pitch, per tick. */
    private static final float SPIN_UP = 0.06F;
    private static final float PITCH_EASE = 0.08F;
    /** The rotor's own speed range, as a multiplier on the recorded 4650 rpm. */
    private static final float IDLE_PITCH = 0.82F;
    private static final float FULL_PITCH = 1.26F;

    private static final Map<Integer, WeatherDroneSound> RUNNING = new HashMap<>();

    private final ClientLevel level;
    private final int droneId;
    private float targetPitch = IDLE_PITCH;
    private float targetVolume = MIN_VOLUME;
    private boolean wanted;

    private WeatherDroneSound(ClientLevel level, WeatherDroneEntity drone) {
        super(ModContent.DRONE_ROTOR_SOUND.get(), SoundSource.NEUTRAL, RandomSource.create());
        this.level = level;
        this.droneId = drone.getId();
        this.looping = true;
        this.delay = 0;
        this.attenuation = SoundInstance.Attenuation.LINEAR;
        this.volume = MIN_VOLUME;
        this.pitch = IDLE_PITCH;
        this.x = drone.getX();
        this.y = drone.getY();
        this.z = drone.getZ();
    }

    /**
     * Keeps the loops in step with the drones a player can hear.
     *
     * <p>Called every client tick. Deliberately idempotent and keyed by entity id: a sound started per tick
     * would stack hundreds of copies on top of each other, and a drone that leaves earshot has to be told so
     * rather than left running forever at a position nobody is near.
     */
    public static void schedule() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            for (WeatherDroneSound sound : new ArrayList<>(RUNNING.values())) {
                sound.wanted = false;
            }
            return;
        }
        Set<Integer> heard = new HashSet<>();
        // Every airframe within earshot gets a loop, including one whose battery has just gone flat: the
        // loop itself decides that a dead rotor is on its way down, and that coast-down is worth hearing.
        for (WeatherDroneEntity drone : level.getEntitiesOfClass(WeatherDroneEntity.class,
                minecraft.player.getBoundingBox().inflate(AUDIBLE_RADIUS_BLOCKS), candidate -> true)) {
            heard.add(drone.getId());
            set(level, drone);
        }
        for (Map.Entry<Integer, WeatherDroneSound> entry : new ArrayList<>(RUNNING.entrySet())) {
            if (!heard.contains(entry.getKey())) {
                entry.getValue().wanted = false;
            }
        }
    }

    private static void set(ClientLevel level, WeatherDroneEntity drone) {
        WeatherDroneSound sound = RUNNING.get(drone.getId());
        // A drone that was unloaded and loaded again is a different instance with the same id only by
        // accident, and a stopped instance is worse than none: the sound engine drops every instance on a
        // level change and an entry left behind by that would be handed out as a sound nobody can hear.
        if (sound != null && (sound.level != level || !Minecraft.getInstance().getSoundManager().isActive(sound))) {
            sound.retire();
            sound = null;
        }
        if (sound == null) {
            sound = new WeatherDroneSound(level, drone);
            RUNNING.put(drone.getId(), sound);
            Minecraft.getInstance().getSoundManager().play(sound);
        }
        sound.wanted = true;
        sound.apply(drone);
    }

    /** The rotor's speed and loudness, taken from the airframe's own state. */
    private void apply(WeatherDroneEntity drone) {
        this.x = drone.getX();
        this.y = drone.getY();
        this.z = drone.getZ();
        // Wide open when it has lost control: the motors are at their limit whether or not the airframe is
        // going anywhere, and hearing the whine peak is how a pilot knows the drone has stopped flying.
        float throttle = drone.overwhelmed() ? 1.0F : Mth.clamp(drone.throttle(), 0.0F, 1.0F);
        targetPitch = IDLE_PITCH + (FULL_PITCH - IDLE_PITCH) * throttle;
        targetVolume = AUDIBLE_RADIUS_BLOCKS / BLOCKS_PER_VOLUME;
    }

    @Override
    public void tick() {
        WeatherDroneEntity drone = level.getEntity(droneId) instanceof WeatherDroneEntity found ? found : null;
        if (drone == null || drone.isRemoved() || drone.flatBattery()) {
            // A drone that is gone, or one whose battery is flat, has rotors that are not turning.
            wanted = false;
        } else {
            apply(drone);
        }
        if (!wanted) {
            targetVolume = 0.0F;
        }
        volume = Mth.lerp(SPIN_UP, volume, targetVolume);
        pitch = Mth.lerp(PITCH_EASE, pitch, targetPitch);
        // The coast-down keeps a floor above zero while it lasts: a fading loop that reaches exactly zero is
        // dropped by the engine instead of fading, which is a click rather than a rotor winding down.
        if (!wanted && volume < SILENCE) {
            retire();
        }
    }

    /**
     * Ends this loop and drops it from the table.
     *
     * <p>{@code stop()} itself cannot be overridden - the base class owns it so the engine can always shut
     * an instance down - which is why every path that finishes a loop goes through the bookkeeping here.
     */
    private void retire() {
        Iterator<Map.Entry<Integer, WeatherDroneSound>> entries = RUNNING.entrySet().iterator();
        while (entries.hasNext()) {
            if (entries.next().getValue() == this) {
                entries.remove();
            }
        }
        stop();
    }

    /** How many drones are currently audible, for diagnostics. */
    public static int runningCount() {
        return RUNNING.size();
    }
}
