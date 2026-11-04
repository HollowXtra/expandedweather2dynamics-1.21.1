package com.imbet.client;

import com.imbet.Config;
import com.imbet.ExpandedWeather2Dynamics;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.io.IOException;

/**
 * The dust you are standing in, drawn as a volume around the eye instead of painted on the screen.
 *
 * <p>The veil this replaces was one full-screen quad with the depth test off: a picture of being engulfed.
 * It had no parallax by construction - turning your head carried the picture with it, and the only motion
 * in it was the pattern's own scroll - which is the one thing the eye notices inside a tornado, because
 * airborne grit is a place rather than a filter over the camera. So the same dust is defined as a
 * world-anchored field, cut into camera-facing slices and blended back to front.</p>
 *
 * <p>Three decisions carry it.
 *
 * <p>The box travels with the camera. It is centred on the eye with a radius of
 * {@code whiteoutVolumeRadius} blocks, so the observer is always inside it and there is no direction to
 * look in or height to stand at where the veil is not already in front of you. It also means the ray-box
 * span always starts at the eye, so every slice is in front of the near plane and none has to be sorted
 * away.
 *
 * <p>Each slice contributes its own slab of optical depth, never a flat alpha. That is what separates a
 * volume from N copies of a veil: the fragment stage converts the field's density to {@code 1 - exp(-d)}
 * for one slab, so the stack accumulates, and many thin slabs and a few thick ones come out the same.
 *
 * <p>And a shaderpack does not cost the third dimension. Under a pack our program is replaced and cannot
 * draw, so the same slices are drawn as world geometry with the veil's own sheet on them, taken from a
 * different place on the sheet per slice so the stack is not one image repeated. The pack then shades them
 * the way it shades everything else in front of you, and they keep their depth.
 *
 * <p>What is deliberately not here: any screen-space path. If this class cannot draw at all - the program
 * failed to load and no pack is active - it says so, and the caller falls back to the flat screen veil. The
 * whiteout can be made three-dimensional, but it can never be made to silently disappear.
 */
public final class TornadoWhiteoutShader {
    private static ShaderInstance shader;

    private static final RenderStateShard.ShaderStateShard SHADER_STATE =
            new RenderStateShard.ShaderStateShard(() -> shader);

