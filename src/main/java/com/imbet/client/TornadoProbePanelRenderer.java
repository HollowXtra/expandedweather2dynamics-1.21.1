package com.imbet.client;

import com.imbet.instruments.ProbeUpgrades;
import com.imbet.instruments.TornadoProbeBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

import java.util.Locale;

/**
 * The tornado probe's readout, laid out like the instrument pack it represents.
 *
 * <p>The state line takes the loud colour, because whether a probe is still waiting, recording, or
 * overrun is what you look at first when you walk back to it. The pressure row carries the drop as
 * well as the absolute value, since the drop is the number the probe was deployed to get.
 */
public class TornadoProbePanelRenderer implements BlockEntityRenderer<TornadoProbeBlockEntity> {
    private static final int ACCENT = 0xFFFF8A65;
    private static final int WAITING = 0xFF90A4AE;
    private static final int RECORDING = 0xFFFF5252;
    private static final int READY = 0xFF4CAF50;
    private static final int LOST = 0xFFB71C1C;
    /** A probe's bar is scaled to the speed that destroys it, so a full bar means it nearly died. */
    private static final double MAX_WIND_FOR_BAR = 300.0D;

    public TornadoProbePanelRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(TornadoProbeBlockEntity probe, float partialTick, PoseStack stack, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        VertexConsumer consumer = PanelRenderPath.consumer(buffer, InstrumentPanelShader.panel(), packedLight);
        PoseStack.Pose pose = stack.last();
        InstrumentPanel.plate(consumer, pose, ACCENT);
        InstrumentPanel.bar(consumer, pose, InstrumentPanel.ROWS[4],
                (float) Math.min(1.0D, probe.peakWindMph() / MAX_WIND_FOR_BAR), stateColor(probe));

        InstrumentPanel.text(stack, buffer, packedLight, 0, header(probe), ACCENT);
        InstrumentPanel.text(stack, buffer, packedLight, 1, probe.stateLabel(), stateColor(probe));
        InstrumentPanel.text(stack, buffer, packedLight, 2,
                String.format(Locale.ROOT, "%.0f MPH", probe.peakWindMph()), 0xFFFFFFFF, 1.5F);
        InstrumentPanel.text(stack, buffer, packedLight, 3, pressureLine(probe), 0xFFCFD8DC);
        InstrumentPanel.text(stack, buffer, packedLight, 5, String.format(Locale.ROOT,
                "DEBRIS %.0f IMP/S", probe.peakDebrisPerSecond()), 0xFFB0BEC5);
        InstrumentPanel.text(stack, buffer, packedLight, 6, footer(probe), 0xFF90A4AE);
    }

    /**
     * The header, with the fitted kit as a suffix when there is one.
     *
     * <p>The badge is deliberately terse and the unfitted header is left alone: the plate is a little under
     * a block wide and the row already uses thirteen characters of it, so "+R+S3" is about all the room a
     * fitted probe has. A longer badge would push the header off the plate on exactly the probes that have
     * the most to say.
     */
    private static String header(TornadoProbeBlockEntity probe) {
        ProbeUpgrades upgrades = probe.upgrades();
        if (upgrades.bare()) {
            return "TORNADO PROBE";
        }
        StringBuilder builder = new StringBuilder("PROBE");
        if (upgrades.housing() > 0) {
            builder.append(" +R");
        }
        if (upgrades.sensorTier() > 0) {
            builder.append(" +S").append(upgrades.sensorTier());
        }
        return builder.toString();
    }

    private static int stateColor(TornadoProbeBlockEntity probe) {
        return switch (probe.state()) {
            case TornadoProbeBlockEntity.STATE_RECORDING -> RECORDING;
            case TornadoProbeBlockEntity.STATE_RECORDED -> READY;
            case TornadoProbeBlockEntity.STATE_LOST -> LOST;
            default -> WAITING;
        };
    }

    private static String pressureLine(TornadoProbeBlockEntity probe) {
        if (Double.isNaN(probe.minPressureHpa())) {
            return "NO PRESSURE YET";
        }
        return String.format(Locale.ROOT, "MIN %.0f  DROP %.0f", probe.minPressureHpa(), probe.pressureDropHpa());
    }

    private static String footer(TornadoProbeBlockEntity probe) {
        if (!probe.hasReading()) {
            return "CLICK TO RE-ARM";
        }
        if (probe.closestApproachBlocks() < 0.0D) {
            return "NO CLOSE PASS";
        }
        return String.format(Locale.ROOT, "CLOSE %.0f BLK", probe.closestApproachBlocks());
    }
}
