package com.imbet.mixin;

import com.imbet.client.WeatherDroneClient;
import com.imbet.drone.WeatherDroneEntity;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts the camera on the drone while its pilot is flying it.
 *
 * <p>This has to be a mixin rather than a mod event, and the reason is exact: every event a mod can subscribe
 * to hands over the camera, but the two methods needed to move it - {@code setPosition} and
 * {@code setRotation} - are {@code protected}. An event handler can therefore only adjust the angles of a
 * camera already standing on the player's own eyes, which is the one thing a remote-piloted drone cannot use:
 * the view would be the airframe's attitude applied to the pilot's head, back where the pilot is, with the
 * drone somewhere else entirely.
 *
 * <p>So this injects at the end of {@code Camera.setup}, where vanilla has just finished deciding where the
 * eye is, and hands that decision to the drone. It is registered as a client mixin because a dedicated server
 * has no camera at all.
 *
 * <p><b>Only the eye is moved, never the aim.</b> An earlier version also forced the camera's rotation to the
 * airframe's own yaw and pitch, and that was the bug behind a camera that could not be turned: the drone is
 * an {@code Entity} whose rotation nothing updates, so the view was bolted to whatever yaw the airframe was
 * spawned with while the mouse went on turning the player. The rotation vanilla already set stays, because
 * while piloting the camera is standing on the pilot's own eyes as far as angles are concerned - the look
 * <em>is</em> the control - and the airframe is told where to point by the control packet instead, so the
 * model follows the aim rather than the aim following the model.
 *
 * <p>The roll is deliberately not here either. Vanilla's camera has no roll of its own - the renderer takes
 * it from a viewport event - so the banking is applied there, where it belongs.
 *
 * <p>The accessor is {@code @Shadow} rather than reached through a cast, because a mixin's own class does not
 * actually extend its target at compile time: without the shadow declaration the compiler is looking at a
 * protected method of another package and refusing, which is exactly what it did.
 */
@Mixin(Camera.class)
public abstract class CameraDroneMixin {

    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Inject(method = "setup", at = @At("RETURN"))
    private void expandedweather2dynamics$rideTheDrone(BlockGetter level, Entity entity, boolean detached, boolean mirrored, float partialTick, CallbackInfo callback) {
        if (!WeatherDroneClient.piloting()) {
            return;
        }
        WeatherDroneEntity drone = WeatherDroneClient.pilotedDrone();
        if (drone == null) {
            return;
        }
        // An event handler must never take the frame down with it: a drone removed between the tick and the
        // render is a race, not a bug worth crashing on.
        try {
            Vec3 at = drone.getPosition(partialTick).add(0.0D, 0.22D, 0.0D);
            setPosition(at);
        } catch (Throwable ignored) {
        }
    }
}
