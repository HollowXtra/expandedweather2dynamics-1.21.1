package com.imbet.client;

import com.imbet.Config;
import com.imbet.network.StructureFlightPayload;
import com.imbet.weather.DynamicStormController;
import com.imbet.weather.TornadoWindField;
import extendedrenderer.particle.entity.ParticleCube;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import weather2.weathersystem.WeatherManagerClient;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Flies a whole body a tornado has taken: a building off its foundation, or a tree out of the ground.
 *
 * <p>The body is driven here rather than by Weather2's debris carrier, and that is the point: blocks
 * that the funnel grabs one at a time are independent things, each pushed by the local wind, which is
 * exactly right for them and exactly wrong for a house. A house has to hold together while it turns.
 * So this class owns every one of its blocks for the first part of the flight and applies one pose to
 * all of them - {@link StructureFlightMotion} - which is what makes the building fly as a building and
 * roll about all three axes instead of dissolving into a cloud of planks on the first tick.</p>
 *
 * <p>Then it comes apart, from the outside in: blocks whose turn has come are pushed out of the body by
 * the local wind and tumble away as splinters until they fade. Nothing here damages anything - the
 * body is pixels, as Weather2's own flying blocks are, and the world damage already came from the
 * storm's own systems.</p>
 *
 * <p>A THROWN body - a tree torn out of the ground - is the other way round. It is not carried, it is
 * slung: {@link StructureFlightMotion#thrownPose} sends it up and out along one ballistic arc and puts
 * it down on the landing point the server aimed at before it was lifted. What happens then is decided
 * by the payload. A tree that shatters fails into wind-carried splinters like a building does. A tree
 * that FELLs - the server only allows that where the ground can take a trunk - settles onto the ground
 * instead: over {@link StructureFlightPayload#FELLED_SETTLE_TICKS} after the impact its pitch eases to
 * a trunk lying on its side, its yaw squares to the throw's axis, its centre drops the last little way
 * to the real surface height, and then it holds at rest for a moment before fading out - on exactly
 * the tick the server places the real felled trunk in the world, so the drawn body is replaced by the
 * actual blocks rather than popping out from under the eye.</p>
 */
public final class ClientStructureFlight {

    /** Every building currently in the air on this client, keyed by the event that took it. */
    private static final Map<Long, Flight> FLIGHTS = new HashMap<>();
    /** Ticks a block that has come off the building tumbles free before it fades out. */
    private static final int SPLINTER_LIFETIME_TICKS = 100;
    /** Ticks a felled body holds at rest after the settle before it fades out - long enough to be seen. */
    private static final int FELLED_REST_TICKS = 10;
    /** Ticks the fade-out of a settled, already-placed felled body takes. */
    private static final int FELLED_FADE_TICKS = 8;
    /** How much of the local wind a splinter still takes - it is debris now, not part of a building. */
    private static final double SPLINTER_WIND_FRACTION = 0.42D;
    /** How far a splinter is thrown clear of the body at the moment it comes off. */
    private static final double SPLINTER_POP_SPEED = 0.22D;
    /** Below this many blocks a body is not worth flying; the server would not have sent it anyway. */
    private static final int MIN_BODY_BLOCKS = 3;
    /** Fallback funnel radius when the storm's own is not readable. */
    private static final double FALLBACK_FUNNEL_RADIUS = 10.0D;

    private ClientStructureFlight() {
    }

    /** A building the server has taken, waiting for the client to put it in the air. */
    public static void accept(StructureFlightPayload payload) {
        if (payload == null || payload.blocks().size() < MIN_BODY_BLOCKS || !Config.VISUALS_ENABLED.get()) {
            return;
        }
        Flight flight = new Flight(payload);
        synchronized (FLIGHTS) {
            FLIGHTS.put(flight.key, flight);
        }
    }

    /**
     * Advances every airborne building one tick: spawns the ones that have just been taken, moves the
     * bodies that are still whole, releases the blocks whose turn it is, and retires the splinters.
     */
    public static void tick(ClientLevel level, WeatherManagerClient manager, long gameTime) {
        if (level == null) {
            return;
        }
        if (!Config.VISUALS_ENABLED.get()) {
            clear();
            return;
        }
        List<Flight> flights;
        synchronized (FLIGHTS) {
            if (FLIGHTS.isEmpty()) {
                return;
            }
            flights = new ArrayList<>(FLIGHTS.values());
        }
        Iterator<Flight> iterator = flights.iterator();
        while (iterator.hasNext()) {
            Flight flight = iterator.next();
            if (!flight.tick(level, manager, gameTime)) {
                synchronized (FLIGHTS) {
                    FLIGHTS.remove(flight.key);
                }
                iterator.remove();
            }
        }
    }

    /** Drops every airborne building, e.g. on disconnect or with the visuals switched off. */
    public static void clear() {
        synchronized (FLIGHTS) {
            for (Flight flight : FLIGHTS.values()) {
                flight.removeAll();
            }
            FLIGHTS.clear();
        }
    }

    private static final class Flight {
        private final long key;
        private final long stormId;
        private final StructureFlightPayload.Kind kind;
        private final double bodyX;
        private final double bodyY;
        private final double bodyZ;
        private final double groundY;
        private final int durationTicks;
        private final float tumbleRate;
        private final double landX;
        private final double landY;
        private final double landZ;
        private final double spinSign;
        private final boolean felled;
        private final List<Piece> pieces;
        private long startTick = -1L;
        private boolean spawned;
        private double funnelX;
        private double funnelZ;
        private double funnelRadius = FALLBACK_FUNNEL_RADIUS;
        private double rotationSign = 1.0D;

        Flight(StructureFlightPayload payload) {
            // One key per event, so two buildings taken by the same storm cannot overwrite each other,
            // and a tree cannot land on a building's key and vanish.
            this.key = payload.stormId() * 1_000_003L + payload.blocks().size() * 31L + payload.durationTicks()
                    + payload.kind().ordinal() * 9_999_991L;
            this.stormId = payload.stormId();
            this.kind = payload.kind();
            this.bodyX = payload.x();
            this.bodyY = payload.y();
            this.bodyZ = payload.z();
            this.groundY = payload.groundY();
            this.durationTicks = Math.max(1, payload.durationTicks());
            this.tumbleRate = payload.tumbleRate();
            this.landX = payload.landX();
            this.landY = payload.landY();
            this.landZ = payload.landZ();
            this.spinSign = payload.spinSign();
            this.felled = payload.fellOnLanding();
            this.funnelX = payload.x();
            this.funnelZ = payload.z();
            double maxRadius = 1.0D;
            List<Piece> built = new ArrayList<>(payload.blocks().size());
            for (StructureFlightPayload.Block block : payload.blocks()) {
                BlockState state = Block.stateById(block.stateId());
                if (state == null || state.isAir()) {
                    continue;
                }
                // Offsets arrive in half blocks, so that a building whose centre falls between two
                // blocks still has every block exactly where it was.
                double ox = block.dx() * 0.5D;
                double oy = block.dy() * 0.5D;
                double oz = block.dz() * 0.5D;
                maxRadius = Math.max(maxRadius, Math.sqrt(ox * ox + oy * oy + oz * oz));
                built.add(new Piece(ox, oy, oz, state, hash(block)));
            }
            this.pieces = built;
            // Shedding order is set once, from how far each block sits from the body's centre. A thrown
            // body does not shed on the way: a tree is in one piece until it hits, so every block stays
            // until the landing tick, which is the last one of the flight.
            for (Piece piece : pieces) {
                if (kind == StructureFlightPayload.Kind.THROWN) {
                    piece.shedProgress = 1.0D;
                    continue;
                }
                double radius = Math.sqrt(piece.ox * piece.ox + piece.oy * piece.oy + piece.oz * piece.oz);
                piece.shedProgress = StructureFlightMotion.shedProgress(radius / maxRadius, piece.jitter);
            }
        }

        boolean tick(ClientLevel level, WeatherManagerClient manager, long gameTime) {
            if (pieces.isEmpty()) {
                return false;
            }
            if (startTick < 0L) {
                startTick = gameTime;
            }
            if (!spawned) {
                spawn(level);
            }
            StormObject storm = findStorm(manager);
            if (storm != null && storm.pos != null) {
                funnelX = storm.pos.x;
                funnelZ = storm.pos.z;
                rotationSign = DynamicStormController.rotationSign(storm);
                double visible = DynamicStormController.stateFor(storm).visibleFunnelWidth();
                if (visible > 1.0D) {
                    funnelRadius = Math.max(4.0D, visible * 0.55D);
                }
            }
            int ticks = (int) Math.max(0L, gameTime - startTick);
            double progress = StructureFlightMotion.progress(ticks, durationTicks);
            // A thrown body's path is fixed by the payload, so it does not read the live funnel at all:
            // the landing point was decided before the lift and both sides have to agree on it.
            //
            // Its arc is driven by the body's CENTRE, not by the ground it stood on. The payload's groundY
            // is the tree's base so the server can place the landing, but a pose that starts at the base
            // would draw the whole tree that far below its own trunk - the body is positioned by its centre,
            // and its blocks hang off that - so the centre's height above the base is carried through to
            // the landing and the tree stands on the ground at both ends of the flight.
            //
            // The FLIGHT ends at the landing tick. What happens after is the settle, and only a felled
            // body has one: its pose walks from the landing pose to a trunk at rest on the ground.
            StructureFlightMotion.Pose pose;
            if (kind == StructureFlightPayload.Kind.THROWN) {
                pose = StructureFlightMotion.thrownPose(bodyX, bodyY, bodyZ,
                        landX, landY + (bodyY - groundY), landZ,
                        spinSign, Math.min(ticks, durationTicks), durationTicks, tumbleRate);
                if (felled && ticks > durationTicks) {
                    pose = settledPose(level, pose, ticks);
                }
            } else {
                pose = StructureFlightMotion.pose(
                        bodyX, groundY, bodyZ, funnelX, funnelZ, funnelRadius,
                        rotationSign, ticks, durationTicks, tumbleRate);
            }

            boolean anyAlive = false;
            boolean shatterNow = kind == StructureFlightPayload.Kind.THROWN && !felled && progress >= 1.0D;
            for (Piece piece : pieces) {
                if (piece.cube == null || piece.removed) {
                    continue;
                }
                anyAlive = true;
                if (piece.shed) {
                    tickSplinter(level, storm, piece);
                    continue;
                }
                if (shatterNow
                        || (!felled && StructureFlightMotion.hasShed(piece.shedProgress, progress))) {
                    shed(piece, level, storm, pose);
                    continue;
                }
                if (felled && progress >= 1.0D) {
                    // The settled body has been placed as real blocks by the server on the settle tick;
                    // after a moment at rest it fades rather than vanishing under the eye.
                    int into = ticks - durationTicks;
                    if (into > StructureFlightPayload.FELLED_SETTLE_TICKS + FELLED_REST_TICKS) {
                        float alpha = Math.max(0.0F, 1.0F - (into - StructureFlightPayload.FELLED_SETTLE_TICKS
                                - FELLED_REST_TICKS) / (float) FELLED_FADE_TICKS);
                        piece.cube.setAlpha(alpha);
                        piece.cube.setFullAlphaTarget(alpha);
                        if (alpha <= 0.0F) {
                            piece.cube.remove();
                            piece.removed = true;
                            continue;
                        }
                    }
                }
                double[] rotated = StructureFlightMotion.rotate(piece.ox, piece.oy, piece.oz,
                        pose.yawDegrees(), pose.pitchDegrees(), pose.rollDegrees());
                moveCube(piece, pose.x() + rotated[0], pose.y() + rotated[1], pose.z() + rotated[2]);
                // Every block of the body carries the SAME orientation, because the body turns as one
                // thing: a per-block spin here would fly a pile of individually tumbling blocks arranged
                // in the shape of a house, not a house. The pose's yaw and pitch are what the textures
                // can follow; the roll lives in the positions, which is where it is actually seen.
                piece.cube.rotationYaw = (float) pose.yawDegrees();
                piece.cube.rotationPitch = (float) (pose.pitchDegrees() * 0.6D);
            }
            return anyAlive;
        }

        /**
         * The felled settle: from the landing pose to a trunk at rest on the ground.
         *
         * <p>Three things change, blended by the shared settle progress. The pitch eases from the
         * tumbling pose to a trunk lying on its side. The yaw squares to the throw's world axis, so
         * the drawn body ends up pointing the way the placed trunk lies. The centre drops the last
         * little way to the real surface at the landing - read fresh from the heightmap, so the tree
         * comes to rest on whatever the crater actually left, not on the height predicted at pluck
         * time. The whole motion is continuous with the flight by construction: at progress 0 the
         * settle returns the landing pose exactly.
         */
        private StructureFlightMotion.Pose settledPose(ClientLevel level, StructureFlightMotion.Pose landing,
                                                       int ticks) {
            double settle = StructureFlightMotion.settleProgress(ticks, durationTicks,
                    StructureFlightPayload.FELLED_SETTLE_TICKS);
            double heading = Math.toDegrees(Math.atan2(landZ - bodyZ, landX - bodyX));
            double yaw = StructureFlightMotion.settleBlend(landing.yawDegrees(),
                    StructureFlightMotion.settledYaw(heading), settle);
            double pitch = StructureFlightMotion.settleBlend(landing.pitchDegrees(),
                    StructureFlightMotion.settledPitchDegrees(), settle);
            double roll = StructureFlightMotion.settleBlend(landing.rollDegrees(), 0.0D, settle);
            double drop = settleDropBlocks(level, landing);
            double[] offset = StructureFlightMotion.settledOffset(settle, drop);
            return new StructureFlightMotion.Pose(landing.x() + offset[0], landing.y() + offset[1],
                    landing.z() + offset[2], yaw, pitch, roll);
        }

        /**
         * How far the centre descends from the landing pose to rest. The drawn body lands with its
         * centre a trunk's half-length above the ground it touched; lying flat, that centre is about
         * one block up. The difference is read against the real surface so a tree landing in a crater
         * settles into it rather than hovering over it.
         */
        private double settleDropBlocks(ClientLevel level, StructureFlightMotion.Pose landing) {
            BlockPos column = BlockPos.containing(landX, landY + 1.0D, landZ);
            if (!level.isLoaded(column)) {
                return 0.0D;
            }
            BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, column);
            double restCentreY = surface.getY() + 0.5D;
            return Math.max(0.0D, landing.y() - restCentreY);
        }

        private void spawn(ClientLevel level) {
            spawned = true;
            float renderCull = Minecraft.getInstance().gameRenderer.getRenderDistance() * 4.0F;
            for (Piece piece : pieces) {
                double[] rotated = StructureFlightMotion.rotate(piece.ox, piece.oy, piece.oz, 0.0D, 0.0D, 0.0D);
                piece.cube = spawnCube(level, bodyX + rotated[0], bodyY + rotated[1], bodyZ + rotated[2],
                        piece.state, renderCull);
                piece.lastX = bodyX + rotated[0];
                piece.lastY = bodyY + rotated[1];
                piece.lastZ = bodyZ + rotated[2];
            }
        }

        private void shed(Piece piece, ClientLevel level, StormObject storm, StructureFlightMotion.Pose pose) {
            piece.shed = true;
            Vec3 wind = windAt(level, storm, piece.cube);
            double outwardX = piece.cube.getPosX() - pose.x();
            double outwardZ = piece.cube.getPosZ() - pose.z();
            double outward = Math.sqrt(outwardX * outwardX + outwardZ * outwardZ);
            double popX = outward < 0.01D ? 0.0D : outwardX / outward;
            double popZ = outward < 0.01D ? 0.0D : outwardZ / outward;
            piece.velX = wind.x * SPLINTER_WIND_FRACTION + popX * SPLINTER_POP_SPEED;
            piece.velY = wind.y * SPLINTER_WIND_FRACTION + 0.06D;
            piece.velZ = wind.z * SPLINTER_WIND_FRACTION + popZ * SPLINTER_POP_SPEED;
        }

        private void tickSplinter(ClientLevel level, StormObject storm, Piece piece) {
            Vec3 wind = windAt(level, storm, piece.cube);
            // It is thrown clear and then starts taking the wind: a splinter is carried much harder
            // than the body it came off, because there is nothing left holding it together.
            piece.velX += (wind.x - piece.velX) * 0.10D;
            piece.velZ += (wind.z - piece.velZ) * 0.10D;
            piece.velY += (wind.y - piece.velY) * 0.10D - 0.006D;
            moveCube(piece,
                    piece.lastX + piece.velX,
                    piece.lastY + piece.velY,
                    piece.lastZ + piece.velZ);
            piece.splinterAge++;
            // A splinter is a lone plank now, so it DOES get its own tumble - that is the difference
            // between being part of a building and being debris.
            piece.cube.rotationYaw += 9.0F + (float) (piece.jitter * 4.0D);
            piece.cube.rotationPitch += 6.0F;
            if (piece.splinterAge > SPLINTER_LIFETIME_TICKS) {
                piece.cube.remove();
                piece.removed = true;
                return;
            }
            int fadeFrom = SPLINTER_LIFETIME_TICKS - 30;
            if (piece.splinterAge > fadeFrom) {
                float alpha = Math.max(0.0F, 1.0F - (piece.splinterAge - fadeFrom) / 30.0F);
                piece.cube.setAlpha(alpha);
                piece.cube.setFullAlphaTarget(alpha);
            }
            keepAlive(piece.cube);
        }

        private Vec3 windAt(ClientLevel level, StormObject storm, ParticleCube cube) {
            if (storm == null || cube == null) {
                return Vec3.ZERO;
            }
            Vec3 position = new Vec3(cube.getPosX(), cube.getPosY(), cube.getPosZ());
            TornadoWindField.WindSample sample = TornadoWindField.sample(storm, level, position);
            if (sample.speedMph() <= 0.0D) {
                return Vec3.ZERO;
            }
            return TornadoWindField.mphToEntityDelta(sample.mphVector());
        }

        private StormObject findStorm(WeatherManagerClient manager) {
            if (manager == null) {
                return null;
            }
            for (WeatherObject object : manager.getStormObjects()) {
                if (object instanceof StormObject storm && storm.ID == stormId && !storm.isDead) {
                    return storm;
                }
            }
            return null;
        }

        void removeAll() {
            for (Piece piece : pieces) {
                if (piece.cube != null && !piece.removed) {
                    piece.cube.remove();
                    piece.removed = true;
                }
            }
        }

        /** Deterministic per-block disorder, from its own offset - the same on every client. */
        private static double hash(StructureFlightPayload.Block block) {
            long value = block.dx() * 73856093L ^ block.dy() * 19349663L ^ block.dz() * 83492791L ^ block.stateId();
            value ^= value >>> 33;
            value *= 0xff51afd7ed558ccdL;
            value ^= value >>> 33;
            return ((value >>> 11) & 0xFFFFL) / 65535.0D;
        }
    }

    private static void moveCube(Piece piece, double x, double y, double z) {
        piece.cube.setPrevPosX(piece.lastX);
        piece.cube.setPrevPosY(piece.lastY);
        piece.cube.setPrevPosZ(piece.lastZ);
        piece.cube.setPosition(x, y, z);
        piece.lastX = x;
        piece.lastY = y;
        piece.lastZ = z;
        keepAlive(piece.cube);
    }

    /**
     * Keeps a cube from ageing out from under us. These live for the length of a flight, not the
     * length of a particle, so the age is pinned just past the fade-in and the alpha is set directly.
     */
    private static void keepAlive(ParticleCube cube) {
        cube.setMotionX(0.0D);
        cube.setMotionY(0.0D);
        cube.setMotionZ(0.0D);
        cube.setGravity(0.0F);
        if (cube.getAge() > cube.getTicksFadeInMax() + 1.0F) {
            cube.setAge((int) cube.getTicksFadeInMax() + 1);
        }
    }

    private static ParticleCube spawnCube(ClientLevel level, double x, double y, double z, BlockState state, float renderCull) {
        ParticleCube cube = new ParticleCube(level, x, y, z, 0.0D, 0.0D, 0.0D, state);
        cube.setMaxAge(20000);
        cube.setTicksFadeInMax(1.0F);
        cube.setParticleSpeed(0.0D, 0.0D, 0.0D);
        cube.setGravity(0.0F);
        cube.setCanCollide(false);
        cube.setKillOnCollide(false);
        cube.setDontRenderUnderTopmostBlock(false);
        cube.setFacePlayer(false);
        cube.spinFast = false;
        cube.spinFastRate = 0.0F;
        cube.isTransparent = false;
        // Weather2's own flying blocks use 0.45, and a ParticleCube's half-extent is its scale, so
        // 0.5 is a cube that exactly fills the one-block cell it came from - any larger and the house
        // arrives in the air already twice its size, blocks overlapping their neighbours.
        cube.setScale(0.5F);
        cube.setAlpha(1.0F);
        cube.setFullAlphaTarget(1.0F);
        cube.setRenderDistanceCull(renderCull);
        cube.spawnAsWeatherEffect();
        return cube;
    }

    /** One block of a flying building, and its own little share of the flight's disorder. */
    private static final class Piece {
        final double ox;
        final double oy;
        final double oz;
        final BlockState state;
        final double jitter;
        ParticleCube cube;
        double shedProgress = 1.0D;
        double lastX;
        double lastY;
        double lastZ;
        double velX;
        double velY;
        double velZ;
        boolean shed;
        boolean removed;
        int splinterAge;

        Piece(double ox, double oy, double oz, BlockState state, double jitter) {
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.state = state;
            this.jitter = jitter;
        }
    }
}
