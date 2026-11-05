package com.imbet.client;

import com.imbet.alert.SirenTone;
import com.imbet.alert.TornadoSirenBlockEntity;
import com.imbet.client.SolidMesh.Builder;
import com.imbet.client.SolidMesh.Material;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The three pole-mounted sirens, built the way the real ones are.
 *
 * <p>These are not one model with a recolour, because they are not one machine:
 *
 * <ul>
 *   <li>The <b>mechanical</b> siren follows the classic rotating two-port machines. A motor on top drives a chopper
 *       in a louvred drum, air leaves through a large bell and a smaller opposed one, and the whole head turns about
 *       the pole, because a siren whose rotor screamed in one direction would warn half a town.</li>
 *   <li>The <b>electronic</b> siren follows the amplifier-driven arrays: a stack of rectangular horn cells on all four
 *       sides of a mast, solid-state with nothing moving, a radio Yagi to activate it, a solar panel keeping its
 *       batteries up, and a strobe. A rectangular mouth is what covers a quadrant evenly.</li>
 *   <li>The <b>two-tone</b> siren is the civil-defence yellow machine: a large low-note bell and a smaller high-note
 *       bell on opposite sides of one head, so as it rocks the note visibly hands over from one side of the street to
 *       the other. The block entity supplies the angle from the tone's own two second step.</li>
 * </ul>
 *
 * <p>All three stand on what the real ones stand on: a creosoted utility pole in a concrete collar, with a
 * weatherproof control cabinet strapped to it, conduit running up to the head and a copper ground wire running down.
 * See {@link SolidMesh} for why this is lit geometry rather than flat-coloured quads.
 */
public class TornadoSirenRenderer implements BlockEntityRenderer<TornadoSirenBlockEntity> {
    /** The pole's top, where every head is bolted on. Everything is built from the block's centre outward. */
    private static final float POLE_TOP = 2.32F;
    private static final float HEAD_Y = POLE_TOP + 0.08F;

    private static final SolidMesh[] POLE = pole();
    private static final SolidMesh[] MECHANICAL_FIXED = mechanicalFixed();
    private static final SolidMesh[] MECHANICAL_HEAD = mechanicalHead();
    private static final SolidMesh[] ELECTRONIC = electronic();
    private static final SolidMesh[] TWO_TONE_FIXED = twoToneFixed();
    private static final SolidMesh[] TWO_TONE_HEAD = twoToneHead();

    /** Each lamp's lens, apart from the rest so its tint and its light can follow the alert. */
    private static final SolidMesh MECHANICAL_LAMP = lamp(0.0F, HEAD_Y + 0.60F, 0.0F, 0.05F);
    private static final SolidMesh ELECTRONIC_LAMP = lamp(0.0F, 3.30F, 0.0F, 0.075F);
    private static final SolidMesh TWO_TONE_LAMP = lamp(0.0F, HEAD_Y + 0.62F, 0.0F, 0.05F);

    private static final int LENS_IDLE = 0xFF3A2C22;

    public TornadoSirenRenderer(BlockEntityRendererProvider.Context context) {
    }

    /** A siren stands three blocks over its footing and its bell reaches out, so the cull box grows with it. */
    @Override
    public AABB getRenderBoundingBox(TornadoSirenBlockEntity entity) {
        return new AABB(entity.getBlockPos()).inflate(1.1D, 0.0D, 1.1D).expandTowards(0.0D, 3.5D, 0.0D);
    }

    @Override
    public int getViewDistance() {
        return 192;
    }

    @Override
    public boolean shouldRender(TornadoSirenBlockEntity entity, Vec3 camera) {
        return Vec3.atCenterOf(entity.getBlockPos()).closerThan(camera, getViewDistance());
    }

    @Override
    public void render(TornadoSirenBlockEntity entity, float partialTick, PoseStack stack, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        draw(entity.tone(), entity.renderSpin(partialTick), entity.renderBeacon(partialTick), entity.sounding(), entity.beaconColor(),
                stack, buffer, packedLight, packedOverlay);
    }

