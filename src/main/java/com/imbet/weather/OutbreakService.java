package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;

/**
 * True outbreaks: not one storm that happens to be violent, but a corridor of tornado-capable cells
 * working across the map together.
 *
 * <p>Everything else in the addon produces <em>a</em> storm. A real outbreak is a different shape of
 * event: one day, one air mass, and a family of supercells - or one long line spinning up short-lived
 * tornadoes - stepping across a hundred miles of the same warm sector while the synoptic low that
 * assembled it all keeps the whole area primed. This is an episode rather than a spawn: it has a
 * corridor, a clock, a planned number of cells, and a beginning and an end.
 *
 * <p><b>What may start one.</b> Extreme conditions, and nothing less. The day has to be issued at
 * extreme or hyper extreme risk - the addon's own extension tiers, which are reachable only by an
 * environment past the significant-severe thresholds outright - and a deeply deepened synoptic low has
 * to have its warm sector over the same ground, and the place has to actually be in that sector or on
 * the low's cold front. Either half of that alone is a bad day and not an outbreak: an extreme day with
 * no system to organise it makes scattered storms, and a deep low in a quiet air mass makes rain.
 *
 * <p><b>What shape it takes.</b> The front the setup is on decides, because that is what decides it in
 * the real world: a warm sector runs discrete supercells, a cold front runs one line. The corridor is
 * drawn along the low's own front - down the middle of the wedge for a family, down the cold front's
 * arm for a line - and the cells are seeded along it as the episode runs, each one a real Weather2
 * storm with an extreme day's outlook behind it. Nothing here guarantees a tornado: what it guarantees
 * is that the air an outbreak needs is actually in the air, in the shape an outbreak has.
 */
public final class OutbreakService {
    /** What the episode is putting in the air, which is also what decides the cells' kind. */
    public enum Shape {
        SUPERCELL_FAMILY("a family of tornado-capable supercells", FrontalCellKind.WARM_SECTOR_SUPERCELL),
        QLCS_LINE("a line of tornado-capable storms", FrontalCellKind.COLD_FRONT_LINE);

        private final String label;
        private final FrontalCellKind cellKind;

        Shape(String label, FrontalCellKind cellKind) {
            this.label = label;
            this.cellKind = cellKind;
        }

        /**
         * The kind of cell this shape seeds. A family is made of warm-sector supercells, which are the
         * only cells allowed their whole ladder; a line is made of forced cells, which is why its
         * tornadoes are the short-lived QLCS kind even on the most extreme day there is.
         */
        public FrontalCellKind cellKind() {
            return cellKind;
        }

        public String label() {
            return label;
        }
    }

    /**
     * One running outbreak: where its corridor is, which way it runs, when it started, when it ends,
     * and how much of it has been put in the air.
     */
    public record Episode(long id, long lowId, Shape shape, double startX, double startZ,
                          double axisX, double axisZ, double corridorLength,
                          long startTick, long endTick,
                          int cellsPlanned, int cellsSeeded, long lastCellTick) {

        public Vec3 axis() {
            return new Vec3(axisX, 0.0D, axisZ);
        }

        public Vec3 start() {
            return new Vec3(startX, 0.0D, startZ);
        }

        /** A point along the corridor: 0 at the first cell's end, 1 at the last. */
        public Vec3 pointAt(double fraction) {
            double t = Mth.clamp(fraction, 0.0D, 1.0D);
            return new Vec3(startX + axisX * corridorLength * t, 0.0D, startZ + axisZ * corridorLength * t);
        }

        public Vec3 midpoint() {
            return pointAt(0.5D);
        }

        public boolean running(long gameTime) {
            return gameTime < endTick;
        }

        public boolean complete() {
            return cellsSeeded >= cellsPlanned;
        }

        public int remaining() {
            return Math.max(0, cellsPlanned - cellsSeeded);
        }

        /** The episode with one more cell on the ground. */
        public Episode seeded(long gameTime) {
            return new Episode(id, lowId, shape, startX, startZ, axisX, axisZ, corridorLength,
                    startTick, endTick, cellsPlanned, cellsSeeded + 1, gameTime);
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putLong("Id", id);
            tag.putLong("LowId", lowId);
            tag.putString("Shape", shape.name());
            tag.putDouble("StartX", startX);
            tag.putDouble("StartZ", startZ);
            tag.putDouble("AxisX", axisX);
            tag.putDouble("AxisZ", axisZ);
            tag.putDouble("Corridor", corridorLength);
            tag.putLong("StartTick", startTick);
            tag.putLong("EndTick", endTick);
            tag.putInt("Planned", cellsPlanned);
            tag.putInt("Seeded", cellsSeeded);
            tag.putLong("LastCellTick", lastCellTick);
            return tag;
        }

        public static Episode load(CompoundTag tag) {
            Shape shape = Shape.SUPERCELL_FAMILY;
            try {
                shape = Shape.valueOf(tag.getString("Shape"));
            } catch (IllegalArgumentException ignored) {
                // A save from before a shape was renamed is read as the family rather than discarded.
            }
            return new Episode(
                    tag.getLong("Id"),
                    tag.getLong("LowId"),
                    shape,
                    tag.getDouble("StartX"),
                    tag.getDouble("StartZ"),
                    tag.getDouble("AxisX"),
                    tag.getDouble("AxisZ"),
                    Math.max(1.0D, tag.getDouble("Corridor")),
                    tag.getLong("StartTick"),
                    tag.getLong("EndTick"),
                    Math.max(1, tag.getInt("Planned")),
                    Math.max(0, tag.getInt("Seeded")),
                    tag.getLong("LastCellTick")
            );
        }
    }

