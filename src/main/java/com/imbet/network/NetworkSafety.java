package com.imbet.network;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class NetworkSafety {
    private NetworkSafety() {
    }

    public static void sendToPlayer(ServerPlayer player, Object payload) {
        ModNetworking.sendToPlayer(player, payload);
    }

    public static void sendToPlayersNear(ServerLevel level, double x, double y, double z, double range, Object payload) {
        ModNetworking.sendToPlayersNear(level, x, y, z, range, payload);
    }
}
