package com.imbet.network;

import com.imbet.ExpandedWeather2Dynamics;
import com.imbet.survey.DamageSurveyResult;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record DamageSurveyResultPayload(DamageSurveyResult result) implements CustomPacketPayload {
    public static final Type<DamageSurveyResultPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "damage_survey_result"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DamageSurveyResultPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> (payload.result() == null ? DamageSurveyResult.NONE : payload.result()).write(buf),
            buf -> new DamageSurveyResultPayload(DamageSurveyResult.read(buf))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
