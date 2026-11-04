package com.imbet.client;

import com.imbet.instruments.BarometerBlockEntity;
import com.imbet.weather.WeatherInstruments;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

import java.util.Locale;

/**
 * The barometer's readout.
 *
 * <p>Laid out the way a barometer is actually read: the absolute number is present but not the point,
 * so it is broken into its altitude base and the weather deficit subtracted from it, and the tendency
 * gets the loud colour. A needle falling at several hPa per hour is the most useful thing this block
 * says, so it is the thing drawn largest.
 */
public class BarometerPanelRenderer implements BlockEntityRenderer<BarometerBlockEntity> {
    private static final int ACCENT = 0xFF7DD3FC;
    private static final int RISING = 0xFF4CAF50;
    private static final int STEADY = 0xFF90A4AE;
    private static final int FALLING = 0xFFFFB300;
    private static final int RAPID = 0xFFFF5252;

    public BarometerPanelRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(BarometerBlockEntity barometer, float partialTick, PoseStack stack, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        VertexConsumer consumer = PanelRenderPath.consumer(buffer, InstrumentPanelShader.panel(), packedLight);
        PoseStack.Pose pose = stack.last();
        InstrumentPanel.plate(consumer, pose, ACCENT);

        double deficit = barometer.lowDeficitHpa() + barometer.coreDeficitHpa();
        InstrumentPanel.bar(consumer, pose, InstrumentPanel.ROWS[3], (float) Math.min(1.0D, deficit / 120.0D),
                barometer.coreDeficitHpa() > 0.5D ? RAPID : ACCENT);

        InstrumentPanel.text(stack, buffer, packedLight, 0, "BAROMETER", ACCENT);
        InstrumentPanel.text(stack, buffer, packedLight, 1, String.format(Locale.ROOT, "%.1f hPa", barometer.hpa()), 0xFFFFFFFF, 1.5F);
        InstrumentPanel.text(stack, buffer, packedLight, 2, barometer.tendencyLabel(), tendencyColor(barometer));
        InstrumentPanel.text(stack, buffer, packedLight, 4,
                String.format(Locale.ROOT, "%+.1f hPa / HOUR", barometer.rateHpaPerHour()), 0xFFB0BEC5);
        InstrumentPanel.text(stack, buffer, packedLight, 5, lowLine(barometer), 0xFFCFD8DC);
        InstrumentPanel.text(stack, buffer, packedLight, 6, stormLine(barometer), 0xFF90A4AE);
    }

    private static int tendencyColor(BarometerBlockEntity barometer) {
        if (barometer.rapidlyFalling()) {
            return RAPID;
        }
        if (barometer.falling()) {
            return FALLING;
        }
        return "RISING".equals(barometer.tendencyLabel()) ? RISING : STEADY;
    }

    private static String lowLine(BarometerBlockEntity barometer) {
        if (barometer.lowDistance() < 0.0D) {
            return "NO ORGANIZED LOW";
        }
        String label = barometer.lowLabel().isBlank() ? "LOW" : barometer.lowLabel();
        return label.toUpperCase(Locale.ROOT) + " " + Math.round(barometer.lowDistance()) + " BLK";
    }

    private static String stormLine(BarometerBlockEntity barometer) {
        if (barometer.stage() < 0) {
            return "NO STORM IN RANGE";
        }
        return WeatherInstruments.stageLabel(barometer.stage()) + " "
                + barometer.stormLabel().toUpperCase(Locale.ROOT);
    }
}
