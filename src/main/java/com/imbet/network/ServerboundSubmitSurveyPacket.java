package com.imbet.network;

import com.imbet.Config;
import com.imbet.ExpandedWeather2Dynamics;
import com.imbet.survey.ChaserSurveySavedData;
import com.imbet.survey.DamageIndicator;
import com.imbet.survey.TornadoSurveySavedData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

public record ServerboundSubmitSurveyPacket(String trackId, BlockPos surveyPos, int ratedEf, int suggestedMph, List<DamageIndicator> indicators) implements CustomPacketPayload {
    public static final Type<ServerboundSubmitSurveyPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "submit_survey"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerboundSubmitSurveyPacket> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> payload.write(buf),
            ServerboundSubmitSurveyPacket::read
    );

    public ServerboundSubmitSurveyPacket {
        trackId = trackId == null ? "" : trackId.substring(0, Math.min(32, trackId.length()));
        surveyPos = surveyPos == null ? BlockPos.ZERO : surveyPos;
        ratedEf = Mth.clamp(ratedEf, 0, 5);
        suggestedMph = Math.max(0, suggestedMph);
        indicators = copyIndicators(indicators);
    }

    public static void handle(ServerboundSubmitSurveyPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) {
                return;
            }
            payload.submit(player, level);
        });
    }

    private void submit(ServerPlayer player, ServerLevel level) {
        if (!Config.SURVEYS_ENABLED.get() || trackId.isBlank() || trackId.equals("NO TRACK")) {
            player.sendSystemMessage(Component.literal("Survey submission rejected: no recent tornado track is selected.").withStyle(ChatFormatting.GRAY));
            return;
        }
        TornadoSurveySavedData.TornadoTrack track = TornadoSurveySavedData.get(level).trackByDisplayId(trackId);
        if (track == null) {
            player.sendSystemMessage(Component.literal("Survey submission rejected: track " + trackId + " is no longer available.").withStyle(ChatFormatting.GRAY));
            return;
        }
        Vec3 surveyCenter = Vec3.atCenterOf(surveyPos);
        double maxDistance = Math.max(Config.SURVEY_RADIUS_BLOCKS.get() * 2.0D, track.recentMaxWidthBlocks(level.getGameTime() - Config.SURVEY_RECENT_TRACK_WINDOW_TICKS.get()) + 96.0D);
        if (player.position().distanceTo(surveyCenter) > 128.0D || track.maxWindNear(surveyCenter, maxDistance) <= 0.0F) {
            player.sendSystemMessage(Component.literal("Survey submission rejected: stand near the surveyed damage path.").withStyle(ChatFormatting.GRAY));
            return;
        }
        ChaserSurveySavedData chaserData = ChaserSurveySavedData.get(level);
        if (chaserData.alreadySubmitted(player, trackId)) {
            player.sendSystemMessage(Component.literal("Survey submission ignored: you already filed a rating for " + trackId + ".").withStyle(ChatFormatting.GRAY));
            return;
        }
        int trueEf = efTierFor(track.maxWindNear(surveyCenter, maxDistance));
        ChaserSurveySavedData.Entry entry = chaserData.record(player, trackId, ratedEf, trueEf, suggestedMph, indicators, level.getGameTime());
        ChatFormatting color = entry.accuracy() >= 90 ? ChatFormatting.GREEN : entry.accuracy() >= 70 ? ChatFormatting.YELLOW : ChatFormatting.RED;
        player.sendSystemMessage(Component.literal("Survey filed for " + trackId + ": rated EF" + ratedEf + ", true profile EF" + trueEf + ", accuracy " + entry.accuracy() + "%.")
                .withStyle(color));
        if (!entry.badge().isBlank()) {
            player.sendSystemMessage(Component.literal("Chaser badge: " + entry.badge()).withStyle(ChatFormatting.AQUA));
        }
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(trackId, 32);
        buf.writeBlockPos(surveyPos);
        buf.writeVarInt(ratedEf);
        buf.writeVarInt(suggestedMph);
        buf.writeVarInt(indicators.size());
        for (DamageIndicator indicator : indicators) {
            buf.writeEnum(indicator);
        }
    }

    private static ServerboundSubmitSurveyPacket read(RegistryFriendlyByteBuf buf) {
        String trackId = buf.readUtf(32);
        BlockPos pos = buf.readBlockPos();
        int ratedEf = buf.readVarInt();
        int suggestedMph = buf.readVarInt();
        int count = Math.min(32, buf.readVarInt());
        List<DamageIndicator> indicators = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            indicators.add(buf.readEnum(DamageIndicator.class));
        }
        return new ServerboundSubmitSurveyPacket(trackId, pos, ratedEf, suggestedMph, indicators);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static List<DamageIndicator> copyIndicators(List<DamageIndicator> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return source.stream()
                .filter(indicator -> indicator != null)
                .distinct()
                .limit(32)
                .toList();
    }

    private static int efTierFor(double mph) {
        if (mph > 200.0D) {
            return 5;
        }
        if (mph >= 166.0D) {
            return 4;
        }
        if (mph >= 136.0D) {
            return 3;
        }
        if (mph >= 111.0D) {
            return 2;
        }
        if (mph >= 86.0D) {
            return 1;
        }
        return 0;
    }
}
