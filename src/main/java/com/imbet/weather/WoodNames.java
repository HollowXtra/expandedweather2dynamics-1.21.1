package com.imbet.weather;

/**
 * The names a wood type's other blocks go by, worked out from the wood's own name.
 *
 * <p>A tree belongs to a mod as often as it belongs to the game. A biome mod adds its own woods - fir and
 * redwood and mahogany - and the game knows nothing about them, so anything here that wants to know what a fir
 * log strips to, or that a fir trunk wears fir leaves, has to find that out from the world rather than from a
 * list written into this mod. A list was the problem: this mod carried a table of the game's own eight woods, so
 * a tornado would debark an oak and leave a fir standing dressed, in exactly the biomes a player went to a biome
 * mod for.
 *
 * <p>What replaces the list is the one thing every wood in the game shares, and shares strictly, because
 * resource packs, recipes, tags and the game's own axe all assume it: a wood is named after its wood. Oak's log
 * is {@code oak_log}, its bark-covered log is {@code oak_wood}, and both strip to the same names with
 * {@code stripped_} in front; its leaves are {@code oak_leaves}. So a log's own registry name is enough to have
 * the other names, and a registry lookup is enough to have the blocks - which is what {@link WoodForms} does with
 * what is worked out here.
 *
 * <p>The convention holds for the mods as well as for the game, which is why this works at all: Biomes O' Plenty's
 * {@code fir_log} strips to {@code stripped_fir_log} and wears {@code fir_leaves}, and so does every other wood
 * shipped by a biome mod that supports an axe. A wood that broke the convention would still be found by
 * {@link WoodForms} if the mod registered it with the game - and would still be left alone, rather than wrongly
 * stripped, if it did not.
 *
 * <p>This class is deliberately free of Minecraft types. It is arithmetic on a name, so the harness can run it
 * against the names of the woods a player actually has - the game's own, and the ones the big biome mods ship -
 * without a game to run it in.
 */
public final class WoodNames {

    /**
     * The suffixes a wood's own blocks end in: a log, the same log barked all round, and the nether's two. The
     * two nether names are here for completeness rather than for their leaves - a crimson stem has none - because
     * a wood whose stripped form is wanted is as likely to be a stem as a log.
     */
    private static final String[] SUFFIXES = {"_log", "_wood", "_stem", "_hyphae"};

    /** The prefix a stripped form of a block carries. */
    private static final String STRIPPED = "stripped_";

    private WoodNames() {
    }

    /**
     * The name a wood's stripped form is expected under: {@code oak_log} is {@code stripped_oak_log}, and
     * {@code crimson_stem} is {@code stripped_crimson_stem}. A name that already carries the prefix is given it
     * twice, which finds nothing - the right answer for a block that is already stripped.
     */
    public static String strippedPath(String path) {
        return path == null || path.isEmpty() ? "" : STRIPPED + path;
    }

    /**
     * The name a wood's leaves are expected under: {@code dark_oak_log} and {@code dark_oak_wood} are both
     * {@code dark_oak_leaves}. A name that is not one of a wood's own blocks keeps its whole name and gets
     * {@code _leaves} appended, which is right for a leaf that is already named after its wood and finds nothing
     * for anything else.
     */
    public static String leafPath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        return woodBase(path) + "_leaves";
    }

    /**
     * The wood a block's name belongs to: {@code dark_oak_log} is {@code dark_oak}, {@code cherry_wood} is
     * {@code cherry}, and {@code oak} is {@code oak}. A stripped form is unwrapped first, so the leaves of a
     * stripped log are still its wood's leaves - which matters in a world where a player can leave one lying
     * about.
     */
    public static String woodBase(String path) {
        if (path == null) {
            return "";
        }
        String base = path;
        if (base.length() > STRIPPED.length() && base.startsWith(STRIPPED)) {
            base = base.substring(STRIPPED.length());
        }
        for (String suffix : SUFFIXES) {
            if (base.length() > suffix.length() && base.endsWith(suffix)) {
                return base.substring(0, base.length() - suffix.length());
            }
        }
        return base;
    }
}