    /** The machine in the block's own frame, for the block and for the item in the hand or the inventory. */
    static void draw(SirenTone tone, float spin, float beacon, boolean sounding, int beaconColor,
                     PoseStack stack, MultiBufferSource buffer, int light, int overlay) {
        VertexConsumer consumer = buffer.getBuffer(SolidMesh.renderType());
        stack.pushPose();
        stack.translate(0.5D, 0.0D, 0.5D);
        emit(POLE, consumer, stack, light, overlay);
        switch (tone) {
            case MECHANICAL -> {
                emit(MECHANICAL_FIXED, consumer, stack, light, overlay);
                stack.pushPose();
                stack.mulPose(Axis.YP.rotationDegrees(spin));
                emit(MECHANICAL_HEAD, consumer, stack, light, overlay);
                stack.popPose();
                drawLamp(MECHANICAL_LAMP, beacon, sounding, beaconColor, consumer, stack, light, overlay);
            }
            case ELECTRONIC -> {
                emit(ELECTRONIC, consumer, stack, light, overlay);
                drawLamp(ELECTRONIC_LAMP, beacon, sounding, beaconColor, consumer, stack, light, overlay);
            }
            case HILO -> {
                emit(TWO_TONE_FIXED, consumer, stack, light, overlay);
                stack.pushPose();
                stack.mulPose(Axis.YP.rotationDegrees(spin));
                emit(TWO_TONE_HEAD, consumer, stack, light, overlay);
                stack.popPose();
                drawLamp(TWO_TONE_LAMP, beacon, sounding, beaconColor, consumer, stack, light, overlay);
            }
        }
        stack.popPose();
    }

    private static void emit(SolidMesh[] meshes, VertexConsumer consumer, PoseStack stack, int light, int overlay) {
        PoseStack.Pose pose = stack.last();
        for (SolidMesh mesh : meshes) {
            mesh.emit(consumer, pose, light, overlay);
        }
    }

    /**
     * A lamp is dark glass until the siren sounds, and then it is its own light: full bright, in the colour of what
     * is being warned about, pulsing on the tone's own attack so the light and the sound are one signal.
     */
    private static void drawLamp(SolidMesh lamp, float beacon, boolean sounding, int color, VertexConsumer consumer, PoseStack stack, int light, int overlay) {
        if (!sounding) {
            lamp.emit(consumer, stack.last(), light, overlay, LENS_IDLE);
            return;
        }
        float glow = 0.35F + 0.65F * Mth.clamp(beacon, 0.0F, 1.0F);
        int red = Math.round((color >>> 16 & 255) * glow);
        int green = Math.round((color >>> 8 & 255) * glow);
        int blue = Math.round((color & 255) * glow);
        lamp.emit(consumer, stack.last(), LightTexture.FULL_BRIGHT, overlay, 0xFF000000 | red << 16 | green << 8 | blue);
    }

    // ------------------------------------------------------------------------------------------------
    // The pole they all stand on.
    // ------------------------------------------------------------------------------------------------

    private static float[] range(float from, float to, int steps) {
        float[] out = new float[steps + 1];
        for (int i = 0; i <= steps; i++) {
            out[i] = from + (to - from) * i / steps;
        }
        return out;
    }

    private static float[] taper(float from, float to, int steps) {
        return range(from, to, steps);
    }

