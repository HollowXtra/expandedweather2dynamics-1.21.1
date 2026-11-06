package com.imbet.client;

import com.imbet.Config;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import weather2.weathersystem.storm.LightningBoltWeatherNew;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Draws every lightning strike in the level with the mod's own channel geometry and its own core
 * shader, at the stage the rest of the mod's weather-space drawing uses.
 *
 * <p>Both the vanilla bolt and Weather2's own are described by three things - where the strike was,
 * the seed it was generated from, and how long ago it fired - and nothing else is needed, because
 * neither class keeps its channel as geometry: both generate it in their renderer from the seed.
 * That is what makes a replacement this cheap, and it is also why the shape has to come from the
 * seed rather than from the entity: two clients, or two frames, must agree on it. Neither class
 * exposes its age either - both keep their life count private - so the age is the mod's own, taken
 * from the first frame the strike was seen.
 *
 * <p>A strike is not driven by its entity for its whole life, and this is the part that decides
 * whether the program's flicker is ever seen at all. Both bolt classes are discarded two to four
 * ticks after they spawn: a tenth of a second, against a flash envelope whose later strokes are
 * forty-five, a hundred and five and a hundred and ninety milliseconds in, and whose afterglow runs
 * for hundreds more. Drawn only while the entity lives, the strike would be cut off at its first
 * stroke and every part of the program that makes a bolt flicker instead of glow would happen off
 * screen. So each strike is held here as its own record - where, from which seed, born when - and
 * drawn for {@link #FLASH_LIFETIME_TICKS} from that record, whether or not the entity is still
 * there. That is faithful rather than generous: the entity is the game's own bookkeeping for damage
 * and sound, and a real strike really is visible longer than the bookkeeping lasts.
 *
 * <p>The draw happens after the weather stage so the channel sits over the rain, the cloud deck and
 * the debris it lights up, which is the one ordering an additive light source is allowed to have.
 */
public final class LightningRevamp {
    /**
     * How long a strike is drawn, in ticks: the program's four strokes and its afterglow, in full.
     *
     * <p>Measured against the fragment program's own envelope, not guessed: the last stroke starts at
     * 0.19s and the afterglow term decays with a time constant of a third of a second, so at 1.1s the
     * channel is under one two-hundredth of its peak brightness and the remaining term is gone.
     */
    private static final long FLASH_LIFETIME_TICKS = 22L;
    /** Two strikes this far apart are different strikes, whatever their entity id says. */
    private static final double SAME_STRIKE_RADIUS = 4.0D;

    /**
     * The strikes being drawn, keyed by entity id.
     *
     * <p>Entity ids are recycled, so an id alone does not identify a strike: a record is only reused
     * when the entity that turns up carrying that id agrees with it about the seed and the place. A
     * bolt is spawned standing still, so a hit somewhere else is a new strike and gets a new clock
     * rather than inheriting the tail of the last one's flash.
     */
    private static final Map<Integer, Strike> STRIKES = new HashMap<>();

    private LightningRevamp() {
    }

    /** One strike, remembered independently of the entity that announced it. */
    private record Strike(double x, double y, double z, long seed, long birthTick) {
        Strike movedTo(double newX, double newY, double newZ) {
            return new Strike(newX, newY, newZ, seed, birthTick);
        }

        boolean sameAs(double newX, double newY, double newZ, long newSeed) {
            if (seed != newSeed) {
                return false;
            }
            double dx = x - newX;
            double dy = y - newY;
            double dz = z - newZ;
            return dx * dx + dy * dy + dz * dz <= SAME_STRIKE_RADIUS * SAME_STRIKE_RADIUS;
        }
    }

    /**
     * Whether the mod's own bolt is the one that will be drawn this frame.
     *
     * <p>The same test gates the two entity renderers being suppressed, so a bolt can never be drawn
     * twice or by neither. The mod's program has to have loaded - a strike left undrawn is worse than a
     * vanilla one - unless the bolt is going through the game's own lightning program, which is always
     * there, and is what a shader pack shades.
     */
    public static boolean replaces() {
        return Config.LIGHTNING_BOLT_REVAMP_ENABLED.get()
                && (throughGameProgram() || LightningBoltShader.ready());
    }

    /**
     * Whether the channel is drawn through the game's own lightning program rather than the mod's. A shader pack replaces
     * the core programs, so the mod's own would come out as nothing under one; the game's lightning program is one every
     * pack provides its own version of. The channel's glow and flicker are worked out per vertex for it instead.
     */
    private static boolean throughGameProgram() {
        return RenderingCompat.isShaderPackActive() || Config.LIGHTNING_BOLT_PACK_PATH.get();
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER || !replaces()) {
            return;
        }
        // Iris runs every level stage again for its shadow map; this belongs to the visible frame only.
        if (RenderingCompat.isRenderingShadowPass()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }

        Vec3 camera = event.getCamera().getPosition();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        long gameTime = level.getGameTime();
        float height = Config.LIGHTNING_BOLT_HEIGHT_BLOCKS.get().floatValue();
        float coreWidth = Config.LIGHTNING_BOLT_CORE_WIDTH.get().floatValue();
        float haloWidth = Config.LIGHTNING_BOLT_HALO_WIDTH.get().floatValue();
        int branches = Config.LIGHTNING_BOLT_BRANCHES.get();
        float glowRadius = Config.LIGHTNING_BOLT_GROUND_GLOW_ENABLED.get()
                ? Config.LIGHTNING_BOLT_GROUND_GLOW_RADIUS.get().floatValue()
                : 0.0F;
        float brightness = Config.LIGHTNING_BOLT_BRIGHTNESS.get().floatValue();
        float coreHeat = Config.LIGHTNING_BOLT_CORE_HEAT.get().floatValue();
        float filaments = Config.LIGHTNING_BOLT_FILAMENTS.get().floatValue();
        float strobes = Config.LIGHTNING_BOLT_STROBES.get() ? 1.0F : 0.0F;
        double range = Config.LIGHTNING_BOLT_RANGE_BLOCKS.get();
        boolean dischargeTypes = Config.LIGHTNING_DISCHARGE_TYPES_ENABLED.get();
        double crawlerChance = Config.LIGHTNING_ANVIL_CRAWLER_CHANCE.get();
        double cloudToCloudChance = Config.LIGHTNING_CLOUD_TO_CLOUD_CHANCE.get();
        float crawlerLength = Config.LIGHTNING_CRAWLER_LENGTH_BLOCKS.get().floatValue();
        float cloudBaseOffset = Config.LIGHTNING_CLOUD_BASE_OFFSET_BLOCKS.get().floatValue();

        Set<Integer> live = observe(level, gameTime);
        boolean gameProgram = throughGameProgram();

        for (Map.Entry<Integer, Strike> entry : STRIKES.entrySet()) {
            Strike strike = entry.getValue();
            float ageTicks = gameTime - strike.birthTick() + partial;
            // A strike still in the level is left to the program's own envelope, so a bolt from
            // another mod that outlives this window is not cut off mid-life by the mod's bookkeeping.
            if (ageTicks < 0.0F || (ageTicks > FLASH_LIFETIME_TICKS && !live.contains(entry.getKey()))) {
                continue;
            }
            float age = ageTicks / 20.0F;

            // The kind of discharge is read off the strike's own seed, so it needs nothing synced and
            // every client draws the same strike the same way. A cloud-to-cloud flash is lifted into
            // the cloud, because the entity it came from is standing on the ground and an intracloud
            // channel has no ground point to be built from; a crawler keeps its foot on the ground and
            // throws its tendrils at the anvil; the ground glow belongs only to a strike that reached it.
            LightningDischarge discharge = dischargeTypes
                    ? LightningDischarge.choose(strike.seed(), crawlerChance, cloudToCloudChance)
                    : LightningDischarge.GROUND;
            float originY = discharge.hangsInCloud() ? cloudBaseOffset : 0.0F;
            float strikeGlow = discharge.touchesGround() ? glowRadius : 0.0F;
            double channelY = strike.y() + originY + (discharge.hangsInCloud() ? 0.0D : height * 0.5D);

            double dx = camera.x - strike.x();
            double dy = camera.y - channelY;
            double dz = camera.z - strike.z();
            // Distance is measured to the middle of the channel, not to the point it struck: the
            // extinction in the program is about how much air lies between the eye and the light. The
            // cutoff at the configured range is safe to make here because the program has already
            // faded the channel to nothing by then.
            float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance > range) {
                continue;
            }

            LightningBoltShape.Mesh mesh = LightningBoltShape.build(
                    discharge,
                    strike.seed(),
                    height,
                    coreWidth,
                    haloWidth,
                    branches,
                    strikeGlow,
                    crawlerLength,
                    camera.x - strike.x(),
                    camera.y - (strike.y() + originY),
                    camera.z - strike.z()
            );
            PoseStack stack = event.getPoseStack();
            stack.pushPose();
            stack.translate(strike.x() - camera.x, strike.y() - camera.y + originY, strike.z() - camera.z);
            if (gameProgram) {
                LightningBoltShader.drawThroughGameProgram(stack, mesh, age, brightness, distance, coreHeat, strobes, (float) range);
            } else {
                LightningBoltShader.draw(stack, mesh, age, seedFraction(strike.seed()), brightness, distance,
                        coreHeat, filaments, strobes, height, (float) range);
            }
            stack.popPose();
        }
        STRIKES.entrySet().removeIf(entry -> gameTime - entry.getValue().birthTick() > FLASH_LIFETIME_TICKS
                && !live.contains(entry.getKey()));
    }

    /**
     * Brings the records up to date with the bolts in the level, and answers which of them are still
     * there. A strike is recorded into {@link LightningFlash} here exactly once - on the frame it is
     * first seen, or the frame a recycled id turns up somewhere else with a new seed - and that is also
     * the moment its flash begins, so the light and the channel always start together.
     *
     * <p>A strike seen for the first time is dated by how long its entity has been ticking, so a
     * player who arrives at a strike already in progress sees it where it actually is in its flash
     * rather than being shown the whole thing again from the start.
     */
    private static Set<Integer> observe(ClientLevel level, long gameTime) {
        Set<Integer> live = new HashSet<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof LightningBolt) && !(entity instanceof LightningBoltWeatherNew)) {
                continue;
            }
            int id = entity.getId();
            live.add(id);
            long seed = boltSeed(entity);
            double x = entity.getX();
            double y = entity.getY();
            double z = entity.getZ();
            Strike known = STRIKES.get(id);
            if (known == null) {
                STRIKES.put(id, new Strike(x, y, z, seed, gameTime - seenTicks(entity)));
                // The same event that decides a channel is drawn decides the strike lights the world:
                // one strike, one flash, wherever it came from.
                LightningFlash.recordStrike(x, y, z);
            } else if (!known.sameAs(x, y, z, seed)) {
                STRIKES.put(id, new Strike(x, y, z, seed, gameTime));
                LightningFlash.recordStrike(x, y, z);
            } else if (known.x() != x || known.y() != y || known.z() != z) {
                STRIKES.put(id, known.movedTo(x, y, z));
            }
        }
        return live;
    }

    /**
     * How long the entity has already existed, in ticks, clamped to the window a strike is drawn for.
     *
     * <p>The clamp is not cosmetic: an entity that has been alive far longer than a flash would
     * otherwise be dated so far in the past that its record is pruned the frame it arrives.
     */
    private static long seenTicks(Entity entity) {
        return Math.max(0L, Math.min(entity.tickCount, FLASH_LIFETIME_TICKS));
    }

    /**
     * The strike's own seed. The vanilla bolt and Weather2's both keep it in a public field, which is
     * the only piece of each class this replacement has to read.
     */
    private static long boltSeed(Entity entity) {
        if (entity instanceof LightningBolt bolt) {
            return bolt.seed;
        }
        return ((LightningBoltWeatherNew) entity).seed;
    }

    /** The seed as a fraction, which is all the noise functions want of it. */
    private static float seedFraction(long seed) {
        return Math.floorMod(seed, 4096L) / 4096.0F;
    }
}
