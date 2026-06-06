package com.imbet.mixin;

import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import weather2.PacketNBTFromServer;

@Mixin(value = PacketNBTFromServer.class, remap = false)
public abstract class PacketNBTFromServerMixin {
    @Mutable
    @Shadow
    @Final
    private CompoundTag nbt;

    @Inject(method = "<init>(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("RETURN"))
    private void expandedweather2dynamics$snapshotWeather2Payload(CompoundTag source, CallbackInfo ci) {
        this.nbt = expandedweather2dynamics$safeCopy(source);
    }

    @Unique
    private static CompoundTag expandedweather2dynamics$safeCopy(CompoundTag source) {
        if (source == null) {
            return new CompoundTag();
        }
        try {
            return source.copy();
        } catch (RuntimeException ignored) {
            CompoundTag fallback = new CompoundTag();
            fallback.putString("packetCommand", source.getString("packetCommand"));
            fallback.putString("command", source.getString("command"));
            return fallback;
        }
    }
}
