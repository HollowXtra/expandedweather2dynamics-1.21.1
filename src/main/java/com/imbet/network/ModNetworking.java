package com.imbet.network;

import com.imbet.ExpandedWeather2Dynamics;
import com.imbet.radar.ClientRadarStore;
import com.imbet.survey.ClientDamageSurveyStore;
import com.imbet.client.ClientStructureFlight;
import com.imbet.weather.ClientAddonWeatherEventStore;
import com.imbet.weather.ClientAlertStore;
import com.imbet.weather.ClientForecastStore;
import com.imbet.weather.ClientLowPressureStore;
import com.imbet.weather.ClientStormStateStore;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class ModNetworking {
    private ModNetworking() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar(ExpandedWeather2Dynamics.MODID)
                .versioned("1")
                .optional()
                .playToClient(RadarSnapshotPayload.TYPE, RadarSnapshotPayload.STREAM_CODEC, (payload, context) ->
                        context.enqueueWork(() -> ClientRadarStore.accept(payload)))
                .playToClient(AddonWeatherEventSyncPayload.TYPE, AddonWeatherEventSyncPayload.STREAM_CODEC, (payload, context) ->
                        context.enqueueWork(() -> ClientAddonWeatherEventStore.accept(payload)))
                .playToClient(StormStateSyncPayload.TYPE, StormStateSyncPayload.STREAM_CODEC, (payload, context) ->
                        context.enqueueWork(() -> ClientStormStateStore.accept(payload)))
                .playToClient(DamageSurveyResultPayload.TYPE, DamageSurveyResultPayload.STREAM_CODEC, (payload, context) ->
                        context.enqueueWork(() -> ClientDamageSurveyStore.accept(payload)))
                .playToClient(LowPressureSyncPayload.TYPE, LowPressureSyncPayload.STREAM_CODEC, (payload, context) ->
                        context.enqueueWork(() -> ClientLowPressureStore.accept(payload)))
                .playToClient(ForecastSnapshotPayload.TYPE, ForecastSnapshotPayload.STREAM_CODEC, (payload, context) ->
                        context.enqueueWork(() -> ClientForecastStore.accept(payload)))
                .playToClient(AlertBannerPayload.TYPE, AlertBannerPayload.STREAM_CODEC, (payload, context) ->
                        context.enqueueWork(() -> ClientAlertStore.accept(payload)))
                .playToClient(StructureFlightPayload.TYPE, StructureFlightPayload.STREAM_CODEC, (payload, context) ->
                        context.enqueueWork(() -> ClientStructureFlight.accept(payload)))
                .playToServer(ServerboundSubmitSurveyPacket.TYPE, ServerboundSubmitSurveyPacket.STREAM_CODEC, ServerboundSubmitSurveyPacket::handle);
    }
}
