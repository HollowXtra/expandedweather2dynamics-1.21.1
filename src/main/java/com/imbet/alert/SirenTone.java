package com.imbet.alert;

import com.imbet.registry.ModContent;
import net.minecraft.sounds.SoundEvent;

/**
 * The three sirens this mod adds, and the tone each one actually makes.
 *
 * <p>Each tone is a real recording, cut to one cycle of the sound it makes and looped, rather than a tone
 * generated from a description of the siren - a field recording carries the horn's own resonance, the
 * slap of the rotor and the room it is standing in, and none of that is in a sine wave. The pitches and
 * cycle lengths below were measured off the installed files rather than assumed.
 *
 * <ul>
 *   <li>A <b>mechanical</b> siren is a rotor chopping air, driven by a motor that cannot hold a speed: it
 *       accelerates against the rotor's drag and coasts back down, which is the wail. This one climbs from
 *       about 430 Hz to a held 686 Hz over four seconds, holds while the motor is at speed, falls back
 *       over the next four, and turns over on an 11.445 second cycle - the classic American outdoor warning
 *       sound, recorded outdoors. Cut between two of the recording's own turn-overs, which are some thirty
 *       decibels down, so the loop joins in near-silence.</li>
 *   <li>An <b>electronic</b> siren is an amplifier driving horn speakers, so its pitch is set by a tone
 *       generator and does not drift. The recording shipped here is a real Whelen head running its
 *       <i>wail</i>: a continuous descending sweep from 1080 Hz to 840 Hz in 1.70 seconds, closed on itself
 *       with a 40 ms crossfade because that recording offers no silence to cut at.</li>
 *   <li>A <b>two-tone</b> siren switches between two notes, each perfectly steady and changing abruptly.
 *       No field recording of that pattern under a licence this mod can ship was found, so this is an
 *       arrangement of two notes from the same recorded siren as the mechanical tone - its steady alert
 *       note at 686 Hz against the slowest part of its wail's descent, gliding 480 Hz to 420 Hz - alternating
 *       every 0.94 seconds. Every sample is a real recording; only the alternation is ours.</li>
 * </ul>
 *
 * <p>Each loop is levelled to the tone it replaced, so swapping the audio did not change the mix, and each
 * is checked at build time for a seam the ear could hear, for clipping, and for a gap long enough to read as
 * a stutter. Sources and licences are credited in the project README's Audio Credits section.
 */
public enum SirenTone {
    MECHANICAL("mechanical", "Mechanical Siren", "Rotor wail, 430-690 Hz",
            "sirenMechanical", 212.1D),
    ELECTRONIC("electronic", "Electronic Siren", "Falling sweep, 840-1080 Hz",
            "sirenElectronic", 10.0D),
    HILO("hilo", "Two-Tone Siren", "Two-tone, 686 Hz / 420-480 Hz glide",
            "sirenHilo", 36.7D);

    private final String id;
    private final String label;
    private final String spec;
    private final String translationKey;
    private final double sampleKib;

    SirenTone(String id, String label, String spec, String translationKey, double sampleKib) {
        this.id = id;
        this.label = label;
        this.spec = spec;
        this.translationKey = translationKey;
        this.sampleKib = sampleKib;
    }

    /** The registry id, which is also the suffix of the block, item and sound. */
    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    /** A one-line description of the tone, shown when the block is used. */
    public String spec() {
        return spec;
    }

    /** The lang key for this block's display name. */
    public String blockTranslationKey() {
        return "block.expandedweather2dynamics." + id + "_siren";
    }

    public double sampleKib() {
        return sampleKib;
    }

    /**
     * The sound this tone plays.
     *
     * <p>Looked up rather than stored, because an enum constant is created while the class loads and the
     * registry holder it reads from is filled during mod construction. Asking at play time is the only
     * order that is safe on both sides.
     */
    public SoundEvent sound() {
        return switch (this) {
            case MECHANICAL -> ModContent.SIREN_MECHANICAL_SOUND.get();
            case ELECTRONIC -> ModContent.SIREN_ELECTRONIC_SOUND.get();
            case HILO -> ModContent.SIREN_HILO_SOUND.get();
        };
    }

    /**
     * How long one cycle of this tone lasts, in seconds: one turn of the wail, one sweep, one hi-lo pair.
     *
     * <p>Measured off the installed recording rather than chosen. The file may hold more than one cycle of
     * it - the mechanical and two-tone files hold two - but this is the period the rotor, the rock and the
     * beacon lamp follow, so the siren's look moves with its sound.
     */
    public double cycleSeconds() {
        return switch (this) {
            case MECHANICAL -> 11.445D;
            case ELECTRONIC -> 1.70D;
            case HILO -> 1.88D;
        };
    }

    public static SirenTone byId(String id) {
        for (SirenTone tone : values()) {
            if (tone.id.equals(id)) {
                return tone;
            }
        }
        return MECHANICAL;
    }
}
