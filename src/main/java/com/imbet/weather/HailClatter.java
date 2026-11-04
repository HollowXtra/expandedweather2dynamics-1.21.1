package com.imbet.weather;

import java.util.Locale;

/**
 * What hail sounds like when it lands, as arithmetic.
 *
 * <p>The mod has always drawn hail and, since {@link HailDamage}, let it break things. What it has never done is
 * let a player <em>hear</em> it, and hail is the one hazard here that a player experiences first through their ears:
 * you are inside, the light goes green, and then the roof starts. That is the whole feature - not a looped
 * hailstorm ambience, but a clatter that is made of the stones actually landing on the things around you, which
 * means the sound has to answer two questions the world already knows the answers to: <em>what</em> is being hit,
 * and <em>how hard</em>.
 *
 * <p>Both answers are material. A wooden roof is a hollow rattle, a stone one is a flat tick, a metal one rings
 * and goes on ringing, a glass pane is a bright ping, and a field of grass is a wet patter with no ring in it at
 * all - so the surface is read off the block's own sound type, which is the game's own answer to what a block is
 * made of, rather than off a second list of materials this class would have to keep in step with new blocks. That
 * is why {@link #surfaceOf(String)} takes a <em>name</em> rather than a block: this model stays free of Minecraft
 * types, and what it is handed is the resource path of the sound the block itself makes.
 *
 * <p>How hard is the size ladder {@link HailModel.Size} already owns: peas are a patter, quarters start to crack,
 * and a baseball is a strike you feel in the floor. Bigger stones are louder, land lower in pitch and land further
 * apart, which is the other half of what makes a hailstorm read as hail rather than as rain: a dense rattle of
 * small ticks and a sparse thud of big ones are different storms, and the pacing here is what keeps a busy core
 * from turning into a machine gun.
 *
 * <p>Arithmetic only, with no Minecraft types at all, so the ladder, the pacing and the material answer are all
 * checkable without a game running.
 */
public final class HailClatter {

    /** How many kinds of surface the clatter knows: one sound each. */
    public static final int SURFACE_COUNT = 6;

    /**
     * What the stones are landing on, as the materials a player can tell apart by ear.
     *
     * <p>Six rather than one sound per block type: what a player learns from the clatter is whether they are under
     * wood, stone, metal or glass, or out in a field, and a seventh material would cost a seventh sound file to say
     * something nobody can hear.
     */
    public enum Surface {
        /** Grass, dirt, gravel, sand, crops, leaves, wool: the wet patter of stones landing in the open. */
        SOFT("soft ground", "hail_soft", 0.45D, 0.78D),
        /** Planks, logs, a wooden roof: hollow, mid-pitched, quick to die. */
        WOOD("wood", "hail_wood", 0.80D, 1.00D),
        /** Stone, deepslate, brick, terracotta: a flat tick with almost no ring to it. */
        STONE("stone", "hail_stone", 0.90D, 1.06D),
        /** Packed ice and blue ice: a hard tick that shatters rather than rings. */
        ICE("ice", "hail_ice", 0.85D, 1.18D),
        /** Glass, including panes: a bright ping, and the one a player is listening for. */
        GLASS("glass", "hail_glass", 0.95D, 1.30D),
        /** Iron, copper, anvils, chains: the loudest and the longest, which is what a metal roof is for. */
        METAL("metal", "hail_metal", 1.00D, 1.40D);

        private final String label;
        private final String soundName;
        private final double loudness;
        private final double tone;

        Surface(String label, String soundName, double loudness, double tone) {
            this.label = label;
            this.soundName = soundName;
            this.loudness = loudness;
            this.tone = tone;
        }

        /** What a player would call it, for a readout or a log line. */
        public String label() {
            return label;
        }

        /** The name of the sound this surface makes, for the sound that is registered under it. */
        public String soundName() {
            return soundName;
        }

        /** How loud this material is against the others, before the size of the stone is taken into account. */
        public double loudness() {
            return loudness;
        }

        /** How high this material sits, before the size of the stone is taken into account. */
        public double tone() {
            return tone;
        }

        /** Whether this material rings and goes on ringing, which is why its strikes are spaced further apart. */
        public boolean rings() {
            return this == METAL || this == GLASS;
        }
    }

    private HailClatter() {
    }