    private static SolidMesh[] pole() {
        Builder wood = new Builder(Material.WOOD_POLE);
        // A pole tapers, top smaller than butt, and the wood repeats up it rather than stretching.
        wood.latheY(range(0.04F, POLE_TOP, 7), taper(0.098F, 0.074F, 7), 14, 1.4F);

        Builder concrete = new Builder(Material.CONCRETE);
        concrete.latheY(new float[]{0.0F, 0.015F, 0.075F, 0.095F}, new float[]{0.26F, 0.25F, 0.19F, 0.10F}, 16, 2.0F);

        Builder bands = new Builder(Material.GALVANIZED);
        for (float y : new float[]{0.62F, 1.07F, 2.20F}) {
            bands.latheY(new float[]{y, y + 0.012F, y + 0.05F, y + 0.062F},
                    new float[]{0.09F, 0.100F, 0.100F, 0.09F}, 14, 3.0F);
        }
        // A collar round the pole's top, and the plate the head bolts on to.
        bands.latheY(new float[]{POLE_TOP - 0.10F, POLE_TOP - 0.08F, POLE_TOP, POLE_TOP + 0.015F, POLE_TOP + 0.05F, POLE_TOP + 0.08F},
                new float[]{0.078F, 0.098F, 0.098F, 0.17F, 0.17F, 0.13F}, 16, 3.0F);

        // Cabinet, on the pole's +Z side: a weatherproof box with a hood over the door.
        Builder cabinet = new Builder(Material.PAINT_GRAY);
        cabinet.box(-0.17F, 0.56F, 0.10F, 0.17F, 1.12F, 0.28F, 2.0F);
        cabinet.box(-0.185F, 1.12F, 0.09F, 0.185F, 1.14F, 0.30F, 2.0F);
        Builder door = new Builder(Material.CABINET_DOOR);
        door.panel(-0.16F, 0.58F, 0.281F, 0.16F, 1.10F);

        Builder pipe = new Builder(Material.GALVANIZED);
        // Conduit from the cabinet's hood up to the head, and a strap holding it to the pole.
        pipe.tube(0.0F, 1.14F, 0.16F, 0.0F, POLE_TOP - 0.02F, 0.115F, 0.018F, 8, 2.0F);
        pipe.tube(0.0F, 1.46F, 0.08F, 0.0F, 1.46F, 0.15F, 0.012F, 6, 3.0F);
        pipe.tube(0.0F, 1.84F, 0.08F, 0.0F, 1.84F, 0.14F, 0.012F, 6, 3.0F);

        // The ground wire every pole carries down its back to a rod in the earth.
        Builder wire = new Builder(Material.COPPER);
        wire.tube(0.0F, 0.04F, -0.098F, 0.0F, POLE_TOP - 0.04F, -0.076F, 0.0065F, 5, 1.0F);

        return new SolidMesh[]{wood.build(), concrete.build(), bands.build(), cabinet.build(), door.build(), pipe.build(), wire.build()};
    }

    // ------------------------------------------------------------------------------------------------
    // Shared parts.
    // ------------------------------------------------------------------------------------------------

    private static SolidMesh lamp(float x, float y, float z, float radius) {
        Builder glass = new Builder(Material.LENS);
        glass.at(x, 0.0F, z);
        float[] stations = {y, y + radius * 0.75F, y + radius * 1.35F, y + radius * 1.68F};
        float[] radii = {radius, radius * 0.96F, radius * 0.64F, 0.002F};
        glass.latheY(stations, radii, 12, 4.0F);
        return glass.build();
    }

    /**
     * A round flared bell along +X, from {@code x0} to {@code x1}: outside, inside, a rolled rim and a bird screen.
     *
     * <p>The flare is exponential because a horn's is: it is what the acoustics want, and it is the curve that makes
     * the shape read as a horn rather than a cone. The bore is shaded from black at the throat to the material at the
     * mouth, which no lighting here can do on its own and is most of what makes an opening look deep.
     */
    private static void bell(Builder skin, Builder bore, Builder dark, float x0, float x1, float r0, float r1, int segments, float tiles) {
        int rings = 10;
        float[] stations = new float[rings + 1];
        float[] outer = new float[rings + 1];
        float[] inner = new float[rings + 1];
        float[] depth = new float[rings + 1];
        double k = Math.log(r1 / r0);
        for (int i = 0; i <= rings; i++) {
            float t = i / (float) rings;
            stations[i] = x0 + (x1 - x0) * t;
            float r = (float) (r0 * Math.exp(k * t));
            outer[i] = r;
            inner[i] = r * 0.94F;
            depth[i] = 0.08F + 0.82F * t * t;
        }
        skin.latheX(stations, outer, segments, tiles);
        bore.latheX(stations, inner, segments, tiles, true, depth);
        // The rolled rim.
        float lip = r1 * 0.05F + 0.008F;
        skin.latheX(new float[]{x1 - 0.002F, x1 + lip * 0.5F, x1 + lip, x1 + lip * 0.5F, x1 - 0.004F},
                new float[]{r1 * 0.94F, r1 * 0.97F, r1 + lip * 0.5F, r1 + lip, r1 + lip * 0.3F},
                segments, tiles * 2.0F);
        // A stiffening bead part way out, and the mesh that keeps the birds out of it.
        float beadX = x0 + (x1 - x0) * 0.62F;
        float beadR = (float) (r0 * Math.exp(k * 0.62D));
        skin.latheX(new float[]{beadX - 0.012F, beadX, beadX + 0.012F}, new float[]{beadR * 1.0F, beadR * 1.05F, beadR * 1.0F}, segments, tiles);
        float mesh = r1 * 0.90F;
        dark.tube(x1 - 0.01F, -mesh, 0.0F, x1 - 0.01F, mesh, 0.0F, 0.0055F, 4, 3.0F);
        dark.tube(x1 - 0.01F, 0.0F, -mesh, x1 - 0.01F, 0.0F, mesh, 0.0055F, 4, 3.0F);
    }

