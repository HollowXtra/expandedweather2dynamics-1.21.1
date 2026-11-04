package com.imbet.spotter;

import com.imbet.Config;
import com.imbet.village.VillageStore;
import com.imbet.weather.AlertLevel;
import com.imbet.weather.AlertService;
import com.imbet.weather.DynamicStormController;
import com.imbet.weather.DynamicStormState;
import com.imbet.weather.StormClassifier;
import com.imbet.weather.StormSubtype;
import com.imbet.weather.WeatherDataStreamService;
import com.imbet.weather.WeatherPhysicalSnapshot;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import weather2.ServerTickHandler;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The spotter net: a player's report of a tornado, made real.
 *
 * <p>Everything the alert system does is built on one question - what has the mod itself observed - and that
 * question has a blind spot the size of a funnel cloud. A circulation that is lowering but has not touched
 * down is not a tornado to a scan, and it is unmistakably one to somebody standing under the wall cloud. So
 * the net lets a person fill that gap: file a report on the circulation they can see, and the report becomes
 * a tornado warning on the same ladder as everything else. Sirens sound, radios hear it, villagers shelter -
 * and the mod's own warning takes over at touchdown, when it knows more than the report does.
 *
 * <p>A report is believed for a fixed lifetime and no longer. That is deliberate: a report is a claim, and a
 * claim that nobody can confirm should stop warning people rather than becoming a permanent fact about the
 * world. While it stands it is a threat like any other, which is what puts villagers under cover and what a
 * tornado probe detects, so a report is not a special case anywhere downstream - it is one more funnel as far
 * as the rest of the mod is concerned.
 *
 * <p>Confirmation is the other half of the loop. When the world does produce a tornado where the report said
 * it would, the report is proven, the reporter is credited towards a rank, and the village that was warned
 * pays what it can out of its own chests. A village that cannot afford it pays nothing, and the credit still
 * counts - so the reward for spotting is a record, and the reward for the village is surviving.
 */
public final class SpotterService {
    /** The smallest core a report is treated as having, so a thin funnel is not a point. */
    private static final double MIN_CORE_RADIUS_BLOCKS = 10.0D;
    /** The product source line, which is what tells a player a warning came from a person and not a scan. */
    public static final String SOURCE = "SPOTTER REPORT";

    /** What filing a report did, so the item can consume a flare only when one was actually used. */
    public enum Outcome {
        FILED,
        NOTHING_VISIBLE,
        TOO_SOON,
        TOO_MANY,
        DISABLED
    }

    /** One circulation a player could be pointing at: a place, a drift, a size and what the mod calls it. */
    public record Circulation(String key, String label, double x, double y, double z, double motionX,
                              double motionZ, double coreRadiusBlocks, int windMph, boolean onGround) {
    }

    /** A report as something the shelter AI and the probe can treat as a funnel. */
    public record Threat(double x, double y, double z, double motionX, double motionZ, double coreRadiusBlocks,
                         double windMph) {
    }

    /** The last report tick per player, for the cooldown. Not persisted: a cooldown is not a fact about the world. */
    private static final Map<UUID, Long> LAST_REPORT = new HashMap<>();

    private SpotterService() {
    }

    // ------------------------------------------------------------------ filing a report

