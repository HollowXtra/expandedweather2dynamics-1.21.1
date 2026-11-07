package com.imbet.client;

import com.imbet.Config;
import com.imbet.ExpandedWeather2Dynamics;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;

/**
 * The sun, the moon and the Milky Way, drawn by this mod instead of by the game.
 *
 * <p>Vanilla's sun and moon are a textured quad each, so they are squares with a picture of a disc painted on
 * them - fine from across a field and obviously wrong from anything closer, and a picture cannot be eclipsed
 * because there is no second body to pass in front of it. This class replaces both with the mod's own art: a sun
 * drawn with real limb darkening and a layered glare, the moon's eight phases as an atlas picked by the phase
 * the level reports, and an eclipse that is the sun's own light being taken away, with a corona and a diamond
 * ring of its own for the two moments an eclipse is known for.
 *
 * <p>The bodies used to be cut out of their quads by the fragment stage - a disc was the distance from the centre
 * of the quad, the phase was a terminator computed per pixel, and the eclipse was two of those discs crossing.
 * They are textures now: the pictures live in {@code textures/environment/celestial}, drawn by
 * {@code .freebuff/scratch/GenCelestialArt.java} and measured - with the rest of this - by
 * {@code .freebuff/scratch/CelestialSim.java} - and the program's job on a body is down to the two things a
 * picture cannot do for itself: scale the glow past the body's own edge by the configured amount, and multiply
 * the whole thing by the light it is drawn with. Every texture is authored to the same convention,
 * {@link #BODY_LIMB} - the body's own edge sits that far out from the centre of its square, and the square's
 * remaining room is the body's glare - and that one number is why a quad can be sized around a body at all, and
 * why the eclipse's black disc can be sized against the sun to the block.
 *
 * <p>The two eclipses are drawn as different things because they are different things. A solar eclipse is a body
 * crossing a body, so it is drawn as bodies: the moon's black disc over the sun, with the corona and the diamond
 * ring of its own on top. A lunar eclipse is not a body at all - it is the Earth's shadow - and what it changes
 * is not what is in front of the moon but the light the moon is drawn with, so it is not drawn over the moon but
 * drawn into it: the same pass that adds the moon's light multiplies it by the shadow, per pixel, so the moon's
 * limb goes copper as it enters the shadow while the rest of it is still full, and the moon's own alpha is what
 * confines the multiply to the moon - nothing behind it is darkened, and no second quad has to be shaped to it.
 * The shadow's own look is two numbers, its distance and its radius, and the arithmetic is in the fragment stage
 * beside the moon's own.
 *
 * <p>The vanilla squares are not removed from the game - they are drawn by {@code LevelRenderer}, and this class
 * is not in that call. They are hidden instead: {@link #shouldHideVanilla} tells a mixin to bind a fully
 * transparent texture in their place, so vanilla still draws its quads and simply draws nothing, and this class
 * draws the real bodies on top. The important part is that the switch is honest: if this renderer is off, or the
 * program failed to load, or the player is not in a dimension with a normal sky, the vanilla squares are left
 * alone. The sky can be upgraded, but it can never silently lose its sun.
 *
 * <p>What "left alone" comes to is worth being plain about, because it is no longer quite the game's own sky. The
 * mod's pictures of the sun and the moon are shipped as the game's own two textures as well -
 * {@code textures/environment/sun.png} and {@code moon_phases.png} - since those are the files a shader pack binds
 * to its own sun and moon quads, and a pack is the one renderer this class cannot hand its art to in any other way:
 * a pack replaces the mod's own program, and it takes the game's whole sky pass out of the frame. So a square this
 * class is not drawing is a square showing the mod's art rather than the game's own, at the game's own size. What
 * is left alone is the drawing, not the picture; the game's own art is nowhere in the mod. The switch that does
 * matter is still the same one - nothing here ever takes a sun out of the sky - and the two eclipses, the Milky Way
 * and the sizes above are the mod's own renderer's alone, so turning this off leaves a sky with one sun, one moon,
 * no eclipse of either, and no band across it.
 *
 * <p>Positions are built in the local frame vanilla uses for its sky - a {@code PoseStack} carrying the camera
 * rotation, then a quarter turn about Y and the day's rotation about X - so a body lands exactly where the
 * vanilla one would and the Milky Way turns with the stars rather than with the player.
 */
