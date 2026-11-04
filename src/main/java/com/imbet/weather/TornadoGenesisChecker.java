package com.imbet.weather;

import java.util.Locale;

/**
 * The rule that decides where a tornado is allowed to be born: over plains, and nowhere else.
 *
 * <p>A tornado is the one thing in this mod that is tied to the country it stands on. Every other
 * vortex here is a reading of the air - a dust devil is dry air, a waterspout is water, a fire
 * whirl is a fire - and they form wherever their own condition is met. A land tornado is different:
 * the environment it needs is the flat, open, loaded air a plains region hands up, and the storms
 * that make one are the storms that plains country makes. Letting it form anywhere made the
 * funnel a decoration on whatever biome the storm happened to drift over, which is what this rule
 * fixes - it is a check on the ground under the genesis point, asked once, at the moment a storm
 * would otherwise be promoted to a tornado.
 *
 * <p>Two ways in, and either one is enough. The biome's own reading is the first: a biome that
 * classifies as plains country - vanilla plains, a meadow, a modded prairie, anything whose name
 * or numbers say plains - is plains, so a pack's own biome works without anybody listing it. The
 * second is the biome tag {@code #expandedweather2dynamics:tornado_genesis}, which is how a pack
 * says "and this one too" about a biome the reading would never call plains: a savanna someone
 * wants in tornado alley, a badlands, an alien flatland. The tag ships holding the vanilla plains
 * biomes, so the rule works out of the box and a datapack extends it without touching a line of
 * code or a config file.
 *
 * <p>This class is arithmetic only - no Minecraft types - so the whole rule can be exercised
 * without a world, which is where its cases are checked.
 */
public final class TornadoGenesisChecker {

    private TornadoGenesisChecker() {
    }

    /** Whether a family is plains country, which is the family a tornado belongs to. */
    public static boolean plains(BiomeClimate.Family family) {
        return family == BiomeClimate.Family.PLAINS;
    }

    /**
     * What the ground under a genesis point turned out to be: which family it reads as, whether it is
     * in the genesis tag, and whether it could be read at all. An unreadable ground is its own case
     * rather than a null family, because the two mean opposite things - one is a position the mod
     * could not judge and the other is a position it judged and did not like.
     */
    public record Ground(boolean readable, BiomeClimate.Family family, boolean tagListed) {

        /** A position the mod could not read: an unloaded chunk, or no level at all. */
        public static final Ground UNREADABLE = new Ground(false, null, false);

        /** Ground that has been read. */
        public static Ground of(BiomeClimate.Family family, boolean tagListed) {
            return new Ground(true, family, tagListed);
        }
    }

    /**
     * Whether a tornado may be born on this ground. The switch turns the whole rule off, which is the
     * behaviour every world had before this existed; ground the mod could not read is allowed, because
     * a reading it could not take is not evidence that the ground is wrong.
     */
    public static boolean allows(Ground ground, boolean plainsOnly) {
        if (ground == null || !ground.readable()) {
            return true;
        }
        if (!plainsOnly) {
            return true;
        }
        return ground.tagListed() || plains(ground.family());
    }

    /** The same answer from a family already read, for callers that have one and no position. */
    public static boolean allows(BiomeClimate.Family family, boolean tagListed, boolean plainsOnly) {
        return allows(Ground.of(family, tagListed), plainsOnly);
    }

    /**
     * The same decision in words, for a command or a log: what the ground here is, and whether a
     * tornado could be born on it. The reason is written to be read by whoever is debugging why
     * their world has no funnels.
     */
    public static String describe(BiomeClimate.Family family, boolean tagListed, boolean plainsOnly) {
        String ground = family == null ? "unknown ground" : family.label().toLowerCase(Locale.ROOT);
        if (!plainsOnly) {
            return ground + " (allowed - the plains-only rule is switched off)";
        }
        if (tagListed) {
            return ground + " (allowed - in the tornado genesis biome tag)";
        }
        if (plains(family)) {
            return ground + " (allowed - plains country)";
        }
        return ground + " (denied - a tornado is only born over plains country, or over a biome in the "
                + "tornado genesis tag)";
    }
}