    /** How often an eligible day is tested for an outbreak, when none is running. */
    private static final long ELIGIBILITY_INTERVAL_TICKS = 600L;
    /** How strongly a place has to be governed by the front for the episode to start there. */
    private static final double MIN_FRONT_INFLUENCE = 0.25D;
    /** How far the corridor's first cell sits from the low's centre, as a fraction of the front arm. */
    private static final double CORRIDOR_START_FRACTION = 0.25D;
    /** Sideways spread of a family's cells across the warm sector, and of a line's about the front. */
    private static final double FAMILY_SPREAD_BLOCKS = 320.0D;
    private static final double LINE_SPREAD_BLOCKS = 70.0D;
    /** How far outside its corridor an episode is still treated as governing a place, in blocks. */
    private static final double CORRIDOR_REACH_BLOCKS = 420.0D;
    /** The fallback storm speed for a cell whose low has since been dropped, in blocks per tick. */
    private static final double FALLBACK_CELL_SPEED = 0.075D;

    private OutbreakService() {
    }

    public static void tick(ServerLevel level) {
        if (!Config.LOW_PRESSURE_ENABLED.get() || !Config.OUTBREAK_ENABLED.get()) {
            OutbreakStore.get(level).end();
            return;
        }
        long gameTime = level.getGameTime();
        OutbreakStore store = OutbreakStore.get(level);
        Episode episode = store.episode();
        if (episode != null) {
            if (!episode.running(gameTime)) {
                store.end();
                return;
            }
            int interval = Math.max(100, Config.OUTBREAK_CELL_INTERVAL_TICKS.get());
            if (!episode.complete() && gameTime - episode.lastCellTick() >= interval) {
                store.setEpisode(seedNextCell(level, episode, gameTime));
            }
            return;
        }
        if (gameTime % ELIGIBILITY_INTERVAL_TICKS != 0L) {
            return;
        }
        maybeBegin(level, store, gameTime);
    }

    /** The running episode in this level, or null. */
    public static Episode active(ServerLevel level) {
        if (level == null) {
            return null;
        }
        return OutbreakStore.get(level).episode();
    }

    /**
     * The advisory an alert station, a radio or the banner should be carrying for a place.
     *
     * <p>It is a product rather than a broadcast: with no station in range a player hears nothing,
     * which is the same rule every other alert follows. What it says is that the day is running an
     * outbreak over this ground - not that a tornado is on the ground here, which is what the storm
     * products are for and what they still say separately.
     */
    public static AlertService.AlertResult alertFor(ServerLevel level, Vec3 origin) {
        Episode episode = active(level);
        if (origin == null || episode == null) {
            return AlertService.NONE;
        }
        double reach = episode.corridorLength() * 0.75D + CORRIDOR_REACH_BLOCKS;
        if (distanceToCorridor(origin, episode) > reach) {
            return AlertService.NONE;
        }
        String label = episode.shape() == Shape.QLCS_LINE
                ? "Outbreak: a line of tornado-capable storms"
                : "Outbreak: a family of tornado-capable supercells";
        Vec3 centre = episode.midpoint();
        return new AlertService.AlertResult(AlertLevel.OUTBREAK_ADVISORY, label, "OBX", "RADAR INDICATED",
                centre, centre.add(episode.axis().scale(episode.corridorLength() * 0.25D)),
                episode.id(), episode.endTick());
    }

