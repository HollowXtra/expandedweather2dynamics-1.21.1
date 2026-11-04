package com.imbet.client;

import com.imbet.Config;
import com.imbet.drone.WeatherDroneEntity;
import com.imbet.drone.WeatherDroneItem;
import com.imbet.network.DroneControlPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.Input;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * What a player sees and does while flying a drone.
 *
 * <p>Three jobs, and each of them is a thing the server cannot do for itself:
 *
 * <ul>
 *   <li><b>The camera rides the drone but keeps the pilot's aim.</b> The pilot stays where they stand, so
 *       their own eye is useless while flying; the camera is moved to the airframe. Its angles are left
 *       alone, so looking around turns the view and the drone follows it - the airframe's own heading is set
 *       from the look that goes up in the control packet.</li>
 *   <li><b>The keys go up.</b> Held movement keys are packed into one byte and sent every tick.</li>
 *   <li><b>The readout is drawn.</b> Battery, link state, the wind the airframe is in and its peak, so the
 *       pilot can tell a storm that is merely windy from one that is about to take the drone.</li>
 * </ul>
 *
 * <p>Deliberately no new keybinds: the drone uses the movement keys and the mouse, because a pilot should not
 * have to learn a second control scheme to fly a camera. Sneaking is the one exception - it is left alone, so
 * a pilot can still drop off a ledge normally the moment they stop flying.
 */
public final class WeatherDroneClient {
    /** How long the readout stays up after the last frame it was needed, in ticks. */
    private static final int READOUT_LINGER_TICKS = 10;

    private static WeatherDroneEntity piloted;
    private static int readoutTicks;

    private WeatherDroneClient() {
    }