public final class CelestialRenderer {
    private static ShaderInstance shader;

    private static final RenderStateShard.ShaderStateShard SHADER_STATE =
            new RenderStateShard.ShaderStateShard(() -> shader);

    /**
     * The additive pass a light source in the sky is drawn with: the body's colour scaled by its own alpha, and
     * added to the sky.
     *
     * <p>This is the blend vanilla itself sets before its own sun and moon quads -
     * {@code blendFuncSeparate(SRC_ALPHA, ONE, ONE, ZERO)}, in {@code LevelRenderer.renderSky} - and it is
     * deliberately not {@link RenderStateShard#ADDITIVE_TRANSPARENCY}, which is {@code blendFunc(ONE, ONE)} and
     * ignores the fragment's alpha entirely. With that one the alpha channel is thrown away, and since the alpha
     * is what carries a body now - the glare's falloff, a phase's terminator, the light a body is drawn with - a
     * glow would arrive as a flat bright patch the size of its quad rather than as a falloff, and a body behind a
     * storm's darkening would not fade at all. That last part was already true of what the program emitted before
     * the bodies became pictures, and it is the one blend this feature needs a state of its own for.
     */
    private static final RenderStateShard.TransparencyStateShard ADDITIVE =
            new RenderStateShard.TransparencyStateShard(
                    ExpandedWeather2Dynamics.MODID + "_celestial_additive",
                    () -> {
                        RenderSystem.enableBlend();
                        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                                GlStateManager.DestFactor.ONE, GlStateManager.SourceFactor.ONE,
                                GlStateManager.DestFactor.ZERO);
                    },
                    () -> {
                        RenderSystem.disableBlend();
                        RenderSystem.defaultBlendFunc();
                    });

    /**
     * Where the sun and the moon sit, in blocks. Vanilla puts them at a hundred and draws the sun sixty
     * across and the moon forty, which is a sky-dome convention rather than a size: the numbers only matter
     * as an angle, and a hundred keeps them clear of the far plane without a depth fight.
     */
    private static final float SKY_DISTANCE = 100.0F;

    /**
     * Where a body's own edge sits inside its texture, as a fraction of the texture's half-width, and therefore
     * how much of the square is the body and how much is its room for glare. It is the renderer's half of the
     * convention every texture in {@code environment/celestial} is drawn to, and the generator's {@code LIMB} is
     * the other half, so a body is drawn at exactly the size it is asked for: a quad is drawn the body's radius
     * over this across, and the texture's own disc lands inside it.
     *
     * <p>A little over half sounds large for a light source wearing a glow, and it is entirely the glow's room:
     * a texture is a square of pixels and the glare has to live in the pixels past the limb, so a body drawn
     * with only a sliver of margin would show its own square the moment its glow passed the edge. The sun and
     * the moon both put their glare in that room; the eclipse's pieces fill it with their own falloff.
     */
    private static final float BODY_LIMB = 0.42F;

    /** A procedurally blank texture, so vanilla's sun and moon quads can be drawn invisibly. */
    private static final ResourceLocation BLANK = ResourceLocation.fromNamespaceAndPath(
            ExpandedWeather2Dynamics.MODID, "celestial_blank");

    /**
     * The glow an art-only piece of the eclipse is drawn with: one, which leaves the texture's own falloff
     * exactly as it was drawn. The eclipse's corona and its diamond ring are light and nothing else, so scaling
     * their glare again would be scaling art that is already the finished thing.
     */
    private static final float ART_GLOW = 1.0F;

    /** The sun's own picture: a photosphere with its limb darkening, and the glare past its edge. */
    private static final ResourceLocation SUN_TEXTURE = celestialTexture("sun");
    /** The moon's eight phases, four columns by two rows, the same layout the game's own moon is drawn from. */
    private static final ResourceLocation MOON_TEXTURE = celestialTexture("moon_phases");
    /** The black disc that takes the sun's light away during an eclipse. */
    private static final ResourceLocation ECLIPSE_MOON_TEXTURE = celestialTexture("eclipse_moon");
    /** The sun's outer atmosphere, still visible once the photosphere is gone. */
    private static final ResourceLocation ECLIPSE_CORONA_TEXTURE = celestialTexture("eclipse_corona");
    /** The last point of photosphere, at the moment the moon has almost swallowed it. */
    private static final ResourceLocation ECLIPSE_RING_TEXTURE = celestialTexture("eclipse_diamond_ring");

    /**
     * The colour the eclipsed moon turns: sunlight that crossed the whole depth of the world's atmosphere,
     * refracted around the world's edge and stripped of everything but the red end on the way. A real umbra is
     * about a ten-thousandth as bright as a full moon, which no screen shows, so this is the same colour at a
     * brightness a player can actually see: a dark copper, and by far the reddest thing in the night sky.
     */
    private static final float[] UMBRA_TINT = {0.52F, 0.18F, 0.12F};

    /** A pure additive pass, which is what a light source in the sky is. */
    private static final RenderType SUN = body("sun", SUN_TEXTURE, true);
    private static final RenderType MOON = body("moon", MOON_TEXTURE, true);
    private static final RenderType ECLIPSE_CORONA = body("eclipse_corona", ECLIPSE_CORONA_TEXTURE, true);
    private static final RenderType ECLIPSE_RING = body("eclipse_ring", ECLIPSE_RING_TEXTURE, true);
    /** The eclipse's moon is the one body that takes light away rather than adding it, so it is not additive. */
    private static final RenderType ECLIPSE_MOON = body("eclipse_moon", ECLIPSE_MOON_TEXTURE, false);

    /**
     * The alpha-blended pass for the galactic band, which is the one thing drawn here that is not a body: it is
     * a great circle and the program still builds it out of noise, so this pass carries no texture and the
     * fragment stage's other Mode ignores the sampler.
     */
    private static final RenderType SOFT = RenderType.create(
            ExpandedWeather2Dynamics.MODID + "_celestial_soft",
            DefaultVertexFormat.POSITION_TEX_COLOR,
            VertexFormat.Mode.QUADS,
            2048,
            false,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(SHADER_STATE)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                    .setOverlayState(RenderStateShard.NO_OVERLAY)
                    .createCompositeState(false)
    );

    private CelestialRenderer() {
    }

    /**
     * One pass for one body: its own picture bound to the sampler, and its own blend. The bodies cannot share a
     * pass the way the band can, because a pass is where a texture is bound and each body is a different file;
     * which of them adds light to the sky and which takes it away is the other half of the same decision - the
     * sun, the moon and the eclipse's light are added with {@link #ADDITIVE}, and the eclipse's black disc is
     * alpha-blended over the sun because its job is to take that light away.
     *
     * <p>The texture is filtered smoothly with no mipmaps. Smoothly because a disc drawn from a couple of hundred
     * texels is presented across more pixels than that on a close look and has to interpolate rather than show
     * its texels; without mipmaps because the moon's is an atlas, and a mip chain would blend one phase into the
     * next at every tile's edge.
     */
    private static RenderType body(String suffix, ResourceLocation texture, boolean additive) {
        return RenderType.create(
                ExpandedWeather2Dynamics.MODID + "_celestial_" + suffix,
                DefaultVertexFormat.POSITION_TEX_COLOR,
                VertexFormat.Mode.QUADS,
                512,
                false,
                true,
                RenderType.CompositeState.builder()
                        .setShaderState(SHADER_STATE)
                        .setTextureState(new RenderStateShard.TextureStateShard(texture, true, false))
                        .setTransparencyState(additive ? ADDITIVE : RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setCullState(RenderStateShard.NO_CULL)
                        .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                        .setOverlayState(RenderStateShard.NO_OVERLAY)
                        .createCompositeState(false));
    }

    private static ResourceLocation celestialTexture(String name) {
        return ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID,
                "textures/environment/celestial/" + name + ".png");
    }

    public static void register(RegisterShadersEvent event) throws IOException {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "celestial");
        event.registerShader(
                new ShaderInstance(event.getResourceProvider(), id, DefaultVertexFormat.POSITION_TEX_COLOR),
                loaded -> shader = loaded
        );
    }

    /**
     * Whether THIS mod is drawing the sun and moon right now, and vanilla's squares should therefore be
     * hidden. False leaves vanilla exactly as it was - the mod's bodies are a replacement that can be turned
     * off, not a one-way edit to the game's sky.
     *
     * <p>With an Iris shaderpack running there is already a sun and a moon in the sky, and they are the
     * pack's, drawn by the pack from its own sky model. A second pair on top would be two suns, and hiding
     * the vanilla ones would hide nothing that is still being drawn, so this stands down and leaves the whole
     * sky to the pack - which is also what keeps the vanilla textures untouched. The eclipse is not a body in
     * this sense and is handled separately, by {@link #drawingEclipseOverlay()}.
     */
    public static boolean drawingCelestialBodies() {
        return drawingEclipseOverlay() && !RenderingCompat.isShaderPackActive();
    }

    /**
     * Whether the mod may draw the eclipse's own pieces right now - the black moon disc, its corona and the
     * diamond ring - with or without a pack.
     *
     * <p>Without a pack this is the same answer as {@link #drawingCelestialBodies()}, because the eclipse is
     * drawn as part of the same sky. With a pack it is the one thing the mod can still try to draw, since the
     * pack takes the sun and the moon and the mod's own sky program with them: an eclipse is an overlay on
     * top of whatever sun is there rather than a second body, so it does not double anything up. The switch
     * that allows it is off by default because a pack that does not provide a program for it draws it with
     * its fallback instead, which may not appear or may look wrong, and because the eclipse's darkening of
     * the sky and the world is a separate reading that happens either way.
     */
    public static boolean drawingEclipseOverlay() {
        if (!Config.VISUALS_ENABLED.get() || !Config.CELESTIAL_ENABLED.get() || !Config.CELESTIAL_ROUND.get()) {
            return false;
        }
        if (Config.classicLook() || shader == null) {
            return false;
        }
        if (RenderingCompat.isShaderPackActive() && !Config.CELESTIAL_ECLIPSE_UNDER_SHADERS.get()) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level != null && minecraft.level.effects().skyType() == DimensionSpecialEffects.SkyType.NORMAL;
    }

    /** Hides vanilla's own sun and moon quads by name, so the vanilla pass draws them invisibly. */
    public static boolean shouldHideVanilla(ResourceLocation location) {
        if (location == null || !drawingCelestialBodies()) {
            return false;
        }
        String path = location.getPath();
        return path.endsWith("sun.png") || path.endsWith("moon_phases.png");
    }

    /**
     * The transparent texture to bind in the sun and moon's place, created and registered on first use.
     *
     * <p>The lookup must be the two-argument one. The one-argument {@code getTexture} never returns null -
     * it fabricates a {@code SimpleTexture} for the name and tries to load a file that does not exist, which
     * logs a failed load and hands back the missing-texture, so a null check on it silently never registers
     * anything. {@code getTexture(BLANK, null)} answers the question that is actually being asked: is there a
     * texture under this name already, without looking on disk for one. It is also the check that survives a
     * resource reload, because a reload clears the manager's map and drops our dynamic texture with it - so
     * this re-registers the next frame, and the sun is never left uglied by a stale registration.
     */
    public static ResourceLocation blankTexture() {
        TextureManager textureManager = Minecraft.getInstance().getTextureManager();
        if (textureManager.getTexture(BLANK, null) == null) {
            // A zeroed one-pixel image is fully transparent, which is the whole point of it.
            NativeImage image = new NativeImage(1, 1, true);
            DynamicTexture texture = new DynamicTexture(image);
            texture.setFilter(false, false);
            texture.upload();
            textureManager.register(BLANK, texture);
        }
        return BLANK;
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) {
            return;
        }
        // Two answers, not one: the bodies are drawn only when the mod owns the sky, while the eclipse may
        // also be drawn over a pack's sky when the player has asked for it. Either one alone is enough to
        // run the stage.
        boolean bodies = drawingCelestialBodies();
        if (!bodies && !drawingEclipseOverlay()) {
            return;
        }
        // The stage fires once for the visible frame and again for Iris's shadow map, so this has to be a
        // visible-pass job only: a sun belongs in the sky, not in a depth map.
        if (RenderingCompat.isRenderingShadowPass()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        double timeOfDay = minecraft.level.getTimeOfDay(partial);
        double starBrightness = minecraft.level.getStarBrightness(partial);
        float hiding = Mth.clamp(
                StormSkyClient.sunHidingLevel(minecraft.level.getRainLevel(partial)), 0.0F, 1.0F);
        float visibility = 1.0F - hiding;
        CelestialSky.Eclipse eclipse = CelestialSky.eclipse(
                minecraft.level.getDayTime(),
                Config.CELESTIAL_ECLIPSE_DURATION_SECONDS.get(),
                Config.CELESTIAL_ECLIPSE_CHANCE.get());

        // The sky frame has to start from the camera's rotation, and at this stage that rotation is not on
        // the event's pose stack. NeoForge dispatches AFTER_SKY with a null stack - vanilla only builds one
        // inside renderSky - so getPoseStack() here is a fresh empty pose, an identity, and a sky built from
        // it has no camera in it at all: it stays put while the player turns, which is not a sky. The camera
        // rotation is instead on the event as its model-view matrix, and it is the very frustum matrix
        // vanilla hands to renderSky, so the frame is rebuilt from that, in vanilla's own order.
        PoseStack pose = new PoseStack();
        pose.mulPose(event.getModelViewMatrix());
        pose.mulPose(Axis.YP.rotationDegrees(-90.0F));
        pose.mulPose(Axis.XP.rotationDegrees((float) (timeOfDay * 360.0D)));
        Matrix4f sky = pose.last().pose();
        // The same frame without the camera in it: where a body is against the horizon is a fact about the world, not
        // about where the player is looking. Asked of the camera's frame, "above the horizon" was "above the middle of
        // the screen", so looking up past the sun or the moon put it in the lower half of the view and faded it out as
        // if it had set - the bodies vanished whenever they were looked at from below.
        PoseStack world = new PoseStack();
        world.mulPose(Axis.YP.rotationDegrees(-90.0F));
        world.mulPose(Axis.XP.rotationDegrees((float) (timeOfDay * 360.0D)));
        Matrix4f horizon = world.last().pose();

        // The lunar eclipse, read from the same shared schedule as the solar one and against the moon's own
        // phase, because only a full moon can be eclipsed. With the feature off the chance is asked for as zero,
        // which is a window that never opens, so there is no second way for this to be switched off.
        double lunarChance = Config.CELESTIAL_LUNAR_ECLIPSE.get()
                ? Config.CELESTIAL_LUNAR_ECLIPSE_CHANCE.get() : 0.0D;
        CelestialSky.LunarEclipse lunar = CelestialSky.lunarEclipse(
                minecraft.level.getDayTime(),
                Config.CELESTIAL_LUNAR_ECLIPSE_DURATION_SECONDS.get(),
                lunarChance,
                minecraft.level.getMoonPhase());

        float sunHalf = Config.CELESTIAL_SUN_SIZE.get().floatValue();
        float moonHalf = Config.CELESTIAL_MOON_SIZE.get().floatValue();
        float coverage = (float) eclipse.coverage();
        boolean canSeeSky = visibility > 0.01F;

        // The Milky Way first, so the bodies sit in front of it. During an eclipse the sky it is painted on
        // is being covered as well, so it dims along with everything else. It is part of the mod's own sky
        // rather than an overlay, so it stands down with the bodies under a pack.
        if (bodies && Config.CELESTIAL_MILKY_WAY.get() && starBrightness > 0.02D) {
            float eclipseDim = (float) (1.0D - coverage * Config.CELESTIAL_ECLIPSE_DARKEN.get());
            float alpha = (float) (starBrightness * Config.CELESTIAL_MILKY_WAY_BRIGHTNESS.get())
                    * visibility * eclipseDim;
            drawBand(sky, alpha);
        }

        float sunHorizon = horizonFade(horizon, 0.0F, SKY_DISTANCE);
        float moonHorizon = horizonFade(horizon, 0.0F, -SKY_DISTANCE);
        if (bodies && canSeeSky && sunHorizon > 0.01F) {
            // The sun: white in the middle, only faintly warm at the limb, and wearing the glare the picture
            // was drawn with - a tight bloom on the limb, the forward-scattered aureole and a wide faint haze.
            // The glow dial scales that glare and leaves the disc alone, which is what "0 gives a clean
            // hard-edged disc" means; the brightness dial scales the whole body.
            drawBody(SUN, sky, SKY_DISTANCE, sunHalf, 0.0F,
                    Config.CELESTIAL_SUN_GLOW.get().floatValue(),
                    Config.CELESTIAL_SUN_BRIGHTNESS.get().floatValue(),
                    visibility * sunHorizon);
        }
        if (bodies && canSeeSky && moonHorizon > 0.01F) {
            // The moon: one tile of the atlas, chosen by the phase the level reports, so a crescent is a
            // crescent and a new moon is the one tile with nothing lit in it - which adds nothing to the sky
            // rather than pasting a dark disc over it. The glow rides on the lit limb in the art itself, so a
            // crescent carries its halo on the side that is lit and the new moon carries none.
            int phase = minecraft.level.getMoonPhase();
            float column = (float) CelestialSky.moonPhaseColumn(phase) / CelestialSky.MOON_ATLAS_COLUMNS;
            float row = (float) CelestialSky.moonPhaseRow(phase) / CelestialSky.MOON_ATLAS_ROWS;
            // A lunar eclipse is the other half of the same idea and the opposite way round: nothing crosses
            // the moon, the moon crosses the Earth's shadow, so the shadow is folded into the moon's own light
            // rather than drawn as a body on top of it.
            setShadow(lunar);
            drawBody(MOON, sky, -SKY_DISTANCE, moonHalf, 0.0F,
                    Config.CELESTIAL_MOON_GLOW.get().floatValue(),
                    Config.CELESTIAL_MOON_BRIGHTNESS.get().floatValue(),
                    visibility * moonHorizon,
                    column, row,
                    column + 1.0F / CelestialSky.MOON_ATLAS_COLUMNS,
                    row + 1.0F / CelestialSky.MOON_ATLAS_ROWS);
            // The moon is the only body the shadow can fall on, and a solar eclipse can share the sky with a
            // lunar one at extreme settings, so the shadow is taken off again rather than left set.
            setUniform("ShadowStrength", 0.0F);
        }
        if (eclipse.active() && coverage > 0.005F && canSeeSky && sunHorizon > 0.01F) {
            drawEclipse(sky, sunHalf, coverage, eclipse.progress() >= 0.5D, visibility * sunHorizon);
        }
    }

    /**
     * Puts the Earth's shadow on the moon for this frame, or takes it off when there is no lunar eclipse.
     *
     * <p>What the fragment stage needs is the shadow's axis as a distance from the moon's own centre and the
     * umbra's width, both in moon radii, plus what the shadow looks like; everything else about the shape - where
     * the moon's limb is, how much of the moon is dark - is the same arithmetic in the same stage that draws the
     * moon, which is why the moon's own art does not have to know it might be eclipsed.
     */
    private static void setShadow(CelestialSky.LunarEclipse lunar) {
        if (!lunar.active()) {
            setUniform("ShadowStrength", 0.0F);
            return;
        }
        // The faint outer penumbra already reaches the moon at first contact of the umbra, so the shadow eases in and
        // out over the ends of the window rather than switching on with part of the moon already dimmed.
        float progress = (float) lunar.progress();
        setUniform("ShadowStrength", smoothstep(0.0F, 0.10F, progress) * smoothstep(0.0F, 0.10F, 1.0F - progress));
        setUniform("ShadowOffset", (float) CelestialSky.lunarShadowOffsetMoonRadii(lunar.progress()));
        setUniform("ShadowUmbra", (float) CelestialSky.UMBRA_RADII);
        setUniform("ShadowTint", UMBRA_TINT[0], UMBRA_TINT[1], UMBRA_TINT[2]);
    }

    /**
     * The eclipse, drawn as the thing itself rather than as a dimmed sun: the moon is a black disc crossing
     * the sun's, so what is left of the sun is a crescent that thins away instead of a disc that fades. The
     * sun can therefore stay at full brightness the whole time - it is the moon taking its light away, not
     * the sun going dim - which is what lets the last sliver stay brilliant right up to the moment it goes.
     *
     * <p>That disc is a picture, and so are the two things a total eclipse is known for on top of it. The corona
     * is the sun's outer atmosphere, still visible once the photosphere is gone: a halo with no body in it,
     * drawn at the sun's own size and centred on the moon, so what shows of it is the ring around the moon's
     * edge. The diamond ring is the single brilliant point the last sliver of photosphere collapses into on the
     * way in, and the first point revealed on the way out, and it rides a narrow window around totality. Both
     * are drawn with {@link #ART_GLOW} because both are already only light: there is no disc in them for the
     * glow dial to be told apart from.
     */
    private static void drawEclipse(Matrix4f sky, float sunHalf, float coverage, boolean leaving, float light) {
        // The moon crosses the sun: in from one side, out of the other. Read off the coverage alone the offset had no
        // sign, so the moon slid in, stopped, and backed out the way it came.
        float side = leaving ? -1.0F : 1.0F;
        float offset = (float) (CelestialSky.moonOffsetSunRadii(coverage) * sunHalf) * side;
        float occultHalf = sunHalf * Config.CELESTIAL_ECLIPSE_MOON_SCALE.get().floatValue();

        // The moon: opaque, near-black, and sized against the sun. Drawn over the sun rather than adding to it,
        // because its job is to take light away, and the sun's remainder is then a crescent rather than a
        // dimmer disc.
        drawBody(ECLIPSE_MOON, sky, SKY_DISTANCE, occultHalf, offset, ART_GLOW, 1.0F, light);

        float corona = Config.CELESTIAL_ECLIPSE_CORONA.get().floatValue();
        if (corona > 0.0F) {
            // The corona is a millionth of the sun's brightness: it is drowned while any photosphere shows at all, and
            // comes out only in the last moments before totality. Ramped in from half coverage it was a halo round
            // a partial eclipse, which no one has ever seen.
            float near = smoothstep(0.955F, 0.998F, coverage);
            if (near > 0.01F) {
                drawBody(ECLIPSE_CORONA, sky, SKY_DISTANCE, sunHalf * 1.04F, offset, ART_GLOW, 1.0F,
                        corona * near * light);
            }
        }

        if (Config.CELESTIAL_ECLIPSE_DIAMOND_RING.get()) {
            float closeness = 1.0F - Math.abs(coverage - 0.982F) / 0.018F;
            if (closeness > 0.0F) {
                // The bead is the last of the sun on the side the moon has not yet covered on the way in, and the first
                // of it on the side the moon is uncovering on the way out - opposite limbs, not the same one twice.
                drawBody(ECLIPSE_RING, sky, SKY_DISTANCE, sunHalf * 0.22F, -side * sunHalf * 0.92F, ART_GLOW, 1.0F,
                        closeness * light);
            }
        }
    }

    /** A smooth 0 to 1 ramp between two edges, the same shape the fragment stage uses for its gradients. */
    private static float smoothstep(float edge0, float edge1, float value) {
        float t = Mth.clamp((value - edge0) / (edge1 - edge0), 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    /** How much of a body on the far side of the horizon should still be drawn, fading across the rim. */
    private static float horizonFade(Matrix4f sky, float x, float y) {
        Vector3f centre = sky.transformPosition(x, y, 0.0F, new Vector3f());
        return Mth.clamp((centre.y + 8.0F) / 16.0F, 0.0F, 1.0F);
    }

    /** One body drawn from its whole texture. */
    private static void drawBody(RenderType type, Matrix4f sky, float y, float half, float offset,
                                 float glow, float gain, float alpha) {
        drawBody(type, sky, y, half, offset, glow, gain, alpha, 0.0F, 0.0F, 1.0F, 1.0F);
    }

    /**
     * One body: a quad where vanilla drew its square, carrying the body's own picture, sized so the texture's
     * limb lands on the body's edge, and lit by whatever light it is drawn with.
     *
     * <p>The quad is drawn larger than the body - by the body's radius over {@link #BODY_LIMB} - so there is
     * room outside the limb for the glare the texture was drawn with. The uv rectangle is the whole texture
     * except for the moon, which asks for the one tile of its atlas the game's phase names.
     */
    private static void drawBody(RenderType type, Matrix4f sky, float y, float half, float offset,
                                 float glow, float gain, float alpha,
                                 float u0, float v0, float u1, float v1) {
        setUniform("Mode", 0.0F);
        setUniform("Limb", BODY_LIMB);
        setUniform("Glow", glow);
        setUniform("Gain", gain);
        // Which part of the texture is the body, so the program can tell where on the body each fragment is.
        setUniform("UvRect", u0, v0, u1, v1);
        setUniform("ColorModulator", 1.0F, 1.0F, 1.0F, 1.0F);
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,
                DefaultVertexFormat.POSITION_TEX_COLOR);
        bodyQuad(builder, sky, y, half / BODY_LIMB, offset, alpha, u0, v0, u1, v1);
        type.draw(builder.buildOrThrow());
    }

    private static void bodyQuad(BufferBuilder builder, Matrix4f sky, float y, float half, float offset,
                                 float alpha, float u0, float v0, float u1, float v1) {
        float x0 = -half + offset;
        float x1 = half + offset;
        // Farthest at the top so the quad's own winding matches the sky dome's, though culling is off.
        builder.addVertex(sky, x0, y, -half).setUv(u0, v0).setColor(1.0F, 1.0F, 1.0F, alpha);
        builder.addVertex(sky, x1, y, -half).setUv(u1, v0).setColor(1.0F, 1.0F, 1.0F, alpha);
        builder.addVertex(sky, x1, y, half).setUv(u1, v1).setColor(1.0F, 1.0F, 1.0F, alpha);
        builder.addVertex(sky, x0, y, half).setUv(u0, v1).setColor(1.0F, 1.0F, 1.0F, alpha);
    }

    /**
     * The Milky Way: a ribbon of quads built around the galactic axis.
     *
     * <p>It is a great circle, so it is generated rather than placed - both edges are the axis direction
     * swung around the two perpendicular axes the band lies between - and the fragment stage gives it the
     * cloud, the falloff across its width and the stars. A closed loop in the sky's own frame, which is what
     * makes it turn with the stars through the night instead of sitting still.
     */
    private static void drawBand(Matrix4f sky, float alpha) {
        if (alpha <= 0.01F) {
            return;
        }
        setUniform("Mode", 1.0F);
        setUniform("ColorModulator", 1.0F, 1.0F, 1.0F, 1.0F);

        double tilt = CelestialSky.galacticTiltRadians();
        double yaw = CelestialSky.galacticYawRadians();
        Vec3 normal = new Vec3(Math.sin(tilt) * Math.cos(yaw), Math.cos(tilt), Math.sin(tilt) * Math.sin(yaw)).normalize();
        Vec3 reference = Math.abs(normal.y) > 0.9D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 axisU = normal.cross(reference).normalize();
        Vec3 axisV = normal.cross(axisU).normalize();

        int segments = 72;
        double width = Math.toRadians(9.0D);
        float red = 0.72F;
        float green = 0.76F;
        float blue = 0.94F;
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,
                DefaultVertexFormat.POSITION_TEX_COLOR);
        for (int index = 0; index < segments; index++) {
            double t0 = (double) index / segments;
            double t1 = (double) (index + 1) / segments;
            Vec3 inner0 = bandEdge(axisU, axisV, normal, t0, width);
            Vec3 outer0 = bandEdge(axisU, axisV, normal, t0, -width);
            Vec3 outer1 = bandEdge(axisU, axisV, normal, t1, -width);
            Vec3 inner1 = bandEdge(axisU, axisV, normal, t1, width);
            // u repeats a few times around the loop so a 72-quad ribbon has enough noise cells along it;
            // v runs across, from the top edge to the bottom.
            float u0 = (float) (t0 * 8.0D);
            float u1 = (float) (t1 * 8.0D);
            bandVertex(builder, sky, inner0, u0, 0.0F, red, green, blue, alpha);
            bandVertex(builder, sky, outer0, u0, 1.0F, red, green, blue, alpha);
            bandVertex(builder, sky, outer1, u1, 1.0F, red, green, blue, alpha);
            bandVertex(builder, sky, inner1, u1, 0.0F, red, green, blue, alpha);
        }
        SOFT.draw(builder.buildOrThrow());
    }

    /** One edge of the band at a parameter around the great circle. */
    private static Vec3 bandEdge(Vec3 axisU, Vec3 axisV, Vec3 normal, double t, double offset) {
        double angle = t * Math.PI * 2.0D;
        Vec3 along = axisU.scale(Math.cos(angle)).add(axisV.scale(Math.sin(angle)));
        return along.scale(Math.cos(offset)).add(normal.scale(Math.sin(offset))).scale(SKY_DISTANCE);
    }

    private static void bandVertex(BufferBuilder builder, Matrix4f sky, Vec3 position, float u, float v,
                                   float red, float green, float blue, float alpha) {
        builder.addVertex(sky, (float) position.x, (float) position.y, (float) position.z)
                .setUv(u, v)
                .setColor(red, green, blue, alpha);
    }

    private static void setUniform(String name, float value) {
        Uniform uniform = shader == null ? null : shader.getUniform(name);
        if (uniform != null) {
            uniform.set(value);
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
