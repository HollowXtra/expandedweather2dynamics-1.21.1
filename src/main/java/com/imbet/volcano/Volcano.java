package com.imbet.volcano;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * One volcano, and everything it is currently doing.
 *
 * <p>This is deliberately the state and not the behaviour: the phase ladder, the block work and the hazards
 * live in {@link VolcanoService}, so that what a volcano is can be saved, loaded and sent to a client without
 * dragging the world with it. A volcano outlives a session - it is a mountain - so everything that decides
 * what it does next is here and written to disk.
 */
public final class Volcano {
    /**
     * The generation of the edifice's own geometry.
     *
     * <p>A mountain is written into the world and stays there, so a change to how the cone is built leaves every
     * existing one wrong: the first version laid one ring per layer and left three block trenches between them.
     * Bumping this makes every volcano loaded with an older version lay its edifice again from the first block,
     * which is safe because each block's material is a hash of its position - the parts that were already right
     * are simply written the same way twice, and the gaps are filled.
     */
    public static final int SHELL_VERSION = 2;

    private final long id;
    private final int x;
    private final int z;
    private int ventY;
    private int scale;
    private int episodes;
    private double intensity;
    private VolcanoPhase phase = VolcanoPhase.DORMANT;
    private long phaseTicks;
    private long phaseDuration;
    private long quiescenceTicks;
    private double ashMm;
    private long ashTick;
    private double windX;
    private double windZ;
    private double windMph;
    private double magmaLevel;
    private double pressure;
    private long lastAshTick;
    private long lastQuakeTick;
    private long lastBombTick;
    private long lastFlowTick;
    private long lastLaharTick;
    private long lastSyncTick;
    private long lastAdviceTick;
    private boolean dead;
    private double edificeGrowth;
    private int buildStage;
    private int buildCursor;
    private int builtScale = -1;
    private int shellVersion;
    private final List<LavaFlow> flows = new ArrayList<>();
    private final List<SurgeFront> surges = new ArrayList<>();
    private final List<Lahar> lahars = new ArrayList<>();

    public Volcano(long id, int x, int z, int ventY) {
        this.id = id;
        this.x = x;
        this.z = z;
        this.ventY = ventY;
    }

    // ------------------------------------------------------------------ identity

    public long id() {
        return id;
    }

    public int x() {
        return x;
    }

    public int z() {
        return z;
    }

    public int ventY() {
        return ventY;
    }

    public void setVentY(int ventY) {
        this.ventY = ventY;
    }

    /** The vent's own position, which is where the column comes out of the ground. */
    public BlockPos vent() {
        return new BlockPos(x, ventY, z);
    }

    public BlockPos center() {
        return new BlockPos(x, ventY, z);
    }

    // ------------------------------------------------------------------ size and history

    public int scale() {
        return scale;
    }

    public void setScale(int scale) {
        this.scale = VolcanoModel.clamp(scale, 0, 40);
    }

    public int episodes() {
        return episodes;
    }

    public void setEpisodes(int episodes) {
        this.episodes = episodes;
    }

    /** How much of the growth this episode has already built, in blocks of scale, for incremental building. */
    public double edificeGrowth() {
        return edificeGrowth;
    }

    public void addEdificeGrowth(double amount) {
        this.edificeGrowth += amount;
        while (edificeGrowth >= 1.0D) {
            edificeGrowth -= 1.0D;
            setScale(scale + 1);
        }
    }

    /**
     * Where the mountain-building has got to: stage 0 is the cone, stage 1 the crater and its throat, and
     * stage 2 means the edifice matches the size this volcano has reached.
     *
     * <p>A volcano is built a few hundred blocks at a time over the minutes of its eruption rather than in one
     * burst, so the order matters and has to survive a reload: the cursor is the position in that order.
     */
    public int buildStage() {
        return buildStage;
    }

    public void setBuildStage(int buildStage) {
        this.buildStage = buildStage;
    }

    public int buildCursor() {
        return buildCursor;
    }

    public void setBuildCursor(int buildCursor) {
        this.buildCursor = Math.max(0, buildCursor);
    }

    /** The scale the edifice in the world actually matches, which trails {@link #scale()} while it grows. */
    public int builtScale() {
        return builtScale;
    }

    public void setBuiltScale(int builtScale) {
        this.builtScale = builtScale;
    }

    public int shellVersion() {
        return shellVersion;
    }

    /**
     * Marks the edifice as needing to be laid again, and moves the version on so it is only done once.
     *
     * <p>Deliberately destructive of the build's own bookkeeping and nothing else: the phase, the ash, the
     * flows and the history are all untouched, because none of them depend on the shape of the cone.
     */
    public void resetEdifice(int version) {
        buildStage = 0;
        buildCursor = 0;
        builtScale = -1;
        shellVersion = version;
    }