    /**
     * Files a report at the best circulation the player can see, or says why it could not.
     *
     * <p>The gate is proximity to something with a vortex in it, not to a tornado: the whole point of a
     * spotter is the minute before touchdown, and a report that only accepted an already-confirmed tornado
     * would be telling the world something it already knew.
     */
    public static Outcome report(ServerPlayer player) {
        if (player == null || !(player.level() instanceof ServerLevel level)) {
            return Outcome.DISABLED;
        }
        if (!Config.SPOTTER_ENABLED.get()) {
            return Outcome.DISABLED;
        }
        long now = level.getGameTime();
        long cooldown = Math.max(0L, Config.SPOTTER_COOLDOWN_TICKS.get());
        long last = LAST_REPORT.getOrDefault(player.getUUID(), Long.MIN_VALUE);
        if (now - last < cooldown) {
            player.sendSystemMessage(Component.literal(SpotterReadout.tooSoonLine(cooldown - (now - last)))
                    .withStyle(ChatFormatting.GRAY));
            return Outcome.TOO_SOON;
        }
        SpotterStore store = SpotterStore.get(level);
        int active = store.activeReports(now).size();
        if (active >= Math.max(1, Config.SPOTTER_MAX_ACTIVE_REPORTS.get())) {
            player.sendSystemMessage(Component.literal(SpotterReadout.tooManyLine(active))
                    .withStyle(ChatFormatting.GRAY));
            return Outcome.TOO_MANY;
        }
        double range = Math.max(16.0D, Config.SPOTTER_REPORT_RANGE_BLOCKS.get());
        Circulation circulation = nearestCirculation(level, player.position(), range,
                Config.SPOTTER_MIN_REPORTED_MPH.get());
        if (circulation == null) {
            player.sendSystemMessage(Component.literal(SpotterReadout.nothingVisibleLine((int) Math.round(range)))
                    .withStyle(ChatFormatting.GRAY));
            return Outcome.NOTHING_VISIBLE;
        }
        long lifetime = Math.max(20L, Config.SPOTTER_LIFETIME_TICKS.get());
        int leadTicks = Math.max(0, Config.ALERT_WARNING_PATH_LEAD_TICKS.get());
        int villagers = villagersWarned(level, new Vec3(circulation.x(), circulation.y(), circulation.z()),
                Math.max(16.0D, Config.SPOTTER_VILLAGER_RADIUS_BLOCKS.get()));
        SpotterReport report = new SpotterReport(
                0L,
                player.getUUID(),
                player.getGameProfile().getName(),
                circulation.x(),
                circulation.y(),
                circulation.z(),
                circulation.x() + circulation.motionX() * leadTicks,
                circulation.z() + circulation.motionZ() * leadTicks,
                circulation.label(),
                circulation.windMph(),
                Math.max(MIN_CORE_RADIUS_BLOCKS, circulation.coreRadiusBlocks()),
                circulation.motionX(),
                circulation.motionZ(),
                now,
                now + lifetime,
                -1L,
                villagers,
                0,
                false
        );
        SpotterReport filed = store.file(report);
        LAST_REPORT.put(player.getUUID(), now);
        double distance = player.position().distanceTo(new Vec3(circulation.x(), circulation.y(), circulation.z()));
        player.sendSystemMessage(Component.literal(SpotterReadout.filedLine(filed, (int) Math.round(distance)))
                .withStyle(ChatFormatting.GOLD));
        launchFlare(level, player.position());
        return Outcome.FILED;
    }

    // ------------------------------------------------------------------ proving or losing a report

    /**
     * Confirms or lapses the reports on file. Called on the level's own tick.
     *
     * <p>A report is confirmed by the mod's own tornado classification rather than by the reporter's word: the
     * point of the credit is that the world proved it.
     */
    public static void tick(ServerLevel level) {
        if (level == null || !Config.SPOTTER_ENABLED.get()) {
            return;
        }
        int interval = Math.max(1, Config.SPOTTER_SCAN_INTERVAL_TICKS.get());
        if (level.getGameTime() % interval != 0L) {
            return;
        }
        long now = level.getGameTime();
        SpotterStore store = SpotterStore.get(level);
        List<SpotterReport> onFile = store.reports();
        if (onFile.isEmpty()) {
            return;
        }
        List<Circulation> observed = null;
        double confirmRange = Math.max(16.0D, Config.SPOTTER_CONFIRM_RANGE_BLOCKS.get());
        for (SpotterReport report : onFile) {
            if (report.confirmed()) {
                continue;
            }
            if (report.lapsed(now)) {
                store.lapse(report);
                notifyReporter(level, report.reporterId(),
                        SpotterReadout.lapsedLine(report), ChatFormatting.GRAY);
                continue;
            }
            if (observed == null) {
                observed = observedTornadoes(level);
            }
            Circulation match = null;
            for (Circulation circulation : observed) {
                if (report.matches(circulation.x(), circulation.z(), confirmRange)) {
                    match = circulation;
                    break;
                }
            }
            if (match == null) {
                continue;
            }
            int emeralds = payBounty(level, report);
            SpotterReport confirmed = report.confirmedAt(now).withPaid(emeralds);
            store.settle(confirmed, emeralds);
            double distance = Math.sqrt(sq(confirmed.x() - match.x()) + sq(confirmed.z() - match.z()));
            notifyReporter(level, confirmed.reporterId(),
                    SpotterReadout.confirmedLine(confirmed, (int) Math.round(distance), emeralds),
                    ChatFormatting.GOLD);
        }
    }

