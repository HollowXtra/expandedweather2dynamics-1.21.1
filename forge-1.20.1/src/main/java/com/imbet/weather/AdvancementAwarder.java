package com.imbet.weather;

import com.imbet.Config;
import com.imbet.ExpandedWeather2Dynamics;
import net.minecraft.advancements.Advancement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public final class AdvancementAwarder {
    public static final String USE_RADAR = "use_radar";
    public static final String USE_ANEMOMETER = "use_anemometer";
    public static final String DETECT_TORNADO = "detect_tornado";
    public static final String MULTIVORTEX = "multivortex";
    public static final String CYCLOIDAL_MARKS = "cycloidal_marks";
    public static final String DELAYED_THUNDER = "delayed_thunder";
    public static final String REINTENSIFICATION = "reintensification";

    private AdvancementAwarder() {
    }

    public static void award(ServerPlayer player, String path) {
        if (!Config.ADVANCEMENTS_ENABLED.get()) {
            return;
        }
        Advancement advancement = player.server.getAdvancements().getAdvancement(new ResourceLocation(ExpandedWeather2Dynamics.MODID, path));
        if (advancement != null) {
            player.getAdvancements().award(advancement, "trigger");
        }
    }
}
