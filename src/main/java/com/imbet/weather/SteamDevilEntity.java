package com.imbet.weather;

import com.imbet.Config;
import com.imbet.registry.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * A steam devil: the column thrown up where lava meets water.
 *
 * <p>It is the dust devil's class again, and its own kind, and it is the most different of the family in the
 * two ways that matter. It is a phreatic phenomenon rather than a weather one - water flashing against hot
 * rock expands hard enough to organise a rotating column in seconds, which is why it can appear in the rain,
 * where every other whirl in this mod needs a clear hot afternoon. And it cannot move a block: steam is hot
 * and it has no mass, so a steam devil scalds whatever is standing in it and throws nothing at all. That
 * asymmetry is not an omission, it is {@link LavaWhirlModel#movesBlocks}, and it is why nothing in the world
 * pass ever looks at one of these.
 */
public class SteamDevilEntity extends DustDevilEntity {

    /** How often the reaction under it is checked, in ticks. */
    private static final int REACTION_CHECK_INTERVAL = 60;

    /** How far around it the reaction is looked for, in blocks. */
    private static final int REACTION_RADIUS = 12;

    /** How close anything has to be to be scalded, as a share of the column's own width. */
    private static final double SCALD_RANGE_MULTIPLIER = 1.25D;

    public SteamDevilEntity(EntityType<? extends SteamDevilEntity> type, Level level) {
        super(type, level);
    }

    public SteamDevilEntity(Level level, Vec3 position, int lifeTicks, float radius, float maxWindMph) {
        super(ModContent.STEAM_DEVIL_ENTITY.get(), level, position, lifeTicks, radius, maxWindMph);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            return;
        }
        if (tickCount % REACTION_CHECK_INTERVAL == 0 && !reactionStillRunning()) {
            // The water is gone, or the lava has crusted: the column is being fed by nothing.
            discard();
            return;
        }
        if (Config.STEAM_DEVIL_SCALD_DAMAGE.get() && tickCount % 10 == 0) {
            scaldWhatIsStandingInIt();
        }
    }

    /** Whether both halves of the phreatic reaction are still under it. */
    private boolean reactionStillRunning() {
        boolean lava = false;
        boolean water = false;
        BlockPos centre = BlockPos.containing(position());
        for (int dx = -REACTION_RADIUS; dx <= REACTION_RADIUS; dx += 3) {
            for (int dz = -REACTION_RADIUS; dz <= REACTION_RADIUS; dz += 3) {
                BlockPos surface = BlockGrabSafety.safeSurface(level(), centre.offset(dx, 0, dz),
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
                if (surface == null) {
                    continue;
                }
                // One read each of the two positions this sample cares about, which is what the four reads that
                // stood here were doing: each took the same chunk lookup, at the same position, a second time
                // to ask it a second question. Nothing can have changed the world in between, because nothing in
                // this loop writes it.
                BlockState surfaceState = level().getBlockState(surface);
                BlockState belowState = level().getBlockState(surface.below());
                if (surfaceState.is(Blocks.LAVA) || belowState.is(Blocks.LAVA)) {
                    lava = true;
                } else if (surfaceState.getFluidState().is(FluidTags.WATER)
                        || belowState.getFluidState().is(FluidTags.WATER)) {
                    water = true;
                }
                if (lava && water) {
                    return true;
                }
            }
        }
        return LavaWhirlModel.steamSurvives(lava, water);
    }

    private void scaldWhatIsStandingInIt() {
        double reach = Math.max(1.5D, radius() * SCALD_RANGE_MULTIPLIER);
        for (Entity entity : level().getEntities(this, getBoundingBox().inflate(reach, 4.0D, reach), Entity::isAlive)) {
            if (entity.fireImmune() || !(entity instanceof LivingEntity living)) {
                continue;
            }
            // A scald and not a burn: the column does not set anything alight, and it never moves a block.
            living.hurt(damageSources().hotFloor(), LavaWhirlModel.steamScaldDamage(maxWindMph()));
        }
    }

    @Override
    protected double entityForceMultiplier() {
        return 0.72D;
    }

    /** What little lift steam has: it rises, but there is nothing to it to lift anything with. */
    @Override
    protected double liftScale() {
        return 0.45D;
    }

    /** Narrower than a lava whirl and much narrower than a dust devil: this is a column, not a wall. */
    @Override
    protected double suctionRangeMultiplier() {
        return 1.7D;
    }

    @Override
    protected double verticalSuctionRange() {
        return 6.0D;
    }

    @Override
    protected double motionDrag() {
        return 0.994D;
    }
}