    /** The streaked sheet the fallback slices are cut from. */
    private static final ResourceLocation VEIL_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            ExpandedWeather2Dynamics.MODID, "textures/environment/tornado_veil.png");

    /**
     * Translucent, depth tested and pointedly not writing depth.
     *
     * <p>The depth test is what lets terrain, structures and the funnel itself occlude the dust: dust
     * behind a wall is behind a wall. Not writing depth is what lets the slices blend into each other
     * instead of each one being cut away by the one behind it, which on a stack this deep would leave the
     * nearest slab as the whole effect.
     */
    private static final RenderType TORNADO_WHITEOUT = RenderType.create(
            ExpandedWeather2Dynamics.MODID + "_tornado_whiteout",
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
     * The pack-safe twin of the above: the same slabs, the same depth semantics, vanilla's own entity
     * program over the veil's sheet, so a shaderpack replaces it properly rather than not at all.
     *
     * <p>The lightmap is off, so a veil of dust is lit by what it is made of rather than by the light under
     * it, and it does not turn black in an unlit hollow or at midnight.
     */
    private static final RenderType PACK_SHEETS = RenderType.create(
            ExpandedWeather2Dynamics.MODID + "_tornado_veil_sheets",
            DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS,
            1024,
            false,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                    .setTextureState(new RenderStateShard.TextureStateShard(VEIL_TEXTURE, false, false))
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                    .setOverlayState(RenderStateShard.NO_OVERLAY)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .createCompositeState(false)
    );

    /**
     * How far past the box each slice quad is drawn.
     *
     * <p>A slice is a flat quad and the volume is a cube, so the quad has to cover the widest the cube's
     * cross-section can be, which is the face diagonal at root two. This is that with slack; past the
     * sliver actually inside, the fragments are discarded unshaded.
     */
    private static final double QUAD_SLACK = 1.8D;

    /**
     * The mean density the fragment stage's field evaluates to, for the path that cannot ask it.
     *
     * <p>The program shades each fragment at its own density; the sheet path has no density to read, so it
     * uses the field's mean over the box. That is the fragment stage's own arithmetic with the averages put
     * in it: a churn averaging a half and a streak term averaging a third against
     * {@code 0.55 + churn * 0.85 + streaks * 0.70}, times the height profile's mean of 0.82. The point of
     * doing the arithmetic rather than picking a number is that the dust must not come out one thickness
     * with a shaderpack and another without, and the only way to know that is to have both sides of it
     * written down.
     */
    private static final double PACK_DENSITY = 0.99D;

    /**
     * The shader's own light-end dust colour, hard-coded in the fragment stage. The storm's tint is sent as
     * a ratio against this through {@code ColorModulator} rather than as a new uniform, so the shipped GLSL
     * - and the tuning deck that runs it byte for byte - does not have to change to gain a tint.
     */
    private static final float BASE_DUST_RED = 0.88F;
    private static final float BASE_DUST_GREEN = 0.87F;
    private static final float BASE_DUST_BLUE = 0.84F;

    private TornadoWhiteoutShader() {
    }

    public static void register(RegisterShadersEvent event) throws IOException {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "tornado_whiteout");
        event.registerShader(
                new ShaderInstance(event.getResourceProvider(), id, DefaultVertexFormat.POSITION_TEX_COLOR),
                loaded -> shader = loaded
        );
    }

    /** Whether the program itself is loaded, which is the path with the per-fragment field. */
    public static boolean programAvailable() {
        return shader != null;
    }

    /**
     * Draws the engulfing dust, and reports whether it drew.
     *
     * <p>False is not a failure: it means neither path could draw, and the caller is expected to fall back
     * to the flat screen veil this class replaced.
     */
    public static boolean draw(RenderLevelStageEvent event, Minecraft minecraft, float whiteout) {
        Player player = minecraft.player;
        if (minecraft.level == null || player == null || whiteout <= 0.01F) {
            return false;
        }
        boolean pack = RenderingCompat.isShaderPackActive();
        if (!pack && shader == null) {
            return false;
        }

        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.getPosition();
        Vec3 view = Vec3.directionFromRotation(camera.getXRot(), camera.getYRot());
        // Straight up or straight down the cross product with the world up collapses, so the reference axis
        // has to move with the view or the slices' basis degenerates.
        Vec3 reference = Math.abs(view.y) > 0.999D ? new Vec3(0.0D, 0.0D, 1.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 right = view.cross(reference).normalize();
        Vec3 up = right.cross(view).normalize();

        // The volume's shape and cost, all user knobs. Radius and slices are the two that matter most:
        // radius decides how far the dust reaches before its own edge would show, slices decide how smooth
        // the stack is at the cost of fill.
        double radius = Config.TORNADO_IMMERSION_WHITEOUT_VOLUME_RADIUS.get();
        int slices = Math.max(1, Config.TORNADO_IMMERSION_WHITEOUT_VOLUME_SLICES.get());
        double extinctionPerBlock = Config.TORNADO_IMMERSION_WHITEOUT_EXTINCTION_PER_BLOCK.get();
        double blocksPerTile = Config.TORNADO_IMMERSION_WHITEOUT_BLOCKS_PER_TILE.get();
        double driftBlocksPerSecond = Config.TORNADO_IMMERSION_WHITEOUT_DRIFT_BLOCKS_PER_SECOND.get();

        // How far along the view the box's own face is. The box is centred on the eye, so this is the nearest
        // face crossing and there is no entry distance to find: the span starts at zero.
        double exit = boxExitDistance(view, radius);
        if (exit <= 0.5D) {
            return false;
        }
        double slab = exit / slices;
        if (slab <= 0.01D) {
            return false;
        }

        double opacity = Mth.clamp(Config.TORNADO_IMMERSION_WHITEOUT_VEIL_OPACITY.get(), 0.0D, 1.0D);
        // Optical depth of a single slab at full density, before the whiteout's own ramp. The program
        // applies the ramp per fragment, because a ramp is what it is: uniform alpha per slab is how the
        // count of slabs and their thickness end up trading off instead of both scaling the darkness.
        double slabDepth = extinctionPerBlock * opacity * slab;

        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        double time = (minecraft.level.getGameTime() + partial) / 20.0D;
        PoseStack.Pose pose = event.getPoseStack().last();
        double half = radius * QUAD_SLACK;

        // The storm's own dust and the ambient light it is standing in. Both paths use them, so the volume
        // cannot be one colour with a shaderpack and another without, and it cannot glow at night.
        TornadoFearClient.DustProfile dust = TornadoFearClient.dust();
        float brightness = Mth.clamp(TornadoFearClient.veilBrightness(), 0.05F, 1.0F);

        Vec3 wind = windVector(player);
        if (pack) {
            float step = (float) (1.0D - Math.exp(-PACK_DENSITY * slabDepth * whiteout));
            drawSheets(pose, cameraPos, wind, view, right, up, half, slab, Mth.clamp(step, 0.0F, 0.99F), time,
                    dust, brightness, blocksPerTile, driftBlocksPerSecond, slices);
        } else {
            setUniform("CameraPos", (float) cameraPos.x, (float) cameraPos.y, (float) cameraPos.z);
            setUniform("BoxCentre", (float) cameraPos.x, (float) cameraPos.y, (float) cameraPos.z);
            setUniform("BoxHalf", (float) radius, (float) radius, (float) radius);
            setUniform("GameTime", (float) time);
            setUniform("WindDirection", (float) wind.x, (float) wind.z);
            setUniform("SliceExtinction", (float) slabDepth);
            setUniform("WhiteoutIntensity", whiteout);
            setUniform("FunnelViolence", TornadoFearClient.funnelViolence());
            // The storm's grit rides on the debris dial the shader already has, and its tint and the ambient
            // light ride on the modulator the shader already multiplies by - so the volume recolours and
            // darkens without the GLSL needing a single new uniform.
            setUniform("DebrisIntensity", Config.TORNADO_IMMERSION_WHITEOUT_DEBRIS.get().floatValue() * dust.grit());
            setUniform("ColorModulator",
                    dust.red() / BASE_DUST_RED * brightness,
                    dust.green() / BASE_DUST_GREEN * brightness,
                    dust.blue() / BASE_DUST_BLUE * brightness,
                    1.0F);
            drawProgram(pose, view, right, up, half, slab, slices);
        }
        return true;
    }

    /**
     * The wind the dust is carried along, in the horizontal plane the field is built on.
     *
     * <p>Taken from the same reading the funnel's own shear uses, so the dust crosses the view the way the
     * air is actually moving rather than the way a screen effect would have chosen. When there is no sample
     * - the reading has not caught up, or the funnel is behind a hill - the vector from the funnel's centre
     * to the player stands in, because that is the direction the flow would carry things past an observer
     * inside it. Both paths want this now: the program is told the direction, and the sheets are carried
     * along it.
     */
    private static Vec3 windVector(Player player) {
        Vec3 wind = TornadoFearClient.activeWindVector();
        double x = wind.x;
        double z = wind.z;
        if (wind.horizontalDistanceSqr() < 1.0E-5D) {
            Vec3 fallback = TornadoFearClient.activeCenter().subtract(player.position());
            x = fallback.x;
            z = fallback.z;
        }
        double length = Math.sqrt(x * x + z * z);
        if (length < 1.0E-5D) {
            return new Vec3(1.0D, 0.0D, 0.0D);
        }
        return new Vec3(x / length, 0.0D, z / length);
    }

    /** Farthest slice first, so a nearer slab of dust blends over a farther one's. */
    private static void drawProgram(PoseStack.Pose pose, Vec3 view, Vec3 right, Vec3 up,
                                    double half, double slab, int slices) {
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,
                DefaultVertexFormat.POSITION_TEX_COLOR);
        for (int index = slices - 1; index >= 0; index--) {
            Vec3 centre = view.scale(slab * (index + 0.5D));
            quad(builder, pose, centre, right, up, half);
        }
        TORNADO_WHITEOUT.draw(builder.buildOrThrow());
    }

    /**
     * The same slabs as world geometry, for the case where a pack owns the program.
     *
     * <p>Every corner's texture coordinate is its own position in the world, so the dust belongs to the
     * place instead of riding the camera. This is the whole difference between a volume and a picture of
     * one: a screen-locked veil does not care where the observer is standing, and walking through it - which
     * is the thing this path exists to do - leaves the frame identical.
     *
     * <p>The progress along the view is in that sum deliberately, and is not a fudge to make the numbers
     * move. The view axis is perpendicular to both of the others, so an anchor built from {@code right} and
     * {@code up} alone would slide when strafing and would not change one pixel when walking straight in.
     * Including it is also what separates the slices: twenty of them a slab apart are twenty cross-sections
     * of one field, so they differ by their own depth rather than by an arbitrary per-slice offset, and the
     * stack gets its structure from the world instead of from an index.
     *
     * <p>The tint and the ambient brightness come in from the storm, and are painted straight onto the
     * vertices because this path has no fragment program of its own to tint with.
     */
    private static void drawSheets(PoseStack.Pose pose, Vec3 camera, Vec3 wind, Vec3 view, Vec3 right, Vec3 up,
                                   double half, double slab, float alpha, double time,
                                   TornadoFearClient.DustProfile dust, float brightness,
                                   double blocksPerTile, double driftBlocksPerSecond, int slices) {
        double tilesPerBlock = 1.0D / Math.max(0.1D, blocksPerTile);
        double drift = time * driftBlocksPerSecond * tilesPerBlock;
        double driftU = wind.dot(right) * drift;
        double driftV = wind.dot(up) * drift;
        float red = dust.red() * brightness;
        float green = dust.green() * brightness;
        float blue = dust.blue() * brightness;
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,
                DefaultVertexFormat.NEW_ENTITY);
        for (int index = slices - 1; index >= 0; index--) {
            Vec3 centre = view.scale(slab * (index + 0.5D));
            Vec3 downLeft = centre.add(right.scale(-half)).add(up.scale(-half));
            Vec3 downRight = centre.add(right.scale(half)).add(up.scale(-half));
            Vec3 upRight = centre.add(right.scale(half)).add(up.scale(half));
            Vec3 upLeft = centre.add(right.scale(-half)).add(up.scale(half));
            sheetVertex(builder, pose, downLeft, sheetU(camera, downLeft, right, view, tilesPerBlock, driftU),
                    sheetV(camera, downLeft, up, view, tilesPerBlock, driftV), view, red, green, blue, alpha);
            sheetVertex(builder, pose, downRight, sheetU(camera, downRight, right, view, tilesPerBlock, driftU),
                    sheetV(camera, downRight, up, view, tilesPerBlock, driftV), view, red, green, blue, alpha);
            sheetVertex(builder, pose, upRight, sheetU(camera, upRight, right, view, tilesPerBlock, driftU),
                    sheetV(camera, upRight, up, view, tilesPerBlock, driftV), view, red, green, blue, alpha);
            sheetVertex(builder, pose, upLeft, sheetU(camera, upLeft, right, view, tilesPerBlock, driftU),
                    sheetV(camera, upLeft, up, view, tilesPerBlock, driftV), view, red, green, blue, alpha);
        }
        PACK_SHEETS.draw(builder.buildOrThrow());
    }

    /**
     * One axis of a sheet corner's texture coordinate, taken from where the corner is in the world.
     *
     * <p>Two projections added together: the corner's offset along the slab's own axis, which is what gives
     * the sheet its structure across the quad, and its progress along the view, which is what makes moving
     * forward through the dust change anything at all.
     */
    private static float sheetU(Vec3 camera, Vec3 offset, Vec3 right, Vec3 view, double tilesPerBlock,
                                double drift) {
        Vec3 corner = camera.add(offset);
        return (float) ((corner.dot(right) + corner.dot(view)) * tilesPerBlock + drift);
    }

    /** The same for a corner's vertical coordinate. Split out so the two axes read alike. */
    private static float sheetV(Vec3 camera, Vec3 offset, Vec3 up, Vec3 view, double tilesPerBlock,
                                double drift) {
        Vec3 corner = camera.add(offset);
        return (float) ((corner.dot(up) + corner.dot(view)) * tilesPerBlock + drift);
    }

    /**
     * One slice for the program: a quad centred on the view ray, spanned by the view's own basis.
     *
     * <p>Its texture coordinates and its colour are both write-only here - the fragment stage evaluates the
     * dust from the world position the vertex stage hands it and tints it itself - so they are the values the
     * format needs and nothing is read from them.
     */
    private static void quad(BufferBuilder builder, PoseStack.Pose pose, Vec3 centre, Vec3 right, Vec3 up,
                             double half) {
        Vec3 downLeft = centre.add(right.scale(-half)).add(up.scale(-half));
        Vec3 downRight = centre.add(right.scale(half)).add(up.scale(-half));
        Vec3 upRight = centre.add(right.scale(half)).add(up.scale(half));
        Vec3 upLeft = centre.add(right.scale(-half)).add(up.scale(half));
        flatVertex(builder, pose, downLeft, 0.0F, 1.0F);
        flatVertex(builder, pose, downRight, 1.0F, 1.0F);
        flatVertex(builder, pose, upRight, 1.0F, 0.0F);
        flatVertex(builder, pose, upLeft, 0.0F, 0.0F);
    }

    /** One corner of a slice for the program: position, uv and white, nothing else the format wants. */
    private static void flatVertex(BufferBuilder builder, PoseStack.Pose pose, Vec3 position,
                                   float u, float v) {
        builder.addVertex(pose, (float) position.x, (float) position.y, (float) position.z)
                .setUv(u, v)
                .setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * One corner of a sheet slice.
     *
     * <p>The position is already relative to the camera, which is the convention every level-stage renderer
     * in this mod writes to. The light is full bright because the type does not sample the lightmap, so
     * writing anything else would only be a value nobody reads.
     */
    private static void sheetVertex(BufferBuilder builder, PoseStack.Pose pose, Vec3 position,
                                    float u, float v, Vec3 forward,
                                    float red, float green, float blue, float alpha) {
        builder.addVertex(pose, (float) position.x, (float) position.y, (float) position.z)
                .setUv(u, v)
                .setColor(red, green, blue, alpha)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(pose, (float) -forward.x, (float) -forward.y, (float) -forward.z);
    }

    /**
     * How far along the view the camera-centred box's face is.
     *
     * <p>Because the eye is the box's centre, the exit is simply the nearest of the three axis pairs measured
     * along the ray, and an axis the view is parallel to never bounds it. Clamped, so a degenerate view
     * cannot ask for an infinite slab.
     */
    private static double boxExitDistance(Vec3 view, double radius) {
        double exit = radius;
        if (Math.abs(view.x) > 1.0E-6D) {
            exit = Math.min(exit, radius / Math.abs(view.x));
        }
        if (Math.abs(view.y) > 1.0E-6D) {
            exit = Math.min(exit, radius / Math.abs(view.y));
        }
        if (Math.abs(view.z) > 1.0E-6D) {
            exit = Math.min(exit, radius / Math.abs(view.z));
        }
        return Math.max(0.0D, exit);
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