    // ------------------------------------------------------------------------------------------------
    // The mechanical siren: a louvred chopper drum, a motor on top, and two opposed bells, all turning together.
    // ------------------------------------------------------------------------------------------------

    private static SolidMesh[] mechanicalFixed() {
        Builder gearbox = new Builder(Material.STEEL_DARK);
        gearbox.latheY(new float[]{HEAD_Y, HEAD_Y + 0.02F, HEAD_Y + 0.075F, HEAD_Y + 0.09F},
                new float[]{0.13F, 0.15F, 0.15F, 0.12F}, 18, 3.0F);
        return new SolidMesh[]{gearbox.build()};
    }

    private static SolidMesh[] mechanicalHead() {
        float base = HEAD_Y + 0.09F;
        float top = base + 0.34F;
        float mid = base + 0.17F;

        Builder drum = new Builder(Material.GALVANIZED);
        drum.latheY(new float[]{base, base + 0.03F, base + 0.05F, top - 0.05F, top - 0.03F, top},
                new float[]{0.15F, 0.215F, 0.215F, 0.215F, 0.215F, 0.15F}, 22, 3.0F);
        drum.discY(top, 0.15F, 22, true, 1.0F);

        // The chopper's ports are what you can actually see working: a band of dark slots round the drum.
        Builder ports = new Builder(Material.LOUVER);
        ports.latheY(new float[]{base + 0.075F, top - 0.075F}, new float[]{0.2175F, 0.2175F}, 22, 2.4F);

        // Motor bell on top with its cooling ribs, and the lifting eye.
        Builder motor = new Builder(Material.STEEL_DARK);
        motor.latheY(new float[]{top, top + 0.015F, top + 0.13F, top + 0.145F, top + 0.16F},
                new float[]{0.10F, 0.125F, 0.125F, 0.10F, 0.03F}, 18, 3.0F);
        for (int i = 0; i < 16; i++) {
            float angle = i * 22.5F;
            motor.resetFrame().turnY(angle);
            motor.box(0.118F, top + 0.02F, -0.006F, 0.139F, top + 0.125F, 0.006F, 3.0F);
        }
        motor.resetFrame();

        Builder skin = new Builder(Material.GALVANIZED);
        Builder bore = new Builder(Material.BORE);
        Builder dark = new Builder(Material.STEEL_DARK);

        // The main bell, tilted up as a real one is so it throws over the rooftops.
        skin.resetFrame().at(0.0F, mid, 0.0F).turnZ(5.0F);
        bore.resetFrame().at(0.0F, mid, 0.0F).turnZ(5.0F);
        dark.resetFrame().at(0.0F, mid, 0.0F).turnZ(5.0F);
        bell(skin, bore, dark, 0.20F, 0.92F, 0.075F, 0.29F, 24, 2.2F);

        // The opposed port: shorter and narrower, carrying the same note the other way.
        skin.resetFrame().at(0.0F, mid, 0.0F).turnY(180.0F).turnZ(5.0F);
        bore.resetFrame().at(0.0F, mid, 0.0F).turnY(180.0F).turnZ(5.0F);
        dark.resetFrame().at(0.0F, mid, 0.0F).turnY(180.0F).turnZ(5.0F);
        bell(skin, bore, dark, 0.20F, 0.66F, 0.07F, 0.20F, 20, 2.2F);

        // Stays from the drum out under each bell, which is what holds that much horn up in a gale.
        dark.resetFrame();
        dark.tube(0.16F, base + 0.01F, 0.0F, 0.54F, mid - 0.07F, 0.0F, 0.011F, 6, 3.0F);
        dark.tube(-0.16F, base + 0.01F, 0.0F, -0.40F, mid - 0.06F, 0.0F, 0.011F, 6, 3.0F);

        return new SolidMesh[]{drum.build(), ports.build(), motor.build(), skin.build(), bore.build(), dark.build()};
    }