    /** Hands a player any bounty that was earned while they were offline. */
    public static void payHeldBounty(ServerPlayer player) {
        if (player == null || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        int held = SpotterStore.get(level).takeHeldBounty(player.getUUID());
        if (held <= 0) {
            return;
        }
        give(player, new ItemStack(Items.EMERALD, held));
        player.sendSystemMessage(Component.literal("Spotter bounty: " + held
                + (held == 1 ? " emerald" : " emeralds") + " from the villages you warned while you were away.")
                .withStyle(ChatFormatting.GOLD));
    }

    /**
     * Pays a confirmed report's bounty out of the nearest village's own chests.
     *
     * <p>The village pays what it has, not what it owes: a village that spent its emeralds rebuilding does not
     * go into debt to a spotter, and the report is credited either way. What the emeralds are for is the other
     * half of the same idea as the builder buying its own materials - a village that survives a tornado should
     * be a village that can afford to.
     */
    private static int payBounty(ServerLevel level, SpotterReport report) {
        int wanted = Math.max(0, Config.SPOTTER_BOUNTY_EMERALDS.get());
        if (wanted <= 0) {
            return 0;
        }
        Villager nearest = nearestVillager(level, report);
        if (nearest == null) {
            return 0;
        }
        ItemStack taken = VillageStore.withdraw(level, nearest.blockPosition(),
                Math.max(4, Config.SPOTTER_BOUNTY_CHEST_RANGE_BLOCKS.get()), Items.EMERALD, wanted);
        int paid = taken == null ? 0 : taken.getCount();
        if (paid <= 0) {
            return 0;
        }
        ServerPlayer reporter = level.getServer() == null ? null
                : level.getServer().getPlayerList().getPlayer(report.reporterId());
        if (reporter == null) {
            SpotterStore.get(level).holdBounty(report.reporterId(), paid);
        } else {
            give(reporter, new ItemStack(Items.EMERALD, paid));
        }
        return paid;
    }

    /** The nearest villager to a report, within the configured bounty range. */
    private static Villager nearestVillager(ServerLevel level, SpotterReport report) {
        double range = Math.max(16.0D, Config.SPOTTER_BOUNTY_RANGE_BLOCKS.get());
        AABB box = new AABB(report.x() - range, report.y() - 128.0D, report.z() - range,
                report.x() + range, report.y() + 192.0D, report.z() + range);
        Villager closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (Villager villager : level.getEntitiesOfClass(Villager.class, box)) {
            if (!villager.isAlive()) {
                continue;
            }
            double distance = sq(villager.getX() - report.x()) + sq(villager.getZ() - report.z());
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = villager;
            }
        }
        return closest;
    }

    // ------------------------------------------------------------------ what the rest of the mod asks

    /**
     * The strongest report in force that threatens this ground, as an alert.
     *
     * <p>Always a tornado warning and never a tornado emergency: a report says a tornado is there, and it does
     * not claim to know how violent it is. The mod's own products keep their own severity, and because
     * {@code AlertService.highestAlert} folds this in after the storms it observed, an observed tornado always
     * outranks a reported one when the two are equally severe.
     */
    public static AlertService.AlertResult alertFor(ServerLevel level, Vec3 origin) {
        if (level == null || origin == null || !Config.SPOTTER_ENABLED.get()) {
            return AlertService.NONE;
        }
        long now = level.getGameTime();
        int leadTicks = Math.max(0, Config.ALERT_WARNING_PATH_LEAD_TICKS.get());
        double range = Config.ALERT_WARNING_RANGE_BLOCKS.get();
        AlertService.AlertResult best = AlertService.NONE;
        for (SpotterReport report : SpotterStore.get(level).activeReports(now)) {
            Vec3 position = new Vec3(report.x(), report.y(), report.z());
            Vec3 projected = new Vec3(report.projectedX(), report.y(), report.projectedZ());
            double here = origin.distanceTo(position);
            double ahead = origin.distanceTo(projected);
            double distance = Math.min(here, ahead);
            if (distance > range) {
                continue;
            }
            AlertService.AlertResult candidate = new AlertService.AlertResult(
                    AlertLevel.TORNADO_WARNING,
                    report.label(),
                    AlertLevel.TORNADO_WARNING.productCode(),
                    SOURCE,
                    position,
                    projected,
                    report.id(),
                    report.expiresTick()
            );
            if (candidate.level().redstonePower() > best.level().redstonePower()) {
                best = candidate;
            }
        }
        // The lead is read here rather than by the caller, so a report's projected position is the same
        // question the storm products ask and not a second convention about how far ahead to look.
        if (best != AlertService.NONE && leadTicks < 0) {
            return AlertService.NONE;
        }
        return best;
    }

