package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;

public final class CompatHooks {
    private static final boolean VALKYRIEN_SKIES_LOADED = ModList.get().isLoaded("valkyrienskies")
            || ModList.get().isLoaded("vs_core");
    private static final boolean SIMPLE_BLOCK_PHYSICS_LOADED = ModList.get().isLoaded("simple_block_physics");
    private static final boolean IMMERSIVE_VEHICLES_LOADED = ModList.get().isLoaded("mts");
    private static final boolean IMMERSIVE_AIRCRAFT_LOADED = ModList.get().isLoaded("immersive_aircraft");
    private static final boolean TREE_PHYSICS_LOADED = ModList.get().isLoaded("treephysics")
            || ModList.get().isLoaded("tree_physics");
    private static Method vsShipManagingPosMethod;
    private static boolean vsReflectionResolved;

    private CompatHooks() {
    }

    public static boolean isValkyrienBlockInteractionUnsafe(Level level, BlockPos pos, BlockState state) {
        if (!VALKYRIEN_SKIES_LOADED || Config.DAMAGE_AFFECT_VALKYRIEN_SHIPS.get()) {
            return false;
        }
        if (level == null || pos == null) {
            return false;
        }
        ResourceLocation dimension = level.dimension().location();
        String dimensionText = dimension.toString().toLowerCase(java.util.Locale.ROOT);
        if (dimensionText.contains("valkyrienskies") || dimensionText.contains("shipyard")) {
            return true;
        }
        if (isValkyrienClass(state == null ? null : state.getBlock().getClass())) {
            return true;
        }
        try {
            BlockEntity blockEntity = level.isLoaded(pos) ? level.getBlockEntity(pos) : null;
            if (blockEntity != null && isValkyrienClass(blockEntity.getClass())) {
                return true;
            }
        } catch (RuntimeException ignored) {
            return true;
        }
        return isManagedByValkyrienShip(level, pos);
    }

    public static boolean shaderPackLoaded() {
        return ModList.get().isLoaded("oculus") || ModList.get().isLoaded("iris");
    }

    public static boolean simpleBlockPhysicsLoaded() {
        return SIMPLE_BLOCK_PHYSICS_LOADED;
    }

    public static boolean immersiveVehiclesLoaded() {
        return IMMERSIVE_VEHICLES_LOADED;
    }

    public static boolean immersiveAircraftLoaded() {
        return IMMERSIVE_AIRCRAFT_LOADED;
    }

    public static boolean treePhysicsLoaded() {
        return TREE_PHYSICS_LOADED;
    }

    public static boolean useVanillaBlockLifting() {
        return !SIMPLE_BLOCK_PHYSICS_LOADED;
    }

    private static boolean isValkyrienClass(Class<?> type) {
        if (type == null || type.getName() == null) {
            return false;
        }
        String name = type.getName().toLowerCase(java.util.Locale.ROOT);
        return name.contains("valkyrienskies") || name.contains("vs_core");
    }

    private static boolean isManagedByValkyrienShip(Level level, BlockPos pos) {
        Method method = resolveVsShipManagingPosMethod();
        if (method == null) {
            return false;
        }
        try {
            Object result = method.invoke(null, level, pos);
            return result != null;
        } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
            return true;
        }
    }

    private static Method resolveVsShipManagingPosMethod() {
        if (vsReflectionResolved) {
            return vsShipManagingPosMethod;
        }
        vsReflectionResolved = true;
        String[] classes = {
                "org.valkyrienskies.mod.common.VSGameUtilsKt",
                "org.valkyrienskies.mod.common.util.VSGameUtilsKt"
        };
        for (String className : classes) {
            try {
                Class<?> type = Class.forName(className);
                for (Method method : type.getMethods()) {
                    if (!method.getName().equals("getShipObjectManagingPos") || method.getParameterCount() < 2) {
                        continue;
                    }
                    Class<?>[] parameters = method.getParameterTypes();
                    boolean levelCompatible = parameters[0].isAssignableFrom(Level.class) || Level.class.isAssignableFrom(parameters[0]);
                    if (levelCompatible && parameters[1].isAssignableFrom(BlockPos.class)) {
                        vsShipManagingPosMethod = method;
                        return vsShipManagingPosMethod;
                    }
                }
            } catch (ClassNotFoundException ignored) {
                // Optional compat only.
            }
        }
        return null;
    }
}