    public double edificeRadius() {
        return VolcanoModel.edificeRadiusBlocks(scale);
    }

    public double edificeHeight() {
        return VolcanoModel.edificeHeightBlocks(scale);
    }

    public double craterRadius() {
        return VolcanoModel.craterRadiusBlocks(scale);
    }

    /** The world height of the summit rim. */
    public double summitY() {
        return ventY + edificeHeight();
    }

    // ------------------------------------------------------------------ the episode

    public double intensity() {
        return intensity;
    }

    public void setIntensity(double intensity) {
        this.intensity = VolcanoModel.clamp(intensity, VolcanoModel.MIN_INTENSITY, VolcanoModel.MAX_INTENSITY);
    }

    public VolcanoPhase phase() {
        return phase;
    }

    public long phaseTicks() {
        return phaseTicks;
    }

    public long phaseDuration() {
        return phaseDuration;
    }

    public void setPhaseDuration(long phaseDuration) {
        this.phaseDuration = Math.max(1L, phaseDuration);
    }

    /** Moves the ladder on one rung and restarts the clock. */
    public VolcanoPhase advancePhase() {
        phase = phase.next();
        phaseTicks = 0L;
        phaseDuration = 1L;
        return phase;
    }

    public void enterPhase(VolcanoPhase next, long durationTicks) {
        phase = next;
        phaseTicks = 0L;
        phaseDuration = Math.max(1L, durationTicks);
    }

    public void tickPhase() {
        phaseTicks++;
    }

    /** How far through its current phase the volcano is, 0..1, which is what the client eases its column with. */
    public double phaseProgress() {
        return VolcanoModel.clamp(phaseTicks / (double) Math.max(1L, phaseDuration), 0.0D, 1.0D);
    }

    public boolean phaseFinished() {
        return phaseTicks >= phaseDuration;
    }

    public long quiescenceTicks() {
        return quiescenceTicks;
    }

    public void setQuiescenceTicks(long quiescenceTicks) {
        this.quiescenceTicks = Math.max(0L, quiescenceTicks);
    }

    public void countQuiescence() {
        if (quiescenceTicks > 0L) {
            quiescenceTicks--;
        }
    }

    public boolean quiescent() {
        return quiescenceTicks <= 0L;
    }

    // ------------------------------------------------------------------ ash, wind, magma

    /** The ash thickness the eruption has laid down at the vent, in millimetres. */
    public double ashMm() {
        return ashMm;
    }

    public void setAshMm(double ashMm) {
        this.ashMm = Math.max(0.0D, ashMm);
    }

    public void addAshMm(double millimetres) {
        if (millimetres > 0.0D) {
            ashMm += millimetres;
        }
    }

    /** How old the ash is, in game days, from the tick it was last added. */
    public double ashAgeDays(long gameTime) {
        return Math.max(0.0D, (gameTime - ashTick) / 24000.0D);
    }

    public void setAshTick(long ashTick) {
        this.ashTick = ashTick;
    }

    public double windX() {
        return windX;
    }

    public double windZ() {
        return windZ;
    }

    public double windMph() {
        return windMph;
    }

    public void setWind(double windX, double windZ, double windMph) {
        double length = Math.hypot(windX, windZ);
        if (length < 1.0E-6D) {
            this.windX = 1.0D;
            this.windZ = 0.0D;
        } else {
            this.windX = windX / length;
            this.windZ = windZ / length;
        }
        this.windMph = Math.max(0.0D, windMph);
    }

    /** The height of this eruption's column, in metres, as the client and the advisory both read it. */
    public double plumeMetres() {
        return VolcanoModel.plumeHeightMetres(intensity) * phase.ashColumnFraction();
    }

    public double magmaLevel() {
        return magmaLevel;
    }

    public void setMagmaLevel(double magmaLevel) {
        this.magmaLevel = VolcanoModel.clamp(magmaLevel, 0.0D, 1.0D);
    }

    public double pressure() {
        return pressure;
    }

    public void setPressure(double pressure) {
        this.pressure = VolcanoModel.clamp(pressure, 0.0D, 1.0D);
    }

    public void buildPressure(double amount) {
        setPressure(pressure + amount);
    }

    /** The tick this volcano last laid an ashfall. Kept separately from ashTick, which is the age of the ash. */
    public long lastAshTick() {
        return lastAshTick;
    }

    public void setLastAshTick(long tick) {
        this.lastAshTick = tick;
    }

