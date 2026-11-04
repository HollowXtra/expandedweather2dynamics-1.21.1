package com.imbet.photo;

import com.imbet.Config;
import com.imbet.chase.ChaseLogReadout;
import com.imbet.weather.DynamicStormController;
import com.imbet.weather.DynamicStormState;
import com.imbet.weather.ServerLightningLog;
import com.imbet.weather.StormClassifier;
import com.imbet.weather.StormSubtype;
import com.imbet.weather.TornadoWindField;
import com.imbet.registry.ModContent;
import com.imbet.weather.WeatherDataStreamService;
import com.imbet.weather.WeatherPhysicalSnapshot;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import weather2.ServerTickHandler;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The camera's one act: decide what was photographed, measure what was between the camera and it, score the
 * frame and file it.
 *
 * <p><b>The subject is what you pointed at.</b> Among the circulations in frame, the one nearest the centre of
 * the view wins, ties going to the nearer camera. That is deliberately not the same rule as "the one that
 * would score best": a photographer decides where to point, and a mod that quietly substituted a better storm
 * for the one you framed would be taking the photograph for you. If what you pointed at is behind a ridge, the
 * ridge is in the photograph, and the score says so.
 *
 * <p><b>The frame is measured, not assumed.</b> Distance and the angle off the aim axis are geometry off the
 * camera's own eye; the occlusion is sampled by casting real rays up the funnel and into the wall cloud above
 * it, because the line of sight to a subject that is two hundred blocks tall is not one ray; the rain is the
 * level's own rain at the camera; and the lightning is a bolt in frame within the last half second, from
 * {@link ServerLightningLog}, because a bolt lives two ticks and a shutter has to be allowed to be slower
 * than that.
 *
 * <p>Everything that is judgement is in {@link PhotoScore}, everything that is a moment is in
 * {@link PhotoShot}, and everything that is a record is in {@link PhotoAlbum}. What is left here is the world:
 * which circulations exist, what is in the way, and what the camera was pointed at.
 */
public final class PhotoService {
    /** What the camera did, which is what the item needs to know to charge for it. */
    public enum Outcome {
        /** A photograph was scored and filed. */
        FILED,
        /** Photography is switched off. */
        DISABLED,
        /** This player's photographs are not counted - a spectator, or a creative player by setting. */
        NOT_COUNTED,
        /** Nothing worth photographing was in the frame. */
        NO_SUBJECT
    }

    /** A camera cannot be narrower or wider than this; a client reporting anything else is clamped to it. */
    public static final double MIN_FOV_DEGREES = 30.0D;
    public static final double MAX_FOV_DEGREES = 170.0D;
    /** How tall the sampled subject is when the funnel itself is invisible, in blocks. */
    public static final double MIN_SUBJECT_HEIGHT_BLOCKS = 16.0D;
    /** The tallest a sampled subject may be, in blocks: past this the samples are looking at empty sky. */
    public static final double MAX_SUBJECT_HEIGHT_BLOCKS = 256.0D;
    /** Two aims within this many radians of the same angle count as the same angle for tie-breaking. */
    private static final double ANGLE_EPSILON = 1.0E-9D;

    private PhotoService() {
    }