    /** How much of the corridor's planned cells are still to come, for a readout. */
    public static String progressLine(Episode episode) {
        if (episode == null) {
            return "";
        }
        return String.format(java.util.Locale.ROOT, "Outbreak in progress: %s, %d of %d cells seeded across %d blocks",
                episode.shape().label(), episode.cellsSeeded(), episode.cellsPlanned(),
                Math.round(episode.corridorLength()));
    }

    // ------------------------------------------------------------------ beginning one

    private static void maybeBegin(ServerLevel level, OutbreakStore store, long gameTime) {
        if (level.players().isEmpty()) {
            return;
        }
        for (var player : level.players()) {
            BlockPos pos = player.blockPosition();
            DailySpcOutlook outlook = DailySpcOutlookService.current(level, pos);
            // The gate, in one line: an extension-tier day. Extreme and hyper extreme are reachable only
            // by an environment past the significant-severe thresholds, so this is the environment's own
            // verdict rather than a second, more generous threshold invented here.
            if (!outlook.isExtremeOutbreak()) {
                continue;
            }
            FrontalEnvironment.Influence influence = FrontalEnvironment.at(level, pos);
            if (!influence.present() || influence.proximity() < MIN_FRONT_INFLUENCE) {
                continue;
            }
            Shape shape = switch (influence.regime()) {
                case WARM_SECTOR -> Shape.SUPERCELL_FAMILY;
                case COLD_FRONT -> Shape.QLCS_LINE;
                default -> null;
            };
            if (shape == null) {
                continue;
            }
            SynopticLow low = strongestLowNear(level, pos);
            if (low == null || low.intensity() < Config.OUTBREAK_MIN_LOW_INTENSITY.get()) {
                continue;
            }
            store.setEpisode(begin(level, store, low, shape, pos, gameTime));
            return;
        }
    }

    /**
     * The corridor, drawn on the low's own geometry.
     *
     * <p>A family runs down the middle of the warm sector, because that is where discrete supercells
     * are strung out; a line runs down the cold front's own arm, because that is the line. Both start
     * a little way out from the centre - a front's first few hundred blocks are still its centre - and
     * run for the configured corridor, which is what makes an outbreak a thing crossing the map rather
     * than a point on it.
     */
    private static Episode begin(ServerLevel level, OutbreakStore store, SynopticLow low, Shape shape, BlockPos anchor, long gameTime) {
        double heading = headingDegrees(low);
        double bearing;
        double armLength;
        if (shape == Shape.QLCS_LINE) {
            bearing = heading + low.coldFrontAngleDegrees();
            armLength = low.coldFrontLengthBlocks();
        } else {
            bearing = heading + (low.warmFrontAngleDegrees() + low.coldFrontAngleDegrees()) * 0.5D;
            armLength = low.radius();
        }
        double radians = Math.toRadians(bearing);
        double axisX = Math.cos(radians);
        double axisZ = Math.sin(radians);
        double startX = low.x() + axisX * armLength * CORRIDOR_START_FRACTION;
        double startZ = low.z() + axisZ * armLength * CORRIDOR_START_FRACTION;
        // Aim the corridor so it passes near the player whose day it is: an event that happens entirely
        // off the edge of what anybody can see is a stat rather than an outbreak.
        double sideways = (anchor.getX() - startX) * -axisZ + (anchor.getZ() - startZ) * axisX;
        if (Math.abs(sideways) > FAMILY_SPREAD_BLOCKS) {
            double shift = Mth.clamp(sideways, -FAMILY_SPREAD_BLOCKS, FAMILY_SPREAD_BLOCKS);
            startX += -axisZ * shift;
            startZ += axisX * shift;
        }
        double corridor = Config.OUTBREAK_CORRIDOR_BLOCKS.get();
        return new Episode(
                store.nextId(),
                low.id(),
                shape,
                startX,
                startZ,
                axisX,
                axisZ,
                corridor,
                gameTime,
                gameTime + Math.max(1200, Config.OUTBREAK_DURATION_TICKS.get()),
                Math.max(1, Config.OUTBREAK_CELL_COUNT.get()),
                0,
                gameTime
        );
    }