    public long lastQuakeTick() {
        return lastQuakeTick;
    }

    public void setLastQuakeTick(long tick) {
        this.lastQuakeTick = tick;
    }

    public long lastBombTick() {
        return lastBombTick;
    }

    public void setLastBombTick(long tick) {
        this.lastBombTick = tick;
    }

    public long lastFlowTick() {
        return lastFlowTick;
    }

    public void setLastFlowTick(long tick) {
        this.lastFlowTick = tick;
    }

    public long lastLaharTick() {
        return lastLaharTick;
    }

    public void setLastLaharTick(long tick) {
        this.lastLaharTick = tick;
    }

    public long lastSyncTick() {
        return lastSyncTick;
    }

    public void setLastSyncTick(long tick) {
        this.lastSyncTick = tick;
    }

    public long lastAdviceTick() {
        return lastAdviceTick;
    }

    public void setLastAdviceTick(long tick) {
        this.lastAdviceTick = tick;
    }

    // ------------------------------------------------------------------ hazards in flight

    public List<LavaFlow> flows() {
        return flows;
    }

    public List<SurgeFront> surges() {
        return surges;
    }

    public List<Lahar> lahars() {
        return lahars;
    }

    /** How far the furthest surge has run, in blocks, for the client's cloud and the advisory. */
    public double furthestSurge() {
        double furthest = 0.0D;
        for (SurgeFront surge : surges) {
            furthest = Math.max(furthest, surge.distance());
        }
        return furthest;
    }

    public boolean dead() {
        return dead;
    }

    public void kill() {
        dead = true;
    }