    /** The drone the local player is flying, or null. */
    public static WeatherDroneEntity pilotedDrone() {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return null;
        }
        if (piloted != null && piloted.isAlive() && piloted.isPilotedBy(player)) {
            return piloted;
        }
        // Resolved by identity rather than by distance, so two players in one area cannot end up flying each
        // other's drone.
        piloted = null;
        for (WeatherDroneEntity drone : minecraft.level.getEntitiesOfClass(WeatherDroneEntity.class,
                player.getBoundingBox().inflate(512.0D), entity -> entity.isPilotedBy(player))) {
            piloted = drone;
            break;
        }
        return piloted;
    }

    /** True while the camera should be riding a drone. */
    public static boolean piloting() {
        return pilotedDrone() != null;
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        // The rotor loops are kept in step here rather than by the airframes themselves: only the client knows
        // which drones it can hear, and a sound instance has to be driven by whoever is playing it.
        WeatherDroneSound.schedule();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        WeatherDroneEntity drone = pilotedDrone();
        if (drone == null) {
            readoutTicks = 0;
            return;
        }
        readoutTicks = READOUT_LINGER_TICKS;
        // The pilot's look rides up with the keys. It is not how the drone is steered - the server reads the
        // pilot's own rotation for that, which vanilla syncs anyway - it is how the airframe is told which way
        // to point, so the model faces where its pilot is looking on every client and not only on the pilot's.
        PacketDistributor.sendToServer(new DroneControlPayload(controlFlags(minecraft),
                minecraft.player.getYRot(), minecraft.player.getXRot()));
        // Switching away from the drone item drops the link, and the server does that (see the entity), so
        // this only lets go locally: a client that told the server about it would be pushing state it does not
        // own, and the two would disagree for a tick. The hand rule is the entity's own - either hand carries
        // the link - because the rule is about the player still having the transmitter, not about which hand
        // the mouse happens to be on.
        if (!holdsDroneItem(minecraft.player)) {
            piloted = null;
        }
    }

    /** True while the player still has the link in hand, in either hand, exactly as the entity reads it. */
    private static boolean holdsDroneItem(Player player) {
        return player.getMainHandItem().getItem() instanceof WeatherDroneItem
                || player.getOffhandItem().getItem() instanceof WeatherDroneItem;
    }

    /**
     * Takes the movement keys off the pilot's own feet while the link is up.
     *
     * <p>This is the honest half of the freeze, and where it happens is the whole argument. Zeroing the
     * player's velocity after their tick would be a fight with physics: it cancels a fall, a push and a
     * tornado's lift along with the walking. Vanilla reads the keys into an {@code Input} and then copies
     * those impulses into the body's movement, and this event fires between those two steps - so the body
     * simply stops being driven while everything the world does to it still applies. A pilot can be picked up
     * by the storm their drone is circling, which is the scene the drone exists for.
     *
     * <p>Jump and sneak go with the walk, because they are the same keys: while the link is up they mean
     * climb and descend, and a pilot hopping or crouching in place while their camera is a hundred blocks
     * away would only be a way to fall off something.
     */
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (!Config.DRONE_FREEZE_PILOT.get() || !piloting()) {
            return;
        }
        Input input = event.getInput();
        input.forwardImpulse = 0.0F;
        input.leftImpulse = 0.0F;
        input.jumping = false;
        input.shiftKeyDown = false;
    }

    /** Held movement keys, packed into the one byte the payload carries. */
    private static byte controlFlags(Minecraft minecraft) {
        byte flags = 0;
        if (minecraft.options.keyUp.isDown()) {
            flags |= WeatherDroneEntity.FLAG_FORWARD;
        }
        if (minecraft.options.keyDown.isDown()) {
            flags |= WeatherDroneEntity.FLAG_BACK;
        }
        if (minecraft.options.keyLeft.isDown()) {
            flags |= WeatherDroneEntity.FLAG_LEFT;
        }
        if (minecraft.options.keyRight.isDown()) {
            flags |= WeatherDroneEntity.FLAG_RIGHT;
        }
        if (minecraft.options.keyJump.isDown()) {
            flags |= WeatherDroneEntity.FLAG_UP;
        }
        if (minecraft.options.keyShift.isDown()) {
            flags |= WeatherDroneEntity.FLAG_DOWN;
        }
        return flags;
    }

    /**
     * Banks the view with the airframe.
     *
     * <p>Only the roll is set here, and the aim is deliberately left exactly as the mouse put it. Where the
     * eye <em>is</em> comes from {@code CameraDroneMixin}, because vanilla's camera exposes that to a mixin and
     * not to an event - but the direction it faces is the pilot's own, so the mouse keeps working while the
     * link is up and the drone turns to follow it. The roll is the opposite way round: vanilla's camera has no
     * roll field at all and the renderer takes one from this event, so banking is the half of the camera that
     * genuinely belongs to a viewport handler.
     */
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        WeatherDroneEntity drone = pilotedDrone();
        if (drone == null) {
            return;
        }
        event.setRoll(bankOf(drone, event.getPartialTick()));
    }

    /** How far the airframe is rolled over, in degrees, taken from how hard it is turning. */
    private static float bankOf(WeatherDroneEntity drone, double partial) {
        Vec3 velocity = drone.getDeltaMovement();
        double side = velocity.x * Math.cos(Math.toRadians(drone.getYRot())) + velocity.z * Math.sin(Math.toRadians(drone.getYRot()));
        return (float) Mth.clamp(-side * 55.0D, -32.0D, 32.0D);
    }

    /** The pilot's own body is not drawn while they are flying: the camera is somewhere else entirely. */
    public static void onRenderGui(RenderGuiEvent.Post event) {
        WeatherDroneEntity drone = pilotedDrone();
        if (drone == null && readoutTicks <= 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.options.hideGui) {
            return;
        }
        if (readoutTicks > 0 && drone == null) {
            readoutTicks--;
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        int width = graphics.guiWidth();
        int y = 8;
        int x = width / 2 - 92;
        drawLine(graphics, x, y, "DRONE  " + drone.statusLine(), drone.overwhelmed() ? ChatFormatting.RED : ChatFormatting.AQUA);
        y += 11;
        drawLine(graphics, x, y, String.format(java.util.Locale.ROOT, "WIND %.0f MPH   PEAK %.0f MPH",
                currentWindMph(drone), drone.peakWindMph()), ChatFormatting.GRAY);
        y += 11;
        if (!Double.isNaN(drone.minPressureHpa())) {
            drawLine(graphics, x, y, String.format(java.util.Locale.ROOT, "LOWEST %.1f hPa   ALOFT %.0f s",
                    drone.minPressureHpa(), drone.secondsAloft()), ChatFormatting.GRAY);
            y += 11;
        }
        drawLine(graphics, x, y, "WASD fly, SPACE up, SHIFT down, look to steer", ChatFormatting.DARK_GRAY);
    }

    private static double currentWindMph(WeatherDroneEntity drone) {
        var sample = com.imbet.weather.WeatherInstruments.wind(drone.level(), drone.position());
        return sample == null ? 0.0D : sample.speedMph();
    }

    private static void drawLine(GuiGraphics graphics, int x, int y, String text, ChatFormatting colour) {
        graphics.drawString(Minecraft.getInstance().font, Component.literal(text).withStyle(colour), x, y, 0xFFFFFF);
    }
}
