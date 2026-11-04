package com.imbet.client;

import com.imbet.Config;
import com.imbet.ExpandedWeather2Dynamics;
import com.imbet.weather.DynamicStormController;
import com.imbet.weather.DynamicStormState;
import com.imbet.weather.PrecipitationField;
import com.imbet.weather.RainVolumeField;
import com.imbet.weather.SupercellStructureService;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import weather2.ClientTickHandler;
import weather2.weathersystem.WeatherManagerClient;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Rain as a volume, drawn by a shader rather than suggested by billboards.
 *
 * <p>The particle sheets this mod draws elsewhere are a stack of flat quads, and the honest limit of
 * that approach is what it can never do: a sheet is a picture of rain, so flying through it gives you no
 * parallax, walking round a storm gives you the same picture from every side, and a curtain can only
 * stand between you and the funnel if a quad happens to be drawn there. A tornado hidden behind rain -
 * which is most of them - depends on all three of those.
 *
 * <p>So the storm's precipitation is instead drawn as what it is: a volume with the storm's own shape.
 * Camera-facing slices are cut through it along the view direction and drawn back to front, and each
 * fragment asks the field how much rain is at its own world position. The density function in the
 * shader is a port of {@link PrecipitationField}, so the volume cannot disagree with the particles, the
 * sky shading or the flooding about where a storm's rain is: the forward-flank core is thick here, the
 * hook wraps the mesocyclone, the notch is genuinely clear, and the stratiform tail trails off downshear.
 *
 * <p>Slices are depth tested and never write depth, so terrain, structures and the tornado's own funnel
 * occlude the rain the way they occlude anything else. Blending them in order accumulates the optical
 * depth the shader computes per slab, which is why the result does not change when the slice count does.
 *
 * <p>Two things it deliberately will not do. It stands down entirely when an Iris shaderpack is active,
 * because a pack replaces core programs and its own weather is better than ours could be - the particle
 * sheets take over, which is the same fallback the tornado's own shader makes. And it is only drawn for
 * storms near enough to matter, since a volume is fill-bound rather than geometry-bound.
 */
public final class RainVolumeShader {
    private static ShaderInstance shader;
    private static final RenderStateShard.ShaderStateShard SHADER_STATE =
            new RenderStateShard.ShaderStateShard(() -> shader);

    /**
     * Translucent, depth tested, and pointedly not writing depth: the volume has to be occluded by the
     * world, but a stack of slices that wrote depth would have each slice cut away by the one behind it
     * and the rain would end up as whatever slice happened to draw first.
     */
    private static final RenderType RAIN_VOLUME = RenderType.create(
            ExpandedWeather2Dynamics.MODID + "_rain_volume",
            DefaultVertexFormat.POSITION_TEX_COLOR,
            VertexFormat.Mode.QUADS,
            1024,
            false,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(SHADER_STATE)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                    .setOverlayState(RenderStateShard.NO_OVERLAY)
                    .createCompositeState(false)
    );

    /**
     * Optical depth per block at full density, before the user's opacity.
     *
     * <p>Chosen from what it does rather than from how it feels, because it is the one number that decides
     * all three of the things this has to do at once: from 150 blocks away the wrapped side of the hook
     * leaves a funnel 74% veiled and the clear slot leaves it 6%, and a look through 600 blocks of
     * torrential core comes out 83% blind, while a light shower over 256 blocks stays at 10%. Raising it
     * hides the funnel from more angles and eventually makes every storm a fog bank; lowering it turns rain
     * wrapping back into a decoration.
     *
     * <p>It is high for a per-block coefficient because it has to be: a Minecraft supercell is around a
     * hundredth the size of the real one it is standing in for, so rain matched to real-world visibility in
     * blocks would never hide anything.
     *
     * <p>Package-private because the pack-safe mesh path shades the same volume on the CPU and has to use the
     * same coefficient: two extinction values would make the rain one thickness with a shader pack on and
     * another with it off.
     */
    static final double EXTINCTION_PER_BLOCK = 0.0320D;

