package com.imbet.weather;

import com.imbet.Config;
import com.imbet.ExpandedWeather2Dynamics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;

/**
 * The gate every land tornado genesis goes through: the ground under the genesis point, read once,
 * and the plains rule applied to it.
 *
 * <p>The rule itself lives in {@link TornadoGenesisChecker} and this is the reading half - the
 * biome at the position, whether it is in the genesis tag, and the config switch. Everything that
 * promotes a storm to a tornado, or seeds a landspout, asks here first, so there is exactly one
 * answer to "may a tornado be born at this position" in the whole mod.
 *
 * <p>A position this gate cannot read - an unloaded chunk - is allowed, not refused. The gate is
 * meant to keep a tornado out of a forest, not to make a world where a storm over unloaded ground
 * can never spin up; a reading this mod could not take is not evidence that the ground is wrong.
 */
public final class TornadoGenesisGate {

    /**
     * The biomes a tornado may be born over, beyond the ones the air itself reads as plains.
     * Datapacks extend it; the mod ships the vanilla plains.
     */
    public static final TagKey<Biome> GENESIS_BIOMES =
            TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "tornado_genesis"));

    private TornadoGenesisGate() {
    }

    /** Whether a tornado may be born at a position, which is what every genesis path asks. */
    public static boolean allows(ServerLevel level, Vec3 pos) {
        return pos == null || allows(level, BlockPos.containing(pos));
    }

    /** Whether a tornado may be born at a position, which is what every genesis path asks. */
    public static boolean allows(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return true;
        }
        return TornadoGenesisChecker.allows(ground(level, pos), Config.tornadoGenesisPlainsOnly());
    }

    /** What the ground at a position is, or that it could not be read. */
    public static TornadoGenesisChecker.Ground ground(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || !level.isLoaded(pos)) {
            return TornadoGenesisChecker.Ground.UNREADABLE;
        }
        return TornadoGenesisChecker.Ground.of(family(level, pos), tagListed(level, pos));
    }

    /**
     * The same answer in words, for a command or a log: what the ground is and whether a tornado
     * belongs on it. Reports the rule as enabled even while the config has it off, so a player can
     * see what the ground would have decided.
     */
    public static String verdict(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return "no position to read";
        }
        boolean plainsOnly = Config.tornadoGenesisPlainsOnly();
        TornadoGenesisChecker.Ground ground = ground(level, pos);
        if (!ground.readable()) {
            return "ground not loaded (allowed while the rule is " + (plainsOnly ? "on" : "off") + ")";
        }
        return TornadoGenesisChecker.describe(ground.family(), ground.tagListed(), plainsOnly);
    }

    /** Which family the biome at a position reads as, through the same classifier the air uses. */
    public static BiomeClimate.Family family(ServerLevel level, BlockPos pos) {
        Holder<Biome> holder = level.getBiome(pos);
        Biome biome = holder.value();
        String path = holder.unwrapKey().map(key -> key.location().getPath()).orElse("");
        return BiomeClimate.classify(new BiomeClimate.Traits(
                biome.getBaseTemperature(),
                biome.hasPrecipitation(),
                biome.coldEnoughToSnow(pos),
                path));
    }

    /** Whether the biome at a position is one a datapack has put in the genesis tag. */
    public static boolean tagListed(ServerLevel level, BlockPos pos) {
        return level.getBiome(pos).is(GENESIS_BIOMES);
    }

    /** The biome's own resource path at a position, for a readout that wants to name it. */
    public static String biomePath(ServerLevel level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey().map(key -> key.location().toString()).orElse("unknown");
    }
}
