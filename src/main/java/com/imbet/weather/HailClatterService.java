package com.imbet.weather;

import com.imbet.Config;
import com.imbet.registry.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hail landing on the world, heard rather than seen.
 *
 * <p>{@link HailClatter} owns what a strike sounds like and how often one surface is allowed to strike again; this is
 * the part that has to look at the world, and it looks at exactly one thing per player: what the stones are landing
 * on around them. The roof over a player's head is the loud one - it is the sound of being inside a hailstorm - and
 * it is found by walking up the column from the player's own head, so a player under three blocks of stone hears
 * stone and a player in the open hears the field.
 *
 * <p>Around that, a handful of columns are sampled per pass within the model's own radius, which is what makes the
 * clatter <em>dynamic</em> rather than a state: a player standing under a wooden roof with a metal barn thirty blocks
 * away hears the wood under them and, when the sample lands on the barn, the metal as well - and a player who walks
 * out of the barn and into the field hears the patter replace the ring inside a couple of seconds, without anything
 * being told that the player moved.
 *
 * <p>Two rules keep it from becoming noise. Nothing is played for a surface that is still ringing from its last
 * strike, which is {@link HailClatter#due(int, HailClatter.Surface, HailModel.Size)} and is per player as well as per
 * surface, so two players in the same storm do not silence each other. And nothing is played for a strike already
 * too far away to hear, because the useful thing the clatter tells a player is where it is.
 *
 * <p>It runs off {@link HailDamage}'s own pass, so the clatter starts exactly where the damage starts and stops
 * when it stops: a storm with no stones falling is a storm with nothing to hear.
 */
public final class HailClatterService {

    /** How often the stones are listened for, in ticks. Short, because a rattle is not a tick. */
    public static final int PASS_TICKS = 4;

    /** How many columns around a player are sampled in one pass, over and above the one they are under. */
    private static final int SAMPLES_PER_PASS = 3;

    /** When each player last heard each surface strike, so one surface cannot machine-gun at them. */
    private static final Map<UUID, Map<HailClatter.Surface, Long>> LAST_STRUCK = new ConcurrentHashMap<>();

    /** How old a player's own record has to be before it is dropped while they are offline. */
    private static final long FORGET_AFTER_TICKS = 24000L;

    private HailClatterService() {
    }

    /**
     * Listens to one storm's stones for every player standing under them.
     *
     * <p>Called from the damage pass with the footprint it already worked out, so a player hears hail exactly where
     * the mod says hail is falling rather than wherever a storm happens to be.
     */
    public static void tick(ServerLevel level, Vec3 centre, double radius, HailModel.Size size) {
        if (level == null || centre == null || radius <= 0.0D) {
            return;
        }
        if (!Config.HAIL_CLATTER_ENABLED.get()) {
            return;
        }
        if (!HailClatter.hasStone(size)) {
            return;
        }
        if (level.getGameTime() % PASS_TICKS != 0L) {
            return;
        }
        double master = Math.max(0.0D, Config.HAIL_CLATTER_VOLUME.get());
        if (master <= 0.0D || level.players().isEmpty()) {
            return;
        }
        forgetTheGone(level);
        // How far to listen is two answers, and the smaller wins: what the player has asked for, and what the stones
        // are worth listening for - a pea's clatter is not worth chasing across a field, and a baseball's is. The
        // model's own ladder decides the second, so the knob is a ceiling rather than a second opinion about it.
        int reach = Math.min(Config.HAIL_CLATTER_RADIUS.get(), HailClatter.sampleRadiusBlocks(size));
        for (ServerPlayer player : level.players()) {
            Vec3 at = player.position();
            double dx = at.x - centre.x;
            double dz = at.z - centre.z;
            if (dx * dx + dz * dz > radius * radius) {
                continue;
            }
            clatter(level, player, size, master, reach);
        }
    }

    /**
     * One player's own clatter: the surface over their head first, then a few columns around them.
     *
     * <p>The roof is asked about before the storm is: a player inside hears the roof and almost nothing else, which
     * is the reason a house is shelter in this mod rather than a smaller amount of damage. The samples around them
     * are what put the rest of the neighbourhood into the mix, and they are taken at random offsets rather than on a
     * grid so that two players standing together do not hear the identical three strikes.
     */
    private static void clatter(ServerLevel level, ServerPlayer player, HailModel.Size size, double master, int reach) {
        double roll = level.random.nextDouble();
        boolean struck = false;
        BlockPos roof = landingSurface(level, player.blockPosition().above(2));
        if (roof != null) {
            HailClatter.Surface surface = surfaceOf(level, roof);
            struck = strike(level, player, surface, Vec3.atCenterOf(roof), size, master, roll);
        } else {
            // Nothing overhead: the stones are landing in the field the player is standing in, which is the one case
            // where the ground under their feet is what they hear.
            BlockPos ground = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    player.blockPosition());
            if (level.isLoaded(ground)) {
                struck = strike(level, player, surfaceOf(level, ground), Vec3.atCenterOf(ground), size, master, roll * 0.8D);
            }
        }
        int samples = Math.min(SAMPLES_PER_PASS, Math.max(0, HailClatter.MAX_STRIKES_PER_PASS - 1));
        for (int i = 0; i < samples; i++) {
            if (!struck && i >= 1) {
                // Nothing has been heard yet this pass, so the budget goes on finding the surface the stones are
                // really landing on rather than on three more quiet ones.
                continue;
            }
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            double distance = Math.sqrt(level.random.nextDouble()) * Math.max(1, reach);
            BlockPos column = BlockPos.containing(player.getX() + Math.cos(angle) * distance,
                    player.getY(), player.getZ() + Math.sin(angle) * distance);
            if (!level.isLoaded(column)) {
                continue;
            }
            BlockPos landing = landingSurface(level, column);
            if (landing == null) {
                landing = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
            }
            if (!level.isLoaded(landing)) {
                continue;
            }
            Vec3 at = Vec3.atCenterOf(landing);
            double heard = at.distanceTo(player.getEyePosition());
            if (!HailClatter.heardAt(heard)) {
                continue;
            }
            strike(level, player, surfaceOf(level, landing), at, size, master, level.random.nextDouble());
        }
    }

    /**
     * The surface the stones land on near a position: the first block in the column above it that is not air.
     *
     * <p>It stops after the model's own roof search distance, because a roof ten blocks up is not what a player is
     * standing under - a tower does not shelter the ground beside it, and the honest answer there is that the stones
     * are landing in the open.
     */
    private static BlockPos landingSurface(ServerLevel level, BlockPos from) {
        for (int dy = 0; dy <= HailClatter.ROOF_SEARCH_BLOCKS; dy++) {
            BlockPos pos = from.offset(0, dy, 0);
            if (!level.isLoaded(pos)) {
                return null;
            }
            BlockState state = level.getBlockState(pos);
            if (!state.isAir() && !state.liquid()) {
                return pos;
            }
        }
        return null;
    }

    /** The material a block is made of, read off the game's own name for the sound it makes. */
    private static HailClatter.Surface surfaceOf(ServerLevel level, BlockPos pos) {
        try {
            BlockState state = level.getBlockState(pos);
            return HailClatter.surfaceOf(state.getSoundType().getBreakSound().getLocation().getPath());
        } catch (RuntimeException ignored) {
            return HailClatter.Surface.STONE;
        }
    }

    /**
     * One strike, if that surface is not still ringing at this player. Answers whether anything was played.
     */
    private static boolean strike(ServerLevel level, ServerPlayer player, HailClatter.Surface surface, Vec3 at,
                                  HailModel.Size size, double master, double roll) {
        if (surface == null || at == null) {
            return false;
        }
        Map<HailClatter.Surface, Long> struck = LAST_STRUCK.computeIfAbsent(player.getUUID(),
                ignored -> new ConcurrentHashMap<>());
        long now = level.getGameTime();
        Long previous = struck.get(surface);
        int since = previous == null ? Integer.MAX_VALUE : (int) Math.min(Integer.MAX_VALUE, now - previous);
        if (!HailClatter.due(since, surface, size)) {
            return false;
        }
        double heard = at.distanceTo(player.getEyePosition());
        float volume = (float) (HailClatter.volumeFor(surface, size) * HailClatter.dampingFor(heard) * master);
        if (volume <= 0.01F) {
            return false;
        }
        struck.put(surface, now);
        level.playSound(null, at.x, at.y, at.z, soundFor(surface), SoundSource.WEATHER, volume,
                HailClatter.pitchFor(surface, size, roll));
        return true;
    }

    /** What each surface sounds like. Six sounds, one per material a player can tell apart. */
    private static SoundEvent soundFor(HailClatter.Surface surface) {
        return switch (surface) {
            case SOFT -> ModContent.HAIL_SOFT_SOUND.get();
            case WOOD -> ModContent.HAIL_WOOD_SOUND.get();
            case STONE -> ModContent.HAIL_STONE_SOUND.get();
            case ICE -> ModContent.HAIL_ICE_SOUND.get();
            case GLASS -> ModContent.HAIL_GLASS_SOUND.get();
            case METAL -> ModContent.HAIL_METAL_SOUND.get();
        };
    }

    /**
     * Drops the records of players who are no longer here, so a long-lived server does not keep a rhythm for every
     * player who ever stood in a hailstorm.
     */
    private static void forgetTheGone(ServerLevel level) {
        if (LAST_STRUCK.size() <= 64) {
            return;
        }
        Map<UUID, Map<HailClatter.Surface, Long>> alive = new LinkedHashMap<>();
        long now = level.getGameTime();
        for (Map.Entry<UUID, Map<HailClatter.Surface, Long>> entry : LAST_STRUCK.entrySet()) {
            long newest = Long.MIN_VALUE;
            for (Long stamp : entry.getValue().values()) {
                newest = Math.max(newest, stamp);
            }
            if (newest == Long.MIN_VALUE || now - newest <= FORGET_AFTER_TICKS) {
                alive.put(entry.getKey(), entry.getValue());
            }
        }
        LAST_STRUCK.clear();
        LAST_STRUCK.putAll(alive);
    }

    /** Forgets one player's clatter rhythm, for a player who has left. */
    public static void forget(UUID player) {
        if (player != null) {
            LAST_STRUCK.remove(player);
        }
    }
}