    /** The box is grown past the field's own reach so the stratiform tail downshear is inside it. */
    private static final double BOX_RADIUS_SCALE = 2.1D;

    /**
     * How fine the falling structure is, in world units: this is one lane of heavier rain every twenty-odd
     * blocks. Coarse on purpose, and measured rather than guessed - the volume is sampled once per slice,
     * so a pattern closer to the slice spacing than it is to the storm gets randomised by that sampling
     * and reads as grain instead of as rain. A finer pattern costs nothing to look at and everything to
     * look through.
     *
     * <p>Not a constant of its own but {@link RainVolumeField#LANE_SCALE}, which is what the CPU field sample
     * uses, so the program and the mesh cannot grow different rain.
     */
    static final double THREAD_SCALE = RainVolumeField.LANE_SCALE;

    private RainVolumeShader() {
    }

    public static void register(RegisterShadersEvent event) throws IOException {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "rain_volume");
        event.registerShader(
                new ShaderInstance(event.getResourceProvider(), id, DefaultVertexFormat.POSITION_TEX_COLOR),
                loaded -> shader = loaded
        );
    }

    /**
     * True while a storm's rain should be drawn as a volume at all, by whichever of the two paths can.
     *
     * <p>This is what the particle sheets stand down for, so it has to be true whenever a volume is going
     * to be drawn - which is the union of the two paths below rather than either of them.
     */
    public static boolean active() {
        return programActive() || RainVolumeMesh.active();
    }

    /**
     * The program path: slices shaded per fragment, with the falling threads sampled at their true scale.
     *
     * <p>Vanilla-rain mode belongs in this list and not beside it: it is the user saying vanilla's own
     * rain <em>is</em> the rain, so drawing a second rain volume over it would be two rains at once. The
     * two fallbacks at the end are the same bargain the tornado's shader makes - a pack replaces core
     * programs, and a program that has not loaded yet has no uniforms to set.
     */
    private static boolean programActive() {
        return Config.RAIN_VOLUME_ENABLED.get()
                && !Config.classicLook()
                && !VanillaRainMode.active()
                && shader != null
                && !RenderingCompat.isShaderPackActive();
    }

    /** True when the pack-safe mesh is what draws the rain, rather than the sheets. */
    private static boolean meshActive() {
        return RainVolumeMesh.active() && !programActive();
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER || !active()) {
            return;
        }
        // Iris runs every level stage again for its shadow map; this belongs to the visible frame only.
        if (RenderingCompat.isRenderingShadowPass()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        WeatherManagerClient manager = ClientTickHandler.weatherManager;
        if (minecraft.level == null || player == null || manager == null) {
            return;
        }
        int slices = Mth.clamp(Config.RAIN_VOLUME_SLICES.get(), 2, 48);
        double range = Config.RAIN_VOLUME_RANGE_BLOCKS.get();
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.getPosition();

        List<StormObject> storms = nearbyStorms(manager, cameraPos, range);
        if (storms.isEmpty()) {
            return;
        }

        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        double time = (minecraft.level.getGameTime() + partial) / 20.0D;
        Vec3 view = Vec3.directionFromRotation(camera.getXRot(), camera.getYRot());
        Vec3 reference = Math.abs(view.y) > 0.999D ? new Vec3(0.0D, 0.0D, 1.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 right = view.cross(reference).normalize();
        Vec3 up = right.cross(view).normalize();
        PoseStack.Pose pose = event.getPoseStack().last();
        boolean mesh = meshActive();

        if (!mesh) {
            setUniform("CameraPos", (float) cameraPos.x, (float) cameraPos.y, (float) cameraPos.z);
            setUniform("GameTime", (float) time);
            setUniform("StreakScale", (float) THREAD_SCALE);
            setUniform("FallSpeed", Config.RAIN_VOLUME_FALL_SPEED.get().floatValue());
        }

        // Farthest first, so a nearer storm's rain blends over a farther one's rather than the other way
        // round. The order matters here for the same reason it does for the slices themselves.
        for (StormObject storm : storms) {
            VolumeInputs inputs = inputs(minecraft.level, storm, cameraPos, view, right, up, time);
            if (inputs == null) {
                continue;
            }
            // What this storm's rain is fogged by and lit by, read once for whichever of the two paths
            // draws it. Both take the same reading deliberately: the ring of rain the sun lights and the
            // storm's own mist are properties of the storm, not of a rendering technique.
            RainVolumeLight light = RainVolumeLight.read(minecraft.level, inputs.skyProbe(), inputs.wetness(), partial);
            if (mesh) {
                RainVolumeMesh.draw(pose, inputs, light, partial);
            } else {
                drawStorm(pose, inputs, light, slices);
            }
        }
    }

    /**
     * Everything one storm's rain is drawn from, worked out once and then drawn by whichever path can.
     *
     * <p>Shared deliberately: the two paths are two drawings of one storm, so the box, the ground it
     * stands on, the field and the slice distances through it have to be the same numbers for both, and
     * the way to guarantee that is for there to be one of them.
     */
    record VolumeInputs(
            PrecipitationField.Geometry field,
            double stormX, double stormZ,
            double forwardX, double forwardZ,
            double rightX, double rightZ,
            double groundY, double topY,
            double radius,
            double virga,
            Vec3 camera, Vec3 view, Vec3 right, Vec3 up,
            double spanStart, double spanEnd,
            double boundingRadius,
            Vec3 boxCentre, Vec3 boxHalf,
            float weight,
            double timeSeconds,
            /** How much of the storm's moisture reaches the ground: what its own mist is scaled by. */
            float wetness,
            /** Where the sky over this storm is sampled for its light: at the storm, at the eye's height. */
            Vec3 skyProbe
    ) {
    }

    /** Works out the volume for one storm, or null when the view does not cross it at all. */
    private static VolumeInputs inputs(ClientLevel level, StormObject storm, Vec3 cameraPos, Vec3 view,
                                       Vec3 right, Vec3 up, double time) {
        DynamicStormState state = DynamicStormController.stateFor(storm);
        PrecipitationField.Geometry field = SupercellStructureService.precipitationGeometry(storm, state);
        SupercellStructureService.StructureSample sample = SupercellStructureService.sample(storm, state);
        double radius = Math.max(48.0D, field.radius());

        // The storm's own axes, flattened and made unit length. They are what the field's own coordinates are
        // projections onto, so a vector that is longer than one, or leaning, would rescale this storm's rain
        // against the same storm's sheets - the two would agree in shape and disagree in size.
        Vec3 forward = horizontalUnit(sample.forward(), new Vec3(1.0D, 0.0D, 0.0D));
        Vec3 stormRight = horizontalUnit(sample.right(), new Vec3(-forward.z, 0.0D, forward.x));

        // The volume's box, in the world. Flat and generous: the field decides the shape inside it, and
        // the fragment shader throws away everything outside, so a box slightly too large costs a few
        // discarded fragments rather than any wrong rain.
        Vec3 centre = storm.pos;
        double halfHorizontal = radius * BOX_RADIUS_SCALE;
        // The ground under the storm, from the heightmap, exactly as the sheets find theirs. A storm's own
        // position is its cloud layer - Weather2 sets it to the layer height - so using it as the ground
        // would hang the rain two hundred blocks up in the sky and leave nothing at eye level, which is the
        // only height anyone ever looks at a tornado from. Over unloaded ground, which is a storm at the
        // edge of the view, the viewer's own ground stands in: they are the reason it is being drawn.
        double worldFloor = level.getMinBuildHeight() + 2.0D;
        double groundY = VolumetricPrecipitationRenderer.floorY(level, centre.x, centre.z);
        if (groundY <= worldFloor) {
            groundY = Math.max(worldFloor, VolumetricPrecipitationRenderer.floorY(level, cameraPos.x, cameraPos.z));
        }
        // The same column the sheets fall from, so rain starts and stops at the same height whichever
        // of the two is drawing it.
        double topY = Math.min(storm.manager == null || storm.manager.getWorld() == null
                ? 256.0D
                : storm.manager.getWorld().getMaxBuildHeight() - 4.0D,
                VolumetricPrecipitationRenderer.topY(storm));
        topY = Math.max(topY, groundY + 32.0D);
        Vec3 boxCentre = new Vec3(centre.x, (groundY + topY) * 0.5D, centre.z);
        Vec3 boxHalf = new Vec3(halfHorizontal, (topY - groundY) * 0.5D, halfHorizontal);
        double boundingRadius = boxHalf.length();

        // How much of the volume the view ray actually crosses, so the slices are spent inside it rather
        // than in the empty shell around it. A span too short to cut into slabs is a storm edge-on to the
        // viewer, which is nothing to draw.
        double[] span = rayBoxSpan(cameraPos, view, boxCentre, boxHalf);
        if (span == null || span[1] - span[0] <= 8.0D) {
            return null;
        }

        float virga = SupercellStructureService.virgaStrength(storm, state);
        float character = SupercellStructureService.precipitationCharacter(storm, state);

        // A storm that is barely raining is barely drawn: the field already thins it, and this keeps a
        // dying cell from costing a full stack of full-screen slices.
        float weight = Mth.clamp(0.35F + character * 0.65F, 0.0F, 1.0F);
        if (weight <= 0.02F) {
            return null;
        }

        return new VolumeInputs(field, centre.x, centre.z,
                forward.x, forward.z, stormRight.x, stormRight.z,
                groundY, topY, radius, virga,
                cameraPos, view, right, up,
                span[0], span[1], boundingRadius,
                boxCentre, boxHalf, weight, time,
                character, new Vec3(centre.x, cameraPos.y, centre.z));
    }

    /**
     * The program path's drawing: the uniforms the fragment program reads, then the slices, farthest first.
     *
     * <p>Everything it needs was worked out once in {@link #inputs}, which is the point of the split: the two
     * drawings of one storm have to agree about its box, its ground, its field and how much of it the view
     * ray crosses, and the way to guarantee that is for there to be one of each.
     */
    private static void drawStorm(PoseStack.Pose pose, VolumeInputs inputs, RainVolumeLight light, int slices) {
        double slab = (inputs.spanEnd() - inputs.spanStart()) / slices;
        if (slab <= 0.01D) {
            return;
        }
        double extinction = EXTINCTION_PER_BLOCK
                * Config.rainOpacity(Math.max(0.05D, Config.RAIN_VOLUME_OPACITY.get())) * slab;
        PrecipitationField.Geometry field = inputs.field();

        // The y of StormCentre is the box's middle and not the storm's own position, which is its cloud
        // layer: the fragment program only reads its xz, where the field is measured from.
        setUniform("StormCentre", (float) inputs.stormX(), (float) inputs.boxCentre().y, (float) inputs.stormZ());
        setUniform("StormForward", (float) inputs.forwardX(), (float) inputs.forwardZ());
        setUniform("StormRight", (float) inputs.rightX(), (float) inputs.rightZ());
        setUniform("StormRadius", (float) inputs.radius());
        setUniform("Anchor0", (float) field.mesoForward(), (float) field.mesoRight(),
                (float) field.coreForward(), (float) field.coreRight());
        setUniform("Anchor1", (float) field.notchForward(), (float) field.notchRight(),
                (float) field.wrapForward(), (float) field.wrapRight());
        setUniform("Anchor2", (float) field.rearFlankForward(), (float) field.rearFlankRight(), 0.0F, 0.0F);
        setUniform("Strengths", (float) field.coreStrength(), (float) field.hookStrength(),
                (float) field.notchStrength(), (float) field.rearStrength());
        setUniform("Stratiform", (float) field.stratiformStrength());
        setUniform("Vertical", (float) inputs.groundY(), (float) inputs.topY());
        setUniform("BoxCentre", (float) inputs.boxCentre().x, (float) inputs.boxCentre().y, (float) inputs.boxCentre().z);
        setUniform("BoxHalf", (float) inputs.boxHalf().x, (float) inputs.boxHalf().y, (float) inputs.boxHalf().z);
        setUniform("SliceExtinction", (float) extinction);
        setUniform("Virga", (float) inputs.virga());
        setUniform("ColorModulator", 1.0F, 1.0F, 1.0F, 1.0F);

        // The fog and the light, from the frame's own reading. The fog's colour, start, end and shape are
        // not set here at all: the shader loader writes them from the world's fog on every apply, which is
        // the point - this draws in the fog that is actually there, including a tornado's whiteout and a
        // pack's own fog. What is ours is how much of it to apply, how misty the storm's own air is, and
        // what is lighting the rain.
        setUniform("FogAmount", light.fogEnabled() ? 1.0F : 0.0F);
        setUniform("StormMist", (float) light.mistPerBlock());
        setUniform("MistDensity", (float) light.mistDensity());
        setUniform("RainLight", (float) light.skyRed(), (float) light.skyGreen(), (float) light.skyBlue());
        setUniform("FlashGain", light.lightEnabled()
                ? Config.RAIN_VOLUME_FLASH_LIGHT.get().floatValue()
                : 0.0F);
        Vec3 strike = light.flashCentre();
        setUniform("FlashCentre", strike == null ? 0.0F : (float) strike.x,
                strike == null ? 0.0F : (float) strike.y,
                strike == null ? 0.0F : (float) strike.z);
        setUniform("FlashStrength", strike == null ? 0.0F : (float) light.flashLevel());

        // Farthest slice first. The vertices are camera-relative because that is what the level-stage
        // pose contains at this point - the camera's rotation and nothing else - so a slice's centre is
        // simply its own distance along the view ray. The world position the shader needs is recovered
        // in the vertex program by adding CameraPos back, which keeps the field, the box and the
        // vertical profile in world coordinates where they belong.
        float weight = inputs.weight();
        double half = inputs.boundingRadius();
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (int index = slices - 1; index >= 0; index--) {
            double t = inputs.spanStart() + slab * (index + 0.5D);
            Vec3 centreAtSlice = inputs.view().scale(t);
            vertex(builder, pose, centreAtSlice.add(inputs.right().scale(-half)).add(inputs.up().scale(-half)), 0.0F, 1.0F, weight);
            vertex(builder, pose, centreAtSlice.add(inputs.right().scale(half)).add(inputs.up().scale(-half)), 1.0F, 1.0F, weight);
            vertex(builder, pose, centreAtSlice.add(inputs.right().scale(half)).add(inputs.up().scale(half)), 1.0F, 0.0F, weight);
            vertex(builder, pose, centreAtSlice.add(inputs.right().scale(-half)).add(inputs.up().scale(half)), 0.0F, 0.0F, weight);
        }
        RAIN_VOLUME.draw(builder.buildOrThrow());
    }

    /** One corner of a slice, in camera-relative coordinates as every renderer here writes them. */
    private static void vertex(BufferBuilder builder, PoseStack.Pose pose, Vec3 cameraRelative, float u, float v, float alpha) {
        builder.addVertex(pose, (float) cameraRelative.x, (float) cameraRelative.y, (float) cameraRelative.z)
                .setUv(u, v)
                .setColor(1.0F, 1.0F, 1.0F, alpha);
    }

    /**
     * A horizontal unit vector, or the fallback if the candidate has no horizontal length at all.
     *
     * <p>The structure service is the authority on which way a storm points; flattening what it says is all
     * this does, so the field's forward and right mean the same thing here as they do everywhere else.
     */
    private static Vec3 horizontalUnit(Vec3 candidate, Vec3 fallback) {
        Vec3 flat = new Vec3(candidate.x, 0.0D, candidate.z);
        if (flat.lengthSqr() < 1.0E-6D) {
            return fallback;
        }
        return flat.normalize();
    }

    /**
     * Where a ray enters and leaves a box, or null if it misses: the ordinary slab test, which is what
     * keeps the slices inside the volume instead of across the whole view distance.
     */
    private static double[] rayBoxSpan(Vec3 origin, Vec3 direction, Vec3 centre, Vec3 half) {
        double tMin = 0.0D;
        double tMax = 8192.0D;
        double[] o = {origin.x, origin.y, origin.z};
        double[] d = {direction.x, direction.y, direction.z};
        double[] c = {centre.x, centre.y, centre.z};
        double[] h = {half.x, half.y, half.z};
        for (int axis = 0; axis < 3; axis++) {
            double directionComponent = d[axis];
            double distance = c[axis] - o[axis];
            if (Math.abs(directionComponent) < 1.0E-6D) {
                if (distance > h[axis] || distance < -h[axis]) {
                    return null;
                }
                continue;
            }
            double first = (distance + h[axis]) / directionComponent;
            double second = (distance - h[axis]) / directionComponent;
            double near = Math.min(first, second);
            double far = Math.max(first, second);
            tMin = Math.max(tMin, near);
            tMax = Math.min(tMax, far);
            if (tMin > tMax) {
                return null;
            }
        }
        return new double[] {tMin, tMax};
    }

    private static List<StormObject> nearbyStorms(WeatherManagerClient manager, Vec3 cameraPos, double range) {
        List<StormObject> storms = new ArrayList<>();
        double rangeSqr = range * range;
        for (WeatherObject object : new ArrayList<>(manager.getStormObjects())) {
            if (!(object instanceof StormObject storm) || storm.pos == null || storm.isDead
                    || DynamicStormController.isAbsorbedOrDecaying(storm) || storm.isCloudlessStorm()) {
                continue;
            }
            double reach = range + DynamicStormController.stormPrecipRadius(storm);
            if (storm.pos.distanceToSqr(cameraPos) > reach * reach) {
                continue;
            }
            // The same test the sheets use, deliberately: a dry LP supercell that is left almost
            // rainless when the rain is drawn as sheets has to be left rainless here too, or one storm
            // is dry from one renderer and wet from the other.
            if (!VolumetricPrecipitationRenderer.shouldRenderDownfall(storm)) {
                continue;
            }
            // A storm whose field is dry all over would be drawn as nothing, so it is skipped rather
            // than paid for.
            DynamicStormState state = DynamicStormController.stateFor(storm);
            PrecipitationField.Geometry field = SupercellStructureService.precipitationGeometry(storm, state);
            if (field.coreStrength() <= 0.02D && field.hookStrength() <= 0.02D) {
                continue;
            }
            storms.add(storm);
        }
        storms.sort(Comparator.comparingDouble(storm -> -storm.pos.distanceToSqr(cameraPos)));
        return storms;
    }



    private static void setUniform(String name, float value) {
        Uniform uniform = shader == null ? null : shader.getUniform(name);
        if (uniform != null) {
            uniform.set(value);
        }
    }

    private static void setUniform(String name, float first, float second) {
        Uniform uniform = shader == null ? null : shader.getUniform(name);
        if (uniform != null) {
            uniform.set(first, second);
        }
    }

    private static void setUniform(String name, float first, float second, float third) {
        Uniform uniform = shader == null ? null : shader.getUniform(name);
        if (uniform != null) {
            uniform.set(first, second, third);
        }
    }

    private static void setUniform(String name, float first, float second, float third, float fourth) {
        Uniform uniform = shader == null ? null : shader.getUniform(name);
        if (uniform != null) {
            uniform.set(first, second, third, fourth);
        }
    }
}
