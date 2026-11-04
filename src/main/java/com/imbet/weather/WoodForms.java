package com.imbet.weather;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * The other blocks of a wood type - the stripped form of a log, the leaves of a tree - found in the registry rather
 * than listed here.
 *
 * <p>This is what lets everything the mod does to a tree work on a tree from any mod. What it replaces was a table
 * of the game's own eight woods written into the tornado's debarking, which is a table that is wrong the moment a
 * player installs a biome mod: the woods they came for are not in it, so those trees were the ones left standing
 * dressed while the vanilla ones around them were stripped.
 *
 * <p>What is looked up is the block a wood's own name says should exist - the convention {@link WoodNames} holds:
 * {@code fir_log} for {@code stripped_fir_log}, {@code dark_oak_log} for {@code dark_oak_leaves}, in whatever
 * namespace the wood came from. The game's own axe keeps a table of the same pairs, but it is protected on
 * {@code AxeItem} and this mod does not carry an access transformer for one cosmetic lookup, so the name is the
 * route - and it is the route the woods themselves are named by, because recipes, tags and resource packs all
 * assume it. A wood whose blocks are named some other way is not found, which leaves it exactly as it is rather
 * than stripping it into something wrong.
 *
 * <p>Every lookup is verified against the tag it claims to be: a block offered as a stripped form has to be a log,
 * and a block offered as leaves has to be leaves. A name that happens to land on the wrong block therefore finds
 * nothing rather than putting a plank in a felled crown, which is the failure mode of guessing by name, closed
 * off. Both lookups answer null rather than something plausible, so a caller can decide what a wood with no
 * stripped form - a mod's decorative bark, a nether stem with no leaves at all - should do instead.
 *
 * <p>Nothing is cached: a registry lookup is a map read, the callers run a few times a second at most, and a cache
 * could hold an answer taken before a mod finished registering its woods.
 */
public final class WoodForms {

    private WoodForms() {
    }

    /**
     * The block a log strips to, or null when this wood has no stripped form. {@code fir_log} is looked up as
     * {@code stripped_fir_log} in the log's own namespace, and only accepted if it is a log.
     */
    public static Block stripped(Block log) {
        return log == null ? null : sibling(log, true, BlockTags.LOGS);
    }

    /**
     * The leaves of a wood, or null when it has none - which is every nether wood, and every block that is not one
     * of a wood's own. {@code dark_oak_log} and {@code dark_oak_wood} are both looked up as {@code dark_oak_leaves},
     * and only accepted if it is leaves.
     */
    public static Block leaves(Block wood) {
        return wood == null ? null : sibling(wood, false, BlockTags.LEAVES);
    }

    /**
     * The block named after this one by the convention, inside the same namespace, or null when nothing is
     * registered under that name or what is registered is not what was asked for.
     */
    private static Block sibling(Block block, boolean stripped, TagKey<Block> expected) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        if (id == null) {
            return null;
        }
        String name = stripped ? WoodNames.strippedPath(id.getPath()) : WoodNames.leafPath(id.getPath());
        Block found;
        try {
            found = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(id.getNamespace(), name));
        } catch (RuntimeException malformed) {
            // A name the game will not accept is a name nothing is registered under, and no reason to fall over.
            return null;
        }
        if (found == null || found == Blocks.AIR) {
            return null;
        }
        return found.defaultBlockState().is(expected) ? found : null;
    }
}
