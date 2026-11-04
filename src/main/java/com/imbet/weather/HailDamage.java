package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import weather2.weathersystem.storm.StormObject;

import java.util.List;

/**
 * Hail landing on the world.
 *
 * <p>{@link HailModel} says how big the stones are and what each size is allowed to break; this is the part
 * that has to touch the ground. It runs off the storm's own supercell structure, so the stones fall where
 * the mod already draws them: the shaft the renderer tints white with the hail core, at the footprint the
 * model gives for a core of that strength.
 *
 * <p>Three rules are worth stating because they are the difference between this and a random damage tick:
 *
 * <ul>
 *   <li><b>Exposure.</b> A block of ice does not fall through a roof, so anything that cannot see the sky
 *       is not hit at all. That makes a house, a cave and a hay bale into shelter, which is the one piece of
 *       gameplay a hailstorm should have.</li>
 *   <li><b>A short column, read honestly.</b> The damage is applied to what the stones would actually land
 *       on in the first few metres: the ground, whatever is growing out of it, and the glass of the wall
 *       beside it. Windows ten blocks up a tower are out of scope and the class says so rather than
 *       pretending to check them.</li>
 *   <li><b>Nothing a player built to stand up.</b> Stone, planks, logs, metal: untouched, always. Glass is
 *       the exception because glass is the thing hail breaks, and {@link HailModel.GlassVerdict} grades even
 *       that: panes break at a dime, glass blocks only at a golf ball.</li>
 * </ul>
 */
public final class HailDamage {

    /** How often the stones are read against the world. */
    private static final int PASS_TICKS = 10;

    /** How many columns of ground one pass may read. */
    private static final int MAX_COLUMNS = 6;

    /** How many blocks one pass may change, across all of its columns. */
    private static final int MAX_EDITS = 14;

    /** How far above the surface a column is read. Two blocks of wall is a ground-floor window. */
    private static final int COLUMN_ABOVE = 4;

    /** How far below the surface a column is read, so a scoured dip still counts as ground. */
    private static final int COLUMN_BELOW = 2;

    private HailDamage() {
    }

    public static void tick(ServerLevel level, StormObject storm, DynamicStormState state) {
        if (!Config.HAIL_DAMAGE_ENABLED.get() || !Config.damageEnabled()) {
            return;
        }
        if (storm == null || storm.pos == null || state == null) {
            return;
        }
        if (level.getGameTime() % PASS_TICKS != 0L) {
            return;
        }
        float core = state.hailCoreStrength();
        float structure = state.supercellStructureStrength();
        if (!HailModel.hasHail(structure, core)) {
            return;
        }
        HailModel.Size size = HailModel.sizeFor(core, SupercellStructureService.precipitationCharacter(storm, state));
        if (size == HailModel.Size.NONE) {
            return;
        }
        SupercellStructureService.StructureSample sample = SupercellStructureService.sample(storm, state);
        if (sample == null || sample.hailShaft() == null) {
            return;
        }
        Vec3 centre = sample.hailShaft();
        double radius = HailModel.footprintRadiusBlocks(core, sample.radius());
        if (radius <= 0.0D) {
            return;
        }
        hurtTheExposed(level, centre, radius, size);
        breakTheLoose(level, centre, radius, size);
        // And the stones are heard landing, in the material they landed on. It runs off this pass rather than off a
        // pass of its own so that the clatter starts exactly where the damage starts and stops when it stops: a
        // storm with no stones falling is a storm with nothing to hear.
        HailClatterService.tick(level, centre, radius, size);
    }

    /**
     * Everything alive in the shaft that is standing in the open. Anything under a roof is skipped before the
     * roll is even made, so a village that shelters takes nothing at all rather than a smaller amount.
     */
    private static void hurtTheExposed(ServerLevel level, Vec3 centre, double radius, HailModel.Size size) {
        if (!HailModel.hurtsPlayers(size)) {
            return;
        }
        double top = centre.y + Math.max(48.0D, radius);
        AABB box = new AABB(centre.x - radius, centre.y - 24.0D, centre.z - radius,
                centre.x + radius, top, centre.z + radius);
        List<LivingEntity> found = level.getEntitiesOfClass(LivingEntity.class, box,
                entity -> !(entity instanceof ArmorStand) && entity.isAlive());
        if (found.isEmpty()) {
            return;
        }
        double chance = HailModel.hitChancePerPass(size, PASS_TICKS);
        double scale = Math.max(0.0D, Config.HAIL_DAMAGE_SCALE.get());
        for (LivingEntity entity : found) {
            if (!level.canSeeSky(entity.blockPosition())) {
                continue;
            }
            if (entity instanceof Player && !Config.HAIL_PLAYER_DAMAGE.get()) {
                continue;
            }
            if (!(entity instanceof Player) && (!Config.HAIL_MOB_DAMAGE.get() || !(entity instanceof Mob))) {
                continue;
            }
            if (level.random.nextDouble() >= chance) {
                continue;
            }
            float damage = entity instanceof Player
                    ? HailModel.playerDamage(size, helmetProtection(entity))
                    : HailModel.mobDamage(size);
            damage *= (float) scale;
            if (damage <= 0.0F) {
                continue;
            }
            entity.hurt(level.damageSources().generic(), damage);
        }
    }

