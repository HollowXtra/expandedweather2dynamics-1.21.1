package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A herd's storm drill.
 *
 * <p>The decision lives in {@link HerdFlock}, which is arithmetic and therefore testable; this is the part
 * that has to find the other animals. It is attached to every animal that joins a level and each one checks
 * for itself whether the weather is worth reacting to, which is the same shape as the villager drill
 * ({@link TornadoShelterGoal}) and for the same reason: a goal added at spawn costs nothing until it runs.
 *
 * <p>Tamed animals are left out on purpose: one belongs to a player, and that player is the one person in the
 * world who might be walking *toward* the tornado - a wolf that drags itself home is better than a wolf that
 * runs into the inflow because the code assumed every animal wants out. Water animals need no exclusion:
 * they are not {@code Animal}s at all, so no goal is ever attached to one.
 */
public final class AnimalHerdGoal extends Goal {
    private static final Map<Animal, Boolean> ATTACHED = new WeakHashMap<>();

    /** How many herd-mates are read to find the centre of the herd. */
    private static final int MAX_HERD_READ = 12;

    private final Animal animal;
    private final int scanInterval;
    private long nextScanTick;
    private long nextAimTick;
    private TornadoThreatService.Threat threat;
    private HerdFlock.Target aim;

    private AnimalHerdGoal(Animal animal) {
        this.animal = animal;
        this.scanInterval = Math.max(5, Config.AI_ANIMAL_HERD_SCAN_INTERVAL_TICKS.get());
        setFlags(EnumSet.of(Flag.MOVE));
    }

    public static void attach(Animal animal) {
        if (!Config.AI_ANIMAL_HERDING.get()) {
            return;
        }
        if (animal instanceof TamableAnimal tame && tame.isTame()) {
            return;
        }
        synchronized (ATTACHED) {
            if (ATTACHED.containsKey(animal)) {
                return;
            }
            ATTACHED.put(animal, Boolean.TRUE);
        }
        animal.goalSelector.addGoal(1, new AnimalHerdGoal(animal));
    }

    @Override
    public boolean canUse() {
        if (!Config.AI_ANIMAL_HERDING.get() || !(animal.level() instanceof ServerLevel level)) {
            return false;
        }
        if (threat == null && level.getGameTime() < nextScanTick) {
            return false;
        }
        refreshThreat(level);
        return threat != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (!(animal.level() instanceof ServerLevel level)) {
            return false;
        }
        refreshThreat(level);
        return threat != null;
    }

    @Override
    public void start() {
        aim = null;
    }

    @Override
    public void tick() {
        if (!(animal.level() instanceof ServerLevel level) || threat == null) {
            return;
        }
        long gameTime = level.getGameTime();
        if (aim == null || gameTime >= nextAimTick) {
            nextAimTick = gameTime + Math.max(5, scanInterval / 2);
            aim = aimAt(level);
        }
        if (aim == null || aim.speed() <= 0.0D) {
            return;
        }
        if (animal.getNavigation().isDone() || animal.tickCount % 10 == 0) {
            double speed = aim.speed() * Math.max(0.2D, Config.AI_ANIMAL_HERD_SPEED.get());
            animal.getNavigation().moveTo(aim.x(), animal.getY(), aim.z(), speed);
        }
    }

    @Override
    public void stop() {
        threat = null;
        aim = null;
        animal.getNavigation().stop();
    }

    private void refreshThreat(ServerLevel level) {
        long gameTime = level.getGameTime();
        if (gameTime < nextScanTick && threat != null) {
            return;
        }
        nextScanTick = gameTime + scanInterval;
        threat = TornadoThreatService
                .nearestThreat(level, animal.position(), Config.AI_ANIMAL_HERD_THREAT_BLOCKS.get())
                .orElse(null);
    }

    /** Read the herd, then ask the model where to go. */
    private HerdFlock.Target aimAt(ServerLevel level) {
        Vec3 here = animal.position();
        double herdRadius = Math.max(4.0D, Config.AI_ANIMAL_HERD_RADIUS_BLOCKS.get());
        AABB box = animal.getBoundingBox().inflate(herdRadius, 6.0D, herdRadius);
        List<Animal> neighbours = level.getEntitiesOfClass(Animal.class, box,
                other -> other != animal && other.isAlive() && other.getClass() == animal.getClass());
        double sumX = here.x;
        double sumZ = here.z;
        int count = 1;
        for (Animal other : neighbours) {
            if (count > MAX_HERD_READ) {
                break;
            }
            sumX += other.getX();
            sumZ += other.getZ();
            count++;
        }
        double herdX = sumX / count;
        double herdZ = sumZ / count;
        double reach = Math.max(16.0D, Config.AI_ANIMAL_HERD_THREAT_BLOCKS.get());
        double alarm = HerdFlock.alarm(threat.distanceBlocks(), reach);
        return HerdFlock.target(here.x, here.z, herdX, herdZ, count,
                threat.center().x, threat.center().z, threat.coreRadiusBlocks(),
                threat.motionX(), threat.motionZ(), alarm);
    }
}
