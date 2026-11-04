package com.imbet.client;

import com.imbet.instruments.RainGaugeBlockEntity;
import com.imbet.weather.WeatherInstruments;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

import java.util.Locale;

/**
 * The rain gauge's readout: the collected total, the rate it is coming down at, and how long it has
 * been dry. The bar is scaled to the heaviest rate the model can produce, so a full bar means the
 * gauge is sitting under a torrential core.
 */
public class RainGaugePanelRenderer implements BlockEntityRenderer<RainGaugeBlockEntity> {
    private static final int ACCENT = 0xFF64B5F6;
    private static final int RAINING = 0xFF29B6F6;
    private static final int DRY = 0xFF90A4AE;
    private static final double MAX_RATE_FOR_BAR = 55.0D;

    public RainGaugePanelRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(RainGaugeBlockEntity gauge, float partialTick, PoseStack stack, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        VertexConsumer consumer = PanelRenderPath.consumer(buffer, InstrumentPanelShader.panel(), packedLight);
        PoseStack.Pose pose = stack.last();
        InstrumentPanel.plate(consumer, pose, ACCENT);

        boolean raining = gauge.raining();
        InstrumentPanel.bar(consumer, pose, InstrumentPanel.ROWS[3],
                (float) Math.min(1.0D, gauge.rateMmPerHour() / MAX_RATE_FOR_BAR),
                raining ? RAINING : DRY);

        InstrumentPanel.text(stack, buffer, packedLight, 0, "RAIN GAUGE", ACCENT);
        InstrumentPanel.text(stack, buffer, packedLight, 1,
                String.format(Locale.ROOT, "%.1f mm", gauge.accumulatedMm()), 0xFFFFFFFF, 1.5F);
        InstrumentPanel.text(stack, buffer, packedLight, 2,
                raining ? String.format(Locale.ROOT, "FALLING %.1f mm/h", gauge.rateMmPerHour()) : "NO RAIN LANDING",
                raining ? RAINING : DRY);
        InstrumentPanel.text(stack, buffer, packedLight, 4, dryLine(gauge), 0xFFCFD8DC);
        InstrumentPanel.text(stack, buffer, packedLight, 5, stormLine(gauge), 0xFF90A4AE);
        InstrumentPanel.text(stack, buffer, packedLight, 6, "R-CLICK RESET", 0xFF607D8B);
    }

    private static String dryLine(RainGaugeBlockEntity gauge) {
        if (gauge.raining()) {
            return "COLLECTING NOW";
        }
        double dryHours = gauge.dryHours(Minecraft.getInstance().level);
        if (dryHours < 0.0D) {
            return "NEVER RAINED HERE";
        }
        return String.format(Locale.ROOT, "DRY FOR %.1f H", dryHours);
    }

    private static String stormLine(RainGaugeBlockEntity gauge) {
        if (gauge.stage() < 0) {
            return "NO STORM IN RANGE";
        }
        return WeatherInstruments.stageLabel(gauge.stage()) + " "
                + Math.round(gauge.stormDistance()) + " BLK";
    }
}