    /**
     * Takes a photograph from this player's eye, along their aim. Nothing is charged and no cooldown is started
     * unless the result is {@link Outcome#FILED} - a camera that punishes you for pointing it at the wrong
     * cloud is a camera nobody tests.
     */
    public static Outcome take(ServerPlayer player, Vec3 aim, double fovDegrees) {
        if (player == null || !(player.level() instanceof ServerLevel level)) {
            return Outcome.NO_SUBJECT;
        }
        if (!Config.PHOTO_ENABLED.get()) {
            player.sendSystemMessage(dim("Photography is disabled here."));
            return Outcome.DISABLED;
        }
        if (!counts(player)) {
            player.sendSystemMessage(dim(player.isSpectator()
                    ? "Spectators cannot take photographs."
                    : "Photographs are not counted in creative mode here."));
            return Outcome.NOT_COUNTED;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 direction = direction(aim, player);
        double fov = Mth.clamp(fovDegrees, MIN_FOV_DEGREES, MAX_FOV_DEGREES);
        double halfFrame = Math.toRadians(fov * 0.5D);
        double maxDistance = Math.max(8.0D, Config.PHOTO_MAX_DISTANCE_BLOCKS.get());
        List<Subject> subjects = subjects(level);
        double[] angles = new double[subjects.size()];
        double[] distances = new double[subjects.size()];
        for (int index = 0; index < subjects.size(); index++) {
            Vec3 toSubject = subjects.get(index).position().subtract(eye);
            distances[index] = toSubject.length();
            angles[index] = angleOffAxis(eye, toSubject, direction);
        }
        int chosen = choose(angles, distances, halfFrame, maxDistance,
                Config.PHOTO_MIN_SUBJECT_MPH.get(), subjects);
        if (chosen < 0) {
            player.sendSystemMessage(dim("Nothing to photograph: no circulation in frame within "
                    + ChaseLogReadout.round(maxDistance) + " blocks."));
            return Outcome.NO_SUBJECT;
        }
        Subject subject = subjects.get(chosen);
        int samples = Mth.clamp(Config.PHOTO_OCCLUSION_SAMPLES.get(), 1, 64);
        int occluded = 0;
        for (Vec3 point : subject.sightlineSamples(samples, Config.PHOTO_FUNNEL_HEIGHT_SCALE.get())) {
            if (blocked(level, player, eye, point)) {
                occluded++;
            }
        }
        boolean raining = level.isRaining() && level.getRainLevel(1.0F) >= Config.PHOTO_RAIN_LEVEL.get();
        boolean insideCore = subject.insideCore(level, eye);
        boolean lightning = lightningInFrame(level, player, eye, direction, halfFrame);
        PhotoFrame frame = new PhotoFrame(
                subject.key(), subject.label(),
                subject.position().x, subject.position().y, subject.position().z,
                subject.stormMph(), subject.visibleFunnelWidth(), subject.debrisSwathBlocks(), subject.coreRadius(),
                subject.structureStrength(),
                eye.x, eye.y, eye.z, direction.x, direction.y, direction.z,
                fov, maxDistance,
                Math.max(1.0D, Config.PHOTO_SWEET_SPOT_MIN_BLOCKS.get()),
                Math.max(1.0D, Config.PHOTO_SWEET_SPOT_MAX_BLOCKS.get()),
                Config.PHOTO_MIN_SUBJECT_MPH.get(),
                occluded, samples, insideCore, raining, lightning, level.getDayTime());
        PhotoShot shot = PhotoShot.of(frame, PhotoScore.of(frame), level.getGameTime());
        PhotoAlbumSavedData.get(level).record(player, shot, Config.PHOTO_ALBUM_SIZE.get());
        if (Config.PHOTO_ANNOUNCE.get()) {
            player.sendSystemMessage(Component.literal(PhotoReadout.filedLine(shot)).withStyle(gradeColor(shot.grade())));
            player.sendSystemMessage(Component.literal(PhotoReadout.partsLine(shot)).withStyle(ChatFormatting.GRAY));
            for (String advice : PhotoReadout.adviceLines(shot)) {
                player.sendSystemMessage(Component.literal(advice).withStyle(ChatFormatting.YELLOW));
            }
        }
        return Outcome.FILED;
    }

    /**
     * Whether this player is holding a camera, in either hand, which is what a viewfinder is drawn for. One home
     * for the question because both sides ask it: the server decides who is worth feeding, and the client decides
     * whether to draw what it was fed.
     */
    public static boolean holdsCamera(Player player) {
        if (player == null) {
            return false;
        }
        return player.getMainHandItem().is(ModContent.CAMERA_ITEM.get())
                || player.getOffhandItem().is(ModContent.CAMERA_ITEM.get());
    }

    /** The lines the weather computer prints for this player's album. */
    public static List<String> readoutLines(ServerPlayer player) {
        if (player == null || !(player.level() instanceof ServerLevel level) || !Config.PHOTO_ENABLED.get()) {
            return List.of();
        }
        PhotoAlbum album = PhotoAlbumSavedData.get(level).album(player.getUUID());
        return PhotoReadout.lines(album, Config.PHOTO_READOUT_LINES.get(), level.getGameTime());
    }

    /** Opens the album screen on this player's client, from the album as the server has it. */
    public static boolean openAlbum(ServerPlayer player) {
        if (player == null || !(player.level() instanceof ServerLevel level)) {
            return false;
        }
        if (!Config.PHOTO_ENABLED.get()) {
            player.sendSystemMessage(dim("Photography is disabled here."));
            return false;
        }
        PhotoAlbum album = PhotoAlbumSavedData.get(level).album(player.getUUID());
        player.openMenu(new SimpleMenuProvider(
                (containerId, inventory, openedPlayer) -> new PhotoAlbumMenu(containerId, inventory, album),
                Component.translatable("screen.expandedweather2dynamics.photo_album")
        ), buffer -> PhotoAlbumMenu.write(buffer, album, level.getGameTime()));
        return true;
    }

    /**
     * The angle between the camera's aim and the direction to a subject, in radians, 0 when the subject is dead
     * ahead. A subject exactly at the eye has no direction and reads as out of frame rather than as dead centre.
     */
    public static double angleOffAxis(Vec3 eye, Vec3 toSubject, Vec3 direction) {
        double length = toSubject.length();
        double dirLength = direction.length();
        if (length < 1.0E-4D || dirLength < 1.0E-6D) {
            return Double.MAX_VALUE;
        }
        double dot = toSubject.dot(direction) / (length * dirLength);
        return Math.acos(Mth.clamp(dot, -1.0D, 1.0D));
    }

    /**
     * Which subject the camera was pointed at: the smallest angle off the aim axis, ties going to the nearer
     * camera. Entries out of frame, beyond the camera's range, or weaker than the reporting floor are not
     * candidates at all - {@code -1} says there was nothing to photograph.
     *
     * <p>Public and free of the world so the rule can be checked directly: it is the one judgement in this
     * class that decides what a photograph is <em>of</em>.
     */
    public static int choose(double[] angles, double[] distances, double halfFrame, double maxDistance,
                            double minSubjectMph, List<Subject> subjects) {
        double[] strengths = new double[subjects.size()];
        for (int index = 0; index < strengths.length; index++) {
            strengths[index] = subjects.get(index).stormMph();
        }
        return chooseIndex(angles, distances, halfFrame, maxDistance, minSubjectMph, strengths);
    }

    /**
     * The rule itself, over bare numbers, because two callers need it: the shutter, deciding what a photograph
     * is of, and the viewfinder, deciding what to draw a reticle around before the shutter is pressed. A
     * viewfinder that pointed at a different subject than the shutter would photograph would be worse than
     * having none, so there is one rule and this is it. Entries out of frame, out of range or below the floor
     * are not candidates, and {@code -1} says there was nothing to point at.
     */
    public static int chooseIndex(double[] angles, double[] distances, double halfFrame, double maxDistance,
                                  double minSubjectMph, double[] strengths) {
        int chosen = -1;
        double bestAngle = 0.0D;
        double bestDistance = 0.0D;
        int count = Math.min(angles.length, Math.min(distances.length, strengths.length));
        for (int index = 0; index < count; index++) {
            double angle = Math.abs(angles[index]);
            double distance = distances[index];
            if (!Double.isFinite(angle) || !Double.isFinite(distance)
                    || angle > halfFrame || distance > maxDistance) {
                continue;
            }
            if (strengths[index] < minSubjectMph) {
                continue;
            }
            boolean better = chosen < 0
                    || angle < bestAngle - ANGLE_EPSILON
                    || (Math.abs(angle - bestAngle) <= ANGLE_EPSILON && distance < bestDistance);
            if (better) {
                chosen = index;
                bestAngle = angle;
                bestDistance = distance;
            }
        }
        return chosen;
    }

    /**
     * The subjects the camera would consider for a player standing at this eye, as bare numbers, for the packet
     * that feeds a viewfinder. Filtered here rather than on the client so the viewfinder cannot offer a subject
     * the shutter would refuse: the floor, the range and the level's own list of circulations all decide this
     * once, on the side that owns them.
     */
    public static List<PhotoTarget> targetsOf(List<Subject> subjects, Vec3 eye, double maxDistanceBlocks) {
        List<PhotoTarget> targets = new ArrayList<>();
        if (subjects == null || eye == null) {
            return targets;
        }
        double range = Math.max(8.0D, maxDistanceBlocks);
        double floor = Config.PHOTO_MIN_SUBJECT_MPH.get();
        for (Subject subject : subjects) {
            if (subject.stormMph() < floor) {
                continue;
            }
            Vec3 position = subject.position();
            if (position == null || position.distanceTo(eye) > range) {
                continue;
            }
            targets.add(new PhotoTarget(subject.key(), subject.label(),
                    position.x, position.y, position.z,
                    subject.stormMph(), subject.visibleFunnelWidth(), subject.debrisSwathBlocks(),
                    subject.coreRadius(), subject.structureStrength()));
            if (targets.size() >= PhotoTarget.MAX_SENT) {
                break;
            }
        }
        return targets;
    }

    /** Whether a line of sight is blocked. Anything you could walk into counts, leaves included. */
    private static boolean blocked(ServerLevel level, ServerPlayer player, Vec3 from, Vec3 to) {
        if (from.distanceToSqr(to) < 1.0E-4D) {
            return false;
        }
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() != HitResult.Type.MISS;
    }

    /**
     * Whether a bolt is in the frame, within a window of ticks, and not behind anything. The window is what
     * makes this a matter of timing rather than of luck: a bolt lives two ticks, so a shutter that needed the
     * bolt to still exist would be a shutter nobody could time.
     */
    private static boolean lightningInFrame(ServerLevel level, ServerPlayer player, Vec3 eye, Vec3 direction,
                                            double halfFrame) {
        int range = Math.max(16, Config.PHOTO_LIGHTNING_RANGE_BLOCKS.get());
        double window = Math.max(1, Config.PHOTO_LIGHTNING_WINDOW_TICKS.get());
        for (ServerLightningLog.Strike strike : ServerLightningLog.recent(level, (long) window)) {
            Vec3 position = strike.position();
            if (eye.distanceTo(position) > range) {
                continue;
            }
            if (angleOffAxis(eye, position.subtract(eye), direction) > halfFrame) {
                continue;
            }
            if (!blocked(level, player, eye, position)) {
                return true;
            }
        }
        return false;
    }

    /** The aim, or the player's own look if a client sent something unusable. */
    private static Vec3 direction(Vec3 aim, ServerPlayer player) {
        if (aim != null && aim.lengthSqr() > 1.0E-6D) {
            return aim.normalize();
        }
        return player.getLookAngle();
    }

    /**
     * Whether this player's photographs are worth filing. Spectators are not there at all; a creative player
     * can stand in a tornado with a full inventory and no consequence, but a camera is a camera - the default
     * counts creative shots, because the alternative is a player who builds something worth photographing and
     * cannot photograph it.
     */
    private static boolean counts(ServerPlayer player) {
        if (player == null || player.isSpectator()) {
            return false;
        }
        return !player.isCreative() || Config.PHOTO_COUNT_CREATIVE.get();
    }

    private static Component dim(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    private static ChatFormatting gradeColor(PhotoScore.Grade grade) {
        return switch (grade) {
            case EXCELLENT -> ChatFormatting.GREEN;
            case GREAT -> ChatFormatting.AQUA;
            case GOOD -> ChatFormatting.YELLOW;
            case FAIR -> ChatFormatting.WHITE;
            case POOR -> ChatFormatting.RED;
        };
    }

    /**
     * One circulation worth photographing: its identity, what it is called, where it is, how strong and how
     * wide it is, whether it has supercell structure over it, and how to sample the wind at a point.
     *
     * <p>Two paths build these, exactly as the chase log does: the published snapshots when the weather scan
     * has run, and the storm objects themselves on the ticks before it has, so a camera is not blind for the
     * first second of a level.
     */
    public record Subject(String key, String label, Vec3 position, double stormMph, double visibleFunnelWidth,
                          double debrisSwathBlocks, double coreRadius, double structureStrength, double structureTopY,
                          WeatherPhysicalSnapshot snapshot, StormObject storm) {
        /** The snapshot path, which carries the vortex's own sampled field. */
        public Subject(WeatherPhysicalSnapshot source) {
            this(source.sourceKey(),
                    source.stageLabel() == null || source.stageLabel().isBlank()
                            ? source.subtype().label()
                            : source.stageLabel() + " " + source.subtype().label(),
                    source.circulationPosition(),
                    source.maxWindMph(),
                    source.visibleFunnelWidth(),
                    // The swath the circulation is cutting, which is the mod's own damage path rather than a
                    // second reading of the wind: the two are different photographs of the same tornado.
                    Math.max(0.0D, source.damagePathWidth()),
                    source.coreRadius(),
                    source.structureStrength(),
                    source.structureMesocyclone().lengthSqr() > 1.0D
                            ? source.structureMesocyclone().y
                            : source.stormPosition().y,
                    source,
                    null);
        }

        /**
         * The fallback path, for a level whose scan has not published yet. A null storm is allowed and means
         * there is no storm object to read a mesocyclone height off, in which case the subject's own height is
         * the top of the sampled column - which is also what a harness constructs so it can check the camera's
         * choice of subject without a world.
         */
        public Subject(String key, String label, Vec3 position, double stormMph, double visibleFunnelWidth,
                       double debrisSwathBlocks, double coreRadius, double structureStrength, StormObject storm) {
            this(key, label, position, stormMph, visibleFunnelWidth, debrisSwathBlocks, coreRadius, structureStrength,
                    storm == null || storm.pos == null ? position.y : storm.pos.y, null, storm);
        }

        /** Whether the camera is standing inside this circulation's core, which is a whiteout. */
        public boolean insideCore(ServerLevel level, Vec3 eye) {
            TornadoWindField.WindSample sample = snapshot != null
                    ? snapshot.sampleAt(eye)
                    : (storm == null ? TornadoWindField.WindSample.CALM : TornadoWindField.sample(storm, level, eye));
            if (sample.distanceFromCore() == Double.MAX_VALUE) {
                return false;
            }
            return sample.distanceFromCore() <= Math.max(1.0D, coreRadius);
        }

        /**
         * The points the line of sight is checked against: up the funnel from its foot, and on into the wall
         * cloud above it, because that is the photograph. The funnel's own height comes from how wide it looks,
         * with a floor so a thin rope still gets a column rather than a point, and a ceiling so a three mile
         * wedge does not spend samples on empty sky.
         */
        public List<Vec3> sightlineSamples(int samples, double heightScale) {
            int count = Math.max(1, samples);
            double height = Mth.clamp(Math.max(0.0D, visibleFunnelWidth) * Math.max(1.0D, heightScale),
                    MIN_SUBJECT_HEIGHT_BLOCKS, MAX_SUBJECT_HEIGHT_BLOCKS);
            double top = Math.max(position.y + height, structureTopY);
            List<Vec3> points = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                double fraction = count == 1 ? 0.0D : index / (double) (count - 1);
                points.add(new Vec3(position.x, position.y + (top - position.y) * fraction, position.z));
            }
            return points;
        }
    }

    /**
     * Every circulation in play worth considering, from the level's own snapshots, falling back to the storm
     * objects while the scan has not published. A funnel cloud is included - it is a photograph of a tornado
     * that is not one, which is the whole reason the type exists - and so are waterspouts, which the chase log
     * excludes because a chase log is about damage.
     */
    public static List<Subject> subjects(ServerLevel level) {
        List<Subject> subjects = new ArrayList<>();
        List<WeatherPhysicalSnapshot> snapshots = WeatherDataStreamService.snapshots(level);
        if (!snapshots.isEmpty()) {
            for (WeatherPhysicalSnapshot snapshot : snapshots) {
                if (snapshot.trackableVortex() && snapshot.circulationPosition() != null) {
                    subjects.add(new Subject(snapshot));
                }
            }
            return subjects;
        }
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null) {
            return subjects;
        }
        for (WeatherObject object : new ArrayList<>(manager.getStormObjects())) {
            if (!(object instanceof StormObject storm) || storm.pos == null || storm.isDead
                    || DynamicStormController.isAbsorbedOrDecaying(storm)) {
                continue;
            }
            DynamicStormState state = DynamicStormController.stateFor(storm);
            if (storm.isTornadoFormingOrGreater()) {
                subjects.add(new Subject(
                        "storm:" + storm.ID,
                        StormClassifier.labelFor(storm, storm.attrib_waterSpout ? StormSubtype.WATERSPOUT : StormSubtype.TORNADO),
                        storm.pos,
                        TornadoWindField.maxWindMph(storm),
                        state.visibleFunnelWidth(),
                        state.damagePathWidth(),
                        Math.max(2.0D, state.damagePathWidth() * 0.5D),
                        state.supercellStructureStrength(),
                        storm
                ));
            }
            for (DynamicStormState.TornadoCirculationState satellite : state.satelliteTornadoes()) {
                if (!satellite.touchedDown() || satellite.absorbed()) {
                    continue;
                }
                subjects.add(new Subject(
                        "storm:" + storm.ID + ":" + satellite.id(),
                        StormSubtype.SATELLITE_TORNADO.label(),
                        DynamicStormController.satelliteCenter(storm, satellite, storm.pos.y),
                        DynamicStormController.satelliteWindMph(storm, satellite),
                        satellite.visibleFunnelWidth(),
                        satellite.damagePathWidth(),
                        Math.max(1.5D, satellite.damagePathWidth() * 0.5D),
                        state.supercellStructureStrength(),
                        storm
                ));
            }
        }
        return subjects;
    }
}