    /**
     * What a block is made of, from the name of the sound it makes.
     *
     * <p>The caller hands in the resource path of the block's own sound - the hit or break sound off its sound type -
     * and the material falls out of the name the game already gave it. Order matters and is deliberate: glass is
     * asked before stone (a stained glass block's path contains both "glass" and nothing else useful), metal before
     * wood (a copper roof is metal even when the block is called a "copper grate"), and snow is asked as soft
     * ground rather than as ice, because powder snow is not a hard surface and does not tick like one. A name this
     * class has never seen is stone, which is the honest default: most solid blocks are.
     */
    public static Surface surfaceOf(String soundPath) {
        if (soundPath == null || soundPath.isBlank()) {
            return Surface.STONE;
        }
        String path = soundPath.toLowerCase(Locale.ROOT);
        if (path.contains("glass") || path.contains("amethyst")) {
            return Surface.GLASS;
        }
        if (path.contains("metal") || path.contains("copper") || path.contains("anvil") || path.contains("chain")
                || path.contains("iron") || path.contains("gold") || path.contains("lantern")
                || path.contains("bell") || path.contains("grindstone")) {
            return Surface.METAL;
        }
        if (path.contains("wood") || path.contains("log") || path.contains("plank") || path.contains("bamboo")
                || path.contains("scaffolding") || path.contains("ladder") || path.contains("barrel")) {
            return Surface.WOOD;
        }
        if (path.contains("ice") || path.contains("frost")) {
            return Surface.ICE;
        }
        if (path.contains("grass") || path.contains("gravel") || path.contains("sand") || path.contains("crop")
                || path.contains("vine") || path.contains("leaves") || path.contains("leaf")
                || path.contains("wool") || path.contains("moss") || path.contains("snow")
                || path.contains("slime") || path.contains("honey") || path.contains("coral")
                || path.contains("berry") || path.contains("plant") || path.contains("lily")
                || path.contains("cloth") || path.contains("dirt") || path.contains("clay")
                || path.contains("sculk") || path.contains("frogspawn")) {
            return Surface.SOFT;
        }
        return Surface.STONE;
    }

    /** Whether a stone size is big enough to make any noise at all. */
    public static boolean hasStone(HailModel.Size size) {
        return size != null && size != HailModel.Size.NONE;
    }

    /** The quietest a strike can be, which is a pea landing: the clatter is there, it is just a patter. */
    public static final float QUIETEST_STRIKE = 0.18F;

    /** The loudest a strike can be: a baseball on a metal roof, at the moment it lands. */
    public static final float LOUDEST_STRIKE = 0.95F;

    /**
     * How loud one strike on a surface is, from the size of the stone and the material it hit.
     *
     * <p>It is the stone's own rung on the size ladder, stretched between a pea and a baseball, times how loud that
     * material is against the others. Nothing here is ever louder than one: a hailstorm that clips is a hailstorm
     * nobody turns up, and the volume a player wants is the config's dial rather than this ladder's.
     */
    public static float volumeFor(Surface surface, HailModel.Size size) {
        if (surface == null || !hasStone(size)) {
            return 0.0F;
        }
        double share = size.tier() / (double) HailModel.Size.BASEBALL.tier();
        double strike = QUIETEST_STRIKE + (LOUDEST_STRIKE - QUIETEST_STRIKE) * share;
        return (float) Math.min(1.0D, strike * surface.loudness());
    }

    /** How much lower a bigger stone lands, per rung of the ladder. A baseball is a thud, not a tick. */
    public static final double PITCH_DROP_PER_TIER = 0.05D;

    /** How far a strike's pitch is allowed to wander, so a rattle of the same stone is not one repeated sample. */
    public static final double PITCH_JITTER = 0.06D;

    /**
     * The pitch one strike plays at.
     *
     * <p>Three things move it: the material's own tone, the size of the stone (bigger lands lower, by a twentieth of
     * the pitch a rung), and a roll of the dice so that two strikes that are identical as sounds are not identical
     * as pitches - which is the difference between hearing hail and hearing a loop. {@code roll} is expected in the
     * range nought to one and is clamped rather than trusted, because it is the one input that comes from outside
     * this class and a pitch outside the game's own accepted range is a sound that does not play at all.
     *
     * <p>There is deliberately no second clamp on the way out. The ladder is inside the range by construction - the
     * tones run from a field's 0.78 to a metal roof's 1.40, the jitter is a sixteenth of that and the size drop only
     * ever lowers a stone - and a clamp over numbers that cannot leave the range is dead code that hides the one
     * thing worth noticing, which is a material whose tone has been moved somewhere a player cannot hear it.
     */
    public static float pitchFor(Surface surface, HailModel.Size size, double roll) {
        if (surface == null) {
            return 1.0F;
        }
        int tier = hasStone(size) ? size.tier() : 1;
        double sizeDrop = 1.0D - PITCH_DROP_PER_TIER * Math.max(0, tier - 1);
        double jitter = PITCH_JITTER * (clampRoll(roll) * 2.0D - 1.0D);
        return (float) ((surface.tone() + jitter) * sizeDrop);
    }