    // ------------------------------------------------------------------------------------------------
    // The electronic array: horn cells on all four sides of a mast. Nothing here moves.
    // ------------------------------------------------------------------------------------------------

    private static SolidMesh[] electronic() {
        Builder mast = new Builder(Material.GALVANIZED);
        mast.box(-0.062F, HEAD_Y, -0.062F, 0.062F, 3.26F, 0.062F, 2.0F);
        // Mounting plates between the tiers.
        for (float y : new float[]{HEAD_Y + 0.02F, 2.72F, 3.00F, 3.24F}) {
            mast.box(-0.14F, y, -0.14F, 0.14F, y + 0.018F, 0.14F, 2.0F);
        }

        Builder cells = new Builder(Material.PAINT_WHITE);
        Builder bores = new Builder(Material.BORE);
        Builder frames = new Builder(Material.STEEL_DARK);
        float[] tiers = {2.58F, 2.86F, 3.12F};
        for (float y : tiers) {
            for (int side = 0; side < 4; side++) {
                float yaw = 45.0F + side * 90.0F;
                cells.resetFrame().at(0.0F, y, 0.0F).turnY(yaw).turnZ(-7.0F);
                bores.resetFrame().at(0.0F, y, 0.0F).turnY(yaw).turnZ(-7.0F);
                frames.resetFrame().at(0.0F, y, 0.0F).turnY(yaw).turnZ(-7.0F);
                // Driver can behind the horn, the horn itself, and the frame round its mouth.
                frames.latheX(new float[]{0.05F, 0.075F, 0.13F, 0.15F}, new float[]{0.02F, 0.045F, 0.045F, 0.03F}, 10, 3.0F);
                cells.hornWalls(0.14F, 0.42F, 0.028F, 0.030F, 0.095F, 0.135F, 2.2F);
                bores.hornBore(0.14F, 0.42F, 0.028F, 0.030F, 0.095F, 0.135F);
                frames.box(0.41F, -0.10F, -0.141F, 0.426F, -0.092F, 0.141F, 3.0F);
                frames.box(0.41F, 0.092F, -0.141F, 0.426F, 0.10F, 0.141F, 3.0F);
                frames.box(0.41F, -0.10F, -0.141F, 0.426F, 0.10F, -0.133F, 3.0F);
                frames.box(0.41F, -0.10F, 0.133F, 0.426F, 0.10F, 0.141F, 3.0F);
            }
        }
        frames.resetFrame();

        // Lamp housing on the mast's top.
        Builder cap = new Builder(Material.ALUMINUM);
        cap.latheY(new float[]{3.26F, 3.275F, 3.295F, 3.30F}, new float[]{0.05F, 0.09F, 0.09F, 0.075F}, 14, 3.0F);

        // A solar panel to keep the batteries up, on a bracket off the pole, tilted at the sky.
        Builder panel = new Builder(Material.SOLAR_CELL);
        panel.at(0.0F, 1.80F, -0.36F).turnX(38.0F);
        panel.quad(-0.26F, 0.0F, 0.19F, 0.26F, 0.0F, 0.19F, 0.26F, 0.0F, -0.19F, -0.26F, 0.0F, -0.19F,
                0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F);
        Builder bracket = new Builder(Material.ALUMINUM);
        bracket.at(0.0F, 1.80F, -0.36F).turnX(38.0F);
        bracket.box(-0.27F, -0.022F, -0.20F, 0.27F, -0.001F, 0.20F, 2.0F);
        bracket.resetFrame();
        bracket.tube(0.0F, 1.74F, -0.075F, 0.0F, 1.79F, -0.32F, 0.014F, 6, 3.0F);

        // The Yagi the siren is activated over: a boom and its elements, aimed off down the valley.
        Builder yagi = new Builder(Material.ALUMINUM);
        yagi.tube(0.075F, 2.18F, 0.0F, 0.60F, 2.20F, 0.0F, 0.0085F, 6, 2.0F);
        float[] elements = {0.11F, 0.22F, 0.33F, 0.44F, 0.55F};
        float[] reach = {0.15F, 0.135F, 0.125F, 0.115F, 0.105F};
        for (int i = 0; i < elements.length; i++) {
            float x = elements[i];
            yagi.tube(x, 2.185F, -reach[i], x, 2.185F, reach[i], 0.0045F, 4, 3.0F);
        }

        return new SolidMesh[]{mast.build(), cells.build(), bores.build(), frames.build(), cap.build(), panel.build(), bracket.build(), yagi.build()};
    }