    /**
     * The reports in force near a place, as objects the shelter AI and the probe can treat as funnels.
     *
     * <p>This is what makes a warned village survive: a villager's drill asks for the nearest threat and
     * shelters from it, so a report standing in front of a village is a threat like any other, with the
     * report's own drift and width.
     */
    public static List<Threat> threatsNear(ServerLevel level, Vec3 position, double radiusBlocks) {
        List<Threat> threats = new ArrayList<>();
        if (level == null || position == null || !Config.SPOTTER_ENABLED.get() || !(radiusBlocks > 0.0D)) {
            return threats;
        }
        for (SpotterReport report : SpotterStore.get(level).activeReports(level.getGameTime())) {
            if (report.distanceTo(position.x, position.z) > radiusBlocks) {
                continue;
            }
            threats.add(new Threat(report.x(), report.y(), report.z(), report.motionX(), report.motionZ(),
                    Math.max(MIN_CORE_RADIUS_BLOCKS, report.coreRadiusBlocks()), report.stormWindMph()));
        }
        return threats;
    }

    /** How many reports are in force, for a message or a diagnostic. */
    public static int activeCount(ServerLevel level) {
        if (level == null) {
            return 0;
        }
        return SpotterStore.get(level).activeReports(level.getGameTime()).size();
    }

    /** The lines the weather computer prints for this player. */
    public static List<String> readoutLines(ServerPlayer player) {
        if (player == null || !(player.level() instanceof ServerLevel level)) {
            return List.of();
        }
        return SpotterReadout.lines(SpotterStore.get(level).ledger(player.getUUID()), level.getGameTime());
    }

    // ------------------------------------------------------------------ the world as a spotter sees it

    /**
     * The nearest circulation a player could report, from the same published snapshots the radar and the chase
     * log read, with the storm list as the fallback for a level whose scan has not run yet.
     *
     * <p>The rule is "there is a vortex here", not "there is a tornado here": a forming funnel, a satellite
     * circulation and a tornado on the ground are all reportable, which is what makes a report a lead rather
     * than an echo. Water spouts are excluded for the same reason the chase log excludes them - they are not
     * over anybody's land.
     */
    public static Circulation nearestCirculation(ServerLevel level, Vec3 position, double rangeBlocks, double minimumMph) {
        Circulation best = null;
        double bestScore = Double.MAX_VALUE;
        for (Circulation circulation : circulations(level, false)) {
            if (circulation.windMph() < minimumMph) {
                continue;
            }
            double distance = Math.sqrt(sq(circulation.x() - position.x) + sq(circulation.z() - position.z()));
            if (distance > rangeBlocks) {
                continue;
            }
            // Distance to the vortex, with a nudge towards the stronger of two it can see, which is how a
            // spotter would choose as well: the thing that is more obviously a tornado wins a near tie.
            double score = distance - circulation.windMph() * 0.15D;
            if (score < bestScore) {
                bestScore = score;
                best = circulation;
            }
        }
        return best;
    }

    /** Every tornado the mod has on the ground, which is what proves a report. */
    public static List<Circulation> observedTornadoes(ServerLevel level) {
        List<Circulation> tornadoes = new ArrayList<>();
        for (Circulation circulation : circulations(level, true)) {
            if (circulation.onGround()) {
                tornadoes.add(circulation);
            }
        }
        return tornadoes;
    }