    /** How long the same surface stays quiet after a strike, before a rung of stones: the solid materials. */
    public static final int SOLID_GAP_TICKS = 5;

    /** The same, for the two materials that ring: a metal roof repeats itself, so it is asked less often. */
    public static final int RESONANT_GAP_TICKS = 9;

    /** No surface is asked more often than this, whatever the ladder says. */
    public static final int MIN_GAP_TICKS = 4;

    /** And none is left silent for longer than this while stones are still falling. */
    public static final int MAX_GAP_TICKS = 24;

    /**
     * How long one surface stays quiet between two strikes, in ticks.
     *
     * <p>This is the whole of the anti-machine-gun rule, and it is per surface rather than global on purpose: a
     * player standing under a glass roof between a metal one and a field is entitled to all three at once, because
     * three different things really are being hit. What is not allowed is the same tick repeated - a big stone is
     * louder and rarer than a small one, and a surface that rings is asked less often than one that thuds, because
     * a ring has not finished before the next strike arrives.
     */
    public static int gapTicks(Surface surface, HailModel.Size size) {
        int tier = hasStone(size) ? size.tier() : 1;
        int base = surface != null && surface.rings() ? RESONANT_GAP_TICKS : SOLID_GAP_TICKS;
        return Math.max(MIN_GAP_TICKS, Math.min(MAX_GAP_TICKS, base + tier));
    }

    /** Whether this surface has been quiet long enough to strike again. */
    public static boolean due(int ticksSinceLast, Surface surface, HailModel.Size size) {
        return ticksSinceLast >= gapTicks(surface, size);
    }

    /**
     * How far around a player the stones are looked for, in blocks.
     *
     * <p>It grows with the ladder because a bigger stone is heard hitting things further away as well as hitting
     * harder - and it is bounded because this is a scan per player per pass, and a scan that reaches across a
     * village to say "hail is landing somewhere" is a sound with nothing to do with where the player is.
     */
    public static int sampleRadiusBlocks(HailModel.Size size) {
        return 6 + (hasStone(size) ? size.tier() : 0);
    }

    /** How many strikes one player's own surroundings are allowed to produce in a single pass. */
    public static final int MAX_STRIKES_PER_PASS = 3;

    /** How far above a player the stones' landing surface is looked for: a roof, or the sky that says there is none. */
    public static final int ROOF_SEARCH_BLOCKS = 6;

    /** How far a single strike is heard from, in blocks, before it has faded to nothing. */
    public static final double HEARD_ACROSS_BLOCKS = 18.0D;

    /** How quiet a strike gets at the edge of what can be heard: audible, and clearly not at your feet. */
    public static final double FAINTEST_SHARE = 0.15D;

    /**
     * How much of a strike is left after it has travelled, as a share.
     *
     * <p>It falls off with distance rather than being all or nothing, because the useful thing a player learns from
     * the clatter is where it is: a strike right overhead is at full weight, one at the far edge of the heard-across
     * distance is a faint tick that says the stones are landing over there. Below the faintest share everything is
     * treated as silence, so a scan never plays a sound nobody can hear.
     */
    public static float dampingFor(double distanceBlocks) {
        double faded = 1.0D - Math.max(0.0D, distanceBlocks) / HEARD_ACROSS_BLOCKS;
        return (float) Math.max(FAINTEST_SHARE, Math.min(1.0D, faded));
    }

    /** Whether a strike at this distance is worth playing at all. */
    public static boolean heardAt(double distanceBlocks) {
        return distanceBlocks <= HEARD_ACROSS_BLOCKS;
    }

    private static double clampRoll(double roll) {
        if (!Double.isFinite(roll)) {
            return 0.5D;
        }
        return Math.max(0.0D, Math.min(1.0D, roll));
    }
}