    // ------------------------------------------------------------------------------------------------
    // The two-tone: a big low bell and a small high bell, one above the other, facing opposite ways.
    // ------------------------------------------------------------------------------------------------

    private static SolidMesh[] twoToneFixed() {
        Builder gearbox = new Builder(Material.PAINT_OLIVE);
        gearbox.latheY(new float[]{HEAD_Y, HEAD_Y + 0.02F, HEAD_Y + 0.07F, HEAD_Y + 0.085F},
                new float[]{0.13F, 0.155F, 0.155F, 0.13F}, 18, 3.0F);
        return new SolidMesh[]{gearbox.build()};
    }

    private static SolidMesh[] twoToneHead() {
        float base = HEAD_Y + 0.085F;
        float lowY = base + 0.14F;
        float highY = base + 0.39F;
        float top = base + 0.50F;

        Builder body = new Builder(Material.PAINT_YELLOW);
        // Two stacked chopper housings, the larger for the low note.
        body.latheY(new float[]{base, base + 0.03F, base + 0.26F, base + 0.28F, base + 0.30F, top - 0.02F, top},
                new float[]{0.14F, 0.205F, 0.205F, 0.15F, 0.165F, 0.165F, 0.10F}, 22, 2.6F);
        body.discY(top, 0.10F, 18, true, 1.0F);

        Builder ports = new Builder(Material.LOUVER);
        ports.latheY(new float[]{base + 0.06F, base + 0.23F}, new float[]{0.2075F, 0.2075F}, 22, 2.4F);
        ports.latheY(new float[]{base + 0.32F, top - 0.04F}, new float[]{0.1675F, 0.1675F}, 18, 2.4F);

        Builder motor = new Builder(Material.PAINT_OLIVE);
        motor.latheY(new float[]{top, top + 0.012F, top + 0.11F, top + 0.12F}, new float[]{0.06F, 0.082F, 0.082F, 0.04F}, 14, 3.0F);

        Builder skin = new Builder(Material.PAINT_YELLOW);
        Builder bore = new Builder(Material.BORE);
        Builder dark = new Builder(Material.STEEL_DARK);

        skin.resetFrame().at(0.0F, lowY, 0.0F).turnZ(3.0F);
        bore.resetFrame().at(0.0F, lowY, 0.0F).turnZ(3.0F);
        dark.resetFrame().at(0.0F, lowY, 0.0F).turnZ(3.0F);
        bell(skin, bore, dark, 0.19F, 0.86F, 0.075F, 0.28F, 24, 2.0F);

        skin.resetFrame().at(0.0F, highY, 0.0F).turnY(180.0F).turnZ(3.0F);
        bore.resetFrame().at(0.0F, highY, 0.0F).turnY(180.0F).turnZ(3.0F);
        dark.resetFrame().at(0.0F, highY, 0.0F).turnY(180.0F).turnZ(3.0F);
        bell(skin, bore, dark, 0.15F, 0.62F, 0.06F, 0.17F, 20, 2.0F);

        dark.resetFrame();
        dark.tube(0.15F, base + 0.01F, 0.0F, 0.50F, lowY - 0.08F, 0.0F, 0.011F, 6, 3.0F);

        return new SolidMesh[]{body.build(), ports.build(), motor.build(), skin.build(), bore.build(), dark.build()};
    }
}
