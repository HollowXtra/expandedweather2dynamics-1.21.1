package com.imbet.weather;

import com.imbet.Config;
import com.imbet.registry.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * A lava whirl: a fire whirl whose heat source is a volcano rather than a forest.
 *
 * <p>It is a dust devil's own class, which is what gives it a body - the vortex renderer draws the column
 * every tick from the entity, and the entity's own suction is what moves what it catches - and its own kind,
 * read off this class on the client, which is what stops it being drawn in the colour of the earth. What makes
 * it a lava whirl rather than a fire whirl is where it stands and what it needs: it is thrown up by a vent with
 * magma at it, it lifts rather than pushes because a fire whirl's inflow comes up off the ground, and it dies
 * when it drifts off the molten rock it was standing on.
 *
 * <p>The one thing it does not do itself is change the world. Igniting what it crosses and spilling lava from
 * the vent are world edits, they are budgeted, and they belong to {@link LavaWhirlService} with every other
 * block pass in this mod. What happens here is what happens to an entity standing in it, which is a burn, and
 * that is read off the whirl's own wind so the number the client draws and the number that hurts are the same
 * one.
 */
public class LavaWhirlEntity extends DustDevilEntity {

    /** How often the ground under it is read, in ticks. */
    private static final int GROUND_CHECK_INTERVAL = 40;

    /** How close a player can stand before the heat is felt at all, as a share of the whirl's own width. */
    private static final double BURN_RANGE_MULTIPLIER = 0.85D;

    public LavaWhirlEntity(EntityType<? extends LavaWhirlEntity> type, Level level) {
        super(type, level);
    }

    public LavaWhirlEntity(Level level, Vec3 position, int lifeTicks, float radius, float maxWindMph) {
        super(ModContent.LAVA_WHIRL_ENTITY.get(), level, position, lifeTicks, radius, maxWindMph);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide() || !Config.LAVA_WHIRL_DAMAGE.get()) {
            return;
        }
        if (tickCount % GROUND_CHECK_INTERVAL == 0 && !standingOnMoltenRock()) {
            // It has wandered off the flow. A whirl over cold ground is a fire whirl with no fire in it, and
            // this is the whole of its own lifetime rule: the eruption does not have to end for it to end.
            discard();
            return;
        }
        if (tickCount % 10 == 0) {
            burnWhatIsStandingInIt();
        }
    }

    /** Whether the ground under it is still hot enough for this to be the phenomenon it says it is. */
    private boolean standingOnMoltenRock() {
        if (level().dimension() == Level.NETHER) {
            return LavaWhirlModel.survivesOn(false, true);
        }
        BlockPos surface = BlockGrabSafety.safeSurface(level(), BlockPos.containing(position()),
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
        if (surface == null) {
            return true;
        }
        return LavaWhirlModel.survivesOn(level().getBlockState(surface).is(Blocks.LAVA), false);
    }

    private void burnWhatIsStandingInIt() {
        double reach = Math.max(1.5D, radius() * BURN_RANGE_MULTIPLIER);
        for (Entity entity : level().getEntities(this, getBoundingBox().inflate(reach, 3.0D, reach), Entity::isAlive)) {
            if (entity.fireImmune() || !(entity instanceof LivingEntity living)) {
                continue;
            }
            living.setRemainingFireTicks(Math.max(living.getRemainingFireTicks(), 60));
            living.hurt(damageSources().inFire(), LavaWhirlModel.burnDamage(maxWindMph()));
        }
    }

    @Override
    protected double entityForceMultiplier() {
        return 1.25D;
    }

    /** A fire whirl lifts: its inflow comes up off the hot ground rather than across it. */
    @Override
    protected double liftScale() {
        return 1.15D;
    }

    @Override
    protected double suctionRangeMultiplier() {
        return 2.4D;
    }

    @Override
    protected double verticalSuctionRange() {
        return 3.5D;
    }

    @Override
    protected double motionDrag() {
        return 0.988D;
    }
}
