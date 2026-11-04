package com.imbet.client;

import com.imbet.instruments.WeatherRadioBlockEntity;
import com.imbet.weather.AlertLevel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

import java.util.Locale;

/**
 * The radio's face: the product in force over its own position, drawn in the product's own colour.
 *
 * <p>It mirrors the alert banner's logic rather than inventing a second look - the product code is the
 * loudest thing on the plate and the level colour drives the accent, so a TOR on a radio matches the
 * TOR on the banner and on the siren. When nothing is in force the panel says so in grey instead of
 * going blank, so a silent radio is distinguishable from a broken one.
 */
public class WeatherRadioPanelRenderer implements BlockEntityRenderer<WeatherRadioBlockEntity> {
    private static final int IDLE = 0xFF607D8B;
    private static final int IDLE_TEXT = 0xFF90A4AE;

    public WeatherRadioPanelRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(WeatherRadioBlockEntity radio, float partialTick, PoseStack stack, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        VertexConsumer consumer = PanelRenderPath.consumer(buffer, InstrumentPanelShader.panel(), packedLight);
        PoseStack.Pose pose = stack.last();

        AlertLevel level = radio.currentLevel();
        boolean active = level != AlertLevel.NONE;
        int accent = active ? level.color() : IDLE;
        InstrumentPanel.plate(consumer, pose, accent);

        // A strip under the header in the product's colour, so the severity is legible at a distance.
        InstrumentPanel.bar(consumer, pose, InstrumentPanel.ROWS[3],
                active ? 1.0F : 0.0F, accent);

        InstrumentPanel.text(stack, buffer, packedLight, 0, "WEATHER RADIO", accent);
        InstrumentPanel.text(stack, buffer, packedLight, 1,
                active ? radio.currentProductCode() : "NO PRODUCT",
                active ? accent : IDLE_TEXT, 1.5F);
        InstrumentPanel.text(stack, buffer, packedLight, 2,
                active ? level.label().toUpperCase(Locale.ROOT) : "NOTHING IN FORCE FOR THIS SPOT",
                active ? 0xFFFFFFFF : IDLE_TEXT);
        InstrumentPanel.text(stack, buffer, packedLight, 4, detailLine(radio, active), 0xFFCFD8DC);
        InstrumentPanel.text(stack, buffer, packedLight, 5, "CHANNEL: " + channelName(radio), IDLE_TEXT);
        InstrumentPanel.text(stack, buffer, packedLight, 6, "SNEAK TO CHANGE", 0xFF546E7A);
    }

    private static String detailLine(WeatherRadioBlockEntity radio, boolean active) {
        if (!active) {
            return "LISTENING";
        }
        return (radio.currentHeadline() + " " + radio.currentSource()).toUpperCase(Locale.ROOT);
    }

    /** Short channel names, because the panel plate only fits a handful of characters a row. */
    private static String channelName(WeatherRadioBlockEntity radio) {
        return switch (radio.getMode()) {
            case 1 -> "WARN";
            case 2 -> "MUTED";
            default -> "ALL";
        };
    }
}