    /**
     * The circulations in play, from the snapshot stream or, when it has nothing to say, from the storms
     * themselves. {@code tornadoesOnly} narrows the fallback to the ones the classifier calls a tornado.
     */
    private static List<Circulation> circulations(ServerLevel level, boolean tornadoesOnly) {
        List<Circulation> found = new ArrayList<>();
        if (level == null) {
            return found;
        }
        List<WeatherPhysicalSnapshot> snapshots = WeatherDataStreamService.snapshots(level);
        if (!snapshots.isEmpty()) {
            for (WeatherPhysicalSnapshot snapshot : snapshots) {
                Vec3 center = snapshot.circulationPosition();
                if (center == null || !snapshot.trackableVortex() || !snapshot.active()) {
                    continue;
                }
                Vec3 motion = snapshot.motion() == null ? Vec3.ZERO : snapshot.motion();
                boolean onGround = snapshot.subtype() == StormSubtype.TORNADO;
                if (tornadoesOnly && !onGround) {
                    continue;
                }
                found.add(new Circulation(snapshot.sourceKey(), snapshotLabel(snapshot), center.x, center.y,
                        center.z, motion.x, motion.z, snapshot.damagePathWidth() * 0.5D,
                        (int) Math.round(snapshot.maxWindMph()), onGround));
            }
            return found;
        }
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null) {
            return found;
        }
        for (WeatherObject object : new ArrayList<>(manager.getStormObjects())) {
            if (!(object instanceof StormObject storm) || storm.pos == null || storm.isDead
                    || DynamicStormController.isAbsorbedOrDecaying(storm) || storm.attrib_waterSpout) {
                continue;
            }
            DynamicStormState state = DynamicStormController.stateFor(storm);
            if (storm.isTornadoFormingOrGreater()) {
                boolean onGround = StormClassifier.classify(storm, state) == StormSubtype.TORNADO;
                if (!tornadoesOnly || onGround) {
                    Vec3 motion = storm.motion == null ? Vec3.ZERO : storm.motion;
                    found.add(new Circulation("storm:" + storm.ID,
                            StormClassifier.labelFor(storm, StormSubtype.TORNADO), storm.pos.x, storm.pos.y,
                            storm.pos.z, motion.x, motion.z, Math.max(4.0D, state.damagePathWidth() * 0.5D),
                            (int) Math.round(com.imbet.weather.TornadoWindField.maxWindMph(storm)), onGround));
                }
            }
            for (DynamicStormState.TornadoCirculationState satellite : state.satelliteTornadoes()) {
                if (!satellite.touchedDown() || satellite.absorbed()) {
                    continue;
                }
                Vec3 center = DynamicStormController.satelliteCenter(storm, satellite, storm.pos.y);
                Vec3 motion = satellite.motion() == null ? Vec3.ZERO : satellite.motion();
                found.add(new Circulation("storm:" + storm.ID + ":" + satellite.id(),
                        StormSubtype.SATELLITE_TORNADO.label(), center.x, center.y, center.z, motion.x, motion.z,
                        Math.max(3.0D, satellite.damagePathWidth() * 0.5D),
                        (int) Math.round(DynamicStormController.satelliteWindMph(storm, satellite)), true));
            }
        }
        return found;
    }

    /** What the mod calls a circulation, in the same words the chase log and the banner use. */
    private static String snapshotLabel(WeatherPhysicalSnapshot snapshot) {
        String stage = snapshot.stageLabel();
        if (stage == null || stage.isBlank()) {
            return snapshot.subtype().label();
        }
        return stage + " " + snapshot.subtype().label();
    }

    /** How many villagers a warning at this place reaches. */
    public static int villagersWarned(ServerLevel level, Vec3 center, double radiusBlocks) {
        if (level == null || center == null) {
            return 0;
        }
        AABB box = new AABB(center.x - radiusBlocks, center.y - 128.0D, center.z - radiusBlocks,
                center.x + radiusBlocks, center.y + 192.0D, center.z + radiusBlocks);
        return level.getEntitiesOfClass(Villager.class, box).size();
    }

    // ------------------------------------------------------------------ the flare

    /**
     * The flare going up: a red column of flame and smoke, seen from a long way off.
     *
     * <p>Particles rather than an entity, because the flare is a signal and not a thing - it exists to be seen
     * rising over a funnel, and what it must not do is become one more object for the world to track, collide
     * with and save.
     */
    public static void launchFlare(ServerLevel level, Vec3 from) {
        if (level == null || from == null) {
            return;
        }
        level.sendParticles(ParticleTypes.FLAME, from.x, from.y + 0.4D, from.z, 45, 0.25D, 1.1D, 0.25D, 0.09D);
        level.sendParticles(new DustParticleOptions(new Vector3f(1.0F, 0.16F, 0.12F), 1.8F),
                from.x, from.y + 0.6D, from.z, 30, 0.2D, 0.9D, 0.2D, 0.04D);
        level.sendParticles(ParticleTypes.SMOKE, from.x, from.y + 0.8D, from.z, 24, 0.3D, 1.4D, 0.3D, 0.02D);
        level.playSound(null, BlockPos.containing(from), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS,
                1.0F, 0.85F);
    }

    // ------------------------------------------------------------------ small helpers

    /** A message to a player who may not be online any more. */
    private static void notifyReporter(ServerLevel level, UUID reporterId, String line, ChatFormatting colour) {
        if (level == null || level.getServer() == null || reporterId == null) {
            return;
        }
        ServerPlayer reporter = level.getServer().getPlayerList().getPlayer(reporterId);
        if (reporter != null) {
            reporter.sendSystemMessage(Component.literal(line).withStyle(colour));
        }
    }

    /** Hands a stack to a player, dropping what will not fit rather than quietly losing it. */
    private static void give(ServerPlayer player, ItemStack stack) {
        if (player == null || stack.isEmpty()) {
            return;
        }
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    private static double sq(double value) {
        return value * value;
    }
}