    /**
     * How much of a hit a helmet takes off. Anything worn on the head counts and nothing else does: the
     * stones land from above, which is the one direction a chestplate does not cover.
     */
    private static double helmetProtection(LivingEntity entity) {
        if (!(entity.getItemBySlot(EquipmentSlot.HEAD).getItem() instanceof ArmorItem)) {
            return 0.0D;
        }
        return Config.HAIL_HELMET_PROTECTION.get();
    }

    /** Crops, canopy, farmland and glass, in the short column the stones actually reach. */
    private static void breakTheLoose(ServerLevel level, Vec3 centre, double radius, HailModel.Size size) {
        boolean crops = Config.HAIL_CROP_DAMAGE.get();
        boolean glass = Config.HAIL_GLASS_DAMAGE.get();
        boolean leaves = Config.HAIL_LEAF_DAMAGE.get();
        boolean ground = Config.HAIL_GROUND_LOSS.get();
        if (!crops && !glass && !leaves && !ground) {
            return;
        }
        int edits = 0;
        for (int i = 0; i < MAX_COLUMNS && edits < MAX_EDITS; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            double distance = Math.sqrt(level.random.nextDouble()) * radius;
            BlockPos base = BlockPos.containing(centre.x + Math.cos(angle) * distance, centre.y,
                    centre.z + Math.sin(angle) * distance);
            if (!level.isLoaded(base)) {
                continue;
            }
            BlockPos surface = BlockGrabSafety.safeSurface(level, base, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
            if (surface == null) {
                continue;
            }
            for (int dy = -COLUMN_BELOW; dy <= COLUMN_ABOVE && edits < MAX_EDITS; dy++) {
                BlockPos pos = surface.offset(0, dy, 0);
                if (!level.isLoaded(pos)) {
                    continue;
                }
                BlockGrabSafety.BlockSample sample = BlockGrabSafety.sample(level, pos, null);
                if (!BlockGrabSafety.canEdit(sample)) {
                    continue;
                }
                BlockState state = sample.state();
                if (isPane(state)) {
                    if (!glass || HailModel.glassVerdict(size) == HailModel.GlassVerdict.NONE) {
                        continue;
                    }
                    if (BlockGrabSafety.safeSetBlock(level, pos, Blocks.AIR.defaultBlockState(), 3)) {
                        edits++;
                    }
                } else if (isGlassBlock(state)) {
                    if (!glass || HailModel.glassVerdict(size) != HailModel.GlassVerdict.SHATTER) {
                        continue;
                    }
                    if (BlockGrabSafety.safeSetBlock(level, pos, Blocks.AIR.defaultBlockState(), 3)) {
                        edits++;
                    }
                } else if (state.is(BlockTags.CROPS)) {
                    if (!crops || level.random.nextDouble() >= HailModel.cropBreakChance(size)) {
                        continue;
                    }
                    if (BlockGrabSafety.safeSetBlock(level, pos, Blocks.AIR.defaultBlockState(), 3)) {
                        edits++;
                    }
                } else if (state.is(BlockTags.LEAVES)) {
                    if (!leaves || level.random.nextDouble() >= HailModel.leafShredChance(size)) {
                        continue;
                    }
                    if (BlockGrabSafety.safeSetBlock(level, pos, Blocks.AIR.defaultBlockState(), 3)) {
                        edits++;
                    }
                } else if (ground && state.is(Blocks.FARMLAND) && state.hasProperty(BlockStateProperties.MOISTURE)) {
                    if (level.random.nextDouble() >= HailModel.farmlandLossChance(size)) {
                        continue;
                    }
                    if (BlockGrabSafety.safeSetBlock(level, pos, Blocks.DIRT.defaultBlockState(), 3)) {
                        edits++;
                    }
                }
            }
        }
    }

    /** A single pane of glass: the thin sheet with nothing behind it. */
    private static boolean isPane(BlockState state) {
        String path = pathOf(state);
        return path.contains("glass_pane") || path.contains("pane");
    }

    /** A full block of glass, including the stained and tinted ones. */
    private static boolean isGlassBlock(BlockState state) {
        String path = pathOf(state);
        return !path.contains("pane") && path.contains("glass");
    }

    private static String pathOf(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }
}