    // ------------------------------------------------------------------ running one

    /**
     * The next cell along the corridor.
     *
     * <p>The cells are spread evenly from one end of the corridor to the other as the episode runs, so
     * the family steps across the map at the pace the episode's own clock sets - which is what an
     * outbreak looks like from the ground: one storm, then the next behind it, all day. Nothing here
     * chooses a spot for danger; a cell lands where its turn in the corridor says, and what happens
     * there is the day's business.
     */
    private static Episode seedNextCell(ServerLevel level, Episode episode, long gameTime) {
        int index = episode.cellsSeeded();
        double t = (index + 0.5D) / Math.max(1, episode.cellsPlanned());
        Vec3 along = episode.pointAt(t);
        double spread = episode.shape() == Shape.QLCS_LINE ? LINE_SPREAD_BLOCKS : FAMILY_SPREAD_BLOCKS;
        double offset = (level.random.nextDouble() - 0.5D) * spread;
        Vec3 perpendicular = new Vec3(-episode.axisZ(), 0.0D, episode.axisX());
        Vec3 position = along.add(perpendicular.scale(offset));
        AddonWeatherEventService.seedFrontalCell(level, position, cellMotion(level, episode), episode.shape().cellKind());
        return episode.seeded(gameTime);
    }

    /** The low's own motion for a cell, or the corridor's heading when the low has since been dropped. */
    private static Vec3 cellMotion(ServerLevel level, Episode episode) {
        SynopticLow low = lowById(level, episode.lowId());
        if (low != null) {
            return FrontalConvectionService.cellMotion(low);
        }
        return episode.axis().scale(FALLBACK_CELL_SPEED);
    }

    /** The deepest low whose circulation reaches a place, or null when none does. */
    private static SynopticLow strongestLowNear(ServerLevel level, BlockPos pos) {
        List<SynopticLow> lows = SynopticLowStore.get(level).lows();
        return lows.stream()
                .filter(low -> distanceTo(low, pos.getX(), pos.getZ()) <= low.radius() * 1.35D)
                .max(Comparator.comparingDouble(SynopticLow::intensity))
                .orElse(null);
    }

    private static SynopticLow lowById(ServerLevel level, long id) {
        for (SynopticLow low : SynopticLowStore.get(level).lows()) {
            if (low.id() == id) {
                return low;
            }
        }
        return null;
    }

    /** How far a place is from the episode's corridor, treating the corridor as the segment it is. */
    private static double distanceToCorridor(Vec3 origin, Episode episode) {
        Vec3 start = episode.start();
        Vec3 end = episode.pointAt(1.0D);
        double dx = end.x - start.x;
        double dz = end.z - start.z;
        double lengthSqr = dx * dx + dz * dz;
        double t = lengthSqr <= 1.0E-9D
                ? 0.0D
                : Mth.clamp(((origin.x - start.x) * dx + (origin.z - start.z) * dz) / lengthSqr, 0.0D, 1.0D);
        double closestX = start.x + dx * t;
        double closestZ = start.z + dz * t;
        double ox = origin.x - closestX;
        double oz = origin.z - closestZ;
        return Math.sqrt(ox * ox + oz * oz);
    }

    private static double distanceTo(SynopticLow low, double x, double z) {
        double dx = low.x() - x;
        double dz = low.z() - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** The low's heading in degrees, in the same x-to-z sense the front angles are measured in. */
    private static double headingDegrees(SynopticLow low) {
        Vec3 motion = low.motion();
        double mx = motion.x;
        double mz = motion.z;
        if (Math.sqrt(mx * mx + mz * mz) < 1.0E-4D) {
            mx = 1.0D;
            mz = -0.35D;
        }
        return Math.toDegrees(Math.atan2(mz, mx));
    }
}