    // ------------------------------------------------------------------ persistence

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Id", id);
        tag.putInt("X", x);
        tag.putInt("Z", z);
        tag.putInt("VentY", ventY);
        tag.putInt("Scale", scale);
        tag.putInt("Episodes", episodes);
        tag.putDouble("Intensity", intensity);
        tag.putString("Phase", phase.name());
        tag.putLong("PhaseTicks", phaseTicks);
        tag.putLong("PhaseDuration", phaseDuration);
        tag.putLong("Quiescence", quiescenceTicks);
        tag.putDouble("AshMm", ashMm);
        tag.putLong("AshTick", ashTick);
        tag.putDouble("WindX", windX);
        tag.putDouble("WindZ", windZ);
        tag.putDouble("WindMph", windMph);
        tag.putDouble("Magma", magmaLevel);
        tag.putDouble("Pressure", pressure);
        tag.putDouble("Growth", edificeGrowth);
        tag.putInt("BuildStage", buildStage);
        tag.putInt("BuildCursor", buildCursor);
        tag.putInt("BuiltScale", builtScale);
        tag.putInt("Shell", shellVersion);
        tag.putLong("LastAsh", lastAshTick);
        tag.putLong("LastQuake", lastQuakeTick);
        tag.putLong("LastBomb", lastBombTick);
        tag.putLong("LastFlow", lastFlowTick);
        tag.putLong("LastLahar", lastLaharTick);
        ListTag flowTags = new ListTag();
        for (LavaFlow flow : flows) {
            flowTags.add(flow.save());
        }
        tag.put("Flows", flowTags);
        ListTag surgeTags = new ListTag();
        for (SurgeFront surge : surges) {
            surgeTags.add(surge.save());
        }
        tag.put("Surges", surgeTags);
        ListTag laharTags = new ListTag();
        for (Lahar lahar : lahars) {
            laharTags.add(lahar.save());
        }
        tag.put("Lahars", laharTags);
        return tag;
    }

    public static Volcano load(CompoundTag tag) {
        Volcano volcano = new Volcano(
                tag.getLong("Id"),
                tag.getInt("X"),
                tag.getInt("Z"),
                tag.contains("VentY") ? tag.getInt("VentY") : 64);
        volcano.scale = tag.getInt("Scale");
        volcano.episodes = tag.getInt("Episodes");
        volcano.intensity = tag.getDouble("Intensity");
        volcano.phase = parsePhase(tag.getString("Phase"));
        volcano.phaseTicks = tag.getLong("PhaseTicks");
        volcano.phaseDuration = Math.max(1L, tag.getLong("PhaseDuration"));
        volcano.quiescenceTicks = tag.getLong("Quiescence");
        volcano.ashMm = Math.max(0.0D, tag.getDouble("AshMm"));
        volcano.ashTick = tag.getLong("AshTick");
        volcano.setWind(tag.getDouble("WindX"), tag.getDouble("WindZ"), tag.getDouble("WindMph"));
        volcano.magmaLevel = tag.getDouble("Magma");
        volcano.pressure = tag.getDouble("Pressure");
        volcano.edificeGrowth = tag.getDouble("Growth");
        volcano.buildStage = tag.getInt("BuildStage");
        volcano.buildCursor = tag.getInt("BuildCursor");
        volcano.builtScale = tag.contains("BuiltScale") ? tag.getInt("BuiltScale") : -1;
        // An older save has no version and so no shell at all as far as this build is concerned.
        volcano.shellVersion = tag.contains("Shell") ? tag.getInt("Shell") : 0;
        volcano.lastAshTick = tag.getLong("LastAsh");
        volcano.lastQuakeTick = tag.getLong("LastQuake");
        volcano.lastBombTick = tag.getLong("LastBomb");
        volcano.lastFlowTick = tag.getLong("LastFlow");
        volcano.lastLaharTick = tag.getLong("LastLahar");
        ListTag flows = tag.getList("Flows", Tag.TAG_COMPOUND);
        for (int index = 0; index < flows.size(); index++) {
            volcano.flows.add(LavaFlow.load(flows.getCompound(index)));
        }
        ListTag surges = tag.getList("Surges", Tag.TAG_COMPOUND);
        for (int index = 0; index < surges.size(); index++) {
            volcano.surges.add(SurgeFront.load(surges.getCompound(index)));
        }
        ListTag lahars = tag.getList("Lahars", Tag.TAG_COMPOUND);
        for (int index = 0; index < lahars.size(); index++) {
            volcano.lahars.add(Lahar.load(lahars.getCompound(index)));
        }
        return volcano;
    }

    private static VolcanoPhase parsePhase(String name) {
        for (VolcanoPhase phase : VolcanoPhase.values()) {
            if (phase.name().equals(name)) {
                return phase;
            }
        }
        return VolcanoPhase.DORMANT;
    }

    /**
     * One arm of lava, walking down the mountain.
     *
     * <p>Only the head is stored, because the channel behind it is already in the world as lava and crust. The
     * head advances downhill each step, and the arm stops when it runs out of either slope or its own
     * cooling-limited length, which is what makes a real flow stop short of the flat ground below the mountain.
     */
    public static final class LavaFlow {
        private double headingX;
        private double headingZ;
        private int headX;
        private int headY;
        private int headZ;
        private int ageTicks;
        private double effusionRate;
        private double budget;
        private double travelled;
        private boolean settled;

        public LavaFlow(double headingX, double headingZ, int headX, int headY, int headZ, double effusionRate) {
            this.headingX = headingX;
            this.headingZ = headingZ;
            this.headX = headX;
            this.headY = headY;
            this.headZ = headZ;
            this.effusionRate = effusionRate;
            this.budget = VolcanoModel.lavaFlowLengthBlocks(effusionRate);
        }

        public double headingX() {
            return headingX;
        }

        public double headingZ() {
            return headingZ;
        }

        public int headX() {
            return headX;
        }

        public int headY() {
            return headY;
        }

        public int headZ() {
            return headZ;
        }

        public void setHead(int x, int y, int z) {
            this.headX = x;
            this.headY = y;
            this.headZ = z;
        }

        public void nudgeHeading(double x, double z) {
            double length = Math.hypot(x, z);
            if (length > 1.0E-6D) {
                headingX = x / length;
                headingZ = z / length;
            }
        }

        public int ageTicks() {
            return ageTicks;
        }

        public void tick() {
            ageTicks++;
        }

        public double effusionRate() {
            return effusionRate;
        }

        public double travelled() {
            return travelled;
        }

        public void addTravel(double blocks) {
            travelled += blocks;
        }

        public double budget() {
            return budget;
        }

        /** Grows the length budget when the eruption keeps feeding the same arm. */
        public void extendBudget(double blocks) {
            budget = Math.min(VolcanoModel.lavaFlowLengthBlocks(effusionRate) * 1.6D, budget + blocks);
        }

        public boolean settled() {
            return settled;
        }

        public void settle() {
            settled = true;
        }

        public double temperature() {
            return VolcanoModel.clamp(1.0D - travelled / Math.max(1.0D, budget), 0.0D, 1.0D);
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putDouble("Hx", headingX);
            tag.putDouble("Hz", headingZ);
            tag.putInt("X", headX);
            tag.putInt("Y", headY);
            tag.putInt("Z", headZ);
            tag.putInt("Age", ageTicks);
            tag.putDouble("Rate", effusionRate);
            tag.putDouble("Budget", budget);
            tag.putDouble("Travelled", travelled);
            tag.putBoolean("Settled", settled);
            return tag;
        }

        public static LavaFlow load(CompoundTag tag) {
            LavaFlow flow = new LavaFlow(
                    tag.getDouble("Hx"),
                    tag.getDouble("Hz"),
                    tag.getInt("X"),
                    tag.getInt("Y"),
                    tag.getInt("Z"),
                    tag.getDouble("Rate"));
            flow.ageTicks = tag.getInt("Age");
            flow.budget = tag.getDouble("Budget");
            flow.travelled = tag.getDouble("Travelled");
            flow.settled = tag.getBoolean("Settled");
            return flow;
        }
    }

    /**
     * One arm of a pyroclastic density current.
     *
     * <p>A collapse does not erupt from a point and stop: it spreads from wherever the column came down, across
     * the whole sector facing away from the wind, and each arm runs out until its energy is spent. The distance
     * is the arm's own, and the runout it is aiming for comes from the energy-cone rule for this intensity.
     */
    public static final class SurgeFront {
        private final double angleRadians;
        private double distance;
        private double runout;
        private int ageTicks;
        private boolean spent;

        public SurgeFront(double angleRadians, double runout) {
            this.angleRadians = angleRadians;
            this.runout = runout;
        }

        public double angleRadians() {
            return angleRadians;
        }

        public double distance() {
            return distance;
        }

        public void setDistance(double distance) {
            this.distance = Math.max(0.0D, distance);
        }

        public double runout() {
            return runout;
        }

        public void setRunout(double runout) {
            this.runout = Math.max(0.0D, runout);
        }

        public int ageTicks() {
            return ageTicks;
        }

        public void tick() {
            ageTicks++;
        }

        public boolean spent() {
            return spent;
        }

        public void spend() {
            spent = true;
        }

        /** The current slows as it spreads and cools, so the distance it gains each step shortens. */
        public double speedFactor() {
            return VolcanoModel.clamp(1.0D - 0.7D * (distance / Math.max(1.0D, runout)), 0.25D, 1.0D);
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putDouble("Angle", angleRadians);
            tag.putDouble("Distance", distance);
            tag.putDouble("Runout", runout);
            tag.putInt("Age", ageTicks);
            tag.putBoolean("Spent", spent);
            return tag;
        }

        public static SurgeFront load(CompoundTag tag) {
            SurgeFront surge = new SurgeFront(tag.getDouble("Angle"), tag.getDouble("Runout"));
            surge.distance = tag.getDouble("Distance");
            surge.ageTicks = tag.getInt("Age");
            surge.spent = tag.getBoolean("Spent");
            return surge;
        }
    }

    /** One arm of a lahar: ash turned to moving mud by rain, going down the same valleys the ash fell on. */
    public static final class Lahar {
        private double headingX;
        private double headingZ;
        private int headX;
        private int headY;
        private int headZ;
        private int ageTicks;
        private double volume;
        private boolean spent;

        public Lahar(double headingX, double headingZ, int headX, int headY, int headZ, double volume) {
            this.headingX = headingX;
            this.headingZ = headingZ;
            this.headX = headX;
            this.headY = headY;
            this.headZ = headZ;
            this.volume = volume;
        }

        public double headingX() {
            return headingX;
        }

        public double headingZ() {
            return headingZ;
        }

        public int headX() {
            return headX;
        }

        public int headY() {
            return headY;
        }

        public int headZ() {
            return headZ;
        }

        public void setHead(int x, int y, int z) {
            this.headX = x;
            this.headY = y;
            this.headZ = z;
        }

        public int ageTicks() {
            return ageTicks;
        }

        public void tick() {
            ageTicks++;
        }

        public double volume() {
            return volume;
        }

        public void drain(double amount) {
            volume = Math.max(0.0D, volume - amount);
        }

        public boolean spent() {
            return spent;
        }

        public void spend() {
            spent = true;
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putDouble("Hx", headingX);
            tag.putDouble("Hz", headingZ);
            tag.putInt("X", headX);
            tag.putInt("Y", headY);
            tag.putInt("Z", headZ);
            tag.putInt("Age", ageTicks);
            tag.putDouble("Volume", volume);
            tag.putBoolean("Spent", spent);
            return tag;
        }

        public static Lahar load(CompoundTag tag) {
            Lahar lahar = new Lahar(
                    tag.getDouble("Hx"),
                    tag.getDouble("Hz"),
                    tag.getInt("X"),
                    tag.getInt("Y"),
                    tag.getInt("Z"),
                    tag.getDouble("Volume"));
            lahar.ageTicks = tag.getInt("Age");
            lahar.spent = tag.getBoolean("Spent");
            return lahar;
        }
    }
}
