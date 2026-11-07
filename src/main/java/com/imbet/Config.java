package com.imbet;

import com.imbet.weather.BiomeClimate;
import com.imbet.weather.DeadlyStormModel;
import com.imbet.weather.ForecastPanel;
import com.imbet.weather.SimpleSettings;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

public final class Config {
    public static final double BLOCKS_PER_MILE = 1609.344D;
    public static final double MAX_CONFIG_WIDTH_MILES = 50_000.0D;
    public static final double MAX_CONFIG_WIDTH_BLOCKS = BLOCKS_PER_MILE * MAX_CONFIG_WIDTH_MILES;
    /** The twelve month multipliers a fresh config starts with: every month left to the season's own shape. */
    private static final List<String> DEFAULT_CALENDAR_MONTH_MULTIPLIERS = List.of(
            "1.0", "1.0", "1.0", "1.0", "1.0", "1.0", "1.0", "1.0", "1.0", "1.0", "1.0", "1.0");

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue LOG_STARTUP = BUILDER
            .comment("Whether to log Weather2 dependency status during common setup.")
            .define("logStartup", true);

    public static final ModConfigSpec.BooleanValue PERSISTENCE_ENABLED;

    public static final ModConfigSpec.BooleanValue DYNAMICS_ENABLED;
    public static final ModConfigSpec.DoubleValue TORNADO_WANDER_MAX_DEGREES;
    public static final ModConfigSpec.DoubleValue TORNADO_WANDER_SMOOTHING;
    public static final ModConfigSpec.IntValue TORNADO_WANDER_MIN_RETARGET_TICKS;
    public static final ModConfigSpec.IntValue TORNADO_WANDER_MAX_RETARGET_TICKS;
    public static final ModConfigSpec.BooleanValue TORNADO_FUNNEL_BEND_ENABLED;
    public static final ModConfigSpec.BooleanValue TORNADO_FORMING_ANIMATION_ENABLED;

    public static final ModConfigSpec.BooleanValue GENESIS_ENABLED;
    public static final ModConfigSpec.DoubleValue GENESIS_DURATION_SECONDS;
    public static final ModConfigSpec.BooleanValue GENESIS_INTENSITY_SCALE;
    public static final ModConfigSpec.DoubleValue GENESIS_JITTER;
    public static final ModConfigSpec.DoubleValue GENESIS_START_WIND_FRACTION;
    public static final ModConfigSpec.DoubleValue GENESIS_TOUCHDOWN_PROGRESS;
    public static final ModConfigSpec.BooleanValue GENESIS_PRE_TOUCHDOWN_DUST_WHIRL;
    public static final ModConfigSpec.BooleanValue GENESIS_PRE_TOUCHDOWN_WHIRLS;
    public static final ModConfigSpec.IntValue GENESIS_WHIRL_COUNT;
    public static final ModConfigSpec.DoubleValue GENESIS_WHIRL_DURATION_SECONDS;
    public static final ModConfigSpec.BooleanValue STORM_LIFECYCLE_ENABLED;
    public static final ModConfigSpec.DoubleValue STORM_LIFECYCLE_MIN_STAGE_SECONDS;
    public static final ModConfigSpec.DoubleValue TORNADO_FUNNEL_BEND_MAX_BLOCKS;
    public static final ModConfigSpec.DoubleValue TORNADO_FUNNEL_BEND_SWAY_BLOCKS;
    public static final ModConfigSpec.DoubleValue TORNADO_FUNNEL_BEND_SMOOTHING;
    public static final ModConfigSpec.DoubleValue TORNADO_FUNNEL_BEND_ANCHOR_FRACTION;
    public static final ModConfigSpec.DoubleValue TORNADO_FUNNEL_BEND_MAX_HEIGHT_FRACTION;
    public static final ModConfigSpec.DoubleValue TORNADO_FUNNEL_TILT_BLOCKS;
    public static final ModConfigSpec.DoubleValue TORNADO_FUNNEL_TILT_WEAKNESS_SCALE;
    public static final ModConfigSpec.BooleanValue TORNADO_ELEPHANT_TRUNK_ENABLED;
    public static final ModConfigSpec.DoubleValue TORNADO_ELEPHANT_TRUNK_CHANCE;
    public static final ModConfigSpec.IntValue TORNADO_ELEPHANT_TRUNK_MIN_STAGE;
    public static final ModConfigSpec.DoubleValue TORNADO_ELEPHANT_TRUNK_MAX_VISIBLE_WIDTH_FRACTION;
    public static final ModConfigSpec.DoubleValue TORNADO_ELEPHANT_TRUNK_CURVE_STRENGTH;
    public static final ModConfigSpec.DoubleValue TORNADO_SHAPE_CHANGE_SECONDS;
    public static final ModConfigSpec.BooleanValue TORNADO_DRILL_BIT_ENABLED;
    public static final ModConfigSpec.BooleanValue TORNADO_ADDITIONAL_SHAPES_ENABLED;
    public static final ModConfigSpec.DoubleValue TORNADO_ADDITIONAL_SHAPES_CHANCE;
    public static final ModConfigSpec.DoubleValue TORNADO_WEDGE_CHANCE;
    public static final ModConfigSpec.DoubleValue TORNADO_DRILL_BIT_CHANCE;
    public static final ModConfigSpec.IntValue TORNADO_DRILL_BIT_MIN_STAGE;
    public static final ModConfigSpec.BooleanValue NO_PLAYER_AIM_ON_SPAWN;
    public static final ModConfigSpec.BooleanValue ENVIRONMENTAL_INTENSITY_ENABLED;
    public static final ModConfigSpec.IntValue ENVIRONMENT_SCAN_INTERVAL_TICKS;
    public static final ModConfigSpec.DoubleValue ENVIRONMENT_REINTENSIFY_THRESHOLD;
    public static final ModConfigSpec.DoubleValue ENVIRONMENT_DEINTENSIFY_THRESHOLD;
    public static final ModConfigSpec.BooleanValue TORNADO_STRENGTHENING_ENABLED;
    public static final ModConfigSpec.DoubleValue TORNADO_STRENGTHENING_SCORE;
    public static final ModConfigSpec.IntValue TORNADO_STRENGTHENING_MAX_RUNGS;
    public static final ModConfigSpec.BooleanValue TORNADO_WEIGHTED_INTENSITY_DISTRIBUTION;
    public static final ModConfigSpec.DoubleValue TORNADO_CAPABLE_RATE_BOOST;
    public static final ModConfigSpec.BooleanValue TORNADO_WIND_SPEED_SPIN_ENABLED;
    public static final ModConfigSpec.DoubleValue TORNADO_WIND_SPEED_SPIN_MIN;
    public static final ModConfigSpec.DoubleValue TORNADO_WIND_SPEED_SPIN_MAX;
    public static final ModConfigSpec.DoubleValue TORNADO_WIND_SPEED_SPIN_SMOOTHING;
    public static final ModConfigSpec.DoubleValue TORNADO_WIND_SPEED_SPIN_REFERENCE_MIN_MPH;
    public static final ModConfigSpec.DoubleValue TORNADO_WIND_SPEED_SPIN_REFERENCE_MAX_MPH;
    public static final ModConfigSpec.BooleanValue ATMOSPHERIC_STEERING_ENABLED;
    public static final ModConfigSpec.DoubleValue ATMOSPHERIC_STEERING_COEFFICIENT;
    public static final ModConfigSpec.DoubleValue ATMOSPHERIC_STEERING_RIGHT_BIAS_DEGREES;
    public static final ModConfigSpec.DoubleValue ATMOSPHERIC_STEERING_ENE_BIAS_STRENGTH;
    public static final ModConfigSpec.DoubleValue ATMOSPHERIC_STEERING_WOBBLE_FRACTION;
    public static final ModConfigSpec.DoubleValue ATMOSPHERIC_STEERING_MIN_SPEED;
    public static final ModConfigSpec.DoubleValue ATMOSPHERIC_STEERING_MAX_SPEED;

    public static final ModConfigSpec.BooleanValue TRACK_REALISTIC_ENABLED;
    public static final ModConfigSpec.DoubleValue TRACK_SPEED_MIN_MPH;
    public static final ModConfigSpec.DoubleValue TRACK_SPEED_MAX_MPH;
    public static final ModConfigSpec.DoubleValue TRACK_INTENSITY_SPEED_SLOWDOWN;
    public static final ModConfigSpec.DoubleValue TRACK_TERRAIN_SPEED_SLOWDOWN;
    public static final ModConfigSpec.DoubleValue TRACK_SPEED_SURGE;
    public static final ModConfigSpec.DoubleValue TRACK_MAX_TURN_DEGREES_PER_TICK;
    public static final ModConfigSpec.DoubleValue TRACK_DEVIANT_MOTION_MAX_DEGREES;
    public static final ModConfigSpec.DoubleValue TRACK_CURVATURE_DEGREES;
    public static final ModConfigSpec.DoubleValue TRACK_SCALLOP_DEGREES;

    public static final ModConfigSpec.BooleanValue VARIABLE_WIDTHS_ENABLED;
    public static final ModConfigSpec.BooleanValue TORNADO_REALISTIC_WIDTH_MODEL;
    public static final ModConfigSpec.DoubleValue TORNADO_WIDTH_MIN_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue TORNADO_WIDTH_MAX_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue TORNADO_WIDTH_CHANGE_SECONDS;
    public static final ModConfigSpec.DoubleValue TORNADO_WIDTH_TURBULENCE;
    public static final ModConfigSpec.IntValue TORNADO_WIDTH_MIN_RETARGET_TICKS;
    public static final ModConfigSpec.IntValue TORNADO_WIDTH_MAX_RETARGET_TICKS;
    public static final ModConfigSpec.DoubleValue TORNADO_MIN_DAMAGE_WIDTH_BLOCKS;
    public static final ModConfigSpec.DoubleValue TORNADO_MAX_DAMAGE_WIDTH_BLOCKS;
    public static final ModConfigSpec.DoubleValue TORNADO_MIN_DAMAGE_WIDTH_MILES;
    public static final ModConfigSpec.DoubleValue TORNADO_MAX_DAMAGE_WIDTH_MILES;
    public static final ModConfigSpec.DoubleValue TORNADO_MAX_VISIBLE_FUNNEL_WIDTH_BLOCKS;
    public static final ModConfigSpec.DoubleValue TORNADO_MAX_VISIBLE_FUNNEL_WIDTH_MILES;
    public static final ModConfigSpec.DoubleValue TORNADO_VISIBLE_FUNNEL_FRACTION_MIN;
    public static final ModConfigSpec.DoubleValue TORNADO_VISIBLE_FUNNEL_FRACTION_MAX;
    public static final ModConfigSpec.BooleanValue TORNADO_SIZE_LIFECYCLE;
    public static final ModConfigSpec.BooleanValue TORNADO_SIZE_TEMPERAMENT;
    public static final ModConfigSpec.DoubleValue TORNADO_SIZE_TEMPERAMENT_SKEW;
    public static final ModConfigSpec.DoubleValue TORNADO_SIZE_ROPE_OUT;
    public static final ModConfigSpec.BooleanValue TORNADO_VISIBLE_FRACTION_FROM_MOISTURE;

    public static final ModConfigSpec.BooleanValue DAMAGE_ENABLED;
    public static final ModConfigSpec.BooleanValue GATE_STORM_SUMMONS;
    public static final ModConfigSpec.IntValue DAMAGE_MAX_BLOCKS_PER_STORM_PER_TICK;
    public static final ModConfigSpec.BooleanValue DAMAGE_WIDTH_SCALED_BUDGET;
    public static final ModConfigSpec.DoubleValue DAMAGE_BLOCK_STRENGTH_MIN_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue DAMAGE_BLOCK_STRENGTH_MAX_MULTIPLIER;
    public static final ModConfigSpec.BooleanValue DAMAGE_LOADED_CHUNKS_ONLY;
    public static final ModConfigSpec.BooleanValue DAMAGE_OPEN_DOORS;
    public static final ModConfigSpec.BooleanValue DAMAGE_GRAB_LIVING_ENTITIES;
    public static final ModConfigSpec.BooleanValue DAMAGE_GRAB_PLAYERS;
    public static final ModConfigSpec.BooleanValue DAMAGE_GROUND_SCOURING;
    public static final ModConfigSpec.BooleanValue DAMAGE_TREE_DEBARKING;
    public static final ModConfigSpec.BooleanValue DAMAGE_PRESERVE_INVENTORY_BLOCK_ENTITIES;
    public static final ModConfigSpec.BooleanValue DAMAGE_NEVER_PICKUP_SOIL;
    public static final ModConfigSpec.BooleanValue DAMAGE_WIND_RESISTANCE;
    public static final ModConfigSpec.BooleanValue DAMAGE_FLYING_BLOCK_IMPACT_DAMAGE;
    public static final ModConfigSpec.BooleanValue DAMAGE_BLOCK_ENTITY_IMPACT_DAMAGE;
    public static final ModConfigSpec.BooleanValue DAMAGE_AFFECT_VALKYRIEN_SHIPS;
    public static final ModConfigSpec.DoubleValue DAMAGE_EF0_PICKUP_MPH;
    public static final ModConfigSpec.DoubleValue DAMAGE_WOOD_PICKUP_MPH;
    public static final ModConfigSpec.DoubleValue DAMAGE_STONE_PICKUP_MPH;
    public static final ModConfigSpec.DoubleValue DAMAGE_HEAVY_PICKUP_MPH;
    public static final ModConfigSpec.DoubleValue DAMAGE_TREE_DEBARK_MPH;
    public static final ModConfigSpec.DoubleValue DAMAGE_HEAVY_TREE_DEBARK_MPH;
    public static final ModConfigSpec.BooleanValue DAMAGE_SCOURING_EXCAVATION;
    public static final ModConfigSpec.IntValue DAMAGE_LIGHT_SCOURING_STAGE;
    public static final ModConfigSpec.IntValue DAMAGE_MEDIUM_SCOURING_STAGE;
    public static final ModConfigSpec.IntValue DAMAGE_HEAVY_SCOURING_STAGE;

    public static final ModConfigSpec.BooleanValue HAIL_DAMAGE_ENABLED;
    public static final ModConfigSpec.BooleanValue HAIL_PLAYER_DAMAGE;
    public static final ModConfigSpec.BooleanValue HAIL_MOB_DAMAGE;
    public static final ModConfigSpec.BooleanValue HAIL_GLASS_DAMAGE;
    public static final ModConfigSpec.BooleanValue HAIL_CROP_DAMAGE;
    public static final ModConfigSpec.BooleanValue HAIL_LEAF_DAMAGE;
    public static final ModConfigSpec.BooleanValue HAIL_GROUND_LOSS;
    public static final ModConfigSpec.DoubleValue HAIL_HELMET_PROTECTION;
    public static final ModConfigSpec.DoubleValue HAIL_DAMAGE_SCALE;
    public static final ModConfigSpec.BooleanValue HAIL_CLATTER_ENABLED;
    public static final ModConfigSpec.IntValue HAIL_CLATTER_RADIUS;
    public static final ModConfigSpec.DoubleValue HAIL_CLATTER_VOLUME;

    public static final ModConfigSpec.BooleanValue STRUCTURE_LIFT_ENABLED;
    public static final ModConfigSpec.DoubleValue STRUCTURE_LIFT_MIN_WIND_MPH;
    public static final ModConfigSpec.IntValue STRUCTURE_LIFT_MIN_STAGE;
    public static final ModConfigSpec.DoubleValue STRUCTURE_LIFT_BLOCK_WIND_MPH;
    public static final ModConfigSpec.BooleanValue STRUCTURE_LIFT_ALLOW_MASONRY;
    public static final ModConfigSpec.IntValue STRUCTURE_LIFT_MIN_BLOCKS;
    public static final ModConfigSpec.IntValue STRUCTURE_LIFT_MAX_BLOCKS;
    public static final ModConfigSpec.IntValue STRUCTURE_LIFT_MAX_RADIUS_BLOCKS;
    public static final ModConfigSpec.IntValue STRUCTURE_LIFT_DURATION_TICKS;
    public static final ModConfigSpec.IntValue STRUCTURE_LIFT_BLOCKS_PER_TICK;
    public static final ModConfigSpec.IntValue STRUCTURE_LIFT_SCAN_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue STRUCTURE_LIFT_MAX_CONCURRENT;
    public static final ModConfigSpec.BooleanValue STRUCTURE_LIFT_WHOLE_BUILDING;
    public static final ModConfigSpec.IntValue STRUCTURE_LIFT_WHOLE_MAX_BLOCKS;
    public static final ModConfigSpec.DoubleValue STRUCTURE_LIFT_FLIGHT_SECONDS;
    public static final ModConfigSpec.DoubleValue STRUCTURE_LIFT_TUMBLE_RATE;

    public static final ModConfigSpec.BooleanValue TREE_LIFT_ENABLED;
    public static final ModConfigSpec.DoubleValue TREE_LIFT_MIN_WIND_MPH;
    public static final ModConfigSpec.IntValue TREE_LIFT_MIN_STAGE;
    public static final ModConfigSpec.IntValue TREE_LIFT_MIN_LOGS;
    public static final ModConfigSpec.IntValue TREE_LIFT_MAX_LOGS;
    public static final ModConfigSpec.BooleanValue TREE_LIFT_CARRY_LEAVES;
    public static final ModConfigSpec.IntValue TREE_LIFT_MAX_CANOPY_BLOCKS;
    public static final ModConfigSpec.IntValue TREE_LIFT_SCAN_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue TREE_LIFT_MAX_CONCURRENT;
    public static final ModConfigSpec.DoubleValue TREE_LIFT_FLIGHT_SECONDS;
    public static final ModConfigSpec.DoubleValue TREE_LIFT_TUMBLE_RATE;
    public static final ModConfigSpec.DoubleValue TREE_LIFT_THROW_DISTANCE_BLOCKS;
    public static final ModConfigSpec.DoubleValue TREE_LIFT_IMPACT_CRUSH_MASS;
    public static final ModConfigSpec.BooleanValue TREE_LIFT_IMPACT_DAMAGES_ENTITIES;
    public static final ModConfigSpec.BooleanValue TREE_LIFT_FELL_ON_LANDING;
    public static final ModConfigSpec.IntValue TREE_LIFT_FELL_CANOPY_BLOCKS;

    public static final ModConfigSpec.BooleanValue WINDFIELD_ENABLED;
    public static final ModConfigSpec.DoubleValue WINDFIELD_FORMING_MPH;
    public static final ModConfigSpec.DoubleValue WINDFIELD_GROUND_CONTACT_FLOOR_MPH;
    public static final ModConfigSpec.DoubleValue WINDFIELD_STAGE1_MPH;
    public static final ModConfigSpec.DoubleValue WINDFIELD_STAGE2_MPH;
    public static final ModConfigSpec.DoubleValue WINDFIELD_STAGE3_MPH;
    public static final ModConfigSpec.DoubleValue WINDFIELD_STAGE4_MPH;
    public static final ModConfigSpec.DoubleValue WINDFIELD_STAGE5_MPH;
    public static final ModConfigSpec.BooleanValue WINDFIELD_DYNAMIC_WIND_SPEEDS;
    public static final ModConfigSpec.DoubleValue WINDFIELD_DYNAMIC_WIND_SMOOTHING;
    public static final ModConfigSpec.DoubleValue WINDFIELD_DYNAMIC_GUSTINESS;
    public static final ModConfigSpec.BooleanValue WINDFIELD_DYNAMIC_GUST_FRONTS;
    public static final ModConfigSpec.BooleanValue WINDFIELD_ENVIRONMENT_WIND;
    public static final ModConfigSpec.DoubleValue WINDFIELD_BAND_WEIGHT;
    public static final ModConfigSpec.DoubleValue WINDFIELD_ENVIRONMENT_CEILING_MPH;
    public static final ModConfigSpec.BooleanValue WINDFIELD_WIND_DRIVEN_STAGE;
    public static final ModConfigSpec.DoubleValue WINDFIELD_RADIAL_INFLOW_FRACTION;
    public static final ModConfigSpec.DoubleValue WINDFIELD_OUTSIDE_PROFILE_EXPONENT;
    public static final ModConfigSpec.BooleanValue DEBRIS_PHYSICS_ENABLED;
    public static final ModConfigSpec.DoubleValue DEBRIS_WIND_MIN_FRACTION;
    public static final ModConfigSpec.DoubleValue DEBRIS_WIND_MAX_FRACTION;
    public static final ModConfigSpec.DoubleValue DEBRIS_DRAG_STRENGTH;
    public static final ModConfigSpec.IntValue DEBRIS_WIND_SAMPLE_RANGE_BLOCKS;
    public static final ModConfigSpec.BooleanValue DEBRIS_ENTITY_ENABLED;
    public static final ModConfigSpec.DoubleValue DEBRIS_ENTITY_GRAVITY;
    public static final ModConfigSpec.DoubleValue DEBRIS_ENTITY_DRAG_STRENGTH;
    public static final ModConfigSpec.DoubleValue DEBRIS_ENTITY_WIND_COUPLING;
    public static final ModConfigSpec.DoubleValue DEBRIS_ENTITY_LIFT;
    public static final ModConfigSpec.DoubleValue DEBRIS_ENTITY_LAUNCH_FRACTION;
    public static final ModConfigSpec.DoubleValue DEBRIS_ENTITY_LAUNCH_LIFT;
    public static final ModConfigSpec.DoubleValue DEBRIS_ENTITY_LIFETIME_SECONDS;
    public static final ModConfigSpec.BooleanValue DEBRIS_ENTITY_SETTLE_INTO_BLOCKS;
    public static final ModConfigSpec.BooleanValue DEBRIS_ENTITY_IMPACT_DAMAGE;
    public static final ModConfigSpec.DoubleValue DEBRIS_ENTITY_DAMAGE_MIN_SPEED_BLOCKS_PER_TICK;
    public static final ModConfigSpec.DoubleValue DEBRIS_ENTITY_DAMAGE_BASE;
    public static final ModConfigSpec.IntValue DEBRIS_ENTITY_MAX_ACTIVE;
    public static final ModConfigSpec.DoubleValue DEBRIS_ENTITY_SPIN_DEGREES_PER_TICK;
    public static final ModConfigSpec.DoubleValue WINDFIELD_VERTICAL_LIFT_FRACTION;
    public static final ModConfigSpec.DoubleValue WINDFIELD_ENTITY_PUSH_SCALE;
    public static final ModConfigSpec.BooleanValue WINDFIELD_PLAYER_LAUNCH_ENABLED;
    public static final ModConfigSpec.DoubleValue WINDFIELD_PLAYER_LAUNCH_MPH;
    public static final ModConfigSpec.DoubleValue WINDFIELD_PLAYER_LAUNCH_STRENGTH;
    public static final ModConfigSpec.BooleanValue WINDFIELD_PLAYER_LAUNCH_FALL_SAFE;
    public static final ModConfigSpec.DoubleValue WINDFIELD_MOB_LAUNCH_SCALE;

    public static final ModConfigSpec.BooleanValue MULTIVORTEX_ENABLED;
    public static final ModConfigSpec.IntValue MULTIVORTEX_MAX_COUNT;
    public static final ModConfigSpec.IntValue MULTIVORTEX_MIN_STAGE;
    public static final ModConfigSpec.DoubleValue MULTIVORTEX_EPISODE_CHANCE;
    public static final ModConfigSpec.IntValue MULTIVORTEX_MIN_ATTEMPT_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue MULTIVORTEX_MAX_ATTEMPT_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue MULTIVORTEX_MIN_EPISODE_TICKS;
    public static final ModConfigSpec.IntValue MULTIVORTEX_MAX_EPISODE_TICKS;
    public static final ModConfigSpec.DoubleValue MULTIVORTEX_DAMAGE_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue MULTIVORTEX_PLANTED_SUCTION_CHANCE;
    public static final ModConfigSpec.BooleanValue MULTIVORTEX_GROUND_SWARM_ENABLED;
    public static final ModConfigSpec.DoubleValue MULTIVORTEX_GROUND_SWARM_CHANCE;
    public static final ModConfigSpec.IntValue MULTIVORTEX_GROUND_SWARM_EXTRA_VORTICES;
    public static final ModConfigSpec.DoubleValue MULTIVORTEX_GROUND_SWARM_TIP_HEIGHT;
    public static final ModConfigSpec.DoubleValue MULTIVORTEX_GROUND_SWARM_PARENT_WIND_FRACTION;
    public static final ModConfigSpec.BooleanValue MULTIVORTEX_LIFTED_PARENT_VISIBLE;
    public static final ModConfigSpec.DoubleValue MULTIVORTEX_LIFTED_TIP_HEIGHT;
    public static final ModConfigSpec.DoubleValue MULTIVORTEX_SUBVORTEX_FUNNEL_SIZE;
    public static final ModConfigSpec.BooleanValue SUBVORTEX_TYPES_ENABLED;
    public static final ModConfigSpec.BooleanValue SUBVORTEX_BREAKDOWN_ENABLED;
    public static final ModConfigSpec.DoubleValue SUBVORTEX_BREAKDOWN_PINCH;
    public static final ModConfigSpec.BooleanValue SUBVORTEX_EVEN_SPACING;
    public static final ModConfigSpec.BooleanValue SUBVORTEX_SHARED_ORBIT_SPEED;
    public static final ModConfigSpec.BooleanValue SUBVORTEX_EPISODE_RAMP;
    public static final ModConfigSpec.BooleanValue SUBVORTEX_SATELLITE_FUNNELS;
    public static final ModConfigSpec.BooleanValue SUBVORTEX_DUST_WHIRLS;
    public static final ModConfigSpec.BooleanValue FUNNEL_LUMPS_ENABLED;
    public static final ModConfigSpec.DoubleValue FUNNEL_LUMP_STRENGTH;
    public static final ModConfigSpec.BooleanValue FUNNEL_DUST_GRADIENT_ENABLED;
    public static final ModConfigSpec.DoubleValue FUNNEL_DUST_GRADIENT_HEIGHT;
    public static final ModConfigSpec.DoubleValue FUNNEL_DUST_TINT_STRENGTH;
    public static final ModConfigSpec.BooleanValue HORIZONTAL_VORTICES_ENABLED;
    public static final ModConfigSpec.IntValue HORIZONTAL_VORTICES_MAX_COUNT;
    public static final ModConfigSpec.IntValue HORIZONTAL_VORTICES_MIN_STAGE;
    public static final ModConfigSpec.DoubleValue HORIZONTAL_VORTICES_STORM_CHANCE;
    public static final ModConfigSpec.DoubleValue HORIZONTAL_VORTICES_BREAK_OFF_CHANCE;
    public static final ModConfigSpec.IntValue HORIZONTAL_VORTICES_BREAK_OFF_FREE_TICKS;

    public static final ModConfigSpec.BooleanValue MERGING_ENABLED;
    public static final ModConfigSpec.IntValue MERGE_SCAN_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue MERGE_OVERLAP_TICKS;
    public static final ModConfigSpec.BooleanValue MERGE_ABSORB_WEAKER;
    public static final ModConfigSpec.BooleanValue MERGE_ORBIT_BEFORE_ABSORB;
    public static final ModConfigSpec.IntValue MERGE_ORBIT_TICKS;
    public static final ModConfigSpec.DoubleValue MERGE_ORBIT_STRENGTH;

    public static final ModConfigSpec.BooleanValue SUPERCELL_MERGING_ENABLED;
    public static final ModConfigSpec.IntValue SUPERCELL_MERGE_MIN_INTENSITY_STAGE;
    public static final ModConfigSpec.IntValue SUPERCELL_MERGE_SCAN_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue SUPERCELL_MERGE_OVERLAP_TICKS;
    public static final ModConfigSpec.DoubleValue SUPERCELL_MERGE_DISTANCE_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue SUPERCELL_MERGE_MAX_DISTANCE_BLOCKS;
    public static final ModConfigSpec.DoubleValue SUPERCELL_MERGE_INTENSITY_PULSE;
    public static final ModConfigSpec.DoubleValue SUPERCELL_MERGE_MOTION_ABSORB_FRACTION;
    public static final ModConfigSpec.BooleanValue SUPERCELL_MERGE_SOFT_DISSIPATE_ABSORBED;
    public static final ModConfigSpec.BooleanValue SUPERCELL_MERGE_HARD_REMOVE_ABSORBED;

    public static final ModConfigSpec.BooleanValue SUPERCELL_STRUCTURE_ENABLED;
    public static final ModConfigSpec.DoubleValue SUPERCELL_STRUCTURE_SCALE;
    public static final ModConfigSpec.IntValue SUPERCELL_STRUCTURE_CLOUD_BASE_OFFSET_BLOCKS;
    public static final ModConfigSpec.BooleanValue SUPERCELL_STRUCTURE_CLIENT_VISUALS;
    public static final ModConfigSpec.BooleanValue SUPERCELL_STRUCTURE_RADAR;
    public static final ModConfigSpec.BooleanValue SUPERCELL_STRUCTURE_WINDFIELD_INFLUENCE;
    public static final ModConfigSpec.IntValue SUPERCELL_STRUCTURE_RENDER_RANGE_BLOCKS;
    public static final ModConfigSpec.IntValue SUPERCELL_STRUCTURE_MAX_PARTICLES_PER_TICK;
    public static final ModConfigSpec.BooleanValue SUPERCELL_STRUCTURE_ANVIL_PARTICLES;
    public static final ModConfigSpec.BooleanValue SUPERCELL_STRUCTURE_CLOUD_MASS_SHAPE;
    public static final ModConfigSpec.BooleanValue SUPERCELL_STRUCTURE_WALL_CLOUD_PARTICLES;
    public static final ModConfigSpec.DoubleValue SUPERCELL_STRUCTURE_WALL_CLOUD_PARTICLE_SIZE;
    public static final ModConfigSpec.BooleanValue SUPERCELL_STRUCTURE_WALL_CLOUD_LOWERING;
    public static final ModConfigSpec.DoubleValue SUPERCELL_STRUCTURE_WALL_CLOUD_MIN_MESOCYCLONE_STRENGTH;
    public static final ModConfigSpec.IntValue SUPERCELL_STRUCTURE_WALL_CLOUD_MIN_PERSISTENCE_TICKS;
    public static final ModConfigSpec.IntValue SUPERCELL_STRUCTURE_WALL_CLOUD_MAX_DIAMETER_BLOCKS;
    public static final ModConfigSpec.IntValue SUPERCELL_STRUCTURE_WALL_CLOUD_MAX_LOWERING_BLOCKS;
    public static final ModConfigSpec.BooleanValue SUPERCELL_STRUCTURE_WALL_CLOUD_ROTATION_PARTICLES;
    public static final ModConfigSpec.DoubleValue SUPERCELL_STRUCTURE_WALL_CLOUD_SCUD_CHANCE;
    public static final ModConfigSpec.DoubleValue SUPERCELL_STRUCTURE_WALL_CLOUD_SCUD_SIZE;
    public static final ModConfigSpec.BooleanValue SUPERCELL_STRUCTURE_WALL_CLOUD_SCUD_FEED_PARTICLES;
    public static final ModConfigSpec.BooleanValue SUPERCELL_STRUCTURE_WALL_CLOUD_RISK_BOOST;
    public static final ModConfigSpec.BooleanValue SUPERCELL_PRECIPITATION_ENABLED;
    public static final ModConfigSpec.DoubleValue SUPERCELL_PRECIPITATION_VARIABILITY;
    public static final ModConfigSpec.DoubleValue SUPERCELL_PRECIPITATION_LP_THRESHOLD;
    public static final ModConfigSpec.DoubleValue SUPERCELL_PRECIPITATION_HP_THRESHOLD;
    public static final ModConfigSpec.DoubleValue SUPERCELL_PRECIPITATION_DRIFT_PER_TICK;

    /**
     * Shapes a storm's rain as a field rather than a blob: a forward-flank core, a rear-flank hook
     * that wraps the mesocyclone, a rain-free inflow notch, and weaker flanking rain.
     */
    public static final ModConfigSpec.BooleanValue SUPERCELL_PRECIPITATION_FIELD_ENABLED;

    /** Scales how much rain wraps the mesocyclone. 0 = no wrapping, 1 = as the storm's own RFD implies. */
    public static final ModConfigSpec.DoubleValue SUPERCELL_PRECIPITATION_HOOK_SCALE;

    /**
     * The chance that a supercell is a rain-wrapped one, from 0 to 1, or -1 to leave it to each storm's own moisture.
     */
    public static final ModConfigSpec.DoubleValue SUPERCELL_RAIN_WRAP_CHANCE;

    /** Scales how rain-free the inflow notch is. Higher = a crisper clear slot around the funnel. */
    public static final ModConfigSpec.DoubleValue SUPERCELL_PRECIPITATION_NOTCH_CLARITY;
    public static final ModConfigSpec.BooleanValue SUPERCELL_STRUCTURE_GUST_FRONT_PARTICLES;
    public static final ModConfigSpec.BooleanValue SUPERCELL_STRUCTURE_FLANKING_LINE_PARTICLES;
    public static final ModConfigSpec.BooleanValue SUPERCELL_STRUCTURE_VIRGA_MAMMATUS_PARTICLES;
    public static final ModConfigSpec.BooleanValue SUPERCELL_STRUCTURE_RFD_STRAIGHT_LINE_WIND;

    public static final ModConfigSpec.BooleanValue VIRGA_ENABLED;
    public static final ModConfigSpec.DoubleValue VIRGA_STRENGTH_SCALE;
    public static final ModConfigSpec.IntValue VIRGA_MIN_FALL_DEPTH_BLOCKS;
    public static final ModConfigSpec.IntValue VIRGA_MAX_FALL_DEPTH_BLOCKS;

    public static final ModConfigSpec.BooleanValue RADAR_ENABLED;
    public static final ModConfigSpec.IntValue RADAR_RANGE_BLOCKS;
    public static final ModConfigSpec.IntValue RADAR_UPDATE_INTERVAL_TICKS;
    public static final ModConfigSpec.BooleanValue RADAR_WORLD_PANEL_ENABLED;
    public static final ModConfigSpec.BooleanValue RADAR_STABLE_NORTH_UP;
    public static final ModConfigSpec.BooleanValue RADAR_CONFIRMED_CIRCULATION_MARKER;
    public static final ModConfigSpec.BooleanValue RADAR_VORTEX_ICONS;
    public static final ModConfigSpec.IntValue PORTABLE_RADAR_LINK_RANGE_BLOCKS;

    public static final ModConfigSpec.BooleanValue DAILY_SPC_OUTLOOK_ENABLED;
    public static final ModConfigSpec.BooleanValue DAILY_SPC_OUTLOOK_ISSUANCE_CYCLE;
    public static final ModConfigSpec.BooleanValue DAILY_SPC_SERENE_SEASONS_THERMODYNAMICS;
    public static final ModConfigSpec.DoubleValue DAILY_SPC_SPAWN_MULTIPLIER_SCALE;
    public static final ModConfigSpec.DoubleValue DAILY_SPC_ROTATION_THRESHOLD_SCALE;

    public static final ModConfigSpec.BooleanValue SURVEYS_ENABLED;
    public static final ModConfigSpec.IntValue SURVEY_RADIUS_BLOCKS;
    public static final ModConfigSpec.IntValue SURVEY_TRACK_RECORD_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue SURVEY_MAX_TRACK_AGE_TICKS;
    public static final ModConfigSpec.IntValue SURVEY_RECENT_TRACK_WINDOW_TICKS;
    public static final ModConfigSpec.IntValue SURVEY_TRACK_CONTEXT_BLOCKS;
    public static final ModConfigSpec.BooleanValue SURVEY_OPEN_GUI_ON_SURVEY;

    public static final ModConfigSpec.BooleanValue SPOTTER_ENABLED;
    public static final ModConfigSpec.IntValue SPOTTER_REPORT_RANGE_BLOCKS;
    public static final ModConfigSpec.DoubleValue SPOTTER_MIN_REPORTED_MPH;
    public static final ModConfigSpec.IntValue SPOTTER_LIFETIME_TICKS;
    public static final ModConfigSpec.IntValue SPOTTER_COOLDOWN_TICKS;
    public static final ModConfigSpec.IntValue SPOTTER_MAX_ACTIVE_REPORTS;
    public static final ModConfigSpec.IntValue SPOTTER_SCAN_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue SPOTTER_CONFIRM_RANGE_BLOCKS;
    public static final ModConfigSpec.IntValue SPOTTER_VILLAGER_RADIUS_BLOCKS;
    public static final ModConfigSpec.IntValue SPOTTER_BOUNTY_EMERALDS;
    public static final ModConfigSpec.IntValue SPOTTER_BOUNTY_RANGE_BLOCKS;
    public static final ModConfigSpec.IntValue SPOTTER_BOUNTY_CHEST_RANGE_BLOCKS;

    public static final ModConfigSpec.BooleanValue PHOTO_ENABLED;
    public static final ModConfigSpec.BooleanValue PHOTO_COUNT_CREATIVE;
    public static final ModConfigSpec.BooleanValue PHOTO_VIEWFINDER;
    public static final ModConfigSpec.IntValue PHOTO_AIM_INTERVAL_TICKS;
    public static final ModConfigSpec.BooleanValue PHOTO_ANNOUNCE;
    public static final ModConfigSpec.IntValue PHOTO_MAX_DISTANCE_BLOCKS;
    public static final ModConfigSpec.IntValue PHOTO_SWEET_SPOT_MIN_BLOCKS;
    public static final ModConfigSpec.IntValue PHOTO_SWEET_SPOT_MAX_BLOCKS;
    public static final ModConfigSpec.DoubleValue PHOTO_MIN_SUBJECT_MPH;
    public static final ModConfigSpec.IntValue PHOTO_OCCLUSION_SAMPLES;
    public static final ModConfigSpec.DoubleValue PHOTO_FUNNEL_HEIGHT_SCALE;
    public static final ModConfigSpec.IntValue PHOTO_LIGHTNING_RANGE_BLOCKS;
    public static final ModConfigSpec.IntValue PHOTO_LIGHTNING_WINDOW_TICKS;
    public static final ModConfigSpec.DoubleValue PHOTO_RAIN_LEVEL;
    public static final ModConfigSpec.IntValue PHOTO_COOLDOWN_TICKS;
    public static final ModConfigSpec.IntValue PHOTO_ALBUM_SIZE;
    public static final ModConfigSpec.IntValue PHOTO_READOUT_LINES;

    public static final ModConfigSpec.BooleanValue CHASE_LOG_ENABLED;
    public static final ModConfigSpec.IntValue CHASE_LOG_INTERVAL_TICKS;
    public static final ModConfigSpec.DoubleValue CHASE_LOG_RADIUS_BLOCKS;
    public static final ModConfigSpec.DoubleValue CHASE_LOG_MIN_STORM_MPH;
    public static final ModConfigSpec.IntValue CHASE_LOG_END_GRACE_TICKS;
    public static final ModConfigSpec.IntValue CHASE_LOG_MAX_ENCOUNTERS;
    public static final ModConfigSpec.IntValue CHASE_LOG_RECENT_LINES;
    public static final ModConfigSpec.BooleanValue CHASE_LOG_COUNT_CREATIVE;
    public static final ModConfigSpec.BooleanValue CHASE_LOG_ANNOUNCE;

    public static final ModConfigSpec.BooleanValue ALERTS_ENABLED;
    public static final ModConfigSpec.BooleanValue ALERTS_STRUCTURED_PRODUCTS;
    public static final ModConfigSpec.BooleanValue ALERTS_TORNADO_EMERGENCY;
    public static final ModConfigSpec.BooleanValue ALERTS_SAFE_FAUX_TONES_ONLY;
    public static final ModConfigSpec.BooleanValue ALERTS_FAUX_SOUND;
    public static final ModConfigSpec.IntValue ALERT_RANGE_BLOCKS;
    public static final ModConfigSpec.IntValue ALERT_WATCH_RANGE_BLOCKS;
    public static final ModConfigSpec.IntValue ALERT_WARNING_RANGE_BLOCKS;
    public static final ModConfigSpec.IntValue ALERT_WARNING_PATH_LEAD_TICKS;
    public static final ModConfigSpec.IntValue ALERT_COOLDOWN_TICKS;
    public static final ModConfigSpec.BooleanValue ALERTS_REQUIRE_SIREN;
    public static final ModConfigSpec.IntValue ALERTS_SIREN_RANGE_BLOCKS;
    public static final ModConfigSpec.BooleanValue ALERTS_SIREN_WIRING_REQUIRED;
    public static final ModConfigSpec.IntValue ALERTS_SIREN_MAX_WIRE_LENGTH;
    public static final ModConfigSpec.IntValue ALERTS_SIREN_DEPLOY_TICKS;
    public static final ModConfigSpec.IntValue ALERTS_SIREN_DISH_RANGE_BLOCKS;
    public static final ModConfigSpec.BooleanValue DRONE_ENABLED;
    public static final ModConfigSpec.DoubleValue DRONE_MAX_AIRSPEED_MPH;
    public static final ModConfigSpec.DoubleValue DRONE_CLIMB_MPH;
    public static final ModConfigSpec.DoubleValue DRONE_WIND_COUPLING;
    public static final ModConfigSpec.IntValue DRONE_FLIGHT_TICKS;
    public static final ModConfigSpec.IntValue DRONE_LINK_RANGE_BLOCKS;
    public static final ModConfigSpec.DoubleValue DRONE_ARM_MPH;
    public static final ModConfigSpec.BooleanValue DRONE_FREEZE_PILOT;
    public static final ModConfigSpec.DoubleValue TORNADO_PROBE_HOUSING_SURVIVAL_BONUS;
    public static final ModConfigSpec.DoubleValue TORNADO_PROBE_SENSOR_RANGE_BONUS;
    public static final ModConfigSpec.BooleanValue ALERTS_SIREN_TONES;
    public static final ModConfigSpec.DoubleValue ALERTS_SIREN_TONE_VOLUME;
    public static final ModConfigSpec.IntValue ALERTS_BANNER_DURATION_TICKS;
    public static final ModConfigSpec.IntValue ALERTS_BANNER_REFRESH_TICKS;
    public static final ModConfigSpec.BooleanValue ALERTS_CHAT_LOG;

    public static final ModConfigSpec.BooleanValue WEATHER_RADIO_ENABLED;
    public static final ModConfigSpec.IntValue WEATHER_RADIO_RANGE_BLOCKS;
    public static final ModConfigSpec.BooleanValue WEATHER_RADIO_TONES;
    public static final ModConfigSpec.BooleanValue WEATHER_VANE_ENABLED;
    public static final ModConfigSpec.BooleanValue BAROMETER_ENABLED;
    public static final ModConfigSpec.BooleanValue RAIN_GAUGE_ENABLED;
    public static final ModConfigSpec.BooleanValue RAIN_GAUGE_REDSTONE_PULSE;
    public static final ModConfigSpec.BooleanValue TORNADO_PROBE_ENABLED;
    public static final ModConfigSpec.IntValue TORNADO_PROBE_ARM_MPH;
    public static final ModConfigSpec.IntValue TORNADO_PROBE_SHRED_MPH;
    public static final ModConfigSpec.IntValue TORNADO_PROBE_DETECT_RANGE_BLOCKS;

    public static final ModConfigSpec.BooleanValue WATERSPOUTS_ENABLED;
    public static final ModConfigSpec.BooleanValue FAIR_WEATHER_WATERSPOUTS_ENABLED;
    public static final ModConfigSpec.BooleanValue FUNNEL_CLOUDS_ENABLED;
    public static final ModConfigSpec.BooleanValue CYCLONES_ENABLED;
    public static final ModConfigSpec.BooleanValue TROPICAL_HEMISPHERE_SPLIT;
    public static final ModConfigSpec.IntValue TROPICAL_HEMISPHERE_EQUATOR_Z;
    public static final ModConfigSpec.BooleanValue LANDSPOUTS_ENABLED;
    public static final ModConfigSpec.BooleanValue DUST_DEVILS_ENABLED;
    public static final ModConfigSpec.BooleanValue GUSTNADOES_ENABLED;
    public static final ModConfigSpec.BooleanValue HURRICANES_ENABLED;
    public static final ModConfigSpec.BooleanValue FLOATER_TORNADOES_ENABLED;
    public static final ModConfigSpec.BooleanValue SNOW_DEVILS_ENABLED;
    public static final ModConfigSpec.BooleanValue SNOW_DEVIL_FAIR_WEATHER_ONLY;
    public static final ModConfigSpec.BooleanValue SPOUT_DAMAGE_ENABLED;
    public static final ModConfigSpec.BooleanValue WILDFIRE_BURNT_PASS;
    public static final ModConfigSpec.BooleanValue WILDFIRE_BURNT_TRUNKS;
    public static final ModConfigSpec.DoubleValue SNOW_DEVIL_STICK_DENSITY;
    public static final ModConfigSpec.IntValue SNOW_DEVIL_MIN_LIFE_TICKS;
    public static final ModConfigSpec.BooleanValue WILDFIRES_ENABLED;
    public static final ModConfigSpec.BooleanValue FIRE_WHIRLS_ENABLED;
    public static final ModConfigSpec.BooleanValue FLAMMAGENITUS_CLOUDS_ENABLED;
    public static final ModConfigSpec.BooleanValue FIRE_WEATHER_RISK_ENABLED;
    public static final ModConfigSpec.BooleanValue WINTER_ALERTS_ENABLED;
    public static final ModConfigSpec.BooleanValue SATELLITE_TORNADOES_ENABLED;
    public static final ModConfigSpec.BooleanValue SATELLITE_TORNADOES_REAL_CIRCULATION;
    public static final ModConfigSpec.IntValue SATELLITE_TORNADOES_MAX_ACTIVE;
    public static final ModConfigSpec.IntValue SATELLITE_TORNADOES_MIN_PARENT_STAGE;
    public static final ModConfigSpec.DoubleValue SATELLITE_TORNADOES_MAX_WIND_FRACTION;
    public static final ModConfigSpec.DoubleValue SATELLITE_TORNADOES_SPAWN_CHANCE;
    public static final ModConfigSpec.IntValue SATELLITE_TORNADOES_DAMAGE_BLOCKS_PER_TICK;
    public static final ModConfigSpec.BooleanValue TWIN_TORNADOES_ENABLED;
    public static final ModConfigSpec.DoubleValue TWIN_SPAWN_CHANCE;
    public static final ModConfigSpec.IntValue TWIN_ATTEMPT_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue TWIN_MIN_PARENT_STAGE;
    public static final ModConfigSpec.IntValue TWIN_MAX_PER_TORNADO;
    public static final ModConfigSpec.DoubleValue TWIN_SPACING_BLOCKS;
    public static final ModConfigSpec.IntValue TWIN_LIFETIME_TICKS;
    public static final ModConfigSpec.BooleanValue TWIN_DIES_WITH_PAIR;
    public static final ModConfigSpec.BooleanValue CLIMATE_ENABLED;
    public static final ModConfigSpec.IntValue CLIMATE_REGION_SIZE_BLOCKS;
    public static final ModConfigSpec.IntValue CLIMATE_SAMPLE_RADIUS_BLOCKS;
    public static final ModConfigSpec.DoubleValue CLIMATE_CONTRAST_STRENGTH;
    public static final ModConfigSpec.BooleanValue CLIMATE_REGIONAL_OUTLOOK;
    public static final ModConfigSpec.DoubleValue CLIMATE_ODDS_STRENGTH;
    public static final ModConfigSpec.DoubleValue CLIMATE_WEIGHT_OCEAN;
    public static final ModConfigSpec.DoubleValue CLIMATE_WEIGHT_COAST;
    public static final ModConfigSpec.DoubleValue CLIMATE_WEIGHT_DESERT;
    public static final ModConfigSpec.DoubleValue CLIMATE_WEIGHT_BADLANDS;
    public static final ModConfigSpec.DoubleValue CLIMATE_WEIGHT_SAVANNA;
    public static final ModConfigSpec.DoubleValue CLIMATE_WEIGHT_PLAINS;
    public static final ModConfigSpec.DoubleValue CLIMATE_WEIGHT_FOREST;
    public static final ModConfigSpec.DoubleValue CLIMATE_WEIGHT_JUNGLE;
    public static final ModConfigSpec.DoubleValue CLIMATE_WEIGHT_SWAMP;
    public static final ModConfigSpec.DoubleValue CLIMATE_WEIGHT_TAIGA;
    public static final ModConfigSpec.DoubleValue CLIMATE_WEIGHT_SNOWY;
    public static final ModConfigSpec.DoubleValue CLIMATE_WEIGHT_MOUNTAIN;
    public static final ModConfigSpec.BooleanValue CALENDAR_ENABLED;
    public static final ModConfigSpec.IntValue CALENDAR_YEAR_LENGTH_DAYS;
    public static final ModConfigSpec.IntValue CALENDAR_START_MONTH;
    public static final ModConfigSpec.BooleanValue CALENDAR_SYNC_SERENE_SEASONS;
    public static final ModConfigSpec.DoubleValue CALENDAR_SPRING_STRENGTH;
    public static final ModConfigSpec.DoubleValue CALENDAR_SUMMER_STRENGTH;
    public static final ModConfigSpec.DoubleValue CALENDAR_AUTUMN_STRENGTH;
    public static final ModConfigSpec.DoubleValue CALENDAR_WINTER_STRENGTH;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> CALENDAR_MONTH_MULTIPLIERS;
    public static final ModConfigSpec.BooleanValue TORNADO_GENESIS_PLAINS_ONLY;
    public static final ModConfigSpec.BooleanValue DEADLY_STORM_FROM_AIR;
    public static final ModConfigSpec.DoubleValue DEADLY_STORM_CAPE_FLOOR_JKG;
    public static final ModConfigSpec.DoubleValue DEADLY_STORM_CAPE_FULL_JKG;
    public static final ModConfigSpec.DoubleValue DEADLY_STORM_SHEAR_SHARE;
    public static final ModConfigSpec.DoubleValue DEADLY_STORM_FULL_SHEAR_MPS;
    public static final ModConfigSpec.DoubleValue LANDSPOUT_SPAWN_CHANCE;
    public static final ModConfigSpec.DoubleValue WATERSPOUT_SPAWN_CHANCE;
    public static final ModConfigSpec.DoubleValue FUNNEL_CLOUD_SPAWN_CHANCE;
    public static final ModConfigSpec.IntValue FUNNEL_CLOUD_TIP_ALTITUDE_BLOCKS;
    public static final ModConfigSpec.DoubleValue DUST_DEVIL_SPAWN_CHANCE;
    public static final ModConfigSpec.DoubleValue GUSTNADO_SPAWN_CHANCE;
    public static final ModConfigSpec.BooleanValue DERECHOS_ENABLED;
    public static final ModConfigSpec.DoubleValue DERECHO_SWATH_BLOCKS;
    public static final ModConfigSpec.DoubleValue DERECHO_MIN_LINE_LENGTH_BLOCKS;
    public static final ModConfigSpec.BooleanValue DERECHO_TREE_FLATTENING;
    public static final ModConfigSpec.BooleanValue DERECHO_ROOF_STRIPPING;
    public static final ModConfigSpec.IntValue DERECHO_MAX_EDITS_PER_PASS;
    public static final ModConfigSpec.IntValue DERECHO_PASS_INTERVAL_TICKS;
    public static final ModConfigSpec.BooleanValue MONSOON_ENABLED;
    public static final ModConfigSpec.IntValue MONSOON_SEASON_DAYS;
    public static final ModConfigSpec.IntValue MONSOON_PERIOD_DAYS;
    public static final ModConfigSpec.DoubleValue MONSOON_RAIN_INTENSITY;
    public static final ModConfigSpec.DoubleValue MONSOON_FLOOD_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue MONSOON_SKY_SHARE;
    public static final ModConfigSpec.BooleanValue LAVA_WHIRLS_ENABLED;
    public static final ModConfigSpec.BooleanValue STEAM_DEVILS_ENABLED;
    public static final ModConfigSpec.DoubleValue LAVA_WHIRL_MIN_INTENSITY;
    public static final ModConfigSpec.BooleanValue LAVA_WHIRL_DAMAGE;
    public static final ModConfigSpec.DoubleValue LAVA_WHIRL_SPILL_CHANCE;
    public static final ModConfigSpec.BooleanValue STEAM_DEVIL_SCALD_DAMAGE;
    public static final ModConfigSpec.BooleanValue HANDHELD_INSTRUMENTS_ENABLED;
    public static final ModConfigSpec.IntValue HANDHELD_READOUT_COOLDOWN_TICKS;
    public static final ModConfigSpec.BooleanValue SENSOR_NETWORK_ENABLED;
    public static final ModConfigSpec.IntValue SENSOR_WIRE_MAX_LENGTH;
    public static final ModConfigSpec.BooleanValue REINFORCED_GLASS_ENABLED;
    public static final ModConfigSpec.BooleanValue REBAR_ANCHORING_ENABLED;
    public static final ModConfigSpec.IntValue REBAR_ANCHOR_HEIGHT;
    public static final ModConfigSpec.BooleanValue METEOROLOGIST_ENABLED;
    public static final ModConfigSpec.DoubleValue HURRICANE_SPAWN_CHANCE;
    public static final ModConfigSpec.BooleanValue HURRICANE_SHIELD_ENABLED;
    public static final ModConfigSpec.DoubleValue HURRICANE_EYE_FRACTION;
    public static final ModConfigSpec.DoubleValue HURRICANE_SHIELD_SIZE_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue HURRICANE_DISC_SPIN_MULTIPLIER;
    public static final ModConfigSpec.IntValue HURRICANE_BAND_COUNT;
    public static final ModConfigSpec.DoubleValue HURRICANE_BAND_SHEAR;
    public static final ModConfigSpec.DoubleValue FLOATER_TORNADO_SPAWN_CHANCE;
    public static final ModConfigSpec.DoubleValue SNOW_DEVIL_SPAWN_CHANCE;
    public static final ModConfigSpec.DoubleValue WILDFIRE_SPAWN_CHANCE;
    public static final ModConfigSpec.DoubleValue FIRE_WHIRL_SPAWN_CHANCE;
    public static final ModConfigSpec.IntValue HURRICANE_MAX_ACTIVE;
    public static final ModConfigSpec.BooleanValue ANTI_CYCLONIC_TORNADOES_ENABLED;
    public static final ModConfigSpec.DoubleValue ANTI_CYCLONIC_TORNADO_CHANCE;
    public static final ModConfigSpec.BooleanValue ANTI_CYCLONIC_INVERT_WEATHER2_SPIN;
    public static final ModConfigSpec.IntValue MAX_ADDON_VORTICES;
    public static final ModConfigSpec.BooleanValue SQUALL_LINES_ENABLED;
    public static final ModConfigSpec.IntValue SQUALL_LINE_MIN_CELLS;
    public static final ModConfigSpec.IntValue SQUALL_LINE_MAX_THICKNESS_BLOCKS;
    public static final ModConfigSpec.IntValue SQUALL_LINE_MAX_AGE_TICKS;
    public static final ModConfigSpec.BooleanValue SQUALL_LINE_STORM_DECK;
    public static final ModConfigSpec.BooleanValue ANEMOMETER_ENABLED;

    public static final ModConfigSpec.BooleanValue FLOODING_ENABLED;
    public static final ModConfigSpec.IntValue FLOODING_SCAN_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue FLOODING_MAX_BLOCKS_PER_TICK;
    public static final ModConfigSpec.IntValue FLOODING_RANGE_BLOCKS;
    public static final ModConfigSpec.DoubleValue FLOODING_RAIN_THRESHOLD;
    public static final ModConfigSpec.IntValue FLOODING_MAX_DEPTH_BLOCKS;

    public static final ModConfigSpec.BooleanValue INFLOW_JETS_ENABLED;
    public static final ModConfigSpec.IntValue INFLOW_JET_LENGTH_BLOCKS;
    public static final ModConfigSpec.IntValue INFLOW_JET_PARTICLES_PER_TICK;

    public static final ModConfigSpec.BooleanValue VISUALS_ENABLED;
    public static final ModConfigSpec.BooleanValue EXTENDED_RENDERING_ENABLED;
    public static final ModConfigSpec.DoubleValue SEVERE_SKY_TINT_STRENGTH;
    public static final ModConfigSpec.BooleanValue STORM_SKY_DARKENING;
    public static final ModConfigSpec.DoubleValue STORM_SKY_DARKEN_SCALE;
    public static final ModConfigSpec.DoubleValue NIGHT_STORM_DARKNESS;
    public static final ModConfigSpec.BooleanValue SKY_HAIL_GREEN;
    public static final ModConfigSpec.DoubleValue HAIL_SKY_GREEN_STRENGTH;
    public static final ModConfigSpec.BooleanValue STORM_COVER_SHADING;
    public static final ModConfigSpec.DoubleValue STORM_COVER_DECK_SCALE;
    public static final ModConfigSpec.BooleanValue LEAF_EDDIES_ENABLED;
    public static final ModConfigSpec.IntValue LEAF_EDDY_RANGE_BLOCKS;
    public static final ModConfigSpec.BooleanValue CLEAR_INTERVALS_ENABLED;
    public static final ModConfigSpec.DoubleValue CLEAR_INTERVAL_RAIN_REDUCTION;
    public static final ModConfigSpec.DoubleValue SKY_SMOOTHING;
    public static final ModConfigSpec.IntValue MIN_SKY_UPDATE_TICKS;
    public static final ModConfigSpec.BooleanValue STICK_DEBRIS_ENABLED;
    public static final ModConfigSpec.IntValue STICK_DEBRIS_RENDER_RANGE_BLOCKS;
    public static final ModConfigSpec.IntValue MAX_STICK_DEBRIS_PER_TICK;
    public static final ModConfigSpec.DoubleValue SUB_VORTEX_STICK_DENSITY;
    public static final ModConfigSpec.DoubleValue LANDSPOUT_STICK_DENSITY;
    public static final ModConfigSpec.DoubleValue DUST_DEVIL_STICK_DENSITY;
    public static final ModConfigSpec.DoubleValue WATERSPOUT_STICK_DENSITY;
    public static final ModConfigSpec.DoubleValue SQUALL_LINE_STICK_DENSITY;
    public static final ModConfigSpec.BooleanValue TORNADO_DEBRIS_CLOUD_ENABLED;
    public static final ModConfigSpec.IntValue TORNADO_DEBRIS_CLOUD_RENDER_RANGE_BLOCKS;
    public static final ModConfigSpec.IntValue TORNADO_DEBRIS_CLOUD_MAX_PARTICLES_PER_TICK;
    public static final ModConfigSpec.DoubleValue TORNADO_DEBRIS_CLOUD_DENSITY;
    public static final ModConfigSpec.DoubleValue TORNADO_DEBRIS_CLOUD_OPACITY;
    public static final ModConfigSpec.BooleanValue TORNADO_DEBRIS_CLOUD_HUMIDITY_GATE;
    public static final ModConfigSpec.DoubleValue TORNADO_DEBRIS_CLOUD_HUMIDITY_SCALE;
    public static final ModConfigSpec.DoubleValue TORNADO_DEBRIS_CLOUD_KICKUP_DENSITY;
    public static final ModConfigSpec.BooleanValue FOLIAGE_WIND_SHADER;
    public static final ModConfigSpec.BooleanValue FOLIAGE_WIND_USE_PHYSICS_MOD;
    public static final ModConfigSpec.BooleanValue FOLIAGE_WIND_LEAVES;
    public static final ModConfigSpec.BooleanValue FOLIAGE_WIND_GRASS;
    public static final ModConfigSpec.IntValue FOLIAGE_WIND_RENDER_RANGE_BLOCKS;
    public static final ModConfigSpec.DoubleValue FOLIAGE_WIND_MAX_OFFSET_BLOCKS;
    public static final ModConfigSpec.DoubleValue FOLIAGE_WIND_BASELINE_MPH;
    public static final ModConfigSpec.DoubleValue FOLIAGE_WIND_THRASH_MPH;
    public static final ModConfigSpec.DoubleValue FOLIAGE_WIND_GUST_WAVELENGTH_BLOCKS;
    public static final ModConfigSpec.DoubleValue FOLIAGE_WIND_GUST_SPEED_SCALE;
    public static final ModConfigSpec.DoubleValue FOLIAGE_WIND_REST_LEAN;
    public static final ModConfigSpec.DoubleValue FOLIAGE_WIND_SKY_FLOOR;
    public static final ModConfigSpec.DoubleValue FOLIAGE_WIND_WAVE_LAG_DEGREES_PER_BLOCK;
    public static final ModConfigSpec.BooleanValue FOLIAGE_WIND_TRAIL;
    public static final ModConfigSpec.IntValue FOLIAGE_WIND_TRAIL_RANGE_BLOCKS;
    public static final ModConfigSpec.DoubleValue FOLIAGE_WIND_TRAIL_RADIUS_BLOCKS;
    public static final ModConfigSpec.DoubleValue FOLIAGE_WIND_TRAIL_STRENGTH;
    public static final ModConfigSpec.IntValue FOLIAGE_WIND_TRAIL_LINGER_TICKS;
    public static final ModConfigSpec.BooleanValue OCEAN_WAVES_ENABLED;
    public static final ModConfigSpec.IntValue OCEAN_WAVES_RENDER_RADIUS_BLOCKS;
    public static final ModConfigSpec.IntValue OCEAN_WAVES_CELL_SIZE;
    public static final ModConfigSpec.IntValue OCEAN_WAVES_LATTICE_REFRESH_TICKS;
    public static final ModConfigSpec.IntValue OCEAN_WAVES_WIND_RANGE_BLOCKS;
    public static final ModConfigSpec.DoubleValue OCEAN_WAVES_MAX_AMPLITUDE_BLOCKS;
    public static final ModConfigSpec.DoubleValue OCEAN_WAVES_WAVELENGTH_BLOCKS;
    public static final ModConfigSpec.DoubleValue OCEAN_WAVES_FULL_WIND_MPH;
    public static final ModConfigSpec.DoubleValue OCEAN_WAVES_BASELINE_WIND_MPH;
    public static final ModConfigSpec.DoubleValue OCEAN_WAVES_MAX_STEEPNESS;
    public static final ModConfigSpec.DoubleValue OCEAN_WAVES_SWELL_BUILD_SECONDS;
    public static final ModConfigSpec.DoubleValue OCEAN_WAVES_SWELL_DECAY_SECONDS;
    public static final ModConfigSpec.DoubleValue OCEAN_WAVES_SURFACE_OPACITY;
    public static final ModConfigSpec.DoubleValue OCEAN_WAVES_FOAM_THRESHOLD;
    public static final ModConfigSpec.DoubleValue OCEAN_WAVES_FOAM_STRENGTH;
    public static final ModConfigSpec.DoubleValue OCEAN_WAVES_SHOAL_DEPTH_BLOCKS;
    public static final ModConfigSpec.BooleanValue OCEAN_WAVES_SHORE_FIT;
    public static final ModConfigSpec.BooleanValue OCEAN_WAVES_SURF_ENABLED;
    public static final ModConfigSpec.DoubleValue OCEAN_WAVES_SURF_BREAK_RATIO;
    public static final ModConfigSpec.DoubleValue OCEAN_WAVES_SURF_SHOAL_GAIN;
    public static final ModConfigSpec.DoubleValue OCEAN_WAVES_SURF_RUN_UP_BLOCKS;
    public static final ModConfigSpec.DoubleValue OCEAN_WAVES_SURF_FOAM;
    public static final ModConfigSpec.DoubleValue FOLIAGE_WIND_SMOOTHING;

    /**
     * The three storm looks, selected by {@link #STORM_LOOK}.
     *
     * <p>Both funnels Weather2 can draw are particle systems, and each half has what the other lacks. The
     * layered funnel holds structure - a ring of particles per block of height, at a radius that chases
     * {@code getTornadoBaseSize()}, which is the number this mod makes dynamic, so it has a real taper and a
     * real width to drive. Weather2's own 1.12.2 scatter has no taper at all (it fills one list with puffs at
     * a single height, spread over a stage-dependent radius) but it is soft, because the art it was built
     * against is faint and hundreds of those puffs stack into cloud instead of into a surface.
     */
    public enum StormLook {
        /** Weather2's own 1.12.2 scatter funnel, over the stock-faint sheet: soft, and free of any taper. */
        CLASSIC,
        /** This mod's layered funnel and storm layers, over the dense sheets: structured, and heaviest. */
        MODERN,
        /**
         * The modern look, with the 1.12.2 character: the layered funnel and every system this mod adds stay
         * exactly as they are, and the change is in the art - the funnel draws a mid-density sheet instead of the
         * dense one, so a puff reads as cloud and a stack of them stops short of a solid interior. That softness
         * is the one thing the 1.12.2 look had that the modern one lost, and it is added without Weather2's own
         * 1.12.2 funnel geometry: no scatter, no taper lost. The classic scatter is available on top as
         * {@code classicCollar}, off by default.
         */
        COMBINED
    }

    public static final ModConfigSpec.EnumValue<StormLook> STORM_LOOK;

    /** Draws Weather2's puffs without depth writes, in order, and feathered into what they touch. */
    public static final ModConfigSpec.BooleanValue STORM_SOFT_PUFFS;
    public static final ModConfigSpec.BooleanValue STORM_LOOK_CLASSIC_COLLAR;
    public static final ModConfigSpec.BooleanValue STORM_LOOK_ORIGINAL_TEXTURES;
    public static final ModConfigSpec.BooleanValue STORM_LOOK_BILLBOARD_PHASES;

    public static final ModConfigSpec.BooleanValue IMMERSIVE_THUNDER_ENABLED;
    public static final ModConfigSpec.DoubleValue THUNDER_SECONDS_PER_MILE;
    public static final ModConfigSpec.IntValue THUNDER_MAX_DELAY_TICKS;

    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_ENABLED;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_ROAR_SOUND;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_ROAR_LOUDNESS;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_ROAR_RADIUS_BLOCKS;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_ROAR_NEAR_RANGE_BLOCKS;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_ROAR_DISTANCE_ROLLOFF;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_ROAR_DISTANCE_LAYER;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_REPLACE_WEATHER2_WIND;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_CAMERA_RUMBLE;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_MAX_CAMERA_ROLL_DEGREES;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_CAMERA_TUMBLE;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_MAX_TUMBLE_ROLL_DEGREES;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_TUMBLE_SPIN_DEGREES;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_TUMBLE_SPEED_MULTIPLIER;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_FOV_PRESSURE;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_FOG_PRESSURE;
    public static final ModConfigSpec.BooleanValue MORNING_FOG_ENABLED;
    public static final ModConfigSpec.DoubleValue MORNING_FOG_DENSITY;
    public static final ModConfigSpec.BooleanValue MIST_ENABLED;
    public static final ModConfigSpec.DoubleValue MIST_DENSITY;
    public static final ModConfigSpec.DoubleValue MIST_RADIUS_BLOCKS;
    public static final ModConfigSpec.DoubleValue MIST_HEIGHT_BLOCKS;
    public static final ModConfigSpec.DoubleValue MIST_WIND_SPEED_MPH;
    public static final ModConfigSpec.DoubleValue MIST_HAZE;
    public static final ModConfigSpec.DoubleValue MIST_PUFF_SIZE;
    public static final ModConfigSpec.DoubleValue MIST_RAIN_MIST;
    public static final ModConfigSpec.DoubleValue MIST_RAIN_FOG;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_CLOSE_DEBRIS;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_INSIDE_CORE_EFFECTS;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_INFLOW_SHADER_OVERLAY;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_EYE_EFFECT;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_BLOWOUT_AUDIO;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_INTENSE_EF3_EFFECTS;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_EF3_WIND_THRESHOLD_MPH;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_HUM_SOUND;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_HUM_WIND_THRESHOLD_MPH;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_HUM_VOLUME;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_HUM_RADIUS_BLOCKS;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_WIND_BED;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_WIND_BED_VOLUME;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_WIND_BED_RADIUS_BLOCKS;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_EF3_ROAR_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_EF3_CAMERA_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_INSIDE_FOG_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_INFLOW_OVERLAY_OPACITY;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_EYE_CLEAR_RADIUS;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_BLOWOUT_AUDIO_INTENSITY;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_WHITEOUT_ENABLED;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_WHITEOUT_FOG_BLOCKS;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_WHITEOUT_VEIL_OPACITY;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_WHITEOUT_RADIUS_FRACTION;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_WHITEOUT_DEBRIS;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_WHITEOUT_STORM_TINT;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_WHITEOUT_SHELTER;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_WHITEOUT_SHELTER_EXPOSURE;
    public static final ModConfigSpec.IntValue TORNADO_IMMERSION_WHITEOUT_VOLUME_RADIUS;
    public static final ModConfigSpec.IntValue TORNADO_IMMERSION_WHITEOUT_VOLUME_SLICES;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_WHITEOUT_EXTINCTION_PER_BLOCK;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_WHITEOUT_BLOCKS_PER_TILE;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_WHITEOUT_DRIFT_BLOCKS_PER_SECOND;
    public static final ModConfigSpec.IntValue TORNADO_IMMERSION_CLOSE_EFFECT_RADIUS_BLOCKS;
    public static final ModConfigSpec.IntValue TORNADO_IMMERSION_RENDER_RANGE_BLOCKS;
    public static final ModConfigSpec.IntValue TORNADO_IMMERSION_MAX_PARTICLES_PER_TICK;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_ACCESSIBILITY_REDUCED_MOTION;

    public static final ModConfigSpec.BooleanValue CELESTIAL_ENABLED;
    public static final ModConfigSpec.BooleanValue CELESTIAL_ROUND;
    public static final ModConfigSpec.DoubleValue CELESTIAL_SUN_SIZE;
    public static final ModConfigSpec.DoubleValue CELESTIAL_MOON_SIZE;
    public static final ModConfigSpec.DoubleValue CELESTIAL_SUN_BRIGHTNESS;
    public static final ModConfigSpec.DoubleValue CELESTIAL_SUN_GLOW;
    public static final ModConfigSpec.DoubleValue CELESTIAL_MOON_BRIGHTNESS;
    public static final ModConfigSpec.DoubleValue CELESTIAL_MOON_GLOW;
    public static final ModConfigSpec.DoubleValue CELESTIAL_ECLIPSE_MOON_SCALE;
    public static final ModConfigSpec.DoubleValue CELESTIAL_ECLIPSE_CORONA;
    public static final ModConfigSpec.BooleanValue CELESTIAL_ECLIPSE_DIAMOND_RING;
    public static final ModConfigSpec.BooleanValue CELESTIAL_MILKY_WAY;
    public static final ModConfigSpec.DoubleValue CELESTIAL_MILKY_WAY_BRIGHTNESS;
    public static final ModConfigSpec.DoubleValue CELESTIAL_ECLIPSE_CHANCE;
    public static final ModConfigSpec.DoubleValue CELESTIAL_ECLIPSE_DURATION_SECONDS;
    public static final ModConfigSpec.DoubleValue CELESTIAL_ECLIPSE_DARKEN;
    public static final ModConfigSpec.BooleanValue CELESTIAL_ECLIPSE_DIM;
    public static final ModConfigSpec.BooleanValue CELESTIAL_ECLIPSE_UNDER_SHADERS;
    public static final ModConfigSpec.BooleanValue CELESTIAL_LUNAR_ECLIPSE;
    public static final ModConfigSpec.DoubleValue CELESTIAL_LUNAR_ECLIPSE_CHANCE;
    public static final ModConfigSpec.DoubleValue CELESTIAL_LUNAR_ECLIPSE_DURATION_SECONDS;

    public static final ModConfigSpec.BooleanValue CYCLOIDAL_MARKS_ENABLED;
    public static final ModConfigSpec.IntValue CYCLOIDAL_MIN_STAGE;
    public static final ModConfigSpec.IntValue CYCLOIDAL_MAX_BLOCKS_PER_TICK;
    public static final ModConfigSpec.IntValue CYCLOIDAL_SWATH_SAMPLES;
    public static final ModConfigSpec.BooleanValue CYCLOIDAL_COSMETIC_ONLY;

    public static final ModConfigSpec.BooleanValue ADVANCEMENTS_ENABLED;

    public static final ModConfigSpec.IntValue MULTIPLAYER_SYNC_RANGE_BLOCKS;
    public static final ModConfigSpec.IntValue MULTIPLAYER_STATE_SYNC_INTERVAL_TICKS;

    public static final ModConfigSpec.BooleanValue LOD_COMPAT_ENABLED;
    public static final ModConfigSpec.BooleanValue LOD_COMPAT_AUTO_DISTANCE;
    public static final ModConfigSpec.IntValue LOD_COMPAT_VIEW_DISTANCE_BLOCKS;
    public static final ModConfigSpec.IntValue LOD_COMPAT_MAX_VIEW_DISTANCE_BLOCKS;
    public static final ModConfigSpec.BooleanValue LOD_COMPAT_EXTEND_FOG;
    public static final ModConfigSpec.BooleanValue LOD_COMPAT_EXTEND_STORMS;
    public static final ModConfigSpec.IntValue LOD_COMPAT_STORM_DISTANCE_BLOCKS;

    public static final ModConfigSpec.BooleanValue VOLUMETRIC_DOWNFALL_ENABLED;
    public static final ModConfigSpec.BooleanValue VOLUMETRIC_DOWNFALL_BYPASS_VANILLA_NEAR_CELLS;
    public static final ModConfigSpec.IntValue VOLUMETRIC_DOWNFALL_RENDER_RANGE_BLOCKS;
    public static final ModConfigSpec.IntValue VOLUMETRIC_DOWNFALL_MAX_CELLS_RENDERED;
    public static final ModConfigSpec.IntValue VOLUMETRIC_DOWNFALL_MAX_VERTICES_PER_FRAME;
    public static final ModConfigSpec.IntValue VOLUMETRIC_DOWNFALL_HEIGHTMAP_SAMPLE_STEP_BLOCKS;
    public static final ModConfigSpec.DoubleValue VOLUMETRIC_DOWNFALL_RAIN_OPACITY;
    public static final ModConfigSpec.DoubleValue VOLUMETRIC_DOWNFALL_HAIL_OPACITY;
    public static final ModConfigSpec.DoubleValue VOLUMETRIC_DOWNFALL_HAIL_TINT_THRESHOLD;
    public static final ModConfigSpec.DoubleValue VOLUMETRIC_DOWNFALL_SHEET_SIZE_MULTIPLIER;
    public static final ModConfigSpec.BooleanValue VOLUMETRIC_DOWNFALL_SHAFT_TEXTURES;
    public static final ModConfigSpec.ConfigValue<String> VOLUMETRIC_DOWNFALL_SHADER_COMPATIBILITY_MODE;

    /** Draws a storm's precipitation as a shaded volume instead of as billboard sheets. */
    public static final ModConfigSpec.BooleanValue RAIN_VOLUME_ENABLED;

    /** How many camera-facing slices the volume is drawn with. More is smoother and costs more fill. */
    public static final ModConfigSpec.IntValue RAIN_VOLUME_SLICES;

    /** Thickness of the rain: how much light a hundred blocks of it takes out. */
    public static final ModConfigSpec.DoubleValue RAIN_VOLUME_OPACITY;

    /** How far away the volume is drawn at all. It is fill-bound, so this is the main cost knob. */
    public static final ModConfigSpec.IntValue RAIN_VOLUME_RANGE_BLOCKS;

    /** How fast the falling threads in the volume run down their lanes. */
    public static final ModConfigSpec.DoubleValue RAIN_VOLUME_FALL_SPEED;

    /** Whether the rain is still drawn as a volume when a shader pack has replaced the custom program. */
    public static final ModConfigSpec.BooleanValue RAIN_VOLUME_PACK_PATH;

    /** Slices in the pack-safe mesh path. It is CPU-shaded, so this is cheaper than the program's. */
    public static final ModConfigSpec.IntValue RAIN_VOLUME_PACK_SLICES;

    /** How finely each slice of the pack-safe mesh path is divided. */
    public static final ModConfigSpec.IntValue RAIN_VOLUME_PACK_DETAIL;

    /** Whether the volumetric rain fades into the world's fog with distance instead of staying crisp. */
    public static final ModConfigSpec.BooleanValue RAIN_VOLUME_FOG;

    /** How much of the light the storm's own air takes out of what is a hundred blocks away inside it. */
    public static final ModConfigSpec.DoubleValue RAIN_VOLUME_FOG_MIST;

    /** How much the storm's mist follows the rain's own density, so the clear slot stays clear. */
    public static final ModConfigSpec.DoubleValue RAIN_VOLUME_FOG_DENSITY;

    /** Whether the sun, the sky's darkening and lightning strikes light the volumetric rain. */
    public static final ModConfigSpec.BooleanValue RAIN_VOLUME_LIGHT;

    /** How lit the rain is at its darkest, so a night storm's rain is dark blue rather than black. */
    public static final ModConfigSpec.DoubleValue RAIN_VOLUME_LIGHT_FLOOR;

    /** The weight on the extra light a strike gives the rain it is standing in. */
    public static final ModConfigSpec.DoubleValue RAIN_VOLUME_FLASH_LIGHT;

    public static final ModConfigSpec.BooleanValue VANILLA_RAIN_ENABLED;
    public static final ModConfigSpec.BooleanValue VANILLA_RAIN_WIND_BEND;
    public static final ModConfigSpec.DoubleValue VANILLA_RAIN_WIND_BEND_STRENGTH;
    public static final ModConfigSpec.DoubleValue VANILLA_RAIN_WIND_BEND_MAX_LEAN_BLOCKS;

    public static final ModConfigSpec.BooleanValue LIGHTNING_BOLT_REVAMP_ENABLED;
    public static final ModConfigSpec.BooleanValue LIGHTNING_BOLT_PACK_PATH;
    public static final ModConfigSpec.IntValue LIGHTNING_BOLT_HEIGHT_BLOCKS;
    public static final ModConfigSpec.IntValue LIGHTNING_BOLT_RANGE_BLOCKS;
    public static final ModConfigSpec.DoubleValue LIGHTNING_BOLT_CORE_WIDTH;
    public static final ModConfigSpec.DoubleValue LIGHTNING_BOLT_HALO_WIDTH;
    public static final ModConfigSpec.IntValue LIGHTNING_BOLT_BRANCHES;
    public static final ModConfigSpec.BooleanValue LIGHTNING_BOLT_GROUND_GLOW_ENABLED;
    public static final ModConfigSpec.DoubleValue LIGHTNING_BOLT_GROUND_GLOW_RADIUS;
    public static final ModConfigSpec.DoubleValue LIGHTNING_BOLT_BRIGHTNESS;
    public static final ModConfigSpec.DoubleValue LIGHTNING_BOLT_CORE_HEAT;
    public static final ModConfigSpec.DoubleValue LIGHTNING_BOLT_FILAMENTS;
    public static final ModConfigSpec.BooleanValue LIGHTNING_BOLT_STROBES;
    public static final ModConfigSpec.BooleanValue LIGHTNING_DISCHARGE_TYPES_ENABLED;
    public static final ModConfigSpec.DoubleValue LIGHTNING_ANVIL_CRAWLER_CHANCE;
    public static final ModConfigSpec.DoubleValue LIGHTNING_CLOUD_TO_CLOUD_CHANCE;
    public static final ModConfigSpec.IntValue LIGHTNING_CRAWLER_LENGTH_BLOCKS;
    public static final ModConfigSpec.IntValue LIGHTNING_CLOUD_BASE_OFFSET_BLOCKS;
    public static final ModConfigSpec.BooleanValue LIGHTNING_SKY_FLASH_ENABLED;
    public static final ModConfigSpec.DoubleValue LIGHTNING_SKY_FLASH_BRIGHTNESS;
    public static final ModConfigSpec.BooleanValue LIGHTNING_SKY_FLASH_CLOUDS;
    public static final ModConfigSpec.BooleanValue LIGHTNING_SKY_FLASH_STROBES;
    public static final ModConfigSpec.BooleanValue LIGHTNING_SKY_FLASH_DISTANCE_FALLOFF;
    public static final ModConfigSpec.BooleanValue LOW_PRESSURE_ENABLED;
    public static final ModConfigSpec.IntValue LOW_PRESSURE_SCAN_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue LOW_PRESSURE_MIN_STORMS;
    public static final ModConfigSpec.IntValue LOW_PRESSURE_MAX_DISPLAY_RANGE_BLOCKS;
    public static final ModConfigSpec.BooleanValue LOW_PRESSURE_NATURAL_SPAWN_ENABLED;
    public static final ModConfigSpec.IntValue LOW_PRESSURE_MAX_NATURAL_LOWS;
    public static final ModConfigSpec.IntValue LOW_PRESSURE_GENESIS_INTERVAL_TICKS;
    public static final ModConfigSpec.DoubleValue LOW_PRESSURE_NATURAL_SPAWN_CHANCE;
    public static final ModConfigSpec.DoubleValue LOW_PRESSURE_MIN_FRONTOGENESIS;
    public static final ModConfigSpec.IntValue LOW_PRESSURE_GENESIS_WINDOW_BLOCKS;
    public static final ModConfigSpec.DoubleValue LOW_PRESSURE_STEERING_COEFFICIENT;
    public static final ModConfigSpec.IntValue LOW_PRESSURE_LIFE_TICKS;
    public static final ModConfigSpec.IntValue LOW_PRESSURE_MERGE_DISTANCE_BLOCKS;
    public static final ModConfigSpec.BooleanValue LOW_PRESSURE_FRONTAL_RISK;
    public static final ModConfigSpec.DoubleValue LOW_PRESSURE_FRONT_BAND_FRACTION;

    /** Whether the world has belts of rising and sinking air, which is where lows come from. */
    public static final ModConfigSpec.BooleanValue THERMAL_CIRCULATION_ENABLED;

    /** Half-width of the rising belt around the equator line. */
    public static final ModConfigSpec.IntValue THERMAL_RISING_BELT_WIDTH_BLOCKS;

    /** How far poleward of the equator line the baroclinic zone - the low genesis corridor - sits. */
    public static final ModConfigSpec.IntValue THERMAL_GENESIS_BELT_OFFSET_BLOCKS;

    /** Width of the transition between two belts. */
    public static final ModConfigSpec.IntValue THERMAL_BELT_TRANSITION_BLOCKS;

    /** How strongly the rising belt's supply feeds and deepens a low. */
    public static final ModConfigSpec.DoubleValue THERMAL_RISING_SUPPLY_STRENGTH;

    /** How strongly the belts steer what drifts through them. */
    public static final ModConfigSpec.DoubleValue THERMAL_TRADE_WIND_STRENGTH;

    /** Whether a low seeds its own convection on its fronts. */
    public static final ModConfigSpec.BooleanValue FRONTAL_CONVECTION_ENABLED;

    public static final ModConfigSpec.IntValue FRONTAL_CONVECTION_INTERVAL_TICKS;

    public static final ModConfigSpec.DoubleValue FRONTAL_CONVECTION_CELL_CHANCE;

    public static final ModConfigSpec.IntValue FRONTAL_CONVECTION_MAX_CELLS_PER_LOW;

    /** Whether a true outbreak can run at all. */
    public static final ModConfigSpec.BooleanValue OUTBREAK_ENABLED;

    public static final ModConfigSpec.DoubleValue OUTBREAK_MIN_LOW_INTENSITY;

    public static final ModConfigSpec.IntValue OUTBREAK_CORRIDOR_BLOCKS;

    public static final ModConfigSpec.IntValue OUTBREAK_DURATION_TICKS;

    public static final ModConfigSpec.IntValue OUTBREAK_CELL_COUNT;

    public static final ModConfigSpec.IntValue OUTBREAK_CELL_INTERVAL_TICKS;

    /** How solid the outlook areas drawn on a forecast display's plate are. */
    public static final ModConfigSpec.DoubleValue FORECAST_POLYGON_FILL_OPACITY;

    /** Whether the significant-hazard areas are hatched, as a real outlook hatches them. */
    public static final ModConfigSpec.BooleanValue FORECAST_POLYGON_HATCHING;

    /** Distance between the hatch lines, in plate units. */
    public static final ModConfigSpec.DoubleValue FORECAST_POLYGON_HATCH_SPACING;

    /** Whether a warning polygon is marked at its corners. */
    public static final ModConfigSpec.BooleanValue FORECAST_POLYGON_CORNER_MARKERS;

    /** Whether a forecast display's numbers stand up on a panel facing the player instead of lying flat. */
    public static final ModConfigSpec.BooleanValue FORECAST_READOUT_PANEL;

    /** How much bigger or smaller that panel is than the size the mod works out for it. */
    public static final ModConfigSpec.DoubleValue FORECAST_READOUT_SCALE;

    public static final ModConfigSpec.BooleanValue AI_VILLAGER_TORNADO_SHELTER;
    public static final ModConfigSpec.IntValue AI_VILLAGER_SHELTER_RADIUS_BLOCKS;
    public static final ModConfigSpec.IntValue AI_VILLAGER_SHELTER_SCAN_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue AI_VILLAGER_SHELTER_LEAD_TICKS;
    public static final ModConfigSpec.BooleanValue AI_VILLAGER_SHELTER_SPREAD;
    public static final ModConfigSpec.BooleanValue AI_VILLAGER_SHELTER_WAKE_SLEEPERS;
    public static final ModConfigSpec.BooleanValue AI_VILLAGER_SHELTER_AVOID_TRACK;
    public static final ModConfigSpec.BooleanValue AI_VILLAGER_CLOSE_DOORS;
    public static final ModConfigSpec.BooleanValue AI_VILLAGER_PANIC;
    public static final ModConfigSpec.BooleanValue AI_ANIMAL_HERDING;
    public static final ModConfigSpec.IntValue AI_ANIMAL_HERD_RADIUS_BLOCKS;
    public static final ModConfigSpec.IntValue AI_ANIMAL_HERD_THREAT_BLOCKS;
    public static final ModConfigSpec.IntValue AI_ANIMAL_HERD_SCAN_INTERVAL_TICKS;
    public static final ModConfigSpec.DoubleValue AI_ANIMAL_HERD_SPEED;
    public static final ModConfigSpec.BooleanValue AI_VILLAGER_SURVEYOR_ENABLED;
    public static final ModConfigSpec.IntValue AI_VILLAGER_SURVEYOR_RANGE_BLOCKS;
    public static final ModConfigSpec.IntValue AI_VILLAGER_SURVEYOR_COOLDOWN_TICKS;
    public static final ModConfigSpec.IntValue AI_VILLAGER_SURVEYOR_ARRIVAL_BLOCKS;
    public static final ModConfigSpec.BooleanValue AI_VILLAGER_SURVEYOR_SIGN;
    public static final ModConfigSpec.BooleanValue AI_VILLAGER_BUILDER_ENABLED;
    public static final ModConfigSpec.IntValue AI_VILLAGER_BUILDER_RANGE_BLOCKS;
    public static final ModConfigSpec.BooleanValue AI_VILLAGER_BUILDER_TRACK_DAMAGE;
    public static final ModConfigSpec.IntValue AI_VILLAGER_BUILDER_TICKS_PER_BLOCK;
    public static final ModConfigSpec.IntValue AI_VILLAGER_BUILDER_BLOCKS_PER_TRIP;
    public static final ModConfigSpec.IntValue AI_VILLAGER_BUILDER_COOLDOWN_TICKS;
    public static final ModConfigSpec.BooleanValue AI_VILLAGER_BUILDER_MATERIALS;
    public static final ModConfigSpec.IntValue AI_VILLAGER_BUILDER_CARRY_LIMIT;
    public static final ModConfigSpec.IntValue AI_VILLAGER_BUILDER_CHEST_RANGE_BLOCKS;
    public static final ModConfigSpec.BooleanValue AI_VILLAGER_BUILDER_CONSTRUCTION;
    public static final ModConfigSpec.IntValue AI_VILLAGER_BUILDER_BUILDINGS;
    public static final ModConfigSpec.IntValue AI_VILLAGER_BUILDER_SITE_RANGE_BLOCKS;
    public static final ModConfigSpec.BooleanValue AI_VILLAGER_BUILDER_PURCHASING;
    public static final ModConfigSpec.IntValue AI_VILLAGER_BUILDER_PURCHASE_RANGE_BLOCKS;
    public static final ModConfigSpec.BooleanValue AI_VILLAGER_BUILDER_TRADE_INTAKE;
    public static final ModConfigSpec.BooleanValue AI_VILLAGER_LUMBERJACK_ENABLED;
    public static final ModConfigSpec.IntValue AI_VILLAGER_LUMBERJACK_RANGE_BLOCKS;
    public static final ModConfigSpec.IntValue AI_VILLAGER_LUMBERJACK_COOLDOWN_TICKS;
    public static final ModConfigSpec.IntValue AI_VILLAGER_LUMBERJACK_LOGS_PER_TRIP;
    public static final ModConfigSpec.IntValue AI_VILLAGER_LUMBERJACK_TICKS_PER_LOG;
    public static final ModConfigSpec.BooleanValue AI_VILLAGER_LUMBERJACK_TRACK_TIMBER;
    public static final ModConfigSpec.IntValue AI_VILLAGER_LUMBERJACK_CHEST_RANGE_BLOCKS;

    public static final ModConfigSpec.BooleanValue IMMERSIVE_VEHICLES_ENABLED;
    public static final ModConfigSpec.IntValue IMMERSIVE_VEHICLES_SCAN_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue IMMERSIVE_VEHICLES_SCAN_RANGE_BLOCKS;
    public static final ModConfigSpec.IntValue IMMERSIVE_VEHICLES_MAX_VEHICLES_PER_TICK;
    public static final ModConfigSpec.DoubleValue IMMERSIVE_VEHICLES_WIND_FORCE_SCALE;
    public static final ModConfigSpec.DoubleValue IMMERSIVE_VEHICLES_LIFT_FORCE_SCALE;
    public static final ModConfigSpec.DoubleValue IMMERSIVE_VEHICLES_FLIP_TORQUE_SCALE;
    public static final ModConfigSpec.DoubleValue IMMERSIVE_VEHICLES_MAX_MOTION_DELTA_PER_TICK;
    public static final ModConfigSpec.BooleanValue IMMERSIVE_VEHICLES_STALLING_ENABLED;
    public static final ModConfigSpec.DoubleValue IMMERSIVE_VEHICLES_STALL_WIND_MPH;
    public static final ModConfigSpec.BooleanValue IMMERSIVE_VEHICLES_OPEN_COCKPIT_EJECT;
    public static final ModConfigSpec.DoubleValue IMMERSIVE_VEHICLES_OPEN_COCKPIT_EJECT_MPH;
    public static final ModConfigSpec.BooleanValue IMMERSIVE_VEHICLES_STRUCTURAL_STRESS_DAMAGE;

    public static final ModConfigSpec.BooleanValue TREE_PHYSICS_COMPAT_ENABLED;
    public static final ModConfigSpec.IntValue TREE_PHYSICS_SCAN_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue TREE_PHYSICS_MAX_FALLING_TREE_ENTITIES;
    public static final ModConfigSpec.DoubleValue TREE_PHYSICS_MIN_TPS;
    public static final ModConfigSpec.DoubleValue TREE_PHYSICS_WEAK_ORBIT_SCALE;
    public static final ModConfigSpec.DoubleValue TREE_PHYSICS_STRONG_LIFT_SCALE;
    public static final ModConfigSpec.DoubleValue TREE_PHYSICS_VIOLENT_LIFT_SCALE;
    public static final ModConfigSpec.BooleanValue STRUCTURE_CRUSHER_ENABLED;
    public static final ModConfigSpec.DoubleValue STRUCTURE_CRUSHER_MIN_KINETIC_ENERGY;
    public static final ModConfigSpec.IntValue STRUCTURE_CRUSHER_MAX_BLOCKS_PER_TICK;

    public static final ModConfigSpec.IntValue PERFORMANCE_STORM_SCAN_INTERVAL_TICKS;

    public static final ModConfigSpec.BooleanValue WEATHER_BALLOON_ENABLED;
    public static final ModConfigSpec.DoubleValue WEATHER_BALLOON_ASCENT_BLOCKS_PER_SECOND;
    public static final ModConfigSpec.IntValue WEATHER_BALLOON_MAX_ALTITUDE_BLOCKS;
    public static final ModConfigSpec.DoubleValue WEATHER_BALLOON_WIND_DRIFT_SCALE;
    public static final ModConfigSpec.DoubleValue WEATHER_BALLOON_BURST_WIND_MPH;
    public static final ModConfigSpec.DoubleValue WEATHER_BALLOON_DESCENT_BLOCKS_PER_SECOND;
    public static final ModConfigSpec.IntValue WEATHER_BALLOON_TELEMETRY_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue WEATHER_BALLOON_STORM_RANGE_BLOCKS;
    public static final ModConfigSpec.BooleanValue WEATHER_BALLOON_RETURN_ON_LANDING;
    public static final ModConfigSpec.IntValue WEATHER_BALLOON_LANDED_LIFETIME_TICKS;
    public static final ModConfigSpec.IntValue WEATHER_BALLOON_MAX_LIFETIME_TICKS;
    public static final ModConfigSpec.IntValue WEATHER_BALLOON_MAX_ACTIVE_PER_PLAYER;
    public static final ModConfigSpec.IntValue WEATHER_BALLOON_RENDER_RANGE_BLOCKS;

    // ------------------------------------------------------------------ volcanoes
    public static final ModConfigSpec.BooleanValue VOLCANO_ENABLED;
    public static final ModConfigSpec.BooleanValue VOLCANO_NATURAL_SPAWNING;
    public static final ModConfigSpec.IntValue VOLCANO_SCAN_INTERVAL_TICKS;
    public static final ModConfigSpec.DoubleValue VOLCANO_SPAWN_CHANCE;
    public static final ModConfigSpec.IntValue VOLCANO_MIN_SPAWN_INTERVAL_TICKS;
    public static final ModConfigSpec.DoubleValue VOLCANO_SPAWN_MIN_DISTANCE_BLOCKS;
    public static final ModConfigSpec.DoubleValue VOLCANO_SPAWN_MAX_DISTANCE_BLOCKS;
    public static final ModConfigSpec.IntValue VOLCANO_MIN_SPACING_BLOCKS;
    public static final ModConfigSpec.DoubleValue VOLCANO_PROVINCE_THRESHOLD;
    public static final ModConfigSpec.IntValue VOLCANO_MIN_RELIEF_BLOCKS;
    public static final ModConfigSpec.DoubleValue VOLCANO_MAX_INTENSITY;
    public static final ModConfigSpec.IntValue VOLCANO_QUIESCENCE_MIN_TICKS;
    public static final ModConfigSpec.IntValue VOLCANO_QUIESCENCE_MAX_TICKS;
    public static final ModConfigSpec.IntValue VOLCANO_WARNING_TICKS;
    public static final ModConfigSpec.IntValue VOLCANO_PHREATIC_TICKS;
    public static final ModConfigSpec.IntValue VOLCANO_COLUMN_TICKS;
    public static final ModConfigSpec.IntValue VOLCANO_COLLAPSE_TICKS;
    public static final ModConfigSpec.IntValue VOLCANO_FLOW_TICKS;
    public static final ModConfigSpec.IntValue VOLCANO_WANING_TICKS;
    public static final ModConfigSpec.IntValue VOLCANO_HAZARD_RADIUS_BLOCKS;
    public static final ModConfigSpec.IntValue VOLCANO_ADVISORY_RANGE_BLOCKS;
    public static final ModConfigSpec.IntValue VOLCANO_BLOCK_BUDGET;
    public static final ModConfigSpec.IntValue VOLCANO_STATE_SYNC_INTERVAL_TICKS;
    public static final ModConfigSpec.BooleanValue VOLCANO_LAVA_FLOWS;
    public static final ModConfigSpec.IntValue VOLCANO_LAVA_FLOW_COUNT;
    public static final ModConfigSpec.DoubleValue VOLCANO_LAVA_FLOW_ACCELERATION;
    public static final ModConfigSpec.BooleanValue VOLCANO_LAVA_BOMBS;
    public static final ModConfigSpec.BooleanValue VOLCANO_ASH_DEPOSITS;
    public static final ModConfigSpec.IntValue VOLCANO_ASH_RADIUS_BLOCKS;
    public static final ModConfigSpec.IntValue VOLCANO_ASH_INTERVAL_TICKS;
    public static final ModConfigSpec.BooleanValue VOLCANO_ASH_LIGHTNING;
    public static final ModConfigSpec.BooleanValue VOLCANO_PYROCLASTIC_DAMAGE;
    public static final ModConfigSpec.BooleanValue VOLCANO_EARTHQUAKES;
    public static final ModConfigSpec.BooleanValue VOLCANO_LAHARS;
    public static final ModConfigSpec.DoubleValue VOLCANO_LAHAR_MIN_RAIN;
    public static final ModConfigSpec.DoubleValue VOLCANO_LAHAR_MIN_ASH_MM;
    public static final ModConfigSpec.DoubleValue VOLCANO_LAHAR_MAX_ASH_AGE_DAYS;
    public static final ModConfigSpec.BooleanValue VOLCANO_ALERTS;
    public static final ModConfigSpec.BooleanValue VOLCANO_SOUNDS;
    public static final ModConfigSpec.BooleanValue VOLCANO_SHAKE;
    public static final ModConfigSpec.BooleanValue VOLCANO_ASH_HAZE;
    public static final ModConfigSpec.DoubleValue VOLCANO_ASH_HAZE_STRENGTH;
    public static final ModConfigSpec.IntValue VOLCANO_RENDER_RANGE_BLOCKS;

    /**
     * The simple settings: one dial per thing a player wants to change, scaling what the detailed sections
     * already decide. Read through the helpers below rather than directly, so a dial has one meaning.
     */
    public static final ModConfigSpec.DoubleValue SIMPLE_STORM_FREQUENCY;
    public static final ModConfigSpec.IntValue SIMPLE_MAX_STORMS;
    public static final ModConfigSpec.IntValue SIMPLE_STORM_LIMIT_RANGE_BLOCKS;
    public static final ModConfigSpec.DoubleValue SIMPLE_STORM_SIZE;
    public static final ModConfigSpec.DoubleValue SIMPLE_TORNADO_STRENGTH;
    public static final ModConfigSpec.DoubleValue SIMPLE_RAIN_STRENGTH;
    public static final ModConfigSpec.BooleanValue SIMPLE_DESTRUCTION;
    public static final ModConfigSpec.DoubleValue SIMPLE_SEASON_STRENGTH;
    public static final ModConfigSpec.BooleanValue SIMPLE_ONLY_SPAWN_TORNADOES_IN_PLAINS;

    static {
        BUILDER.push("simple");
        SIMPLE_STORM_FREQUENCY = BUILDER.comment("How often storms come, as a multiplier on every chance the mod rolls for one - the supercells, the squall lines, the landspouts and waterspouts, the dust devils, the fire whirls and the hurricanes. 1.0 is the mod as it is tuned, 3.0 is a sky that is rarely quiet, 0.2 is one storm every few days, and 0 is a world with no weather in it at all. The detailed chances in the sections below this one still decide the shape of what comes - which environment produces what, and how a supercell grows - so this is a frequency dial rather than a difficulty one.")
                .defineInRange("stormFrequency", 1.0D, 0.0D, 8.0D);
        SIMPLE_MAX_STORMS = BUILDER.comment("The most storms the mod will keep going within the range below, after which it stops promoting storms to tornado-capable and stops spawning its own events until one of them dies. This is the dial for a machine that struggles, or for a world where a tornado is an event rather than a background: 1 means at most one at a time, 3 is a busy day, and 0 means no limit at all, which is the mod's own behaviour. It counts the storms the mod itself is running, not the ordinary thunderstorms Weather2 spawns on its own.")
                .defineInRange("maxStorms", 0, 0, 10);
        SIMPLE_STORM_LIMIT_RANGE_BLOCKS = BUILDER.comment("How near a storm has to be to the player to count against the limit above. A distant storm is not in the way of anything, so counting the whole world's weather would have a storm in another biome quietly suppressing the one forming over your head.")
                .defineInRange("stormLimitRangeBlocks", 1024, 256, 4096);
        SIMPLE_STORM_SIZE = BUILDER.comment("How big tornadoes get, as a multiplier on both the width that damages and the width you see - so 2.0 is a tornado twice the size of the one the detailed width settings describe, funnel and damage path together, and 0.5 is half of it. At the top of its range it is the three-mile wedge the width settings allow, and past that there is nothing left to scale.")
                .defineInRange("stormSize", 1.0D, 0.0D, 4.0D);
        SIMPLE_TORNADO_STRENGTH = BUILDER.comment("How violent storms are, as a multiplier on every wind speed the mod reads out of a storm: the winds a player and a mob are pushed by, what a block is damaged at, whether a roof tears off, and the strength an anemometer reports. 1.0 is the mod's own intensities - an EF3 is an EF3 - 1.5 makes every storm a step stronger, 0.5 halves them into a world of weak tornadoes, and 0 makes even a mile-wide wedge a wind that cannot lift a fence post. The stage a storm reaches is untouched: this scales the wind of a stage rather than deciding which stage it gets to.")
                .defineInRange("tornadoStrength", 1.0D, 0.0D, 4.0D);
        SIMPLE_RAIN_STRENGTH = BUILDER.comment("How hard it rains, as a multiplier on the thickness of a storm's rain whichever way it is drawn - the volumetric volume, the pack-safe mesh and the sheets. 1.0 is the mod's own rain, 2.0 is a torrential curtain that hides what is behind it, 0.5 is a shower. It cannot be turned all the way off: rain drawn at zero would be a thunderstorm overhead with nothing falling out of it, so the dial bottoms out at a thin veil.")
                .defineInRange("rainStrength", 1.0D, 0.0D, 4.0D);
        SIMPLE_DESTRUCTION = BUILDER.comment("Whether storms may break anything at all. Off is a world where a tornado still forms, still tracks, still picks up debris and still throws entities around, but never breaks a block, never scours the ground, never strips a tree and never lifts a structure - the one switch for a peaceful server, and the only way to be sure nothing of the mod's can edit terrain. It can only take destruction away, never add it: the damage section's own switch and the per-system settings still apply, so this is a master off and not a force on.")
                .define("destruction", true);
        SIMPLE_SEASON_STRENGTH = BUILDER.comment("How much the world's seasons matter, as a multiplier on the whole calendar: 1.0 is the mod's own year - spring's tornadoes, summer's pulse storms, autumn's flow and winter's fronts - 2.0 makes every season twice as far from the neutral day, and 0 makes the year turn without the sky ever noticing, every day as neutral as every other. The season_calendar section's own strengths still decide the shape of each season; this is the dial for whether the seasons are a thing in your world at all.")
                .defineInRange("seasonStrength", 1.0D, 0.0D, 2.0D);
        SIMPLE_ONLY_SPAWN_TORNADOES_IN_PLAINS = BUILDER.comment("Whether a land tornado may only be born over plains country. Off lets any storm anywhere spin one up, which is how the mod behaved before the rule existed. Like the destruction switch it can only ever restrict: the tornado_genesis section's own switch still applies, so this is a master off and never a force on, and whirls and waterspouts - which belong to their own country rather than to the plains - are unaffected either way.")
                .define("onlySpawnTornadoesInPlains", true);
        BUILDER.pop();

        BUILDER.push("persistence");
        PERSISTENCE_ENABLED = BUILDER.comment("Persists addon storm state such as tornado width, bend, alert cooldowns, and satellite tornadoes through Weather2 storm NBT.")
                .define("enabled", true);
        BUILDER.pop();

        BUILDER.push("dynamics");
        DYNAMICS_ENABLED = BUILDER.comment("Enables unpredictable Weather2 tornado motion.")
                .define("enabled", true);
        TORNADO_WANDER_MAX_DEGREES = BUILDER.comment("Maximum continuous sinuous heading wander added to Weather2 tornado movement (used when atmospheric steering is off). Weaker and rope tornadoes wander up to this; violent wedges track much straighter.")
                .defineInRange("tornadoWanderMaxDegrees", 38.0D, 0.0D, 120.0D);
        TORNADO_WANDER_SMOOTHING = BUILDER.comment("Legacy smoothing rate for the old stepwise wander target; the continuous wander no longer uses it.")
                .defineInRange("tornadoWanderSmoothing", 0.045D, 0.001D, 1.0D);
        TORNADO_WANDER_MIN_RETARGET_TICKS = BUILDER.defineInRange("tornadoWanderMinRetargetTicks", 60, 1, 20 * 60 * 10);
        TORNADO_WANDER_MAX_RETARGET_TICKS = BUILDER.defineInRange("tornadoWanderMaxRetargetTicks", 180, 1, 20 * 60 * 10);
        TORNADO_FUNNEL_BEND_ENABLED = BUILDER.comment("Makes Weather2 tornado funnels visibly lean, bend, and curve by offsetting funnel layers.")
                .define("tornadoFunnelBend", true);
        TORNADO_FORMING_ANIMATION_ENABLED = BUILDER.comment("Lifecycle funnel animation: condensation descends from the cloud base down while the tornado forms (on the genesis timeline below) and the funnel ropes out from the ground up when it decays. Turning this off pops the funnel in at full height while genesis still controls its strength.")
                .define("tornadoFormingAnimation", true);
        TORNADO_FUNNEL_BEND_MAX_BLOCKS = BUILDER.defineInRange("tornadoFunnelBendMaxBlocks", 72.0D, 0.0D, 240.0D);
        TORNADO_FUNNEL_BEND_SWAY_BLOCKS = BUILDER.defineInRange("tornadoFunnelBendSwayBlocks", 20.0D, 0.0D, 120.0D);
        TORNADO_FUNNEL_BEND_SMOOTHING = BUILDER.defineInRange("tornadoFunnelBendSmoothing", 0.075D, 0.001D, 1.0D);
        TORNADO_FUNNEL_BEND_ANCHOR_FRACTION = BUILDER.defineInRange("funnelBendAnchorFraction", 0.06D, 0.0D, 0.95D);
        TORNADO_FUNNEL_BEND_MAX_HEIGHT_FRACTION = BUILDER.defineInRange("funnelBendMaxHeightFraction", 1.0D, 0.0D, 1.0D);
        TORNADO_FUNNEL_TILT_BLOCKS = BUILDER.comment("How far the top of the funnel leans downshear (along the storm's motion direction) at full height. The lean grows smoothly with height and is stronger for weak/rope tornadoes than violent wedges.")
                .defineInRange("tornadoFunnelTiltBlocks", 52.0D, 0.0D, 240.0D);
        TORNADO_FUNNEL_TILT_WEAKNESS_SCALE = BUILDER.comment("Multiplier applied to the downshear tilt for weak and rope tornadoes - the weaker the funnel, the more it leans over. 0 disables the weakness scaling entirely.")
                .defineInRange("tornadoFunnelTiltWeaknessScale", 1.0D, 0.0D, 2.5D);
        TORNADO_ELEPHANT_TRUNK_ENABLED = BUILDER.comment("Allows narrow/moderate tornadoes to use an elephant-trunk visual morphology without changing tornado labels or windfield physics.")
                .define("elephantTrunkTornadoes", true);
        TORNADO_ELEPHANT_TRUNK_CHANCE = BUILDER.comment("Per-retarget chance for a narrow tornado to become an elephant-trunk visual variant. Kept low because this tag is rare and mutually exclusive with sidewinders and multi-vortex episodes.")
                .defineInRange("elephantTrunkChance", 0.07D, 0.0D, 1.0D);
        TORNADO_ELEPHANT_TRUNK_MIN_STAGE = BUILDER.comment("Minimum Weather2 tornado intensity stage for elephant-trunk visuals. Weather2 forming stage is normally 4.")
                .defineInRange("elephantTrunkMinStage", 4, 0, 32);
        TORNADO_ELEPHANT_TRUNK_MAX_VISIBLE_WIDTH_FRACTION = BUILDER.comment("Maximum visible-funnel to damage-path width fraction for elephant-trunk visuals. Wider wedge-like funnels are blocked.")
                .defineInRange("elephantTrunkMaxVisibleWidthFraction", 0.70D, 0.05D, 1.0D);
        TORNADO_ELEPHANT_TRUNK_CURVE_STRENGTH = BUILDER.defineInRange("elephantTrunkCurveStrength", 1.25D, 0.0D, 3.0D);
        TORNADO_SHAPE_CHANGE_SECONDS = BUILDER.comment("How long a funnel takes to morph from one shape into the next, in seconds. A funnel takes the shape its width, height and age call for: a rope when it is thin or dying, a cone or plain funnel in between, and a stovepipe or bowl when it is broad. It holds a shape for at least a minute before it may change again.")
                .defineInRange("shapeChangeSeconds", 30.0D, 2.0D, 300.0D);
        TORNADO_DRILL_BIT_ENABLED = BUILDER.comment("Allows rare drill-bit tornado morphology: a very narrow, fast-spinning funnel that can still carry strong windfield damage.")
                .define("drillBitTornadoes", true);
        TORNADO_DRILL_BIT_CHANCE = BUILDER.comment("Per-retarget chance for an eligible tornado to lock into the drill-bit tag. The tag is mutually exclusive with sidewinders, elephant trunks, and multi-vortex episodes.")
                .defineInRange("drillBitChance", 0.035D, 0.0D, 1.0D);
        TORNADO_DRILL_BIT_MIN_STAGE = BUILDER.comment("Minimum Weather2 tornado intensity stage for drill-bit visuals.")
                .defineInRange("drillBitMinStage", 5, 0, 32);
        TORNADO_ADDITIONAL_SHAPES_ENABLED = BUILDER.comment("Adds cone, stovepipe, rope, corkscrew, and bowl funnel morphologies to the weighted shape pool. Weighted by width and stage: ropes favor weak narrow tornadoes, corkscrews favor violent ones, stovepipes and bowls favor wider paths.")
                .define("additionalTornadoShapes", true);
        TORNADO_ADDITIONAL_SHAPES_CHANCE = BUILDER.comment("Global multiplier for how often an eligible tornado locks into one of the additional shapes (cone/stovepipe/rope/corkscrew/bowl). 0 disables them entirely; 2 doubles their frequency.")
                .defineInRange("additionalShapesChance", 1.0D, 0.0D, 2.0D);
        TORNADO_WEDGE_CHANCE = BUILDER.comment("How often a tornado that grows past the wedge width actually takes the wedge shape. Rolled once each time it crosses into that width and held until it shrinks back below it, so a storm is one shape for the length of an episode rather than flickering. Width is a measurement and the wedge is a look: a half-mile damage path can be a wedge, a large cone, a barrel or a massive multiple-vortex, and in the record violent tornadoes are all of those. The chance scales up with size, so a monster is more likely to read as a wedge than one that has only just crossed the threshold. 1 restores the old behaviour where any tornado past the threshold was always a wedge; 0 removes the wedge tag entirely while the funnel still grows to follow its damage path.")
                .defineInRange("wedgeChance", 0.55D, 0.0D, 1.0D);
        NO_PLAYER_AIM_ON_SPAWN = BUILDER.comment("Prevents natural Weather2 tornadoes from steering directly at the nearest player when they form.")
                .define("noPlayerAimOnSpawn", true);
        ENVIRONMENTAL_INTENSITY_ENABLED = BUILDER.comment("Allows tornadoes to reintensify or deintensify based on nearby environment.")
                .define("environmentalIntensity", true);
        ENVIRONMENT_SCAN_INTERVAL_TICKS = BUILDER.defineInRange("environmentScanIntervalTicks", 100, 20, 20 * 60 * 5);
        ENVIRONMENT_REINTENSIFY_THRESHOLD = BUILDER.defineInRange("environmentReintensifyThreshold", 0.68D, 0.0D, 1.0D);
        ENVIRONMENT_DEINTENSIFY_THRESHOLD = BUILDER.defineInRange("environmentDeintensifyThreshold", 0.28D, 0.0D, 1.0D);
        TORNADO_STRENGTHENING_ENABLED = BUILDER.comment("Lets a tornado climb past the intensity ceiling it was born with when the air around it is exceptional. Every natural tornado rolls a maximum rung at birth - that is what makes EF0s common and EF5s rare - but a storm sitting in violent air should not be stuck at a bicycle-speed ceiling for its whole life, which is what the roll alone meant: a tornado already at its own maximum could only ever weaken. With this on, a storm whose environment score reaches the strengthening threshold while it is already at its ceiling earns one more rung, up to the top of the ladder, and the ordinary reintensify path then spends the next few minutes actually climbing it.")
                .define("tornadoStrengthening", true);
        TORNADO_STRENGTHENING_SCORE = BUILDER.comment("Environment score a tornado must reach before it may climb past the ceiling it was born with, on the same 0..1 scale as environmentReintensifyThreshold. Set it above that threshold deliberately: this is the over-performing storm, not merely a favourable one.")
                .defineInRange("strengtheningEnvironmentScore", 0.86D, 0.0D, 1.0D);
        TORNADO_STRENGTHENING_MAX_RUNGS = BUILDER.comment("Most rungs past its birth ceiling one tornado may ever earn. Each rung takes at least the intensity-change cooldown to win, so this bounds how far a monster can grow rather than how fast.")
                .defineInRange("strengtheningMaxRungs", 2, 0, 4);
        TORNADO_WEIGHTED_INTENSITY_DISTRIBUTION = BUILDER.comment("Reweights natural Weather2 tornado max-intensity rolls so EF0/EF1 are common, EF2/EF3 are uncommon, and EF4/EF5 are rare. This never sets the current storm stage directly.")
                .define("weightedTornadoIntensityDistribution", true);
        TORNADO_CAPABLE_RATE_BOOST = BUILDER.comment("Small multiplier for natural tornado-capable max-intensity rolls from the daily SPC outlook. Values above 1 make tornado-capable storms a little more common without instant spawning.")
                .defineInRange("tornadoCapableRateBoost", 1.18D, 0.0D, 4.0D);
        TORNADO_WIND_SPEED_SPIN_ENABLED = BUILDER.comment("Scales Weather2 tornado visual spin speed from the addon tornado windfield mph.")
                .define("windSpeedSpin", true);
        TORNADO_WIND_SPEED_SPIN_MIN = BUILDER.defineInRange("windSpeedSpinMin", 0.055D, 0.0D, 2.0D);
        TORNADO_WIND_SPEED_SPIN_MAX = BUILDER.defineInRange("windSpeedSpinMax", 0.36D, 0.0D, 2.0D);
        TORNADO_WIND_SPEED_SPIN_SMOOTHING = BUILDER.defineInRange("windSpeedSpinSmoothing", 0.12D, 0.001D, 1.0D);
        TORNADO_WIND_SPEED_SPIN_REFERENCE_MIN_MPH = BUILDER.defineInRange("windSpeedSpinReferenceMinMph", 65.0D, 0.0D, 500.0D);
        TORNADO_WIND_SPEED_SPIN_REFERENCE_MAX_MPH = BUILDER.defineInRange("windSpeedSpinReferenceMaxMph", 321.0D, 1.0D, 500.0D);
        ATMOSPHERIC_STEERING_ENABLED = BUILDER.comment("Binds natural Weather2 storm motion to Weather2's global wind grid with an ENE/right-of-flow steering bias.")
                .define("atmosphericSteering", true);
        ATMOSPHERIC_STEERING_COEFFICIENT = BUILDER.comment("Target storm translational speed multiplier: target velocity = global wind vector * this coefficient.")
                .defineInRange("atmosphericSteeringCoefficient", 0.38D, 0.0D, 4.0D);
        ATMOSPHERIC_STEERING_RIGHT_BIAS_DEGREES = BUILDER.comment("Clockwise right-of-flow rotation applied to the global wind vector. 15 degrees matches the default mesocyclone bias.")
                .defineInRange("atmosphericSteeringRightBiasDegrees", 15.0D, -90.0D, 90.0D);
        ATMOSPHERIC_STEERING_ENE_BIAS_STRENGTH = BUILDER.comment("Blends the rotated wind toward an east-northeast storm track. Set 0 for pure Weather2 wind-grid flow.")
                .defineInRange("atmosphericSteeringEneBiasStrength", 0.55D, 0.0D, 1.0D);
        ATMOSPHERIC_STEERING_WOBBLE_FRACTION = BUILDER.comment("Slow steering-current variability. This is deterministic meander, not per-tick random jitter; hurricanes use a stronger minimum. Tornado meander scales with strength: violent wedges track straight (as low as ~0.4x), weak and rope tornadoes dance harder (up to ~1.5x).")
                .defineInRange("atmosphericSteeringWobbleFraction", 0.32D, 0.0D, 1.5D);
        ATMOSPHERIC_STEERING_MIN_SPEED = BUILDER.comment("Minimum natural drift speed while atmospheric steering is active.")
                .defineInRange("atmosphericSteeringMinSpeed", 0.055D, 0.0D, 2.0D);
        ATMOSPHERIC_STEERING_MAX_SPEED = BUILDER.comment("Maximum natural drift speed from atmospheric steering before Weather2 sync.")
                .defineInRange("atmosphericSteeringMaxSpeed", 0.54D, 0.01D, 4.0D);
        BUILDER.pop();

        BUILDER.push("genesis");
        GENESIS_ENABLED = BUILDER.comment("Real-time tornado formation. Instead of the storm being handed full strength the tick its stage turns tornadic, it passes through organizing -> condensation descent -> touchdown -> mature on a real-time clock, and its wind, damage swath, dust whirl and funnel all build with it. Turn this off to get the old instant tornado back.")
                .define("enabled", true);
        GENESIS_DURATION_SECONDS = BUILDER.comment("Nominal seconds from the wall cloud starting to organize to a mature tornado on the ground. Real genesis runs from about 30 seconds to 15 minutes; the default is deliberately on the slow side so a tornado builds over minutes instead of arriving at full strength, and it is the main dial for how quickly a funnel can reach damaging wind.")
                .defineInRange("durationSeconds", 240.0D, 5.0D, 900.0D);
        GENESIS_INTENSITY_SCALE = BUILDER.comment("Violent tornadoes tighten up and reach the ground sooner than weak ones, which can churn for many minutes without putting a visible funnel down. Scales the duration from 1.18x at the weakest tornado stage down to 0.62x at the strongest.")
                .define("intensityScalesDuration", true);
        GENESIS_JITTER = BUILDER.comment("Per-storm variation in genesis length, as a fraction. 0.25 means each storm forms somewhere between 25% faster and 25% slower than nominal, so no two storms form in lockstep.")
                .defineInRange("durationJitter", 0.25D, 0.0D, 0.9D);
        GENESIS_START_WIND_FRACTION = BUILDER.comment("Fraction of the storm's stage wind present the moment it starts organizing. This is the weak circulation that turns below a lowering wall cloud before any condensation appears.")
                .defineInRange("startWindFraction", 0.03D, 0.0D, 0.9D);
        GENESIS_TOUCHDOWN_PROGRESS = BUILDER.comment("Point in the timeline where the condensation reaches the ground. 0.62 means the funnel docks with the surface whirl around 62% of the way through genesis, leaving the last third for widening and strengthening.")
                .defineInRange("touchdownProgress", 0.62D, 0.15D, 0.95D);
        GENESIS_PRE_TOUCHDOWN_DUST_WHIRL = BUILDER.comment("Turns up a dust whirl at the surface during the descent stage, before the condensation has reached the ground - the way real tornadoes announce themselves before the funnel is visible.")
                .define("preTouchdownDustWhirl", true);
        GENESIS_PRE_TOUCHDOWN_WHIRLS = BUILDER.comment("Scatters a swarm of small vortices over the ground while the condensation is still descending, drifting inward as they turn: the swirls real tornadoes show before they touch down. They are visual only - the storm's own wind ramp still decides the real circulation.")
                .define("preTouchdownWhirls", true);
        GENESIS_WHIRL_COUNT = BUILDER.comment("How many small vortices form over the whole descent. Together with the lifetime this sets how many turn at once, and a fast violent storm packs in more of them than a slow one. The survivors are absorbed by the funnel as it reaches the ground.")
                .defineInRange("whirlCount", 6, 0, 12);
        GENESIS_WHIRL_DURATION_SECONDS = BUILDER.comment("Average lifetime of one small vortex, in seconds, varied by about half either way per whirl. Real pre-touchdown dust whirls turn for a few seconds to a minute; raised, more turn at once.")
                .defineInRange("whirlDurationSeconds", 14.0D, 2.0D, 40.0D);
        BUILDER.pop();

        BUILDER.push("storm_lifecycle");
        STORM_LIFECYCLE_ENABLED = BUILDER.comment("The mod's own storm structure: Weather2's timer-driven staging is paced by this mod instead, so a storm is held on each rung of its ladder for a real dwell before it may climb to the next. Weather2 used to advance a storm on its own accumulation timer, which let a fresh cell reach a violent rung within a couple of minutes and be worth that force while its funnel was still a rope. Because the windfield reads the rung, pacing the ladder is also half of keeping a tornado from arriving over 150 mph. Storms driven by the command, a weather machine, a player or another feature (a squall line's cells, a hurricane's, cyclones and waterspouts) are exempt and stage exactly as before. Turn this off to restore Weather2's own pacing.")
                .define("enabled", true);
        STORM_LIFECYCLE_MIN_STAGE_SECONDS = BUILDER.comment("The dwell a naturally progressing storm is held on each rung of the severe ladder before it may advance again, in seconds. It applies from the thunderstorm rung upward and to ordinary land cells only. Longer means a storm takes minutes to organise into a supercell rather than seconds; shorter approaches Weather2's own behaviour. 45 seconds means a storm cannot climb more than about one rung a minute.")
                .defineInRange("minStageSeconds", 45.0D, 0.0D, 600.0D);
        BUILDER.pop();

        BUILDER.push("tornado_track");
        TRACK_REALISTIC_ENABLED = BUILDER.comment("Real-world tornado track behaviour: honest translation speeds in mph, right-of-motion deviant veer that grows with intensity, long slow arcing curvature instead of symmetric snaking, a multi-vortex sway that scallops the path, and a curvature limit so tracks can never kink. Disabling this restores the previous meander and speed clamps.")
                .define("realisticTracks", true);
        TRACK_SPEED_MIN_MPH = BUILDER.comment("Slowest a tornado track may translate, in miles per hour. Real slow movers still crawl forward; 6 mph is a realistic floor.")
                .defineInRange("translationSpeedMinMph", 6.0D, 0.0D, 120.0D);
        TRACK_SPEED_MAX_MPH = BUILDER.comment("Fastest a tornado track may translate, in miles per hour. Real tornadoes essentially never exceed 40 mph; Joplin averaged about 30 mph over its 22 mile path.")
                .defineInRange("translationSpeedMaxMph", 34.0D, 1.0D, 120.0D);
        TRACK_INTENSITY_SPEED_SLOWDOWN = BUILDER.comment("How much a violent tornado is slowed below the ambient flow speed. Strong tornadoes are deviant and slower than the wind carrying them; 0 keeps every tornado at flow speed.")
                .defineInRange("intensitySpeedSlowdown", 0.22D, 0.0D, 1.0D);
        TRACK_TERRAIN_SPEED_SLOWDOWN = BUILDER.comment("How much rough or hilly terrain bleeds off track speed, up to the roughness penalty. Real tornadoes slow slightly crossing broken terrain.")
                .defineInRange("terrainSpeedSlowdown", 0.30D, 0.0D, 1.0D);
        TRACK_SPEED_SURGE = BUILDER.comment("How much the track's forward speed varies over the tornado's life, as a fraction. The storm crawls while the mesocyclone cycles and then lunges forward; 0 holds one steady pace. The surge is applied before the translationSpeedMin/MaxMph clamp, so it can never push a tornado outside the honest 6-34 mph band - a storm already at the 34 mph ceiling can slow down but not exceed it. Weak and rope funnels surge hardest, violent wedges stay steadier.")
                .defineInRange("translationSpeedSurge", 0.42D, 0.0D, 1.2D);
        TRACK_MAX_TURN_DEGREES_PER_TICK = BUILDER.comment("Curvature limit for the track, in degrees per tick. 0.18 is about 3.6 degrees per second, enough for smooth long arcs while making a kinked or snapped heading impossible. Weak and rope tornadoes are allowed to turn faster than violent wedges.")
                .defineInRange("maxTurnDegreesPerTick", 0.18D, 0.01D, 2.0D);
        TRACK_DEVIANT_MOTION_MAX_DEGREES = BUILDER.comment("Extra clockwise (right-of-motion) veer at full intensity, on top of atmosphericSteeringRightBiasDegrees. Deviant motion is strongest on violent tornadoes - an EF5 can run 30 degrees right of the mean flow - and fades to nothing on weak ones.")
                .defineInRange("deviantMotionMaxDegrees", 22.0D, 0.0D, 60.0D);
        TRACK_CURVATURE_DEGREES = BUILDER.comment("Amplitude of the slow persistent track curvature, in degrees. The dominant term has a period of roughly 90 minutes, so a single tornado lifetime only sees part of one swing - a long arc like a real damage path, not a wiggle. The arc is biased toward veering right so paths mostly curve one way.")
                .defineInRange("trackCurvatureDegrees", 30.0D, 0.0D, 90.0D);
        TRACK_SCALLOP_DEGREES = BUILDER.comment("Periodic sway of the whole circulation while suction vortices orbit it, which scallops the edge of the damage path during multi-vortex episodes. Scaled by how much orbiting activity the tornado currently has, so single vortex tornadoes stay smooth.")
                .defineInRange("trackScallopDegrees", 3.0D, 0.0D, 25.0D);
        BUILDER.pop();

        BUILDER.push("widths");
        VARIABLE_WIDTHS_ENABLED = BUILDER.comment("Enables tornado width changes that are independent of windspeed.")
                .define("enabled", true);
        TORNADO_REALISTIC_WIDTH_MODEL = BUILDER.comment("Uses separate damage-path width, visible funnel width, core radius, and radius of maximum wind.")
                .define("realisticModel", true);
        TORNADO_WIDTH_MIN_MULTIPLIER = BUILDER.comment("Legacy multiplier used only when realisticModel=false. This is not miles.")
                .defineInRange("tornadoWidthMinMultiplier", 0.45D, 0.1D, 50.0D);
        TORNADO_WIDTH_MAX_MULTIPLIER = BUILDER.comment("Legacy multiplier used only when realisticModel=false. This is not miles.")
                .defineInRange("tornadoWidthMaxMultiplier", 2.35D, 0.1D, 50.0D);
        TORNADO_WIDTH_CHANGE_SECONDS = BUILDER.comment("About how long a tornado takes to settle on a new width, in seconds. It gathers pace into a change and eases out of it, and never grows or shrinks faster than about 3% of its own width a second, so a big change takes a minute or more.")
                .defineInRange("tornadoWidthChangeSeconds", 18.0D, 1.0D, 300.0D);
        TORNADO_WIDTH_TURBULENCE = BUILDER.comment("Width surges layered over the eased dynamic width. 0 holds the calm curve; higher swings the funnel further from that width, and it swings by arriving at a new width and holding it for the better part of a minute rather than by swelling and shrinking on a beat. Each storm draws its own levels, so funnels never surge in lockstep. Weak funnels surge hardest and wide wedges stay statelier. The damage path and wind field keep their calm width.")
                .defineInRange("tornadoWidthTurbulence", 0.24D, 0.0D, 0.6D);
        TORNADO_WIDTH_MIN_RETARGET_TICKS = BUILDER.defineInRange("widthRetargetMinTicks", 200, 1, 20 * 60 * 10);
        TORNADO_WIDTH_MAX_RETARGET_TICKS = BUILDER.defineInRange("widthRetargetMaxTicks", 700, 1, 20 * 60 * 10);
        TORNADO_MIN_DAMAGE_WIDTH_BLOCKS = BUILDER.comment("Minimum realistic damage-path width in blocks when minDamageWidthMiles is 0.")
                .defineInRange("minDamageWidthBlocks", 12.0D, 3.0D, MAX_CONFIG_WIDTH_BLOCKS);
        TORNADO_MAX_DAMAGE_WIDTH_BLOCKS = BUILDER.comment("Maximum realistic damage-path width in blocks when maxDamageWidthMiles is 0. One mile is 1609.344 blocks. Defaults now allow a 3-mile-wide monster.")
                .defineInRange("maxDamageWidthBlocks", 4828.032D, 3.0D, MAX_CONFIG_WIDTH_BLOCKS);
        TORNADO_MIN_DAMAGE_WIDTH_MILES = BUILDER.comment("Optional minimum damage-path width in miles. Set to 0 to use minDamageWidthBlocks.")
                .defineInRange("minDamageWidthMiles", 0.0D, 0.0D, MAX_CONFIG_WIDTH_MILES);
        TORNADO_MAX_DAMAGE_WIDTH_MILES = BUILDER.comment("Optional maximum damage-path width in miles. Set to 5.0 for a five-mile max path, which converts to 8046.72 blocks. Set to 0 to use maxDamageWidthBlocks.")
                .defineInRange("maxDamageWidthMiles", 1.0D, 0.0D, MAX_CONFIG_WIDTH_MILES);
        TORNADO_MAX_VISIBLE_FUNNEL_WIDTH_BLOCKS = BUILDER.comment("Safety cap for the rendered Weather2 funnel base size in blocks when maxVisibleFunnelWidthMiles is 0. Damage-path width can still be larger. Defaults now allow a 3-mile-wide monster.")
                .defineInRange("maxVisibleFunnelWidthBlocks", 4828.032D, 3.0D, MAX_CONFIG_WIDTH_BLOCKS);
        TORNADO_MAX_VISIBLE_FUNNEL_WIDTH_MILES = BUILDER.comment("Optional rendered funnel cap in miles. Set to 0 to use maxVisibleFunnelWidthBlocks. Huge values can be heavy because Weather2 also uses funnel size internally.")
                .defineInRange("maxVisibleFunnelWidthMiles", 0.0D, 0.0D, MAX_CONFIG_WIDTH_MILES);
        TORNADO_VISIBLE_FUNNEL_FRACTION_MIN = BUILDER.comment("Share of the damage path the condensation funnel covers in the driest air, when the visible share comes from the air (visibleFunnelFractionFromMoisture). With that setting off it is just the low end of the old random roll.")
                .defineInRange("visibleFunnelFractionMin", 0.35D, 0.05D, 1.0D);
        TORNADO_VISIBLE_FUNNEL_FRACTION_MAX = BUILDER.comment("Share of the damage path the condensation funnel covers in saturated air - raining, or over water in a warm biome. A dryline tornado and a rain-wrapped one can scour the same swath; this pair is what separates them.")
                .defineInRange("visibleFunnelFractionMax", 0.75D, 0.05D, 1.0D);
        TORNADO_SIZE_LIFECYCLE = BUILDER.comment("Gives each tornado a size life arc: it widens as it organizes, keeps that width through its climax, and gives it back as it ropes out. Off restores the old memoryless draw, where every width retarget rolls a fresh size from the stage ladder alone.")
                .define("tornadoSizeLifecycle", true);
        TORNADO_SIZE_TEMPERAMENT = BUILDER.comment("With the size lifecycle on, gives each storm a temperament fixed for its whole life, hashed from the storm: a thin-boned tornado stays thin and a big-boned one is wide from the start. Off re-rolls the temperament every retarget instead, so a storm can be a rope one minute and a wedge the next.")
                .define("tornadoSizeTemperament", true);
        TORNADO_SIZE_TEMPERAMENT_SKEW = BUILDER.comment("How rare the big ones are, when the size lifecycle is on. The ladder itself is geometric, so this is the population dial rather than a cap: 1 puts the median storm at the middle of the ladder, near 0.6 makes wedges and monsters common (over half of all storms), and 4 leaves cones and ropes holding the world. The default of 3 was chosen by measuring the population (SizeArcSim): the median storm comes out around 80 blocks of damage path and monsters stay under 5%, while the top percentile is still a wedge at half a mile. On the default one-mile ladder that is a world where an ordinary tornado is an ordinary size; lower it if you want the big ones commoner.")
                .defineInRange("tornadoSizeTemperamentSkew", 3.0D, 0.2D, 4.0D);
        TORNADO_SIZE_ROPE_OUT = BUILDER.comment("How much of its width a decaying tornado gives back as it ropes out, as a share of its own potential. 0.55 turns a wedge into a rope by the end of its life; 0 leaves a storm the same width until it dies.")
                .defineInRange("tornadoSizeRopeOut", 0.55D, 0.0D, 0.95D);
        TORNADO_VISIBLE_FRACTION_FROM_MOISTURE = BUILDER.comment("Takes the visible funnel's share of the damage path from the air the storm is standing in - warmth, rain and water - instead of rolling it. This is what lets a thin condensation funnel stand over a mile-wide damage swath in dry air, and a rain-wrapped wall of condensation do the same over the same swath in a saturated one.")
                .define("tornadoVisibleFractionFromMoisture", true);
        BUILDER.pop();

        BUILDER.push("damage");
        DAMAGE_ENABLED = BUILDER.comment("Enables balanced tornado damage limits and strength checks.")
                .define("enabled", true);
        GATE_STORM_SUMMONS = BUILDER.comment("Blocks storm-summoning commands for players who are not op or are in survival mode - /ewd spawn and Weather2's /weather2 summon alike. Server console and command blocks are unaffected. Vanilla commands like /summon and /gamemode are vanilla's to police.")
                .define("gateStormSummonCommands", true);
        DAMAGE_MAX_BLOCKS_PER_STORM_PER_TICK = BUILDER.comment("Base block-damage budget per storm per tick. Scales up with tornado stage (x1.2 EF2, x1.55 EF3, x2.1 EF4, x2.8 EF5) so violent tornadoes tear far more than weak ones.")
                .defineInRange("maxBlocksPerStormPerTick", 18, 0, 1000);
        DAMAGE_WIDTH_SCALED_BUDGET = BUILDER.comment("Scales the block-damage budget with damage-path width on top of the stage multiplier, so a mile-wide wedge actually clears its whole footprint instead of taking as long as a narrow rope. A 3-mile monster gets roughly 3x the base budget per tick; disable for the old flat-per-stage behaviour.")
                .define("widthScaledBudget", true);
        DAMAGE_BLOCK_STRENGTH_MIN_MULTIPLIER = BUILDER.defineInRange("blockStrengthMinMultiplier", 0.8D, 0.05D, 10.0D);
        DAMAGE_BLOCK_STRENGTH_MAX_MULTIPLIER = BUILDER.defineInRange("blockStrengthMaxMultiplier", 1.5D, 0.05D, 10.0D);
        DAMAGE_LOADED_CHUNKS_ONLY = BUILDER.define("loadedChunksOnly", true);
        DAMAGE_OPEN_DOORS = BUILDER.define("openDoorsInsteadOfRipping", true);
        DAMAGE_GRAB_LIVING_ENTITIES = BUILDER.define("grabLivingEntities", true);
        DAMAGE_GRAB_PLAYERS = BUILDER.define("grabPlayers", true);
        DAMAGE_GROUND_SCOURING = BUILDER.define("groundScouring", true);
        DAMAGE_TREE_DEBARKING = BUILDER.define("treeDebarking", true);
        DAMAGE_PRESERVE_INVENTORY_BLOCK_ENTITIES = BUILDER.comment("Keeps chests, barrels, furnaces, and other block entities from being destroyed by addon damage by default.")
                .define("preserveInventoryBlockEntities", true);
        DAMAGE_NEVER_PICKUP_SOIL = BUILDER.comment("Prevents tornado block pickup from turning grass, dirt, farmland, mud, and similar terrain into flying debris. Scouring can still convert the surface separately.")
                .define("neverPickupSoil", true);
        DAMAGE_WIND_RESISTANCE = BUILDER.comment("Requires local sampled tornado windspeed to beat material resistance before a block can be grabbed or destroyed.")
                .define("windResistance", true);
        DAMAGE_FLYING_BLOCK_IMPACT_DAMAGE = BUILDER.comment("Adds a small capped debris-impact damage pass near strong tornadoes without spawning real item spam.")
                .define("flyingBlockImpactDamage", true);
        DAMAGE_BLOCK_ENTITY_IMPACT_DAMAGE = BUILDER.comment("Allows debris-impact damage to affect non-inventory block entities. Inventory block entities are still preserved when preserveInventoryBlockEntities=true.")
                .define("blockEntityImpactDamage", false);
        DAMAGE_AFFECT_VALKYRIEN_SHIPS = BUILDER.comment("When false, skips tornado block pickup/scouring on Valkyrien Skies ship-managed blocks to avoid physics conflicts.")
                .define("affectValkyrienShips", false);
        DAMAGE_EF0_PICKUP_MPH = BUILDER.comment("Minimum wind for weak blocks such as plants and glass.")
                .defineInRange("ef0PickupMph", 65.0D, 0.0D, 500.0D);
        DAMAGE_WOOD_PICKUP_MPH = BUILDER.comment("Minimum wind for wood, doors, fences, slabs, and similar house materials. Keeps EF0 tornadoes from shredding houses.")
                .defineInRange("woodPickupMph", 115.0D, 0.0D, 500.0D);
        DAMAGE_STONE_PICKUP_MPH = BUILDER.comment("Minimum wind for logs, stone, brick, concrete, and sturdy structure blocks.")
                .defineInRange("stonePickupMph", 145.0D, 0.0D, 500.0D);
        DAMAGE_HEAVY_PICKUP_MPH = BUILDER.comment("Minimum wind for heavy metal or very hard blocks that are not explicitly protected.")
                .defineInRange("heavyPickupMph", 200.0D, 0.0D, 500.0D);
        DAMAGE_TREE_DEBARK_MPH = BUILDER.defineInRange("treeDebarkMph", 135.0D, 0.0D, 500.0D);
        DAMAGE_HEAVY_TREE_DEBARK_MPH = BUILDER.defineInRange("heavyTreeDebarkMph", 170.0D, 0.0D, 500.0D);
        DAMAGE_SCOURING_EXCAVATION = BUILDER.comment("Violent tornado cores (185+ mph damage wind) scour the topsoil clean off into shallow pits instead of only converting the surface. Matching the main swath, satellites, and subvortices.")
                .define("scouringExcavation", true);
        DAMAGE_LIGHT_SCOURING_STAGE = BUILDER.comment("Weather2 stage for light scouring. 5 is F1 / stage 1.")
                .defineInRange("lightScouringStage", 6, 4, 9);
        DAMAGE_MEDIUM_SCOURING_STAGE = BUILDER.defineInRange("mediumScouringStage", 7, 4, 9);
        DAMAGE_HEAVY_SCOURING_STAGE = BUILDER.defineInRange("heavyScouringStage", 8, 4, 9);
        BUILDER.pop();

        BUILDER.push("hail");
        HAIL_DAMAGE_ENABLED = BUILDER.comment("Hailstones land on things. The mod has always grown a hail core and drawn its shaft; this is the ice itself arriving: crops knocked down, canopy stripped, thin glass broken, farmland beaten flat, and anyone caught in the open hit by whatever size is falling. Off leaves hail exactly as it was - a reading the sky and the renderer use.")
                .define("hailDamage", true);
        HAIL_PLAYER_DAMAGE = BUILDER.comment("Hail hurts players. Below a quarter of an inch it does not hurt at all, whatever this says: the ladder in HailModel is what decides, and this only switches the top of it off.")
                .define("playerDamage", true);
        HAIL_MOB_DAMAGE = BUILDER.comment("Hail hurts animals and monsters too. A herd bunched in a field with no roof is the classic hailstorm casualty, so this is on by default; it takes the same sizes as players, with no helmet to help.")
                .define("mobDamage", true);
        HAIL_GLASS_DAMAGE = BUILDER.comment("Hail breaks glass. Panes go first - a pane is one sheet with nothing behind it, and a dime is enough - and a full block of glass needs a golf ball. Stained and tinted glass count as glass.")
                .define("glassDamage", true);
        HAIL_CROP_DAMAGE = BUILDER.comment("Hail knocks crops down. A quarter takes most of a field and a golf ball takes all of it; below a dime the stones bruise the plants without breaking them.")
                .define("cropDamage", true);
        HAIL_LEAF_DAMAGE = BUILDER.comment("Hail shreds leaves. Hail strips a canopy long before it does anything to the trunks, and a stripped tree is what a hailstorm's track looks like from the air.")
                .define("leafDamage", true);
        HAIL_GROUND_LOSS = BUILDER.comment("Hail beats farmland flat, turning tilled soil back to plain dirt. Only a quarter or bigger does it at all.")
                .define("groundLoss", true);
        HAIL_HELMET_PROTECTION = BUILDER.comment("How much of a hailstrike a helmet takes off. This is protection rather than immunity on purpose: at the default of 0.65 a baseball still hurts through an iron helmet, which is the honest reading of a cap in a hailstorm. Set it to 1.0 for complete immunity to anything from a quarter down and a third of the damage above it, or 0 to make helmets irrelevant.")
                .defineInRange("helmetProtection", 0.65D, 0.0D, 1.0D);
        HAIL_DAMAGE_SCALE = BUILDER.comment("Multiplier on every hailstrike's damage. Hail is the one hazard in this mod that can hit a player who is doing nothing wrong in a field, so this is the dial for a world that wants the stones to sting rather than to kill; 0 disables the damage while leaving crops, canopy and glass exactly as they are.")
                .defineInRange("damageScale", 1.0D, 0.0D, 4.0D);
        HAIL_CLATTER_ENABLED = BUILDER.comment("Hail that lands on things makes a sound, in the material it landed on: a wooden roof rattles, a stone one ticks, a metal one rings, a glass one pings, and the grass around you patters. The surface is read off the block's own sound type, so a roof of any new block still sounds like what it is made of, and what a player hears is the stones hitting the things around them rather than a looped hailstorm. Off leaves hail silent.")
                .define("clatter", true);
        HAIL_CLATTER_RADIUS = BUILDER.comment("How far around a player the stones are listened for, in blocks. A strike inside this radius is played where it happened and fades with distance, which is what puts the roof over your head in front of the barn across the field. It is a ceiling rather than the whole answer: the model caps it by the size of the stones, because a pea's clatter is not worth chasing across a field and a baseball's is. At 0 you hear only what is directly above or under you; past about 16 every strike in the shaft overlaps and the clatter stops telling you where it is.")
                .defineInRange("clatterRadius", 12, 0, 32);
        HAIL_CLATTER_VOLUME = BUILDER.comment("Multiplier on every piece of hail clatter. 0 is the same as switching the clatter off, 1 is the mod's own mix - measured so a baseball on a metal roof is loud and a pea in a field is barely there - and above 1 is for a world that wants the storm to fill the room.")
                .defineInRange("clatterVolume", 1.0D, 0.0D, 2.0D);
        BUILDER.pop();

        BUILDER.push("structure_lift");
        STRUCTURE_LIFT_ENABLED = BUILDER.comment("Lets a strong tornado tear a whole building off its foundation in one coherent sweep, instead of only nibbling the loose blocks out of it.")
                .define("enabled", true);
        STRUCTURE_LIFT_MIN_WIND_MPH = BUILDER.comment("Storm max wind needed before a building can be committed to demolition. 120 is a strong EF2, the point where real houses start losing their roofs.")
                .defineInRange("structureLiftMinWindMph", 120.0D, 0.0D, 400.0D);
        STRUCTURE_LIFT_MIN_STAGE = BUILDER.comment("Weather2 stage needed before a building can be committed. 6 is EF2, 7 is EF3.")
                .defineInRange("structureLiftMinStage", 6, 4, 9);
        STRUCTURE_LIFT_BLOCK_WIND_MPH = BUILDER.comment("Wind the funnel must actually deliver to a block before that block comes off the building. Keeps the fringe of a weak storm's damage path from stripping a house it barely grazed.")
                .defineInRange("blockTearMph", 90.0D, 0.0D, 400.0D);
        STRUCTURE_LIFT_ALLOW_MASONRY = BUILDER.comment("When false only wood, glass, and other light building materials are committed, leaving brick and concrete shells to the normal material rules.")
                .define("allowMasonry", true);
        STRUCTURE_LIFT_MIN_BLOCKS = BUILDER.comment("Smallest connected structure worth a demolition. Below this it stays a normal block-by-block pick-up.")
                .defineInRange("minStructureBlocks", 48, 8, 4096);
        STRUCTURE_LIFT_MAX_BLOCKS = BUILDER.comment("Largest structure torn in one episode, and also the point where a structure is judged too big to lift: a connected mass bigger than this is left to the normal block-by-block damage rather than sliced through.")
                .defineInRange("maxStructureBlocks", 384, 16, 8192);
        STRUCTURE_LIFT_MAX_RADIUS_BLOCKS = BUILDER.comment("Horizontal and vertical reach of the structure search from its seed block.")
                .defineInRange("maxStructureRadiusBlocks", 20, 3, 96);
        STRUCTURE_LIFT_DURATION_TICKS = BUILDER.comment("Nominal ticks the tear-off takes, scaled by structure size. The roof and the windward face go first and the foundation course last.")
                .defineInRange("tearDurationTicks", 70, 10, 600);
        STRUCTURE_LIFT_BLOCKS_PER_TICK = BUILDER.comment("Hard cap on how many blocks of one structure can be torn away per tick, so a huge build cannot burst all at once.")
                .defineInRange("maxStructureBlocksPerTick", 12, 1, 256);
        STRUCTURE_LIFT_SCAN_INTERVAL_TICKS = BUILDER.comment("How often a storm looks for a building to demolish. Scans are random-sampled and bounded.")
                .defineInRange("structureLiftScanIntervalTicks", 20, 5, 1200);
        STRUCTURE_LIFT_WHOLE_BUILDING = BUILDER.comment("Takes the whole building at once and carries it as one tumbling body, instead of tearing it away storey by storey. The structure is plucked off its foundation in a single step, flies as a rigid building that orbits the funnel and rolls about all three axes, then breaks apart in the air as its blocks are shed. Requires wind strong enough for the whole structure - a brick house still needs masonry-strength wind, so a stronger tornado in the same storm is what finally takes it.")
                .define("wholeBuildingLift", true);
        STRUCTURE_LIFT_WHOLE_MAX_BLOCKS = BUILDER.comment("Largest building that can be lifted whole. Above this it is too big to be carried as one body - a warehouse is not a house - and it falls back to the storey-by-storey tear.")
                .defineInRange("wholeBuildingMaxBlocks", 160, 16, 1024);
        STRUCTURE_LIFT_FLIGHT_SECONDS = BUILDER.comment("How long a lifted building flies as one body before it has come apart. Most of it stays intact for the first half, then sheds blocks from the outside in.")
                .defineInRange("structureFlightSeconds", 5.0D, 1.0D, 20.0D);
        STRUCTURE_LIFT_TUMBLE_RATE = BUILDER.comment("How fast a lifted building rolls as it flies. 1.0 is a plausible tumble for something that size; higher values make it visibly flipping end over end.")
                .defineInRange("structureTumbleRate", 1.0D, 0.0D, 4.0D);
        STRUCTURE_LIFT_MAX_CONCURRENT = BUILDER.comment("How many structures one level may be demolishing at once.")
                .defineInRange("maxConcurrentLifts", 2, 0, 16);
        BUILDER.pop();

        BUILDER.push("tree_lift");
        TREE_LIFT_ENABLED = BUILDER.comment("Lets a strong tornado pull a whole tree out of the ground - trunk, roots and canopy together - and carry it as one tumbling body. It is thrown downwind and smashes whatever it lands on. Weather2 only ever grips a tree a block at a time, which can strip a canopy but never uproots one.")
                .define("enabled", true);
        TREE_LIFT_MIN_WIND_MPH = BUILDER.comment("Storm max wind needed before a tree can be uprooted. 125 is a solid EF2-EF3; below it the tornado only strips bark and branches the way TreeDamage already does.")
                .defineInRange("treeLiftMinWindMph", 125.0D, 0.0D, 400.0D);
        TREE_LIFT_MIN_STAGE = BUILDER.comment("Weather2 stage needed before a tree can be uprooted. 6 is EF2, 7 is EF3.")
                .defineInRange("treeLiftMinStage", 6, 4, 9);
        TREE_LIFT_MIN_LOGS = BUILDER.comment("Trunk blocks a tree must have before it is worth uprooting. A sapling or a two-block shrub is not a tree.")
                .defineInRange("treeLiftMinLogs", 3, 2, 32);
        TREE_LIFT_MAX_LOGS = BUILDER.comment("Largest trunk taken whole. Above this the tree is a redwood - too heavy to come out of the ground in one piece - and it is left to the normal branch-stripping damage.")
                .defineInRange("treeLiftMaxLogs", 26, 4, 128);
        TREE_LIFT_CARRY_LEAVES = BUILDER.comment("Carries the canopy with the trunk. Off, only the trunk flies and the leaves are left to fall on their own.")
                .define("carryLeaves", true);
        TREE_LIFT_MAX_CANOPY_BLOCKS = BUILDER.comment("Largest canopy carried with the trunk. A giant jungle or dark oak crown is trimmed to this rather than flying as a solid green cube.")
                .defineInRange("treeLiftMaxCanopyBlocks", 220, 0, 2048);
        TREE_LIFT_SCAN_INTERVAL_TICKS = BUILDER.comment("How often a storm looks for a tree to uproot. Scans are random-sampled and bounded.")
                .defineInRange("treeLiftScanIntervalTicks", 20, 5, 1200);
        TREE_LIFT_MAX_CONCURRENT = BUILDER.comment("How many trees one level may have in the air at once.")
                .defineInRange("treeLiftMaxConcurrent", 3, 0, 32);
        TREE_LIFT_FLIGHT_SECONDS = BUILDER.comment("How long an uprooted tree is in the air before it lands. It stays whole for the whole flight - it only comes apart when it hits something.")
                .defineInRange("treeFlightSeconds", 4.5D, 1.0D, 20.0D);
        TREE_LIFT_TUMBLE_RATE = BUILDER.comment("How fast an uprooted tree rolls as it flies. A tree is lighter than a house, so it is expected to tumble harder; 1.0 matches a lifted building's tumble.")
                .defineInRange("treeTumbleRate", 1.7D, 0.0D, 6.0D);
        TREE_LIFT_THROW_DISTANCE_BLOCKS = BUILDER.comment("How far downwind of where it stood a tree is thrown before it comes down. This is the distance the landing impact is aimed at, so a longer throw is a longer reach across a settlement.")
                .defineInRange("treeThrowDistanceBlocks", 38.0D, 4.0D, 160.0D);
        TREE_LIFT_IMPACT_CRUSH_MASS = BUILDER.comment("Mass a landed tree is treated as when it hits, in the same units the block crusher uses (a block is about 1). Drives the size of the crater in a roof, so a bigger number breaks more of what it lands on.")
                .defineInRange("treeImpactCrushMass", 12.0D, 0.0D, 64.0D);
        TREE_LIFT_IMPACT_DAMAGES_ENTITIES = BUILDER.comment("Whether a tree landing on a player or mob hurts them. Block damage is governed by the crusher settings; this only covers living things.")
                .define("treeImpactDamagesEntities", true);
        TREE_LIFT_FELL_ON_LANDING = BUILDER.comment("Lets a tree that lands on open, soft-enough ground stay in the world as a real felled trunk lying along the throw direction - placed as the drawn body settles, breakable and collectable like any log, with a trimmed crown of persistent leaves. Off, or on ground too built-up or too hard to take a trunk, it shatters into wind-carried debris instead. The landing impact itself is unchanged either way.")
                .define("fellOnLanding", true);
        TREE_LIFT_FELL_CANOPY_BLOCKS = BUILDER.comment("Leaf blocks placed on the crown of a felled trunk. The felled crown is trimmed harder than the flying one - a tree on the ground has shed most of its leaves in the wind - and 0 leaves a bare pole.")
                .defineInRange("fellCanopyBlocks", 14, 0, 512);
        BUILDER.pop();

        BUILDER.push("windfield");
        WINDFIELD_ENABLED = BUILDER.comment("Enables modified Rankine-style tornado wind sampling for gameplay systems.")
                .define("enabled", true);
        WINDFIELD_FORMING_MPH = BUILDER.defineInRange("formingMph", 75.0D, 0.0D, 400.0D);
        WINDFIELD_GROUND_CONTACT_FLOOR_MPH = BUILDER.comment("The wind floor, in mph, for a tornado whose condensation is on the ground. 65 is where the published EF scale begins - EF0 is 65-85 mph, not \"anything under 86\" - so a funnel the player can watch touching down can never read below the bottom of the scale again. A grounded tornado is also held to the bottom of its own rank's band where that is higher, so a storm a rung above forming cannot read EF0 while its own rung says more: this is the number the display, the probe, the label and the damage gates all see, and they cannot contradict the stage the storm is wearing. The genesis ramp still owns everything above the floor, a storm past its peak keeps the flat value so a roping-out tornado can wind down, and a funnel still aloft is unaffected. Set 0 to let a grounded tornado read any value the ramps produce.")
                .defineInRange("groundContactFloorMph", 65.0D, 0.0D, 150.0D);
        WINDFIELD_STAGE1_MPH = BUILDER.defineInRange("stage1Mph", 115.0D, 0.0D, 400.0D);
        WINDFIELD_STAGE2_MPH = BUILDER.defineInRange("stage2Mph", 145.0D, 0.0D, 400.0D);
        WINDFIELD_STAGE3_MPH = BUILDER.defineInRange("stage3Mph", 180.0D, 0.0D, 400.0D);
        WINDFIELD_STAGE4_MPH = BUILDER.defineInRange("stage4Mph", 240.0D, 0.0D, 450.0D);
        WINDFIELD_STAGE5_MPH = BUILDER.defineInRange("stage5Mph", 321.0D, 0.0D, 500.0D);
        WINDFIELD_DYNAMIC_WIND_SPEEDS = BUILDER.comment("Makes tornado max wind a smooth live value from Weather2 intensity, storm structure, global wind, width, and gust pulses instead of a fixed stage number.")
                .define("dynamicWindSpeeds", true);
        WINDFIELD_DYNAMIC_WIND_SMOOTHING = BUILDER.comment("Per-tick smoothing for live tornado wind speed. Higher values react faster.")
                .defineInRange("dynamicWindSmoothing", 0.16D, 0.01D, 1.0D);
        WINDFIELD_DYNAMIC_GUSTINESS = BUILDER.comment("How much live max wind can pulse around its target from embedded gusts and subvortex surges.")
                .defineInRange("dynamicGustiness", 0.13D, 0.0D, 0.50D);
        WINDFIELD_DYNAMIC_GUST_FRONTS = BUILDER.comment("Replaces the smooth wind pulse with a real gust event train: sharp 1-2 second attacks, ragged decays, lulls between events, and a slow 1-2 minute envelope that brings gusty and calm periods. Anemometers, probes and damage systems read the peaks; structural and alert gates use the sustained wind. Off restores the old smooth pulse.")
                .define("dynamicGustFronts", true);
        WINDFIELD_ENVIRONMENT_WIND = BUILDER.comment("Reads the air mass the tornado is standing in - the same CAPE and deep-layer shear the SPC outlook is built from - and lets it set the wind, instead of letting the storm's rung bracket the number. The rung keeps a vote (bandWeight), so a storm can outrun its own stage in a loaded air mass and fall well short of it in a marginal one. That is what takes the EF band off the wind: it is the air the funnel is standing in that decides how hard it blows, not the class the ladder put it in. Off restores the rung-bracketed model exactly as it was.")
                .define("environmentWind", true);
        WINDFIELD_BAND_WEIGHT = BUILDER.comment("How much of the wind target the storm's own stage band still owns, against the air-mass reading that owns the rest. At 0.35 a loaded air mass can carry a stage-1 storm to violent winds and a marginal one holds a stage-5 storm down. 1.0 is the old rung-first balance; 0.0 lets the air mass alone decide.")
                .defineInRange("bandWeight", 0.35D, 0.0D, 1.0D);
        WINDFIELD_ENVIRONMENT_CEILING_MPH = BUILDER.comment("Hard ceiling, in mph, on the wind an air mass may be read at, before the overshoot the structure, width and morphology bonuses add inside it. The old per-stage ceiling is gone - the ladder can no longer cap a tornado the air can feed - so this is the only absolute cap left, and it is what stops an extreme profile from inventing a 500 mph tornado. 330 mph is past the top of the published scale.")
                .defineInRange("environmentCeilingMph", 330.0D, 120.0D, 600.0D);
        WINDFIELD_WIND_DRIVEN_STAGE = BUILDER.comment("Lets a tornado's own stage follow the wind it is actually making: when the air mass carries it past its rung's band, the stage is raised to the one the wind has earned, along with its intensity ceiling, so the stage tag and the EF class on the HUD stop contradicting each other. Weather2 still owns the way down - it drains intensity and steps the stage back after the peak - so this only ever promotes, and a commanded stage (command, pet, weather machine) is never overridden.")
                .define("windDrivenStage", true);
        WINDFIELD_RADIAL_INFLOW_FRACTION = BUILDER.defineInRange("radialInflowFraction", 0.36D, 0.0D, 2.0D);
        WINDFIELD_OUTSIDE_PROFILE_EXPONENT = BUILDER.comment("The Rankine vortex's outer decay exponent: outside the radius of maximum wind the tangential wind falls as Vmax * (Rmax / r)^a, and this is a. A real tornado's profile is between the two textbook ends - a is about 0.5 for a smooth, wide vortex and about 1 for one with a sharp outer edge - and the mod has always run at the 0.72 that chases the observed falloff at the damage-path edge. Lower values make the wind hold up further out (a broader violent swath, closer to a Rankine a = 0.5); values near 1.0 make it collapse to the calm outside the core. The inner core (r <= Rmax) is always linear - Vmax * r / Rmax - and is not affected by this.")
                .defineInRange("outsideProfileExponent", 0.72D, 0.2D, 2.0D);
        WINDFIELD_VERTICAL_LIFT_FRACTION = BUILDER.defineInRange("verticalLiftFraction", 0.22D, 0.0D, 2.0D);
        WINDFIELD_ENTITY_PUSH_SCALE = BUILDER.comment("Converts sampled wind mph into small per-tick entity velocity changes.")
                .defineInRange("entityPushScale", 0.00055D, 0.0D, 0.02D);
        WINDFIELD_PLAYER_LAUNCH_ENABLED = BUILDER.comment("Lets the violent core capture players: instead of a gentle push, the wind converges their motion onto a real orbit around the funnel with strong upward lift, launching them up and spinning them around the tornado like a caught object.")
                .define("playerLaunch", true);
        WINDFIELD_PLAYER_LAUNCH_MPH = BUILDER.comment("Local wind speed at which player capture begins.")
                .defineInRange("playerLaunchMph", 115.0D, 0.0D, 400.0D);
        WINDFIELD_PLAYER_LAUNCH_STRENGTH = BUILDER.comment("Scales the captured orbit speed and lift. 0 disables the motion (fall-safe and camera effects still follow capture state).")
                .defineInRange("playerLaunchStrength", 1.0D, 0.0D, 2.0D);
        WINDFIELD_PLAYER_LAUNCH_FALL_SAFE = BUILDER.comment("While captured (and for a short grace after), cancels fall damage so a launch that drops the player from altitude doesn't instantly kill them.")
                .define("playerLaunchFallSafe", true);
        WINDFIELD_MOB_LAUNCH_SCALE = BUILDER.comment("Scales the same capture orbit/lift for mobs and animals caught in the violent core. Mobs are lighter than players, so they fly a bit harder by default.")
                .defineInRange("mobLaunchScale", 1.12D, 0.0D, 2.0D);
        BUILDER.pop();

        BUILDER.push("multi_vortex");
        MULTIVORTEX_ENABLED = BUILDER.define("enabled", true);
        MULTIVORTEX_MAX_COUNT = BUILDER.defineInRange("maxCount", 10, 0, 10);
        MULTIVORTEX_MIN_STAGE = BUILDER.defineInRange("minStage", 6, 4, 9);
        MULTIVORTEX_EPISODE_CHANCE = BUILDER.comment("Base chance for a mature tornado to enter a multi-vortex episode on each attempt. Stage/intensity can raise this slightly, but episodes remain rare.")
                .defineInRange("episodeChance", 0.07D, 0.0D, 1.0D);
        MULTIVORTEX_MIN_ATTEMPT_INTERVAL_TICKS = BUILDER.defineInRange("minAttemptIntervalTicks", 1400, 20, 20 * 60 * 20);
        MULTIVORTEX_MAX_ATTEMPT_INTERVAL_TICKS = BUILDER.defineInRange("maxAttemptIntervalTicks", 3600, 20, 20 * 60 * 20);
        MULTIVORTEX_MIN_EPISODE_TICKS = BUILDER.defineInRange("minEpisodeTicks", 240, 20, 20 * 60 * 10);
        MULTIVORTEX_MAX_EPISODE_TICKS = BUILDER.defineInRange("maxEpisodeTicks", 760, 20, 20 * 60 * 10);
        MULTIVORTEX_DAMAGE_MULTIPLIER = BUILDER.defineInRange("damageMultiplier", 1.35D, 1.0D, 5.0D);
        MULTIVORTEX_PLANTED_SUCTION_CHANCE = BUILDER.comment("Share of multi-vortex episodes where the tornado is on the ground as usual and its suction vortices turn inside the base of the parent funnel. This is the ordinary multi-vortex tornado: the parent keeps its height, width and strength, and its lowest layers are drawn back only slightly so a small vortex orbiting inside them can be seen. Raised, more episodes look like this; set 0 for none.")
                .defineInRange("plantedSuctionChance", 0.45D, 0.0D, 1.0D);
        MULTIVORTEX_GROUND_SWARM_ENABLED = BUILDER.comment("The ground swarm: a multi-vortex tornado whose parent condensation only just reaches toward the ground while its sub-vortices are fully grounded. The parent is drawn as a thin, faint rope that stops short of the surface, the extra vortices are all grounded types, and the parent's surface circulation is weakened - so the damage on the ground is the swarm's, not the parent's.")
                .define("groundSwarm", true);
        MULTIVORTEX_GROUND_SWARM_CHANCE = BUILDER.comment("Share of multi-vortex episodes that come out as a ground swarm instead of a normal one. Applies when the episode starts, and the type is then held for that episode.")
                .defineInRange("groundSwarmChance", 0.22D, 0.0D, 1.0D);
        MULTIVORTEX_GROUND_SWARM_EXTRA_VORTICES = BUILDER.comment("Extra sub-vortices a ground swarm carries beyond what the storm would otherwise throw, since the grounded vortices are doing all of the surface work. Still capped by maxCount.")
                .defineInRange("groundSwarmExtraVortices", 2, 0, 8);
        MULTIVORTEX_GROUND_SWARM_TIP_HEIGHT = BUILDER.comment("How far above the ground the parent condensation gives up, as a fraction of the funnel's height. 0.16 leaves the bottom sixth of the funnel as clear air with the sub-vortices turning underneath it. Each ground swarm varies around this.")
                .defineInRange("groundSwarmTipHeight", 0.16D, 0.02D, 0.45D);
        MULTIVORTEX_GROUND_SWARM_PARENT_WIND_FRACTION = BUILDER.comment("Fraction of its normal surface wind the parent circulation still produces during a ground swarm. 0.55 leaves the broad parent swath weak while the sub-vortices stay violent, so a ground swarm scours the ground in patches rather than as one wide path.")
                .defineInRange("groundSwarmParentWindFraction", 0.55D, 0.05D, 1.0D);
        MULTIVORTEX_LIFTED_PARENT_VISIBLE = BUILDER.comment("Whether a lifted multi-vortex episode still draws the parent funnel. On, the parent's condensation has retracted up off the surface and the column above the retraction is drawn as the tornado it always was - only its foot is missing, with the sub-vortices turning in the clear air beneath it. Off, the parent is hidden entirely and the sub-vortices are the whole tornado.")
                .define("liftedParentVisible", true);
        MULTIVORTEX_LIFTED_TIP_HEIGHT = BUILDER.comment("How far up the funnel a lifted parent's condensation has retracted, as a fraction of the funnel's height. Everything below this is clear air; at the retraction the column is hazy and tapered, and it is back to the parent's own width and opacity a little above it. Each episode varies around this, and the retraction grows in over the episode's first two fifths and settles back to the ground over its last three tenths.")
                .defineInRange("liftedTipHeight", 0.28D, 0.0D, 0.62D);
        MULTIVORTEX_SUBVORTEX_FUNNEL_SIZE = BUILDER.comment("Width of a sub-vortex's own small funnel, as a fraction of its full size. The funnel tapers - narrowest at its lowest reach, flaring toward the cloud base - and carries a wider dust swirl around its base; this scales that silhouette's width only, so the vortex stays as tall as it was. 1.0 is the full-size funnel, 0.6 makes slender rope-like vortices.")
                .defineInRange("subVortexFunnelSize", 0.85D, 0.35D, 1.6D);
        SUBVORTEX_TYPES_ENABLED = BUILDER.comment("Gives multi-vortex tornadoes several distinct kinds of sub-vortex instead of one generic type: inner-core spots pinned to the centre, wide weak peripheral vortices near the damage-path edge, descending vortex breakdown bubbles that pinch the funnel, and the classic fast suction vortices.")
                .define("subVortexTypes", true);
        SUBVORTEX_BREAKDOWN_ENABLED = BUILDER.comment("Allows descending vortex breakdown bubbles, which travel down the funnel, choke the circulation, and pinch the condensation funnel as they pass.")
                .define("vortexBreakdowns", true);
        SUBVORTEX_BREAKDOWN_PINCH = BUILDER.comment("How hard a vortex breakdown bubble pinches the funnel it is descending through. 0 disables the visual pinch, 0.6 is a severe necking.")
                .defineInRange("vortexBreakdownPinch", 0.30D, 0.0D, 0.60D);
        SUBVORTEX_EVEN_SPACING = BUILDER.comment("Spreads orbiting sub-vortices evenly around the parent circulation when they spawn, so a multi-vortex episode reads as one circulation with several embedded vortices instead of unrelated columns that drift in and out of each other.")
                .define("evenVortexSpacing", true);
        SUBVORTEX_SHARED_ORBIT_SPEED = BUILDER.comment("Lets every sub-vortex in an episode rotate at one shared angular rate, scaled per type the way real vortices fall off with distance from the radius of maximum wind: inner-core spots fastest, peripheral and hanging funnels slowest.")
                .define("sharedOrbitSpeed", true);
        SUBVORTEX_EPISODE_RAMP = BUILDER.comment("Makes a multi-vortex episode develop, peak and decay instead of holding a flat vortex count for its whole duration.")
                .define("episodeRamp", true);
        SUBVORTEX_SATELLITE_FUNNELS = BUILDER.comment("Allows hanging satellite funnels: a detached secondary condensation funnel that hangs from the cloud base beside the parent, stays aloft, and never scours the ground. Only one can exist at a time.")
                .define("satelliteFunnels", true);
        SUBVORTEX_DUST_WHIRLS = BUILDER.comment("Allows dust whirls: tight ground swirls visible only through the debris they lift, with no condensation funnel. Common filler vortices on weaker and rope tornado episodes.")
                .define("dustWhirls", true);
        FUNNEL_DUST_GRADIENT_ENABLED = BUILDER.comment("Gives the funnel itself a vertical tone: the lower stretch reads as lifted dust and soil, fading up into ordinary grey condensation at the cloud base - the ground-to-cloud transition a real tornado has. Purely visual: it never changes wind, damage or state.")
                .define("funnelDustGradient", true);
        FUNNEL_DUST_GRADIENT_HEIGHT = BUILDER.comment("How far up the funnel the dust tone reaches, as a fraction of the visible funnel height. 0.45 is the reference look (dusty foot, condensation above); 0.25 is only a short skirt; 0.65 is a dust-dominated column fit for a bone-dry desert storm.")
                .defineInRange("funnelDustGradientHeight", 0.45D, 0.10D, 0.80D);
        FUNNEL_DUST_TINT_STRENGTH = BUILDER.comment("How completely the dust tone replaces the condensation grey at the very foot of the funnel. 1.0 is fully dust-coloured; lower values leave the foot part cloud, which reads better on a funnel that has only just grounded.")
                .defineInRange("funnelDustTintStrength", 0.85D, 0.0D, 1.0D);
        FUNNEL_LUMPS_ENABLED = BUILDER.comment("Deforms the parent condensation funnel into lumps that travel around it with the orbiting sub-vortices - the multi-lobed, clefted base that defines the look of a multi-vortex tornado. Purely visual: it never changes wind, damage or state.")
                .define("funnelLumps", true);
        FUNNEL_LUMP_STRENGTH = BUILDER.comment("How far the funnel wall bulges where a sub-vortex passes. 0.28 is a clearly lobed base; 0.5 is extreme; 0 leaves the funnel a clean surface of revolution.")
                .defineInRange("funnelLumpStrength", 0.28D, 0.0D, 0.80D);
        BUILDER.pop();

        BUILDER.push("horizontal_vortices");
        HORIZONTAL_VORTICES_ENABLED = BUILDER.comment("Adds short horizontal vortex tubes: rope-like funnels that curl off the upper wall of the tornado, wrap around it, and drift away when they tear off. They ride the funnel well above the ground and are purely a visual - they do no damage, no scouring and no block pickup.")
                .define("enabled", true);
        HORIZONTAL_VORTICES_MAX_COUNT = BUILDER.defineInRange("maxCount", 4, 0, 10);
        HORIZONTAL_VORTICES_MIN_STAGE = BUILDER.defineInRange("minStage", 5, 4, 9);
        HORIZONTAL_VORTICES_STORM_CHANCE = BUILDER.comment("Share of tornadoes that grow horizontal vortices at all, so that a funnel either has them for its whole life or never has them. The trait is rolled once, the first time a storm reaches the minStage above - which for a storm building up the ladder is its bottom rung, so in practice this value is close to the share of all tornadoes you will see them on. A storm that arrives already violent rolls against better odds, up to 20 points higher. Set 1 to give every tornado them (the old behaviour), 0 to remove them everywhere; both apply immediately, including to storms already running.")
                .defineInRange("stormChance", 0.40D, 0.0D, 1.0D);
        HORIZONTAL_VORTICES_BREAK_OFF_CHANCE = BUILDER.comment("Chance that a horizontal vortex tears off the funnel wall part way through its life and drifts away as a free-roaming tube before it dissipates. 0 keeps every tube attached for its whole life.")
                .defineInRange("breakOffChance", 0.40D, 0.0D, 1.0D);
        HORIZONTAL_VORTICES_BREAK_OFF_FREE_TICKS = BUILDER.comment("Longest a torn-off horizontal vortex keeps drifting free before it fades. Each one picks a shorter drift than this.")
                .defineInRange("breakOffFreeTicks", 90, 20, 20 * 30);
        BUILDER.pop();

        BUILDER.push("merging");
        MERGING_ENABLED = BUILDER.define("enabled", true);
        MERGE_SCAN_INTERVAL_TICKS = BUILDER.defineInRange("mergeScanIntervalTicks", 20, 5, 20 * 60);
        MERGE_OVERLAP_TICKS = BUILDER.defineInRange("mergeOverlapTicks", 40, 1, 20 * 60);
        MERGE_ABSORB_WEAKER = BUILDER.define("mergeAbsorbWeaker", true);
        MERGE_ORBIT_BEFORE_ABSORB = BUILDER.comment("Makes merging tornadoes/supercells orbit or curve around a shared center before the weaker circulation is absorbed.")
                .define("orbitBeforeAbsorb", true);
        MERGE_ORBIT_TICKS = BUILDER.defineInRange("orbitTicks", 80, 0, 20 * 60);
        MERGE_ORBIT_STRENGTH = BUILDER.defineInRange("orbitStrength", 0.22D, 0.0D, 2.0D);
        BUILDER.pop();

        BUILDER.push("supercell_merging");
        SUPERCELL_MERGING_ENABLED = BUILDER.comment("Merges severe Weather2 thunderstorm/supercell cells server-side using soft dissipation for the absorbed storm.")
                .define("enabled", true);
        SUPERCELL_MERGE_MIN_INTENSITY_STAGE = BUILDER.comment("Minimum Weather2 intensity stage eligible for supercell merging. Default 1 matches Weather2 thunder stage.")
                .defineInRange("minIntensityStage", 1, 0, 9);
        SUPERCELL_MERGE_SCAN_INTERVAL_TICKS = BUILDER.defineInRange("scanIntervalTicks", 20, 5, 20 * 60);
        SUPERCELL_MERGE_OVERLAP_TICKS = BUILDER.defineInRange("overlapTicks", 60, 1, 20 * 60);
        SUPERCELL_MERGE_DISTANCE_MULTIPLIER = BUILDER.defineInRange("mergeDistanceMultiplier", 0.65D, 0.05D, 4.0D);
        SUPERCELL_MERGE_MAX_DISTANCE_BLOCKS = BUILDER.defineInRange("maxMergeDistanceBlocks", 220.0D, 16.0D, 2048.0D);
        SUPERCELL_MERGE_INTENSITY_PULSE = BUILDER.defineInRange("intensityPulse", 0.35D, 0.0D, 1.0D);
        SUPERCELL_MERGE_MOTION_ABSORB_FRACTION = BUILDER.defineInRange("motionAbsorbFraction", 0.30D, 0.0D, 1.0D);
        SUPERCELL_MERGE_SOFT_DISSIPATE_ABSORBED = BUILDER.define("softDissipateAbsorbed", true);
        SUPERCELL_MERGE_HARD_REMOVE_ABSORBED = BUILDER.comment("Unsafe fallback for debugging only. Default false avoids Weather2 removal/sync crashes.")
                .define("hardRemoveAbsorbed", false);
        BUILDER.pop();

        BUILDER.push("supercell_structure");
        SUPERCELL_STRUCTURE_ENABLED = BUILDER.comment("Adds organized supercell structure to severe Weather2 land storms: updraft, wall cloud, anvil, FFD/RFD, inflow, and radar structure.")
                .define("enabled", true);
        SUPERCELL_STRUCTURE_SCALE = BUILDER.comment("How large the supercell's structure is, as a multiplier on every dimension the structure service places: the mesocyclone's standoff, the anvil's reach, the flanking line, the gust front, the precipitation footprint and the wall cloud's diameter. 1.0 is the mod's own scale - a structure radius of 92 to 280 blocks depending on the storm's stage and width. A real supercell is 30 to 50 chunks across, which this reaches around 2.5 to 3.0: at 3.0 the radius runs to 840 blocks, about 52 chunks. It is a visual and geometric scale rather than a force scale - it decides how far the parts sit from the storm, not how hard the storm blows - but the inflow and gust-front wind the structure adds spread out with it. Raise the supercell render range to match, or the far parts will be drawn short of where the structure says they are.")
                .defineInRange("scale", 1.0D, 0.5D, 3.0D);
        SUPERCELL_STRUCTURE_CLOUD_BASE_OFFSET_BLOCKS = BUILDER.comment("How far the structure's cloud base is raised or lowered, in blocks, on top of the height it already takes from the storm's own size. The rain-free base climbs with the structure radius by default (32 to 54 blocks above the storm); this shifts it further, so a scaled-up or high-based supercell can keep its wall cloud, anvil and precipitation tops proportional rather than flattening them against the deck. Positive lifts the base, negative lowers it.")
                .defineInRange("cloudBaseOffsetBlocks", 0, -32, 160);
        SUPERCELL_STRUCTURE_CLIENT_VISUALS = BUILDER.define("clientVisuals", true);
        SUPERCELL_STRUCTURE_RADAR = BUILDER.define("radarStructure", true);
        SUPERCELL_STRUCTURE_WINDFIELD_INFLUENCE = BUILDER.comment("Lets supercell inflow, forward-flank downdraft, and rear-flank downdraft affect anemometers and radar velocity.")
                .define("windfieldInfluence", true);
        SUPERCELL_STRUCTURE_RENDER_RANGE_BLOCKS = BUILDER.defineInRange("renderRangeBlocks", 900, 64, 4096);
        SUPERCELL_STRUCTURE_MAX_PARTICLES_PER_TICK = BUILDER.defineInRange("maxStructureParticlesPerTick", 220, 0, 800);
        SUPERCELL_STRUCTURE_ANVIL_PARTICLES = BUILDER.define("anvilParticles", true);
        SUPERCELL_STRUCTURE_CLOUD_MASS_SHAPE = BUILDER.comment("Sculpts the storm's own persistent cloud deck (the big mass Weather2 draws for every storm) into a coherent cauliflower silhouette: a flared skirt, a pinched waist, stacked lobes and a bright billowed crown, rotating slowly as one object. Positions only - no extra particles are spawned and Weather2's population is reused as-is.")
                .define("cloudMassCauliflowerShape", true);
        SUPERCELL_STRUCTURE_WALL_CLOUD_PARTICLES = BUILDER.define("wallCloudParticles", true);
        SUPERCELL_STRUCTURE_WALL_CLOUD_PARTICLE_SIZE = BUILDER.comment("Size of the wall cloud's cloud puffs and rotating tags, as a fraction of their full size. The lowered wall cloud is built from a stack of large cloud quads, and at full size the ones close to you fill the screen. Smaller values make a finer, more finely broken wall cloud; 1.0 is the original full-size puffs, and because the wall cloud spawns a fresh batch of puffs every tick the cloud stays as dense as before however small they get - it just becomes finer grained.")
                .defineInRange("wallCloudParticleSize", 0.22D, 0.05D, 2.0D);
        SUPERCELL_STRUCTURE_WALL_CLOUD_LOWERING = BUILDER.comment("Adds a persistent lowered rotating wall cloud under the supercell rain-free updraft base.")
                .define("wallCloudLowering", true);
        SUPERCELL_STRUCTURE_WALL_CLOUD_MIN_MESOCYCLONE_STRENGTH = BUILDER.comment("Minimum mesocyclone strength before wall-cloud lowering can organize.")
                .defineInRange("wallCloudMinMesocycloneStrength", 0.45D, 0.0D, 1.0D);
        SUPERCELL_STRUCTURE_WALL_CLOUD_MIN_PERSISTENCE_TICKS = BUILDER.comment("Ticks a rotating wall cloud must persist before it modestly boosts radar/SPC tornado potential.")
                .defineInRange("wallCloudMinPersistenceTicks", 200, 0, 20 * 60 * 20);
        SUPERCELL_STRUCTURE_WALL_CLOUD_MAX_DIAMETER_BLOCKS = BUILDER.comment("Maximum visual wall-cloud lowering diameter in blocks.")
                .defineInRange("wallCloudMaxDiameterBlocks", 240, 24, 1024);
        SUPERCELL_STRUCTURE_WALL_CLOUD_MAX_LOWERING_BLOCKS = BUILDER.comment("Maximum visual lowering depth below the rain-free base in blocks.")
                .defineInRange("wallCloudMaxLoweringBlocks", 42, 0, 192);
        SUPERCELL_STRUCTURE_WALL_CLOUD_ROTATION_PARTICLES = BUILDER.comment("Adds rotating ragged cloud tags around organized wall clouds.")
                .define("wallCloudRotationParticles", true);
        SUPERCELL_STRUCTURE_WALL_CLOUD_SCUD_CHANCE = BUILDER.comment("Chance per tick that scud wisps spawn under the wall cloud. 1.0 keeps a constant scud collar; lower values make scud appear in occasional rotating wisps like real inflow scud.")
                .defineInRange("wallCloudScudChance", 0.30D, 0.0D, 1.0D);
        SUPERCELL_STRUCTURE_WALL_CLOUD_SCUD_SIZE = BUILDER.comment("How big a scud wisp is, in blocks, as a share of the realistic size the revamp draws them at. Real inflow scud is roughly fist- to car-sized ragged shreds hanging under the wall cloud, not puffs a hundred feet across, so 1.0 is already small (about a block and a half) and this scales from there. The wisps are spawned in numbers instead, which is what makes the inflow read as shreds tearing past rather than as a few grey blobs.")
                .defineInRange("wallCloudScudSize", 1.0D, 0.2D, 6.0D);
        SUPERCELL_STRUCTURE_WALL_CLOUD_SCUD_FEED_PARTICLES = BUILDER.comment("Adds upward scud/fractus feeding into the wall cloud from the inflow side: small ragged shreds born low and upwind, climbing toward the lowered base the way real inflow scud is drawn in.")
                .define("wallCloudScudFeedParticles", true);
        SUPERCELL_STRUCTURE_WALL_CLOUD_RISK_BOOST = BUILDER.comment("Lets persistent rotating wall clouds modestly increase the radar's tornado potential without triggering TOR warnings by themselves. The SPC outlook is a forecast and is never moved by observed storm structure, so this affects the radar only.")
                .define("wallCloudRiskBoost", true);
        BUILDER.pop();

        BUILDER.push("supercell_precipitation");
        SUPERCELL_PRECIPITATION_ENABLED = BUILDER.comment("Gives each supercell its own precipitation character, so some are dry low-precipitation storms with a crisp, hail-dominated core while others are high-precipitation storms with a torrential rain shield that wraps the tornado.")
                .define("enabled", true);
        SUPERCELL_PRECIPITATION_VARIABILITY = BUILDER.comment("How different neighbouring supercells are from one another. 0 = every storm's rain follows its own accumulated moisture alone (storms in the same airmass all look alike); 1 = the character is almost entirely a per-storm draw, so a bone-dry LP and a rain-wrapped HP can sit side by side.")
                .defineInRange("stormVariability", 0.55D, 0.0D, 1.0D);
        SUPERCELL_PRECIPITATION_LP_THRESHOLD = BUILDER.comment("Character below this is a low-precipitation (LP) storm: little to no rain, hail-dominated, crisp visible structure.")
                .defineInRange("lowPrecipitationThreshold", 0.34D, 0.0D, 1.0D);
        SUPERCELL_PRECIPITATION_HP_THRESHOLD = BUILDER.comment("Character above this is a high-precipitation (HP) storm: heavy rain shield that wraps the mesocyclone and hides the tornado.")
                .defineInRange("highPrecipitationThreshold", 0.66D, 0.0D, 1.0D);
        SUPERCELL_PRECIPITATION_DRIFT_PER_TICK = BUILDER.comment("How fast a storm's precipitation character drifts toward what its airmass supports. Small values keep a storm's type stable for its whole life; larger values let it moisten up or dry out as it moves and precipitates.")
                .defineInRange("characterDriftPerTick", 0.0012D, 0.0D, 0.05D);
        SUPERCELL_PRECIPITATION_FIELD_ENABLED = BUILDER.comment("Gives each storm's rain a shape instead of making it a blob: the bulk of it in a forward-flank core, a rear-flank hook wrapping around the mesocyclone, a rain-free notch scoured out where the inflow is being drawn up, and weaker rain on the rear flank and trailing under the anvil. This is what lets a tornado be visible from one side and hidden behind a wall of rain from the other, rather than being equally buried or equally clear from everywhere. Off falls back to a symmetric shield centred on the storm.")
                .define("precipitationField", true);
        SUPERCELL_PRECIPITATION_HOOK_SCALE = BUILDER.comment("How much of a storm's rain wraps around its mesocyclone. 0 = the rain stays in the forward flank and the vortex is never rain-wrapped, 1 = as much as the storm's own rear-flank downdraft implies, above 1 wraps it harder. Raising this makes rain-wrapped tornadoes more common and hides funnels more often.")
                .defineInRange("hookScale", 1.0D, 0.0D, 2.0D);
        SUPERCELL_RAIN_WRAP_CHANCE = BUILDER.comment("The chance that a supercell is rain-wrapped: a high-precipitation storm whose rain closes round its tornado and hides it. -1 (the default) leaves it to each storm's own moisture, so wet airmasses wrap their tornadoes more often than dry ones. 0 to 1 sets it outright instead: 0 = never, 0.25 = about one storm in four, 1 = every one. Each storm rolls once and keeps the result for its life. A storm that does not roll a wrap can still have some rain round its back, as a classic supercell does, but never round the funnel. Needs supercell precipitation enabled.")
                .defineInRange("rainWrapChance", -1.0D, -1.0D, 1.0D);
        SUPERCELL_PRECIPITATION_NOTCH_CLARITY = BUILDER.comment("How rain-free the inflow notch is - the clear slot that opens toward the funnel from the inflow side, which is what lets you see a tornado inside a raining storm. 0 = no notch, the rain closes right around the vortex; 1 = a crisp slot; above 1 scours it wider and further out. Only affects storms with structure (supercells).")
                .defineInRange("notchClarity", 1.0D, 0.0D, 1.5D);
        SUPERCELL_STRUCTURE_GUST_FRONT_PARTICLES = BUILDER.define("gustFrontParticles", true);
        SUPERCELL_STRUCTURE_FLANKING_LINE_PARTICLES = BUILDER.define("flankingLineParticles", true);
        SUPERCELL_STRUCTURE_VIRGA_MAMMATUS_PARTICLES = BUILDER.define("virgaMammatusParticles", true);
        SUPERCELL_STRUCTURE_RFD_STRAIGHT_LINE_WIND = BUILDER.comment("Optional entity-push hook for severe rear-flank/forward-flank outflow. Off by default; anemometers still read the wind when windfieldInfluence=true.")
                .define("rfdStraightLineWind", false);
        BUILDER.pop();

        BUILDER.push("virga");
        VIRGA_ENABLED = BUILDER.comment("Virga: precipitation that falls out of a cloud base and evaporates or sublimates on the way down, so it hangs as shafts and wisps that never reach the ground. Driven by how dry the air below the cloud is - a bone-dry low-precipitation supercell in dry air produces it constantly, while a rain-wrapped high-precipitation storm puts everything down. Where virga is strong the shafts stop tens of blocks up, the rain fades out toward the evaporation edge instead of switching off, nothing lands, and the storm does not flood the ground.")
                .define("enabled", true);
        VIRGA_STRENGTH_SCALE = BUILDER.comment("How readily precipitation evaporates on the way down. 0 turns virga off without disabling the feature (every storm rains to the ground), 1 is realistic, above 1 makes dry air evaporate the shafts harder.")
                .defineInRange("strengthScale", 1.0D, 0.0D, 2.0D);
        VIRGA_MIN_FALL_DEPTH_BLOCKS = BUILDER.comment("How far below the cloud base precipitation still falls when virga is at its strongest - the driest air, shortest stubs of rain hanging under the cloud.")
                .defineInRange("minFallDepthBlocks", 28, 8, 160);
        VIRGA_MAX_FALL_DEPTH_BLOCKS = BUILDER.comment("The depth of dry air at which evaporation reaches full effect. Evaporation takes time, so a shallow layer under a low cloud base can barely eat anything while a deep one can consume a whole shaft - this is the column depth at which the dry layer is working at full strength, and roughly twice a typical storm's cloud-base height above the ground. Raise it if virga looks too aggressive, lower it if dry storms still rain to the ground.")
                .defineInRange("maxFallDepthBlocks", 64, 24, 384);
        BUILDER.pop();

        BUILDER.push("radar");
        RADAR_ENABLED = BUILDER.define("enabled", true);
        RADAR_RANGE_BLOCKS = BUILDER.defineInRange("rangeBlocks", 1024, 64, 8192);
        RADAR_UPDATE_INTERVAL_TICKS = BUILDER.defineInRange("updateIntervalTicks", 20, 5, 20 * 60);
        RADAR_WORLD_PANEL_ENABLED = BUILDER.comment("Uses an in-world animated radar panel instead of opening a GUI.")
                .define("worldPanel", true);
        RADAR_STABLE_NORTH_UP = BUILDER.comment("Keeps the radar map north-up and stable; only storm returns move.")
                .define("stableNorthUp", true);
        RADAR_CONFIRMED_CIRCULATION_MARKER = BUILDER.comment("Draws confirmed tornado/satellite/landspout circulations at server-authoritative positions.")
                .define("confirmedCirculationMarker", true);
        RADAR_VORTEX_ICONS = BUILDER.comment("Draws the signature icons a real radar overlay uses: a red inverted triangle for a tornadic vortex signature, the same triangle in yellow for an elevated one - tornadic rotation in the storm with nothing on the ground yet - and a yellow circle for a mesocyclone. They follow the tornado detection algorithm's own gates, read as the gate-to-gate velocity difference: 36 m/s for a TVS, or 25 m/s for a tornado already on the ground. A TVS is drawn at the tornado, where its couplet is in the velocity product, and the others at the mesocyclone.")
                .define("vortexSignatureIcons", true);
        PORTABLE_RADAR_LINK_RANGE_BLOCKS = BUILDER.comment("How far a portable radar reaches for a weather radar to link to, in blocks. The handheld shows the nearest weather radar block's own picture - its product, its range and its live returns - and only falls back to a sweep of the player's own position when no weather radar is within this distance.")
                .defineInRange("portableLinkRangeBlocks", 512, 16, 8192);
        BUILDER.pop();

        BUILDER.push("daily_spc_outlook");
        DAILY_SPC_OUTLOOK_ENABLED = BUILDER.comment("Calculates a fictional daily SPC-style thermodynamic outlook each game morning. It only gates new storm spawning, not active tornado width/lifecycle. The ladder runs past the published high risk onto extreme and hyper extreme, which need a near-ceiling environment (around 4600 J/kg of CAPE with 34 m/s of shear for extreme, 5000 with 38 for hyper extreme).")
                .define("enabled", true);
        DAILY_SPC_OUTLOOK_ISSUANCE_CYCLE = BUILDER.comment("Reissues the outlook on the SPC schedule through the day (0600Z, 1300Z, 1630Z, 2000Z, 0100Z). Each issue re-examines the environment with a diurnal heating curve, so an outlook can be upgraded in the afternoon and downgraded overnight. Off issues one outlook per day, computed at dawn.")
                .define("outlookIssuanceCycle", true);
        DAILY_SPC_SERENE_SEASONS_THERMODYNAMICS = BUILDER.comment("If Serene Seasons is installed, use its season/sub-season as a read-only thermodynamic modifier.")
                .define("sereneSeasonsThermodynamics", true);
        DAILY_SPC_SPAWN_MULTIPLIER_SCALE = BUILDER.comment("Global scale for daily SPC spawn multipliers before an event initializes.")
                .defineInRange("spawnMultiplierScale", 1.0D, 0.0D, 8.0D);
        DAILY_SPC_ROTATION_THRESHOLD_SCALE = BUILDER.comment("Global scale for parent rotation threshold gating. This never mutates active tornado physics.")
                .defineInRange("rotationThresholdScale", 1.0D, 0.2D, 2.0D);
        BUILDER.pop();

        BUILDER.push("surveys");
        SURVEYS_ENABLED = BUILDER.comment("Enables persistent in-game tornado tracks and automatic fictional EF-style damage surveys.")
                .define("enabled", true);
        SURVEY_RADIUS_BLOCKS = BUILDER.defineInRange("radiusBlocks", 96, 16, 512);
        SURVEY_TRACK_RECORD_INTERVAL_TICKS = BUILDER.defineInRange("trackRecordIntervalTicks", 20, 5, 20 * 60);
        SURVEY_MAX_TRACK_AGE_TICKS = BUILDER.defineInRange("maxTrackAgeTicks", 72000, 20 * 60, 20 * 60 * 240);
        SURVEY_RECENT_TRACK_WINDOW_TICKS = BUILDER.comment("Only tracks with points inside this recent window can be selected by the Damage Surveyor. Keeps old tornadoes out of new surveys.")
                .defineInRange("recentTrackWindowTicks", 20 * 60 * 8, 20 * 10, 20 * 60 * 60);
        SURVEY_TRACK_CONTEXT_BLOCKS = BUILDER.comment("Track map only sends/draws recent points near the survey area instead of the full lifetime path.")
                .defineInRange("trackContextBlocks", 360, 64, 4096);
        SURVEY_OPEN_GUI_ON_SURVEY = BUILDER.define("openGuiOnSurvey", true);
        BUILDER.pop();

        // The spotter net: a player's sighting of a funnel, made into a warning the rest of the mod believes.
        // It sits beside the chase log because the two are the same idea from both ends - one records what a
        // storm did to you, the other lets you tell the world what it is about to do to somebody else.
        BUILDER.push("spotter");
        SPOTTER_ENABLED = BUILDER.comment("Whether players can file tornado reports with a spotter flare. A report issues a real tornado warning - sirens sound, radios hear it and villagers shelter - which is the lead a spotter exists to give: the mod's own scan only knows a storm once it has put a circulation on the ground, and a person can see a funnel lowering before that. Turn this off and the flare does nothing.")
                .define("enabled", true);
        SPOTTER_REPORT_RANGE_BLOCKS = BUILDER.comment("How close a player has to be to a circulation to report it, in blocks. This is the distance to the vortex itself rather than to the storm, and it is deliberately generous: a report is a sighting made from a safe distance, and a chaser who has to stand in the inflow to report anything has the incentive backwards.")
                .defineInRange("reportRangeBlocks", 256, 32, 1024);
        SPOTTER_MIN_REPORTED_MPH = BUILDER.comment("How strong a circulation has to be before it is worth reporting, in mph. This floor is much lower than the one the mod uses to call something a tornado, and that is the point: a forming funnel, a satellite circulation and a touched-down tornado are all reportable, so a report can warn a village before the scan would have said anything. What it prevents is reporting a dust devil's parent cloud.")
                .defineInRange("minReportedMph", 35.0D, 0.0D, 120.0D);
        SPOTTER_LIFETIME_TICKS = BUILDER.comment("How long a report stands, in ticks, before it lapses. A report is a claim rather than a fact, so it stops warning people after a minute (1200 ticks) unless the world proves it in the meantime - which a lowering funnel usually does within seconds, and which is why the settle window and the confirm window are the same number.")
                .defineInRange("lifetimeTicks", 1200, 100, 12000);
        SPOTTER_COOLDOWN_TICKS = BUILDER.comment("How long a player must wait between reports, in ticks. This is a rate limit on the net rather than on the player: a report is a warning that reaches every station in range, so filing one every tick would let a single player keep the whole area under permanent alert.")
                .defineInRange("cooldownTicks", 200, 0, 6000);
        SPOTTER_MAX_ACTIVE_REPORTS = BUILDER.comment("How many reports may be in force in a level at once. When the net is full a new report is refused rather than the oldest being dropped, because a report in force is a warning somebody may be sheltering from.")
                .defineInRange("maxActiveReports", 8, 1, 64);
        SPOTTER_SCAN_INTERVAL_TICKS = BUILDER.comment("How often, in ticks, the net checks its reports against what the world is actually doing. Confirmation and lapse are both resolved on this pass, so it is the resolution of the reward loop rather than of the warning itself.")
                .defineInRange("scanIntervalTicks", 20, 1, 200);
        SPOTTER_CONFIRM_RANGE_BLOCKS = BUILDER.comment("How close an observed tornado has to be to a report for the report to count as proven, in blocks. A report is credited when the world produces what it claimed where it claimed it, and a tornado that touched down across the county is not that report's tornado.")
                .defineInRange("confirmRangeBlocks", 192, 16, 1024);
        SPOTTER_VILLAGER_RADIUS_BLOCKS = BUILDER.comment("How far around a reported circulation the villagers the warning reaches are counted, in blocks. This number is only ever reported back to the spotter - it is the answer to \"did that report stand in front of anybody\" - and it is what the village shelter AI is doing about it, so it is worth having right.")
                .defineInRange("villagerRadiusBlocks", 256, 32, 1024);
        SPOTTER_BOUNTY_EMERALDS = BUILDER.comment("How many emeralds a confirmed report is worth, taken from the chests of the nearest village that the warning covered. A village pays what it can afford and never goes into debt: the credit for the report is awarded either way, so a poor village's survival is still a reward.")
                .defineInRange("bountyEmeralds", 6, 0, 64);
        SPOTTER_BOUNTY_RANGE_BLOCKS = BUILDER.comment("How far from a confirmed tornado a village may be and still pay the bounty, in blocks. This is the village that the warning was for - the one whose villagers were in range - rather than any village the spotter can walk to afterwards.")
                .defineInRange("bountyRangeBlocks", 256, 32, 1024);
        SPOTTER_BOUNTY_CHEST_RANGE_BLOCKS = BUILDER.comment("How far around the nearest villager the village's chests are looked for when the bounty is paid, in blocks. The emeralds come out of those chests, which is what stops the reward being conjured from nowhere - and what lets a village spend its rebuilding money on warning itself next time.")
                // 20 is the cap the village store's own chest walk uses, so a bigger number here would be a
                // setting that reads as if it did something and did not.
                .defineInRange("bountyChestRangeBlocks", 16, 2, 20);
        BUILDER.pop();

        // Photography: a camera that scores the shot you actually framed. It sits here because it is the third
        // thing in this mod a player does with a storm - the chase log records being near one, the spotter net
        // warns about one, and the camera is what you do while you wait for it.
        BUILDER.push("photo");
        PHOTO_ENABLED = BUILDER.comment("Whether the camera takes photographs. Each one is scored on what a severe-weather photographer judges a frame on - composition, distance, how much of the storm is visible, the structure above it, the light, and something in the foreground - and kept in that player's album. Turn this off and the camera does nothing.")
                .define("enabled", true);
        PHOTO_COUNT_CREATIVE = BUILDER.comment("Whether photographs taken in creative mode are scored and filed. Unlike the chase log - which is a record of having been in danger - a camera is a camera, so this counts creative shots by default: the alternative is a player who has built something worth photographing and cannot photograph it.")
                .define("countCreative", true);
        PHOTO_VIEWFINDER = BUILDER.comment("Whether a held camera shows a live viewfinder: a reticle with the edge of the frame and the band that earns full distance marks drawn on it, a mark where the storm sits, and two lines saying what a shot taken now would be given for framing and for distance. It shows those two parts because they are what the camera's own aim and lens decide; the light, the foreground and how much of the funnel is behind a hill are facts about the world, and a viewfinder that guessed at them would be worse than one that does not show them.")
                .define("viewfinder", true);
        PHOTO_AIM_INTERVAL_TICKS = BUILDER.comment("How often, in ticks, the server tells a held camera what is worth photographing nearby. Four ticks is a fifth of a second, which is short enough that a funnel moving fast has not moved far enough to matter and long enough that the packet is nothing; the readings that move when a player turns are worked out on the client every frame and do not wait for this.")
                .defineInRange("aimIntervalTicks", 4, 1, 200);
        PHOTO_ANNOUNCE = BUILDER.comment("Whether taking a photograph reports its score, its breakdown and any advice in chat. The album keeps the shot either way; this only decides whether you are told about it at the time.")
                .define("announce", true);
        PHOTO_MAX_DISTANCE_BLOCKS = BUILDER.comment("How far away a circulation can be and still be photographed, in blocks. Beyond this there is no subject to point at, so the shutter answers that nothing was in frame rather than filing a score near zero - a wall of grey is not a bad photograph, it is not a photograph.")
                .defineInRange("maxDistanceBlocks", 480, 48, 4096);
        PHOTO_SWEET_SPOT_MIN_BLOCKS = BUILDER.comment("The near end of the distance band that earns full proximity marks, in blocks. Closer than this and the storm stops being a subject and starts being a wall: standing in the inflow gives the flattest light and the least information in a frame.")
                .defineInRange("sweetSpotMinBlocks", 48, 8, 1024);
        PHOTO_SWEET_SPOT_MAX_BLOCKS = BUILDER.comment("The far end of that band, in blocks. Past it the marks fall away to nothing at the maximum distance, because a tornado four hundred blocks away is a smudge.")
                .defineInRange("sweetSpotMaxBlocks", 220, 16, 4096);
        PHOTO_MIN_SUBJECT_MPH = BUILDER.comment("How strong a circulation has to be before it is worth photographing, in mph. This floor is also the zero of the subject score, so a dust devil below it is not a subject at all and one at it scores nothing for its strength.")
                .defineInRange("minSubjectMph", 40.0D, 0.0D, 250.0D);
        PHOTO_OCCLUSION_SAMPLES = BUILDER.comment("How many points along a storm's own height the line of sight is checked at. A funnel two hundred blocks tall is not one ray, so a single sample would call a storm visible through a gap between two trees. Each sample is one block raycast per photograph.")
                .defineInRange("occlusionSamples", 9, 1, 64);
        PHOTO_FUNNEL_HEIGHT_SCALE = BUILDER.comment("How tall a funnel is taken to be for the line-of-sight check, as a multiple of how wide it looks in blocks. A wide wedge is a tall column and a thin rope is a short one; the result is floored at 16 blocks and capped at 256 so the samples stay on the storm.")
                .defineInRange("funnelHeightScale", 6.0D, 1.0D, 24.0D);
        PHOTO_LIGHTNING_RANGE_BLOCKS = BUILDER.comment("How far away a bolt can be and still be in the frame, in blocks. The frame itself is the real limit - a bolt behind you is not in the photograph whatever its distance - and this stops a distant storm's flashes counting as part of your subject.")
                .defineInRange("lightningRangeBlocks", 256, 16, 2048);
        PHOTO_LIGHTNING_WINDOW_TICKS = BUILDER.comment("How long after a strike it still counts as being in the photograph, in ticks. A bolt lives two ticks, so this is the camera's slow shutter: without a window the lightning bonus would be a matter of luck, and ten ticks (half a second) is what makes it a matter of timing.")
                .defineInRange("lightningWindowTicks", 10, 1, 200);
        PHOTO_RAIN_LEVEL = BUILDER.comment("How hard it has to be raining where the camera is standing before the shot is penalised, from 0 to 1. Rain between the camera and a storm flattens the light and greys everything beyond it, and the level's own rain level is the measure of it.")
                .defineInRange("rainLevel", 0.2D, 0.0D, 1.0D);
        PHOTO_COOLDOWN_TICKS = BUILDER.comment("How long between photographs, in ticks. Nothing is spent and no cooldown starts unless a photograph is actually filed, so pointing the camera at clouds costs nothing - this is a rate limit on the server's own raycasts rather than on the player.")
                .defineInRange("cooldownTicks", 20, 0, 1200);
        PHOTO_ALBUM_SIZE = BUILDER.comment("How many photographs a player's album keeps. This is the ranked gallery, so a full album drops its weakest shot rather than its oldest: an album of your best work should not forget a good storm to remember a poor one.")
                .defineInRange("albumSize", 12, 1, 64);
        PHOTO_READOUT_LINES = BUILDER.comment("How many of the album's best shots the weather computer prints. The album keeps them all; this is how much of it fits on the computer's screen.")
                .defineInRange("readoutLines", 5, 0, 24);
        BUILDER.pop();

        BUILDER.push("chase_log");
        CHASE_LOG_ENABLED = BUILDER.comment("Keeps a per-player record of the storms you have been close to: the strongest wind you have stood in, your closest approach, how long you spent inside a core, and the three chases worth beating. It is a record and not a behaviour - it awards nothing, changes nothing and gives no advantage, and the numbers it keeps are the ones the instruments already read.")
                .define("enabled", true);
        CHASE_LOG_INTERVAL_TICKS = BUILDER.comment("How often, in ticks, a player's position is measured against the storms near them. The numbers are extremes, so this is a resolution rather than an accuracy: a coarser interval can miss the single worst gust of a pass, a finer one costs one wind sample per storm per player. Ten ticks (twice a second) is the default and is enough to catch a core passage.")
                .defineInRange("intervalTicks", 10, 1, 200);
        CHASE_LOG_RADIUS_BLOCKS = BUILDER.comment("How close a storm has to be, in blocks, for it to count as being chased. This is a gate on distance to the vortex, not to the storm cell, so a supercell whose tornado is three hundred blocks away is still a storm you are chasing. Measured to the circulation itself, which matters because Weather2's storm position is the middle of the whole mesocyclone and precipitation shield.")
                .defineInRange("radiusBlocks", 384.0D, 32.0D, 2048.0D);
        CHASE_LOG_MIN_STORM_MPH = BUILDER.comment("How strong a tornado has to be before it is worth starting a log entry, in mph. 65 is the bottom of the published EF0 band, so by default a weak tornado is the smallest thing that opens a chase. Only tornadoes are logged at all, whatever their wind: the entry a chase closes with is rated on the EF bands, which rate tornadoes, so a dust devil, a fire whirl, a snow devil, a gustnado, a waterspout or a funnel cloud is not a chase and never enters the log. The entry then stays open however much the storm weakens, because riding a tornado down is still a chase.")
                .defineInRange("minStormMph", 65.0D, 0.0D, 300.0D);
        CHASE_LOG_END_GRACE_TICKS = BUILDER.comment("How long a storm may go unseen, in ticks, before its chase is logged and closed. This exists so a storm that dips out of the snapshot list for a moment - a satellite circulation, a tornado whose condensation lifts and returns - is one chase rather than three, and it is not counted as time beside the storm: the entry closes at the last observation it actually had.")
                .defineInRange("endGraceTicks", 100, 20, 1200);
        CHASE_LOG_MAX_ENCOUNTERS = BUILDER.comment("How many finished chases are kept per player. The lifetime totals - storms logged, how many were EF2 or stronger, and the three best-remembered chases - are kept separately and are unaffected by this, so old chases aging out of the list never make the record smaller.")
                .defineInRange("maxEncounters", 25, 1, 200);
        CHASE_LOG_RECENT_LINES = BUILDER.comment("How many of the most recent chases the weather computer prints beside the summary. 0 leaves the summary and your bests without the storm-by-storm list.")
                .defineInRange("recentLines", 3, 0, 10);
        CHASE_LOG_COUNT_CREATIVE = BUILDER.comment("Whether creative players' chases are recorded. Off by default: a chase implies having been in danger, and a creative player can stand in a core with nothing to lose.")
                .define("countCreative", false);
        CHASE_LOG_ANNOUNCE = BUILDER.comment("Whether a chase is announced in chat when it closes, with its class, wind, closest approach and time in the core. This is the feedback that makes the log worth having: the moment a storm pulls away is when you find out what it was.")
                .define("announceOnFinalize", true);
        BUILDER.pop();

        BUILDER.push("alerts");
        ALERTS_ENABLED = BUILDER.define("enabled", true);
        ALERTS_STRUCTURED_PRODUCTS = BUILDER.define("structuredProducts", true);
        ALERTS_TORNADO_EMERGENCY = BUILDER.define("tornadoEmergency", true);
        ALERTS_SAFE_FAUX_TONES_ONLY = BUILDER.comment("Never plays real or simulated EAS/SAME/WEA tones; only custom vanilla-style faux tones.")
                .define("safeFauxTonesOnly", true);
        ALERTS_FAUX_SOUND = BUILDER.comment("Uses vanilla note/bell sounds for fictional in-game alerts.")
                .define("fauxSound", true);
        ALERT_RANGE_BLOCKS = BUILDER.defineInRange("rangeBlocks", 900, 64, 8192);
        ALERT_WATCH_RANGE_BLOCKS = BUILDER.defineInRange("watchRangeBlocks", 1400, 64, 8192);
        ALERT_WARNING_RANGE_BLOCKS = BUILDER.defineInRange("warningRangeBlocks", 900, 64, 8192);
        ALERT_WARNING_PATH_LEAD_TICKS = BUILDER.defineInRange("warningPathLeadTicks", 600, 0, 20 * 60 * 20);
        ALERT_COOLDOWN_TICKS = BUILDER.defineInRange("cooldownTicks", 20 * 45, 20, 20 * 60 * 30);
        ALERTS_REQUIRE_SIREN = BUILDER.comment("Alerts are broadcast by Weather Alert Siren blocks instead of to every player in storm range: a player only sees an alert banner if a siren block stands within its coverage radius. A siren must exist somewhere, so a world with no sirens has no alerts. Turning this off restores the old behaviour of alerting every player within the alert range, still as a banner.")
                .define("requireSiren", true);
        ALERTS_SIREN_RANGE_BLOCKS = BUILDER.comment("How far a Weather Alert Siren broadcasts, in blocks. Anyone inside this radius of any siren is covered by it; the siren keeps broadcasting while its chunk is unloaded, so leaving your base does not silence the network.")
                .defineInRange("sirenRangeBlocks", 256, 32, 4096);
        ALERTS_SIREN_DEPLOY_TICKS = BUILDER.comment("How long a Weather Alert Siren takes to raise its antenna, in ticks. The mast climbs out of the housing and the dish unfurls over the last part of the climb; 0 puts the antenna up at once. The default is a slow seven-second rise.")
                .defineInRange("sirenDeployTicks", 140, 0, 20 * 60);
        ALERTS_SIREN_WIRING_REQUIRED = BUILDER.comment("Sirens must be wired to a Siren Control Panel that is switched on before they warn anyone: an unwired siren keeps its mast stowed and is silent. The panel takes a hand-thrown lever or a redstone signal, so a storm detector or a daylight sensor can drive it. Turning this off makes every placed siren live immediately, which is the behaviour before wiring existed.")
                .define("sirenWiringRequired", true);
        ALERTS_SIREN_MAX_WIRE_LENGTH = BUILDER.comment("How many wire blocks a run between a control panel and a siren may pass through. A longer run counts as no connection at all, so a cable snaking across the map cannot arm everything it passes near. Wire is cheap; connect in a sensible run or place a second panel.")
                .defineInRange("sirenMaxWireLength", 64, 1, 1024);
        ALERTS_SIREN_DISH_RANGE_BLOCKS = BUILDER.comment("How far a Weather Alert Siren's dish looks for a storm to point at, in blocks. It points at whatever it is currently warning about, then at the closest storm within this radius, and sweeps on its own when there is nothing to track.")
                .defineInRange("sirenDishRangeBlocks", 1400, 64, 8192);
        ALERTS_SIREN_TONES = BUILDER.comment("Tornado sirens sound their own recorded tones instead of vanilla note blocks: a wailing mechanical rotor, an electronic attack ramp, or a two-tone alternating siren, each synthesised from the real rotor speeds and amplifier frequencies. The old note-block chime is only used by the Weather Alert Siren's brief attention tone, which this does not replace.")
                .define("sirenTones", true);
        ALERTS_SIREN_TONE_VOLUME = BUILDER.comment("Master volume for siren tones, 0 to 1. Sirens are loud on purpose - they are meant to be heard from a distance - so this is the knob to turn down rather than the range.")
                .defineInRange("sirenToneVolume", 0.85D, 0.0D, 1.0D);
        ALERTS_BANNER_DURATION_TICKS = BUILDER.comment("How long an alert banner stays on screen after the last broadcast, in ticks. A continuing alert keeps refreshing, so the banner holds until the hazard is gone and then fades.")
                .defineInRange("bannerDurationTicks", 20 * 8, 20, 20 * 60);
        ALERTS_BANNER_REFRESH_TICKS = BUILDER.comment("How often a siren re-broadcasts an alert that is still in force, in ticks.")
                .defineInRange("bannerRefreshTicks", 20 * 6, 20, 20 * 60);
        ALERTS_CHAT_LOG = BUILDER.comment("Also prints each alert into chat as well as showing the banner, so the product record is still visible in the chat log.")
                .define("chatLog", false);
        BUILDER.pop();

        BUILDER.push("instruments");
        WEATHER_RADIO_ENABLED = BUILDER.comment("Enables the Weather Radio, which reads the alert products in force over its own position and broadcasts them to players in range like a siren does.")
                .define("weatherRadio", true);
        WEATHER_RADIO_RANGE_BLOCKS = BUILDER.comment("How far a Weather Radio reaches, in blocks. Independent of the siren's own range, so a radio can be a short-range indoor set or a wide-area station.")
                .defineInRange("weatherRadioRangeBlocks", 192, 32, 4096);
        WEATHER_RADIO_TONES = BUILDER.comment("Whether a Weather Radio rings the faux alert tones into the players it reaches. A siren never does this, because it already rings out of its own block.")
                .define("weatherRadioTones", true);
        WEATHER_VANE_ENABLED = BUILDER.comment("Enables the Weather Vane, which points with the local wind so direction is readable at a glance.")
                .define("weatherVane", true);
        BAROMETER_ENABLED = BUILDER.comment("Enables the Barometer, which shows station pressure and whether it is rising or falling.")
                .define("barometer", true);
        RAIN_GAUGE_ENABLED = BUILDER.comment("Enables the Rain Gauge, which accumulates the rain that actually reaches the ground at its position.")
                .define("rainGauge", true);
        RAIN_GAUGE_REDSTONE_PULSE = BUILDER.comment("Whether a Rain Gauge emits redstone while rain is falling on it, so it can be wired as a rain detector.")
                .define("rainGaugeRedstonePulse", true);
        TORNADO_PROBE_ENABLED = BUILDER.comment("Enables the deployable Tornado Probe, which records peak wind, lowest pressure and debris loading as a tornado passes over it.")
                .define("tornadoProbe", true);
        TORNADO_PROBE_ARM_MPH = BUILDER.comment("Wind speed at which a Tornado Probe arms and starts recording, in mph. Below this it is simply waiting.")
                .defineInRange("tornadoProbeArmMph", 45, 5, 300);
        TORNADO_PROBE_SHRED_MPH = BUILDER.comment("Wind speed that destroys a Tornado Probe, in mph. Set above the arm threshold; a probe that dies records the reading it had reached before it gave out.")
                .defineInRange("tornadoProbeShredMph", 165, 10, 400);
        TORNADO_PROBE_DETECT_RANGE_BLOCKS = BUILDER.comment("How far a Tornado Probe looks for a vortex to measure, in blocks.")
                .defineInRange("tornadoProbeDetectRangeBlocks", 220, 32, 2048);
        DRONE_ENABLED = BUILDER.comment("Enables the deployable camera drone, which a player flies from their own position with a first-person view from the airframe and which records the wind and pressure it flies through.")
                .define("weatherDrone", true);
        DRONE_MAX_AIRSPEED_MPH = BUILDER.comment("The drone's top speed through the AIR, in mph. Ground track is this plus the wind, which is why flying into a 40 mph inflow makes almost no progress and the same drone downwind crosses the county. Raising this also raises the wind the drone can still fight, because control authority fails once the wind passes this value.")
                .defineInRange("droneMaxAirspeedMph", 42.0D, 8.0D, 200.0D);
        DRONE_CLIMB_MPH = BUILDER.comment("How fast the drone climbs and descends when the pilot holds up or down, in mph.")
                .defineInRange("droneClimbMph", 18.0D, 4.0D, 120.0D);
        DRONE_WIND_COUPLING = BUILDER.comment("How much of the wind the drone is carried by, as a fraction. 1.0 is an airframe with no inertia to speak of; lower values make it feel heavier and are the honest knob for a player who finds flying in a storm too punishing.")
                .defineInRange("droneWindCoupling", 1.0D, 0.0D, 2.0D);
        DRONE_FLIGHT_TICKS = BUILDER.comment("Flight time on one charge, in ticks. Holding station costs current whether or not the pilot is commanding it, and working the controls costs twice as much, so the default is twelve minutes of hovering or six minutes of hard flying. A drone left holding station still drains, so it does not last forever.")
                .defineInRange("droneFlightTicks", 20 * 60 * 12, 200, 20 * 60 * 60);
        DRONE_LINK_RANGE_BLOCKS = BUILDER.comment("How far the drone can fly from its pilot before the link drops, in blocks. Past this it stops accepting controls and descends where it is, and recalling it from beyond this range is not possible - which is what makes a long flight into a storm a decision.")
                .defineInRange("droneLinkRangeBlocks", 320, 32, 4096);
        DRONE_ARM_MPH = BUILDER.comment("Wind speed at which the drone starts counting what it flies through, in mph. Below this the air is just air.")
                .defineInRange("droneArmMph", 20.0D, 5.0D, 200.0D);
        DRONE_FREEZE_PILOT = BUILDER.comment("Takes the movement keys off the pilot's own feet while they are flying, which is how a drone operator actually stands still with their eyes on the screen. The keys still work, they just drive the drone. Nothing else about the pilot changes: gravity, being pushed and being lifted by a storm all still apply, so a pilot can be picked up while their drone circles the funnel. Turn it off to walk around while the drone flies.")
                .define("droneFreezePilot", true);
        TORNADO_PROBE_HOUSING_SURVIVAL_BONUS = BUILDER.comment("How much more wind a reinforced probe housing takes before the probe is overrun, as a multiple of its stock limit. The load that destroys a probe rises with the square of wind speed, so 0.75 here (1.75x the wind) is roughly three times the pressure. An armoured probe survives far more than its number suggests, and still has a limit.")
                .defineInRange("tornadoProbeHousingSurvivalBonus", 0.75D, 0.0D, 5.0D);
        TORNADO_PROBE_SENSOR_RANGE_BONUS = BUILDER.comment("How much further each grade of probe sensor head reaches into a storm, as a fraction per grade. Sensor grades also shorten the sampling interval and raise the fraction of the true peak wind the head reports, because an instrument that averages over a long interval never sees the gust it was deployed to measure.")
                .defineInRange("tornadoProbeSensorRangeBonus", 0.35D, 0.0D, 2.0D);
        BUILDER.pop();

        BUILDER.push("storm_types");
        WATERSPOUTS_ENABLED = BUILDER.define("waterspouts", true);
        FAIR_WEATHER_WATERSPOUTS_ENABLED = BUILDER.comment("Spawns addon-owned fair-weather waterspout vortices over water near developing Weather2 showers/thunderstorms.")
                .define("fairWeatherWaterspouts", true);
        FUNNEL_CLOUDS_ENABLED = BUILDER.comment("Spawns cold-air funnel clouds: rotating condensation funnels hanging under a developing shower with no circulation on the ground at all. They look like a tornado and touch nothing, which is the entire point of the type.")
                .define("funnelClouds", true);
        CYCLONES_ENABLED = BUILDER.comment("The southern hemisphere's hurricane: the same storm on the same tropical ladder, rotating the other way and named a cyclone rather than a hurricane. Requires the hemisphere split below to matter - with that on, a cyclone can only form south of the equator line and a hurricane only north of it, which is the one real difference between the two names.")
                .define("cyclones", false);
        TROPICAL_HEMISPHERE_SPLIT = BUILDER.comment("Draws an equator across the world and keeps the two tropical families on their own sides of it: cyclonic tropical systems (hurricanes) north of the line, anticyclonic ones (cyclones) south of it. A Minecraft world has no latitude, so this is the only thing that can stand in for one, and it is what makes the two families different storms in a world rather than two names for the same one. Turn it off and the older behaviour returns: whichever families are enabled are chosen between at random, anywhere.")
                .define("hemisphereSplit", true);
        TROPICAL_HEMISPHERE_EQUATOR_Z = BUILDER.comment("Where that equator line sits, as a world Z coordinate. South is +Z, so the default of 0 puts the southern half of the world in the cyclones' half. Move it if your world's warm ocean is mostly on one side, or if you built the town you want to lose in the other.")
                .defineInRange("hemisphereEquatorZ", 0, -30000000, 30000000);
        HURRICANES_ENABLED = BUILDER.comment("Tropical cyclones: a storm over warm ocean turns into a real water storm in late-summer/early-autumn thermodynamic windows, climbs the tropical ladder (Tropical Disturbance, Depression, Tropical Storm, then a named hurricane with a category) and carries the 1.12.2 hurricane shield: an eye punched in the middle of a wide disc, spinning at double rate with spiral bands.")
                .define("hurricanes", true);
        LANDSPOUTS_ENABLED = BUILDER.comment("Spawns addon-owned non-supercell landspout vortices near developing Weather2 thunderstorms over land.")
                .define("landspouts", true);
        SPOUT_DAMAGE_ENABLED = BUILDER.comment("A landspout or waterspout takes what a strong gust takes: leaves off the trees, carpets off the floor, torches and flowers and crops off the ground. This is the tier below a tornado and it is meant to stay survivable - a spout never touches soil, stone, planks or logs, and it never digs. Off returns the old behaviour, where a spout under 62 mph was decoration.")
                .define("spoutDamage", true);
        DUST_DEVILS_ENABLED = BUILDER.comment("Spawns small fair-weather dust devils on clear, dry, hot terrain.")
                .define("dustDevils", true);
        GUSTNADOES_ENABLED = BUILDER.comment("Spawns brief shallow vortices along thunderstorm and squall-line outflow boundaries.")
                .define("gustnadoes", true);
        FLOATER_TORNADOES_ENABLED = BUILDER.comment("Allows rare Weather2 tornadoes to lock into a floater morphology tag that keeps the funnel visually detached from the ground.")
                .define("floaterTornadoes", true);
        SNOW_DEVILS_ENABLED = BUILDER.comment("Spawns very rare fair-weather snow devils over cold snow or ice surfaces.")
                .define("snowDevils", true);
        SNOW_DEVIL_FAIR_WEATHER_ONLY = BUILDER.comment("Whether a snow devil needs a fair sky, which is what is meant by fair-weather: nothing falling on it at all, snow included. Off - the default - is the physical reading, because snowfall is exactly the weather a snow devil forms in and the level-wide rain flag that vanilla reports it with is the same flag a thaw uses. On restores the stricter behaviour, where a whirl can only appear in a clear sky.")
                .define("snowDevilFairWeatherOnly", false);
        SNOW_DEVIL_MIN_LIFE_TICKS = BUILDER.comment("The life a snow devil is guaranteed whatever the ground under it does, in ticks. A whirl that wanders off its drift and onto bare rock should be allowed to keep wandering for a while; this is the floor that stops one being erased in the second it appeared, which is what made them look like they never spawned at all.")
                .defineInRange("snowDevilMinLifeTicks", 200, 0, 2400);
        WILDFIRES_ENABLED = BUILDER.comment("Allows dry, hot fire-weather outbreaks that ignite and spread small wildfire cores.")
                .define("wildfires", true);
        FIRE_WHIRLS_ENABLED = BUILDER.comment("Allows intense wildfire cores to spin up short-lived fire whirls with damaging windfields.")
                .define("fireWhirls", true);
        FLAMMAGENITUS_CLOUDS_ENABLED = BUILDER.comment("Allows large wildfire heat plumes to produce flammagenitus/pyrocumulus cloud columns.")
                .define("flammagenitusClouds", true);
        FIRE_WEATHER_RISK_ENABLED = BUILDER.comment("Includes wildfire, fire whirl, and flammagenitus signals in SPC-style categorical outlooks.")
                .define("fireWeatherRisk", true);
        WILDFIRE_BURNT_PASS = BUILDER.comment("A wildfire leaves the ground it has been over: the canopy burned away, some trunks left standing as charred spars, bare earth where the litter was. The scar follows the fire's own track rather than appearing in a ring around it, so the pass has a direction by the time the fire has walked a few hundred blocks. Off leaves a burnt forest looking exactly like an unburnt one, which is what vanilla fire does.")
                .define("burntPass", true);
        WILDFIRE_BURNT_TRUNKS = BUILDER.comment("Whether trunks are left standing as charred wood. Off burns the canopy and scorches the ground but leaves every trunk where it stood, for a world that wants the scar without the changed timber.")
                .define("burntTrunks", true);
        LANDSPOUT_SPAWN_CHANCE = BUILDER.defineInRange("landspoutSpawnChance", 0.018D, 0.0D, 1.0D);
        WATERSPOUT_SPAWN_CHANCE = BUILDER.defineInRange("waterspoutSpawnChance", 0.024D, 0.0D, 1.0D);
        FUNNEL_CLOUD_SPAWN_CHANCE = BUILDER.comment("Per-scan chance that a precipitating storm puts up a funnel cloud. It is checked every storm scan (2 s by default) for as long as the shower lasts, so the value is small on purpose: at 0.002 a shower spends roughly a quarter of its life with a funnel hanging under it, which is about as common as a cold-air funnel is in a real cool-season shower. Raise it for a sky full of them.")
                .defineInRange("funnelCloudSpawnChance", 0.002D, 0.0D, 1.0D);
        FUNNEL_CLOUD_TIP_ALTITUDE_BLOCKS = BUILDER.comment("How far above the terrain the ragged tip of a funnel cloud hangs, in blocks. This is the one number that decides whether a funnel cloud reads as a funnel cloud rather than as a tornado: it never reaches zero.")
                .defineInRange("funnelCloudTipAltitudeBlocks", 42, 16, 160);
        DUST_DEVIL_SPAWN_CHANCE = BUILDER.defineInRange("dustDevilSpawnChance", 0.010D, 0.0D, 1.0D);
        GUSTNADO_SPAWN_CHANCE = BUILDER.defineInRange("gustnadoSpawnChance", 0.016D, 0.0D, 1.0D);
        HURRICANE_SPAWN_CHANCE = BUILDER.comment("Per-attempt chance that an eligible ocean storm begins tropical genesis. A hurricane is a long-lived system, so this is deliberately rare.")
                .defineInRange("hurricaneSpawnChance", 0.006D, 0.0D, 1.0D);
        HURRICANE_SHIELD_ENABLED = BUILDER.comment("Draw tropical storms with the 1.12.2 hurricane shield: a wide flat cloud disc with an eye in the middle, spin at double rate and spiral bands, instead of the supercell cloud mass.")
                .define("hurricaneShield", true);
        HURRICANE_EYE_FRACTION = BUILDER.comment("Radius of the hurricane's eye as a fraction of its shield radius. Weather2 1.12.2's 1/1.75 came to 0.57, which is a third of the disc's area and reads as a hole in the sky when the deck sits a few dozen blocks overhead; a real storm's eye runs 0.10 to 0.20, which is what this now defaults to. The deck is kept out of that circle so the eye stays open as the disc spins, and the eye wall rises from within it.")
                .defineInRange("hurricaneEyeFraction", 0.18D, 0.08D, 0.85D);
        HURRICANE_SHIELD_SIZE_MULTIPLIER = BUILDER.comment("How much wider a hurricane's cloud shield is than a supercell's. Weather2 1.12.2 gave water storms three times the size base a land storm got, against one and a half for tornadoes - twice as wide - which is what this defaults to.")
                .defineInRange("hurricaneShieldSizeMultiplier", 2.0D, 1.0D, 4.0D);
        HURRICANE_DISC_SPIN_MULTIPLIER = BUILDER.comment("How much faster the shield's rim sweeps than the storm's own wind blowing over it - the 1.12.2 water storm's doubled spin. 1.0 drives the cloud at exactly the wind speed; 2.0, the default, makes the bands visibly wheel the way the reference storm does.")
                .defineInRange("hurricaneSpinMultiplier", 2.0D, 1.0D, 5.0D);
        HURRICANE_BAND_COUNT = BUILDER.comment("Spiral rain bands wrapped through the shield. The bands are what make a hurricane read as a spinning disc rather than a circular cloud, and they shear with radius so they coil inward as they turn.")
                .defineInRange("hurricaneBandCount", 5, 2, 10);
        HURRICANE_BAND_SHEAR = BUILDER.comment("How far the bands coil between the eye wall and the outer shield, as a fraction of a full turn. Higher values wrap the bands further around the storm, giving the tighter spiral of a mature hurricane.")
                .defineInRange("hurricaneBandShear", 0.55D, 0.0D, 2.0D);
        FLOATER_TORNADO_SPAWN_CHANCE = BUILDER.comment("Per-retarget chance for an eligible Weather2 tornado to become floater-tagged. This no longer creates addon-owned /ewd events.")
                .defineInRange("floaterTornadoSpawnChance", 0.006D, 0.0D, 1.0D);
        SNOW_DEVIL_SPAWN_CHANCE = BUILDER.defineInRange("snowDevilSpawnChance", 0.003D, 0.0D, 1.0D);
        WILDFIRE_SPAWN_CHANCE = BUILDER.comment("Per-player fire-weather spawn chance before SPC multipliers. Runtime caps this so wildfires stay rare even with old configs.")
                .defineInRange("wildfireSpawnChance", 0.0012D, 0.0D, 1.0D);
        FIRE_WHIRL_SPAWN_CHANCE = BUILDER.defineInRange("fireWhirlSpawnChance", 0.012D, 0.0D, 1.0D);
        HURRICANE_MAX_ACTIVE = BUILDER.defineInRange("maxActiveHurricanes", 1, 0, 4);
        ANTI_CYCLONIC_TORNADOES_ENABLED = BUILDER.comment("Allows rare anti-cyclonic Weather2 tornadoes. The addon flips Weather2 StormObject.spinSpeed negative for these tornadoes.")
                .define("antiCyclonicTornadoes", true);
        ANTI_CYCLONIC_TORNADO_CHANCE = BUILDER.comment("Chance that a newly assigned natural Weather2 tornado rotates anti-cyclonically. Rare by default.")
                .defineInRange("antiCyclonicTornadoChance", 0.035D, 0.0D, 1.0D);
        ANTI_CYCLONIC_INVERT_WEATHER2_SPIN = BUILDER.comment("Uses Weather2's own spinSpeed field so Weather2 particles/funnel visuals rotate the opposite way.")
                .define("antiCyclonicInvertWeather2Spin", true);
        MAX_ADDON_VORTICES = BUILDER.defineInRange("maxAddonVortices", 8, 0, 64);
        BUILDER.pop();

        BUILDER.push("satellite_tornadoes");
        SATELLITE_TORNADOES_ENABLED = BUILDER.define("enabled", true);
        SATELLITE_TORNADOES_REAL_CIRCULATION = BUILDER.comment("Models satellite tornadoes as addon-owned circulations with their own position, motion, windfield, and damage.")
                .define("realCirculation", true);
        SATELLITE_TORNADOES_MAX_ACTIVE = BUILDER.comment("Maximum active satellite tornadoes per parent tornado. The runtime count also scales with parent wind (one at 110+ mph, two at 160+, three at 210+). Satellites now form, wander, and spiral into the parent to be absorbed, so several stay readable.")
                .defineInRange("maxActive", 2, 0, 4);
        SATELLITE_TORNADOES_MIN_PARENT_STAGE = BUILDER.defineInRange("minParentStage", 5, 4, 9);
        SATELLITE_TORNADOES_MAX_WIND_FRACTION = BUILDER.defineInRange("maxWindFraction", 0.60D, 0.05D, 1.0D);
        SATELLITE_TORNADOES_SPAWN_CHANCE = BUILDER.comment("Per-attempt satellite tornado chance. Runtime also caps this to a rare maximum for existing configs.")
                .defineInRange("spawnChance", 0.045D, 0.0D, 1.0D);
        SATELLITE_TORNADOES_DAMAGE_BLOCKS_PER_TICK = BUILDER.defineInRange("damageBlocksPerTick", 6, 0, 128);
        BUILDER.pop();

        BUILDER.push("twins");
        TWIN_TORNADOES_ENABLED = BUILDER.comment("Lets a violent tornado have a twin: a second real tornado storm seeded beside it at its own strength, on its own parallel track. The pair is two Weather2 storms, so Weather2 draws a funnel for each out of its own storm state and the addon's wind field, damage, funnel build-in, radar signature, warnings and damage survey treat both of them as the tornadoes they are - two funnels, two tracks, two damage paths.")
                .define("enabled", true);
        TWIN_SPAWN_CHANCE = BUILDER.comment("Per-attempt chance of the second tornado. Attempts are made on the interval below and only on a tornado that has finished forming, so what this settles is how often a strong tornado ends up with a twin: the shipped 0.05 on a one-minute interval gives a tornado that lasts eight minutes about a one in three chance of one, and a storm that only lasts two minutes almost none.")
                .defineInRange("spawnChance", 0.05D, 0.0D, 1.0D);
        TWIN_ATTEMPT_INTERVAL_TICKS = BUILDER.comment("How often a twin is rolled for, in ticks (20 = one second). Attempts are stateless, so a save and reload can neither hand a storm two twins nor take away the one it had.")
                .defineInRange("attemptIntervalTicks", 20 * 60, 100, 20 * 60 * 10);
        TWIN_MIN_PARENT_STAGE = BUILDER.comment("The Weather2 rung a tornado must reach before it can have a twin. 5 is the first rung at which a land storm is a tornado at all, 6 is a strong tornado, and 7 or more keeps twins to the violent ones. The default is 6, because a pair is a violent tornado's sight.")
                .defineInRange("minParentStage", 6, 5, 9);
        TWIN_MAX_PER_TORNADO = BUILDER.comment("How many twins one tornado may have at once. One is a pair; two is a cluster, which stops reading as twins.")
                .defineInRange("maxPerTornado", 1, 0, 2);
        TWIN_SPACING_BLOCKS = BUILDER.comment("How far apart the two funnels stand, in blocks. Both have to fit in one view for a pair to be a pair, so this stays inside the range two funnels can be seen in: 320 puts the second funnel a fifth of a mile off, which is a pair on the horizon. The floor of 120 is as close as the two damage swaths can get before they stop being separate, and the ceiling of 512 is Weather2's own cloud-deck cull - past it the second storm's sky is not drawn from where the first stands, so the two are not a pair any more however they look.")
                .defineInRange("spacingBlocks", 320.0D, 120.0D, 512.0D);
        TWIN_LIFETIME_TICKS = BUILDER.comment("How long a twin lives once its own funnel is born, in ticks (20 = one second). It is held up as a tornado for this long and then handed back to Weather2's own decay, so the second funnel ropes out instead of vanishing. Eight minutes is about as long as the tornado it formed beside lasts.")
                .defineInRange("lifetimeTicks", 20 * 60 * 8, 20 * 20, 20 * 60 * 30);
        TWIN_DIES_WITH_PAIR = BUILDER.comment("Ends the second tornado when the first one ends. With this off a twin outlives its pair as a tornado in its own right until its own life runs out, which can leave a lone second tornado crossing the map with nothing left to be a pair with.")
                .define("diesWithPair", true);
        BUILDER.pop();

        BUILDER.push("climate");
        CLIMATE_ENABLED = BUILDER.comment("Makes the ground decide the air. Every biome is read as one of twelve families - desert, plains, forest, jungle, swamp, savanna, badlands, taiga, snowy, mountain, coast, ocean - from what the biome itself reports and what it calls itself, and the mixture of families around a place sets that place's heat, moisture, instability and, where dry country meets wet, how sharp the moisture boundary across it is. Storms, their strength, hail, snow, fog, fire risk and tornado odds all read that air, so the same afternoon is a different afternoon in different country. Off leaves the air exactly as it was before this existed.")
                .define("enabled", true);
        CLIMATE_REGION_SIZE_BLOCKS = BUILDER.comment("How big a piece of the map shares one air mass, in blocks. This is the grain of the map's weather: 1024 is a region a quarter of a mile across, which is a storm's own neighbourhood, and small enough that crossing into the next biome changes what the sky is able to do.")
                .defineInRange("regionSizeBlocks", 1024, 256, 4096);
        CLIMATE_SAMPLE_RADIUS_BLOCKS = BUILDER.comment("How far out around a region's centre the biome mixture is read, in blocks. Wider is what finds a boundary: 192 sees about four hundred blocks across, which is enough for a desert edge beside plains to register as one, and the widest setting reads a whole valley.")
                .defineInRange("sampleRadiusBlocks", 192, 64, 512);
        CLIMATE_CONTRAST_STRENGTH = BUILDER.comment("How much a moisture boundary - dry country meeting wet - counts towards the storm mode index, which is what decides whether a place is a marginal day or a violent one. This is the dryline, and it is the reason a boundary between two biomes is more dangerous than either of them: at 1 a full boundary is worth up to 0.12 of the index, several hundred feet of CAPE and half a category. 0 switches it off, leaving every biome's air uniform.")
                .defineInRange("contrastStrength", 1.0D, 0.0D, 2.0D);
        CLIMATE_REGIONAL_OUTLOOK = BUILDER.comment("Issues the day's outlook per region instead of once for the whole world, so the risk map is a map: the alley can sit at moderate while the desert next door is marginal on the same afternoon, and a storm is rated against the air it is actually in. Off gives every place the outlook issued at the world spawn, which is what the mod did before climate existed.")
                .define("regionalOutlook", true);
        CLIMATE_ODDS_STRENGTH = BUILDER.comment("How much of this mod's own spawn machinery is handed to the map. The air over a place already differs by biome - its heat, moisture, instability and the sharpness of the boundary across it feed CAPE, storm mode and the tornado odds drawn from them - so at 0 a desert is still a desert. What this dial adds on top is the country's own character: the dryline's tornadoes, the sea's waterspouts, the desert's dust devils, the cold shower's funnel clouds, worth roughly a third to twice each phenomenon's own spawn chance. 1 is the full table, 0.75 is the default and keeps the world's average spawn rate near what it was tuned to, and 0 makes every phenomenon exactly as likely everywhere.")
                .defineInRange("oddsStrength", 0.75D, 0.0D, 1.0D);
        CLIMATE_WEIGHT_OCEAN = BUILDER.comment("How stormy one family's air is, on top of what that family already is: 1 leaves it alone, 0 makes that country incapable of convection, and 2 doubles how readily its air converts. A family's own moisture is untouched, so a jungle turned down is still wet - it simply stops making storms out of it.")
                .defineInRange("oceanInstability", 1.0D, 0.0D, 2.0D);
        CLIMATE_WEIGHT_COAST = BUILDER.defineInRange("coastInstability", 1.0D, 0.0D, 2.0D);
        CLIMATE_WEIGHT_DESERT = BUILDER.defineInRange("desertInstability", 1.0D, 0.0D, 2.0D);
        CLIMATE_WEIGHT_BADLANDS = BUILDER.defineInRange("badlandsInstability", 1.0D, 0.0D, 2.0D);
        CLIMATE_WEIGHT_SAVANNA = BUILDER.defineInRange("savannaInstability", 1.0D, 0.0D, 2.0D);
        CLIMATE_WEIGHT_PLAINS = BUILDER.defineInRange("plainsInstability", 1.0D, 0.0D, 2.0D);
        CLIMATE_WEIGHT_FOREST = BUILDER.defineInRange("forestInstability", 1.0D, 0.0D, 2.0D);
        CLIMATE_WEIGHT_JUNGLE = BUILDER.defineInRange("jungleInstability", 1.0D, 0.0D, 2.0D);
        CLIMATE_WEIGHT_SWAMP = BUILDER.defineInRange("swampInstability", 1.0D, 0.0D, 2.0D);
        CLIMATE_WEIGHT_TAIGA = BUILDER.defineInRange("taigaInstability", 1.0D, 0.0D, 2.0D);
        CLIMATE_WEIGHT_SNOWY = BUILDER.defineInRange("snowyInstability", 1.0D, 0.0D, 2.0D);
        CLIMATE_WEIGHT_MOUNTAIN = BUILDER.defineInRange("mountainInstability", 1.0D, 0.0D, 2.0D);
        BUILDER.pop();

        // The world's own calendar. Everything else in this file is about what the sky can do; this is
        // about when it does it, which is the one thing a storm cannot read off the ground under it.
        BUILDER.push("season_calendar");
        CALENDAR_ENABLED = BUILDER.comment("Gives the world a calendar and lets it move the weather: spring raises instability, shear and the tornado odds, summer makes many storms out of heat and moisture while taking the shear away, autumn lifts the flow and the fronts, and winter takes the instability away and leaves the sky to its fronts instead. The day is counted from the world's own clock, so a world keeps its season across a restart, and a season is only ever a deviation applied to the air this mod already computes - switched off, every day is exactly the day this mod had before calendars existed.")
                .define("enabled", true);
        CALENDAR_YEAR_LENGTH_DAYS = BUILDER.comment("How long a year is, in game days. 120 is this mod's own compact year - ten days to a month, so a whole spring-to-winter turn happens inside a few real hours of play - and 365 is a real calendar year, one real day per game day, for a world meant to be lived in across months. Any length between works: a month is always a twelfth of the year.")
                .defineInRange("yearLengthDays", 120, 12, 3650);
        CALENDAR_START_MONTH = BUILDER.comment("Which month a brand-new world opens in. 3 starts a fresh save in March, so its first weeks are spring, which is the season this mod's storms were tuned in; 1 opens on the first of January.")
                .defineInRange("startMonth", 3, 1, 12);
        CALENDAR_SYNC_SERENE_SEASONS = BUILDER.comment("When Serene Seasons is installed, follow its season instead of this mod's own cycle. A player running a seasons mod has told the world which season it is, and this mod has no business disagreeing, so its season is read and used and this mod's own cycle is only the source of the day count. Off keeps the internal calendar and simply ignores the other mod.")
                .define("syncToSereneSeasons", true);
        CALENDAR_SPRING_STRENGTH = BUILDER.comment("How much of spring's own character applies. 1 is the mod's shipped spring - rising instability and shear, and the highest tornado odds of the year, on a front or a dryline; 0 makes spring behave like every other season; 2 doubles how far it departs from neutral. This scales the season's personality rather than replacing it, so a world with a quiet spring still has the rest of the year.")
                .defineInRange("springStrength", 1.0D, 0.0D, 2.0D);
        CALENDAR_SUMMER_STRENGTH = BUILDER.comment("How much of summer's own character applies. 1 is the shipped summer: hot, wet, and short on shear, which is the pulse season - many storms that rain hard and end, and few tornadoes out of them.")
                .defineInRange("summerStrength", 1.0D, 0.0D, 2.0D);
        CALENDAR_AUTUMN_STRENGTH = BUILDER.comment("How much of autumn's own character applies. 1 is the shipped autumn: the instability of summer going, the shear of winter arriving, and the frontal convection a low makes lifting with both.")
                .defineInRange("autumnStrength", 1.0D, 0.0D, 2.0D);
        CALENDAR_WINTER_STRENGTH = BUILDER.comment("How much of winter's own character applies. 1 is the shipped winter: the least instability of the year, the most shear, the most front activity, and almost nothing for a tornado to form in - the frontal season, where the weather is a low's own fronts rather than an afternoon's thunderstorms.")
                .defineInRange("winterStrength", 1.0D, 0.0D, 2.0D);
        CALENDAR_MONTH_MULTIPLIERS = BUILDER.comment("A multiplier for each month of the year, January first, for modpack makers who want the calendar to be theirs: 3.0 in month 5 makes every day of May three times as severe, 0 makes it as quiet as the calendar can make it, and 1.0 leaves the month to its season's own shape. Every month is 1.0 by default, because the season already carries the year; this is the dial for a pack that wants its own, such as a world whose storms belong to autumn alone. The twelve entries are read in calendar order, and a list that is not twelve numbers is ignored.")
                .defineList("monthMultipliers", DEFAULT_CALENDAR_MONTH_MULTIPLIERS, () -> "1.0", value -> value instanceof String);
        BUILDER.pop();

        // Where a tornado may be born, which is the one part of genesis the air cannot decide.
        BUILDER.push("tornado_genesis");
        TORNADO_GENESIS_PLAINS_ONLY = BUILDER.comment("Only lets a land tornado be born over plains country. A tornado is the one vortex in this mod that belongs to the ground rather than to the air: flat, open, loaded plains air is what it forms out of, and its own storm overhead is what it needs. A biome that reads as plains country, or that a pack has listed in the #expandedweather2dynamics:tornado_genesis biome tag, is allowed; anything else refuses the genesis, which is how a forest, a jungle, an ocean or a mountain stays free of funnels however favourable the air above it is. Switching this off restores the old behaviour, where any storm anywhere could spin one up. Whirls and spouts are not affected: a dust devil, a snow devil, a fire whirl and a waterspout belong to their own country and form wherever their own condition is met.")
                .define("plainsOnly", true);
        BUILDER.pop();

        // Whether a deadly day is the air's question or a dice roll's. Weather2's own deadly timer fires every
        // three to ten game days and is not this mod's to move; the flag that timer asks for is, and so is the
        // percentage the forecast prints about it.
        BUILDER.push("deadly_storm");
        DEADLY_STORM_FROM_AIR = BUILDER.comment("Lets the air decide whether a deadly storm is possible, instead of the dice roll deciding on its own. Weather2 promotes a freshly formed storm into a deadly one every three game days (or ten, for its land-based timer) - that cadence is Weather2's and this mod does not move it - but it only ever promotes a storm whose spawner said it may be deadly, which is a flag this mod sets on every cell it seeds. Left on, a cell is only allowed to be deadly where the air could carry one: high CAPE and real shear, read the same way the world's own convection is read. A capped, blown-out sky then cannot produce a deadly storm however long the timer has been running, which is the whole point - and the storm that is refused is still an ordinary storm, since the flag only decides the promotion. Switched off restores the old behaviour, where every cell this mod seeds is deadly-capable and the three-to-ten-day timer is the only thing that matters.")
                .define("fromAir", true);
        DEADLY_STORM_CAPE_FLOOR_JKG = BUILDER.comment("The CAPE below which the air cannot carry a deadly storm at all. 1000 J/kg is the conventional floor for a severe updraft: below it a day is a day of ordinary storms, and no amount of shear turns a capped column into a deadly one. This is the bottom of the readout's percentage as well as the bottom of the flag, so a quiet sky reads 0.00% a day and nothing here has to be taken on trust.")
                .defineInRange("capeFloorJkg", 1000.0D, 0.0D, 5500.0D);
        DEADLY_STORM_CAPE_FULL_JKG = BUILDER.comment("The CAPE at which the air is worth the whole of the odds Weather2's timer describes. 3000 J/kg is a loaded, deeply unstable afternoon. Between this and the floor the reading rises smoothly, so a marginal day is worth part of the odds rather than none or all of them; a sky above this mark is worth no more than a sky at it.")
                .defineInRange("capeFullJkg", 3000.0D, 0.0D, 5500.0D);
        DEADLY_STORM_SHEAR_SHARE = BUILDER.comment("How much of the reading is shear's to give. Instability gates the reading and shear shapes it: at 0.25 a day with no shear at all is worth three quarters of what its CAPE says, and a day with the full 30 m/s is worth all of it. It is not zero because a marginal but strongly sheared day is exactly the day a low-topped supercell comes out of, and not one because a sheared but capped day is nothing at all.")
                .defineInRange("shearShare", 0.25D, 0.0D, 1.0D);
        DEADLY_STORM_FULL_SHEAR_MPS = BUILDER.comment("The deep-layer shear at which the shear half of that reading is full. 30 m/s is a well-sheared severe day; anything above it counts as much as it does.")
                .defineInRange("fullShearMps", 30.0D, 1.0D, 60.0D);
        BUILDER.pop();

        BUILDER.push("winter");
        WINTER_ALERTS_ENABLED = BUILDER.define("alerts", true);
        BUILDER.pop();

        BUILDER.push("squall_lines");
        SQUALL_LINES_ENABLED = BUILDER.comment("Builds a continuous Weather2-seeded gust-front / shelf-cloud boundary with straight-line wind hazards.")
                .define("enabled", true);
        SQUALL_LINE_MIN_CELLS = BUILDER.defineInRange("minCells", 3, 2, 16);
        SQUALL_LINE_MAX_THICKNESS_BLOCKS = BUILDER.defineInRange("maxThicknessBlocks", 220, 40, 1200);
        SQUALL_LINE_MAX_AGE_TICKS = BUILDER.comment("Upper cap for organized squall-line lifetime. Common lines roll 3-8 real hours; exceptional lines can persist 12-30 real hours before this cap.")
                .defineInRange("maxAgeTicks", 20 * 60 * 60 * 30, 20 * 60 * 60, 20 * 60 * 60 * 30);
        SQUALL_LINE_STORM_DECK = BUILDER.comment("Draw the line out of the storm objects it was seeded from, by spawning each cell's own Weather2 cloud deck along the front instead of in a square around the cell. This is the line's silhouette and nothing else: the cells remain ordinary Weather2 thunderstorms, the puffs are Weather2's own, spawned by its own deck loop with its own art, size, lifetime, population and wind drift, and each section's footprint covers the same sky the cell's square did, so no cell's cloud is made denser or bigger by being on a line. The stretch along the front is what turns a row of round masses into one long, low shelf, and the thin depth across it is what keeps the sky under the shelf open instead of roofed over. Needs a synced line to belong to, so it stands down by itself where there is none. Off leaves every cell drawn as the round thunderstorm Weather2 would draw.")
                .define("stormDeck", true);
        BUILDER.pop();

        BUILDER.push("flooding");
        FLOODING_ENABLED = BUILDER.comment("Places capped shallow water in low terrain under heavy Weather2 rain cores, squall lines, and hurricanes.")
                .define("enabled", true);
        FLOODING_SCAN_INTERVAL_TICKS = BUILDER.defineInRange("scanIntervalTicks", 20, 5, 20 * 30);
        FLOODING_MAX_BLOCKS_PER_TICK = BUILDER.defineInRange("maxBlocksPerTick", 12, 0, 256);
        FLOODING_RANGE_BLOCKS = BUILDER.defineInRange("rangeBlocks", 360, 32, 2048);
        FLOODING_RAIN_THRESHOLD = BUILDER.comment("Minimum derived rain intensity before flood water is allowed to accumulate.")
                .defineInRange("rainThreshold", 0.62D, 0.0D, 2.0D);
        FLOODING_MAX_DEPTH_BLOCKS = BUILDER.comment("Maximum shallow flood depth the addon will create at one surface column.")
                .defineInRange("maxDepthBlocks", 1, 1, 4);
        BUILDER.pop();

        BUILDER.push("anemometer");
        ANEMOMETER_ENABLED = BUILDER.define("enabled", true);
        BUILDER.pop();

        BUILDER.push("weather_balloon");
        WEATHER_BALLOON_ENABLED = BUILDER.comment("Enables the launchable weather balloon (radiosonde): an instrument package under a latex balloon that ascends, radios back temperature, dewpoint, pressure and wind, and pops at altitude before parachuting home.")
                .define("enabled", true);
        WEATHER_BALLOON_ASCENT_BLOCKS_PER_SECOND = BUILDER.comment("Free-lift ascent rate. A real radiosonde climbs at about 5 blocks (metres) per second.")
                .defineInRange("ascentBlocksPerSecond", 5.0D, 0.5D, 40.0D);
        WEATHER_BALLOON_MAX_ALTITUDE_BLOCKS = BUILDER.comment("Height above the launch site at which the balloon bursts. Capped by the world build height.")
                .defineInRange("maxAltitudeBlocks", 320, 16, 1024);
        WEATHER_BALLOON_WIND_DRIFT_SCALE = BUILDER.comment("How strongly the balloon is carried by the local wind, including tornado windfields and supercell structure winds. Wind below roughly 12 blocks is reduced by surface friction, ramping to full strength by 100 blocks up.")
                .defineInRange("windDriftScale", 1.0D, 0.0D, 3.0D);
        WEATHER_BALLOON_BURST_WIND_MPH = BUILDER.comment("Wind speed at the balloon that tears the envelope apart. Launching into a tornado is how you get a tornado-intercept sounding, but the balloon will not survive it.")
                .defineInRange("burstWindMph", 95.0D, 30.0D, 400.0D);
        WEATHER_BALLOON_DESCENT_BLOCKS_PER_SECOND = BUILDER.comment("Parachute descent rate once the balloon pops.")
                .defineInRange("descentBlocksPerSecond", 7.0D, 0.5D, 60.0D);
        WEATHER_BALLOON_TELEMETRY_INTERVAL_TICKS = BUILDER.comment("How often the sonde radios a reading back to the player who launched it.")
                .defineInRange("telemetryIntervalTicks", 40, 5, 20 * 60);
        WEATHER_BALLOON_STORM_RANGE_BLOCKS = BUILDER.comment("Range within which a storm is reported in the sonde telemetry.")
                .defineInRange("stormRangeBlocks", 512, 64, 4096);
        WEATHER_BALLOON_RETURN_ON_LANDING = BUILDER.comment("Drops the balloon item where the payload lands, so a sounding can be flown again. Off makes sondes single use.")
                .define("returnOnLanding", true);
        WEATHER_BALLOON_LANDED_LIFETIME_TICKS = BUILDER.comment("How long a landed payload stays in the world before it is written off (only used when the item is not returned).")
                .defineInRange("landedLifetimeTicks", 20 * 60, 20, 20 * 60 * 60);
        WEATHER_BALLOON_MAX_LIFETIME_TICKS = BUILDER.comment("Hard lifetime cap for a single sounding, so a balloon can never be left drifting forever.")
                .defineInRange("maxLifetimeTicks", 20 * 60 * 20, 20 * 10, 20 * 60 * 60);
        WEATHER_BALLOON_MAX_ACTIVE_PER_PLAYER = BUILDER.comment("Maximum simultaneous balloons per player.")
                .defineInRange("maxActivePerPlayer", 3, 1, 32);
        WEATHER_BALLOON_RENDER_RANGE_BLOCKS = BUILDER.comment("Client draw distance for balloons. Sondes climb high, so they need more than the vanilla entity distance to stay visible from the ground.")
                .defineInRange("balloonRenderRangeBlocks", 640, 64, 4096);
        BUILDER.pop();

        BUILDER.push("inflow_jets");
        INFLOW_JETS_ENABLED = BUILDER.define("enabled", false);
        INFLOW_JET_LENGTH_BLOCKS = BUILDER.defineInRange("lengthBlocks", 100, 10, 512);
        INFLOW_JET_PARTICLES_PER_TICK = BUILDER.defineInRange("particlesPerTick", 5, 0, 80);
        BUILDER.pop();

        BUILDER.push("visuals");
        VISUALS_ENABLED = BUILDER.define("enabled", true);
        LEAF_EDDIES_ENABLED = BUILDER.comment("The little spirals of leaves a gust front lifts off the ground before its storm arrives - the first thing anybody sees of an approaching outflow, minutes before the rain. They need wind, a storm close enough that the wind is its inflow, and something growing on the ground to lift, so they only appear where they should. Client-side only; nothing about them is saved or sent.")
                .define("leafEddies", true);
        LEAF_EDDY_RANGE_BLOCKS = BUILDER.comment("How far from you the leaves are looked for. This is a search radius, not a drawing distance: an eddy found at the rim of it still swirls where it was found, which is the point of standing in a field watching one.")
                .defineInRange("leafEddyRangeBlocks", 40, 8, 96);
        EXTENDED_RENDERING_ENABLED = BUILDER.comment("Uses Weather2/ExtendedRenderer EntityRotFX particles for addon storm visuals. Disable only if a renderer or shader pack conflicts.")
                .define("extendedRendering", true);
        SEVERE_SKY_TINT_STRENGTH = BUILDER.defineInRange("severeSkyTintStrength", 0.8D, 0.0D, 1.5D);
        STORM_SKY_DARKENING = BUILDER.comment("Darken the sky, the fog, the cloud deck and the world's own ambient light under a storm's mass, from this mod's own reading of that mass rather than by driving the game's rain state. The sun, the moon and the stars are hidden by the same number, so there is one cause and no second system to disagree with it. Off leaves every one of those exactly as the game drew it, however heavy the storm. Measured at the shipped scale a directly-overhead storm leaves the sky about as dark as vanilla's own midnight.")
                .define("stormSkyDarkening", true);
        STORM_SKY_DARKEN_SCALE = BUILDER.comment("How far into that darkening a storm goes. 1.0 lets a direct hit with a hailing core reach the game's own midnight, 0.5 tops out halfway there, 0 leaves the shapes and takes none of the light.")
                .defineInRange("stormSkyDarkenScale", 0.85D, 0.0D, 1.0D);
        NIGHT_STORM_DARKNESS = BUILDER.comment("How dark a storm's clouds, rain and funnel go at night. Lit like the ground, a pale cloud glows against the night sky; at 1.0 a storm at midnight is lit by under a third of the sky light the ground is, so it is a dark shape in a dark sky, and lightning lights it up. 0 lights it like the ground.")
                .defineInRange("nightStormDarkness", 1.0D, 0.0D, 1.0D);
        SKY_HAIL_GREEN = BUILDER.comment("A green sky over a hailing cell. A green sky is light coming through a storm's hail core, so this mod puts it where the hail actually is: the sky turns green only where stones are falling around you or over a hail core in sight, gated on the same size ladder as the hail itself, with nothing below quarter-sized stones. A storm with a rain core and no hail never turns green.")
                .define("hailSkyGreen", true);
        HAIL_SKY_GREEN_STRENGTH = BUILDER.comment("How green the sky goes at the middle of a baseball-sized hail core. 1 is the mod's own mix - muted, so it reads as weather rather than as a filter - and 0 is the same as switching the green off.")
                .defineInRange("hailSkyGreenStrength", 0.85D, 0.0D, 1.5D);
        STORM_COVER_SHADING = BUILDER.comment("Measure a storm's darkening by the cover above you - its cloud deck, its wall cloud, its anvil and the precipitation falling on you - instead of by how near the storm's centre is. Shade then ends at the edge of the cloud mass rather than fading out over 700 blocks, so being under the deck or the rain is what darkens the world, and a storm you can see the sky past does not. Off restores the distance-based model exactly as it was.")
                .define("stormCoverShading", true);
        STORM_COVER_DECK_SCALE = BUILDER.comment("How far a storm's cloud shield reaches, as a multiplier of the size Weather2 gives the storm. Above 1 shades a wider area under the same deck; below 1 confines the shade to the cloud mass itself.")
                .defineInRange("stormCoverDeckScale", 1.0D, 0.35D, 2.0D);
        CLEAR_INTERVALS_ENABLED = BUILDER.define("clearIntervals", true);
        CLEAR_INTERVAL_RAIN_REDUCTION = BUILDER.defineInRange("clearIntervalRainReduction", 0.03D, 0.0D, 1.0D);
        SKY_SMOOTHING = BUILDER.defineInRange("skySmoothing", 0.04D, 0.001D, 1.0D);
        MIN_SKY_UPDATE_TICKS = BUILDER.defineInRange("minSkyUpdateTicks", 4, 1, 200);
        STICK_DEBRIS_ENABLED = BUILDER.comment("Spawns visual-only loose debris in tornadoes, sub-vortices, satellites, landspouts, dust devils, waterspouts, and squall-line winds: a chunk of the ground under each piece, drawn with that block's own texture, or a stick where the column holds no ground to tear, such as open water.")
                .define("stickDebris", true);
        STICK_DEBRIS_RENDER_RANGE_BLOCKS = BUILDER.defineInRange("stickDebrisRenderRangeBlocks", 512, 32, 4096);
        MAX_STICK_DEBRIS_PER_TICK = BUILDER.defineInRange("maxStickDebrisPerTick", 80, 0, 1000);
        SUB_VORTEX_STICK_DENSITY = BUILDER.defineInRange("subVortexStickDensity", 0.55D, 0.0D, 1.0D);
        LANDSPOUT_STICK_DENSITY = BUILDER.defineInRange("landspoutStickDensity", 0.42D, 0.0D, 1.0D);
        DUST_DEVIL_STICK_DENSITY = BUILDER.defineInRange("dustDevilStickDensity", 0.55D, 0.0D, 1.0D);
        SNOW_DEVIL_STICK_DENSITY = BUILDER.comment("How much loose debris a snow devil lifts, where the other whirls read the dust devil's own figure. Cold ground holds far less loose material than a desert does, and what it does hold is snow, so this is deliberately a fifth of a dust devil's.")
                .defineInRange("snowDevilStickDensity", 0.12D, 0.0D, 1.0D);
        WATERSPOUT_STICK_DENSITY = BUILDER.defineInRange("waterspoutStickDensity", 0.10D, 0.0D, 1.0D);
        SQUALL_LINE_STICK_DENSITY = BUILDER.defineInRange("squallLineStickDensity", 0.12D, 0.0D, 1.0D);
        TORNADO_DEBRIS_CLOUD_ENABLED = BUILDER.comment("Spawns a visual-only dark-brown Weather2 cloud256 debris cloud around tornado ground circulations.")
                .define("tornadoDebrisCloud", true);
        TORNADO_DEBRIS_CLOUD_RENDER_RANGE_BLOCKS = BUILDER.defineInRange("tornadoDebrisCloudRenderRangeBlocks", 640, 32, 4096);
        TORNADO_DEBRIS_CLOUD_MAX_PARTICLES_PER_TICK = BUILDER.defineInRange("maxTornadoDebrisCloudParticlesPerTick", 140, 0, 1200);
        TORNADO_DEBRIS_CLOUD_OPACITY = BUILDER.comment("Opacity of the tornado inflow dust/debris cloud. Low values keep the condensation funnel visible through the dust; 1.0 restores the full wall of dust, 0 disables the particles entirely.")
                .defineInRange("tornadoDebrisCloudOpacity", 0.30D, 0.0D, 1.0D);
        TORNADO_DEBRIS_CLOUD_DENSITY = BUILDER.defineInRange("tornadoDebrisCloudDensity", 1.15D, 0.0D, 2.0D);
        TORNADO_DEBRIS_CLOUD_HUMIDITY_GATE = BUILDER.comment("Tornado dust clouds form and grow based on the storm's humidity: dry storms raise dust early and keep it big, while humid / rain-wrapped storms show almost no dust until the tornado is violent.")
                .define("tornadoDebrisCloudHumidityGate", true);
        TORNADO_DEBRIS_CLOUD_HUMIDITY_SCALE = BUILDER.comment("Sensitivity of the humidity response: 0 disables it (always dusty), 1 is realistic, 2 makes humidity dominate.")
                .defineInRange("tornadoDebrisCloudHumidityScale", 1.0D, 0.0D, 2.0D);
        TORNADO_DEBRIS_CLOUD_KICKUP_DENSITY = BUILDER.comment("Small dirt clods kicked up off the ground around the tornado base: dry storms throw loose surface dirt, wet / rain-wrapped storms skip it entirely. 0 disables.")
                .defineInRange("tornadoDebrisCloudKickupDensity", 1.0D, 0.0D, 2.0D);
        FOLIAGE_WIND_SHADER = BUILDER.comment("Uses cutout terrain vertex shaders to sway leaves and grass from local Weather2 storm wind. Client-side visual only.")
                .define("foliageWindShader", true);
        FOLIAGE_WIND_USE_PHYSICS_MOD = BUILDER.comment("When Physics Mod is installed, samples its local weather wind domain and skips blocks Physics Mod already simulates.")
                .define("foliageWindUsePhysicsMod", true);
        FOLIAGE_WIND_LEAVES = BUILDER.define("foliageWindLeaves", true);
        FOLIAGE_WIND_GRASS = BUILDER.define("foliageWindGrass", true);
        FOLIAGE_WIND_RENDER_RANGE_BLOCKS = BUILDER.defineInRange("foliageWindRenderRangeBlocks", 384, 32, 4096);
        FOLIAGE_WIND_MAX_OFFSET_BLOCKS = BUILDER.comment("How far a plant's tip may travel from upright, in blocks. A one block blade tipped this far is leaning atan(offset) degrees over, so the size of this number is the whole difference between a field that moves and one that does not: 0.18 is about ten degrees and reads as barely stirring, 0.45 is a hard blow, and 0.7 lies a meadow flat. Leaves are held to a fraction of it, because a leaf block that slid this far would tear away from the trunk it is drawn against.")
                .defineInRange("foliageWindMaxOffsetBlocks", 0.45D, 0.0D, 1.0D);
        FOLIAGE_WIND_BASELINE_MPH = BUILDER.comment("A floor under the wind the sway reads, in mph: a light breeze that is always blowing. The sway follows Weather2's own ambient wind, which is what the clouds are drawn from, so on any day with real air moving this changes nothing. It takes over only when the world is genuinely still, and it has to exist because a sway scaled by a wind of zero does not move at all - the blades are pinned rigid, which reads as a broken effect rather than a calm day. Grass at this speed leans a few degrees and nods. 0 restores sway that stops completely in dead air.")
                .defineInRange("foliageWindBaselineMph", 12.0D, 0.0D, 60.0D);
        FOLIAGE_WIND_THRASH_MPH = BUILDER.comment("The wind at which a plant is as far over as this feature will put it, in mph. Lower it and ordinary weather moves the field as hard as a gale does.")
                .defineInRange("foliageWindThrashMph", 95.0D, 1.0D, 500.0D);
        FOLIAGE_WIND_SMOOTHING = BUILDER.defineInRange("foliageWindSmoothing", 0.12D, 0.001D, 1.0D);
        FOLIAGE_WIND_GUST_WAVELENGTH_BLOCKS = BUILDER.comment("How far apart the travelling gust bands stand, in blocks. A plant leans as a band passes over it and its neighbour a wavelength downwind leans a moment later, so the whole field ripples in waves; too short and the field shimmers, too long and it moves as one sheet.")
                .defineInRange("foliageWindGustWavelengthBlocks", 26.0D, 4.0D, 160.0D);
        FOLIAGE_WIND_GUST_SPEED_SCALE = BUILDER.comment("How fast the gust bands travel, as a multiple of the local wind's own speed - a gust is carried by the wind that made it, so a gale's bands cross a field quickly and a breeze's drift. 0 pins the bands in place, which turns the ripple back into a hum.")
                .defineInRange("foliageWindGustSpeedScale", 1.0D, 0.0D, 3.0D);
        FOLIAGE_WIND_REST_LEAN = BUILDER.comment("How far a plant leans in completely still air, as a fraction of its maximum sway. Every plant holds a small, different tilt, so a field at rest still reads as plants rather than as decals; 0 stands every plant perfectly upright.")
                .defineInRange("foliageWindRestLean", 0.35D, 0.0D, 1.0D);
        FOLIAGE_WIND_SKY_FLOOR = BUILDER.comment("How much sway survives where the sky cannot be seen - a cave, a building, deep under a canopy. Sway is scaled by the sky light at the plant, and this is the floor it is scaled to; 1.0 turns the sheltering off and lets buried grass wave as hard as a meadow.")
                .defineInRange("foliageWindSkyFloor", 0.20D, 0.0D, 1.0D);
        FOLIAGE_WIND_WAVE_LAG_DEGREES_PER_BLOCK = BUILDER.comment("How far the motion lags for every block of height above a plant's root, in degrees. This is what sends a wave travelling up each blade: the root starts to move and the tip follows a moment later, so the plant flexes instead of rotating on a hinge. At 0 the whole plant swings in one piece, which is what makes grass look like a metronome; 60-90 reads as a real stem and taller values make the tips trail further. Old Weather2's foliage shader lagged the same way, which is why its grass rippled down the length of a blade.")
                .defineInRange("foliageWindWaveLagDegreesPerBlock", 70.0D, 0.0D, 360.0D);
        FOLIAGE_WIND_TRAIL = BUILDER.comment("Lets players and mobs lay the grass over as they push through it, and lets it stand back up afterwards. Client-side visual only, and it costs nothing when nothing nearby is moving.")
                .define("foliageWindTrail", true);
        FOLIAGE_WIND_TRAIL_RANGE_BLOCKS = BUILDER.comment("How far away something can be and still lay grass where you are. Only the strongest few tracks are sent to the shader, so this is about how much tracks you see at once, not how many are simulated.")
                .defineInRange("foliageWindTrailRangeBlocks", 32, 4, 128);
        FOLIAGE_WIND_TRAIL_RADIUS_BLOCKS = BUILDER.comment("How wide one print is, in blocks - the radius around a track that grass is pushed down within.")
                .defineInRange("foliageWindTrailRadiusBlocks", 1.7D, 0.4D, 8.0D);
        FOLIAGE_WIND_TRAIL_STRENGTH = BUILDER.comment("How hard a print lays the grass over, as a fraction of the maximum sway. A fresh print also holds the wind off the plant it has flattened.")
                .defineInRange("foliageWindTrailStrength", 0.85D, 0.0D, 1.5D);
        FOLIAGE_WIND_TRAIL_LINGER_TICKS = BUILDER.comment("How long a print takes to spring back up, in ticks. The grass is laid over as something passes and recovers over this time, so a path closes behind you instead of snapping straight at your heels.")
                .defineInRange("foliageWindTrailLingerTicks", 18, 1, 200);
        BUILDER.pop();

        BUILDER.push("ocean_waves");
        OCEAN_WAVES_ENABLED = BUILDER.comment("Displaces the ocean surface into wind-driven waves. The sea state is read from the local wind - a hurricane piles up a real swell, a tornado passing over water throws chop, and settled weather leaves only a light ripple - and the surface is a pure function of the wind, so it needs no syncing and no saved state. Client-side, visual only: nothing here moves water blocks or the player.")
                .define("enabled", true);
        OCEAN_WAVES_RENDER_RADIUS_BLOCKS = BUILDER.comment("How far the wave surface is drawn around the camera. The outermost third of it fades out, so the lattice's edge dissolves rather than ending on a hard square. Cost rises with the square of this: the default 112 blocks at a 4 block cell is a 57 by 57 lattice, so about 3,100 quads in one draw call.")
                .defineInRange("renderRadiusBlocks", 112, 32, 384);
        OCEAN_WAVES_CELL_SIZE = BUILDER.comment("Blocks per wave cell - the resolution the surface is drawn at, and so the shortest wavelength it can resolve. Smaller is smoother and costs more; keep it under a quarter of the wavelength the sea is running at, or the waves will alias into flicker.")
                .defineInRange("cellSizeBlocks", 4, 2, 16);
        OCEAN_WAVES_LATTICE_REFRESH_TICKS = BUILDER.comment("How often the water under the lattice is re-checked. The surface height only changes when water is added or removed, and the lattice also rebuilds the moment the camera crosses a cell, so this is the backstop for a stationary player watching a river.")
                .defineInRange("latticeRefreshTicks", 8, 1, 200);
        OCEAN_WAVES_WIND_RANGE_BLOCKS = BUILDER.comment("How far away a storm can be and still build the sea where the player is. A hurricane's wind field reaches further than a tornado's, and this is what decides whether you see its swell from the shore.")
                .defineInRange("windRangeBlocks", 512, 64, 4096);
        OCEAN_WAVES_MAX_AMPLITUDE_BLOCKS = BUILDER.comment("How high the crests stand above the flat water plane at the storm's full wind speed. The troughs never go below it, because a surface that dips under the water it is drawn over would be swallowed by the flat vanilla surface; the whole wave rides above.")
                .defineInRange("maxAmplitudeBlocks", 2.2D, 0.0D, 8.0D);
        OCEAN_WAVES_WAVELENGTH_BLOCKS = BUILDER.comment("Wavelength of the dominant train at full wind. It scales with wind speed, as real wind-sea does, so a breeze makes short chop and a hurricane makes long swell - which is also why the period comes out right: the trains use deep-water dispersion, so a 44 block swell runs with a period of about 5 seconds and a speed of about 18 mph.")
                .defineInRange("wavelengthBlocks", 44.0D, 6.0D, 200.0D);
        OCEAN_WAVES_FULL_WIND_MPH = BUILDER.comment("The wind at which the sea reaches its configured maximum. A hurricane's eyewall is well past this, so a major hurricane and a 100 mph gale both flatten against the ceiling; lower it to make ordinary storms look rougher.")
                .defineInRange("fullWindMph", 105.0D, 20.0D, 400.0D);
        OCEAN_WAVES_BASELINE_WIND_MPH = BUILDER.comment("A floor under the wave wind, so the open ocean keeps a light chop in settled weather instead of going glassy. The sea takes a storm's direction whenever there is one; this only sets how rough it never gets quieter than.")
                .defineInRange("baselineWindMph", 8.0D, 0.0D, 80.0D);
        OCEAN_WAVES_MAX_STEEPNESS = BUILDER.comment("How steep the waves may get, as trough-to-crest height over wavelength. Capping it is what keeps a strong wind from drawing a spiky sea: real water breaks at about a seventh, which is the ceiling here, and the cap bites hardest at high wind and short wavelength.")
                .defineInRange("maxSteepness", 1.0D / 7.0D, 0.02D, 1.0D / 7.0D);
        OCEAN_WAVES_SWELL_BUILD_SECONDS = BUILDER.comment("How long the swell takes to build when the wind rises, as a time constant. This is the lag that makes a sea look like it has been blowing rather than switched on; a hurricane arriving over about half a minute reads well, and longer values give a slower, heavier build.")
                .defineInRange("swellBuildSeconds", 22.0D, 1.0D, 600.0D);
        OCEAN_WAVES_SWELL_DECAY_SECONDS = BUILDER.comment("How long the swell takes to die away once the wind drops. Shorter than the build, as it is in life: a sea builds slowly and lies down faster.")
                .defineInRange("swellDecaySeconds", 11.0D, 1.0D, 600.0D);
        OCEAN_WAVES_SURFACE_OPACITY = BUILDER.comment("How opaque the wave surface is. It is drawn over the flat vanilla water, so it has to be nearly opaque to hide the sheet underneath it - lower this and the flat surface starts showing through the troughs.")
                .defineInRange("surfaceOpacity", 0.92D, 0.0D, 1.0D);
        OCEAN_WAVES_FOAM_THRESHOLD = BUILDER.comment("Where in the crest foam starts, as a fraction of the crest. Higher means only the very tops whitecap.")
                .defineInRange("foamThreshold", 0.72D, 0.5D, 1.0D);
        OCEAN_WAVES_FOAM_STRENGTH = BUILDER.comment("How white a fully-foamed crest goes. 0 leaves clean water, 1 makes the tops read as solid whitecaps.")
                .defineInRange("foamStrength", 0.55D, 0.0D, 1.0D);
        OCEAN_WAVES_SHOAL_DEPTH_BLOCKS = BUILDER.comment("The water depth at which the waves reach their full height, in blocks. Shallower water shoals them down toward the flat surface, so the sea settles onto a beach instead of holding full height right up to it - which, without it, leaves the surface hanging above the sand rather than running into it. The depth is only measured in the band of water around the shoreline, so this costs nothing out at sea. 0 turns shoaling off.")
                .defineInRange("shoalDepthBlocks", 6.0D, 0.0D, 24.0D);
        OCEAN_WAVES_SHORE_FIT = BUILDER.comment("Draws the cells that touch land at one block per sample instead of one sample per cell. A cell is 4 blocks across by default, so without this the wave surface stops a whole cell short of the sand; with it the edge of the water follows the water's own blocks and the surface reaches the beach. The surf run-up also needs it, because the sand it runs up is sampled there.")
                .define("shoreFit", true);
        OCEAN_WAVES_SURF_ENABLED = BUILDER.comment("Waves break where the water gets too shallow to hold them. A wave stands up as it shoals and is then cut back to the height its depth allows - the depth-limited breaking limit - and the height it loses becomes whitewater behind it, so shoaling water grows a breaker line with broken water inside it instead of just settling quietly. It is scaled by the sea state, because the limit is proportional to the wave's own height: a hurricane's surf zone reaches out many blocks and a light chop barely laps the sand. Off leaves the swell shoaling down smoothly with no breakers - and no wash on the sand either, since the water that runs up the beach is the water that broke.")
                .define("surfEnabled", true);
        OCEAN_WAVES_SURF_BREAK_RATIO = BUILDER.comment("How tall a wave may stand before the water under it is too shallow to hold it, as trough-to-crest height over depth. Real water breaks at about 0.78. Higher lets waves run taller into the shallows and break later and closer in; lower breaks them earlier and further out to sea.")
                .defineInRange("surfBreakRatio", 0.78D, 0.3D, 1.2D);
        OCEAN_WAVES_SURF_SHOAL_GAIN = BUILDER.comment("How much the swell may stand up as it shoals, as a multiplier on its open-water height. Green's law grows a wave as the water under it shallows, which is what makes the last stretch before a beach look like it is rearing up; the cap here stops that running away, and the breaking limit above always cuts it back. 1 turns the growth off and leaves only the cut-back.")
                .defineInRange("surfShoalGain", 1.25D, 1.0D, 2.0D);
        OCEAN_WAVES_SURF_RUN_UP_BLOCKS = BUILDER.comment("How far up the beach the swash runs, in blocks, at the biggest swell the wind can build - measured from the top of the block of sand the water is standing against, because a shore is made of blocks and the first one past the water stands a whole block above it, so that step is climbed before this reach is spent. It is animated rather than fixed: the waterline advances with each incoming crest and drains back between them, in step with the water it came from. Smaller seas scale it down, so a calm day does not wash the beach: the step the sea can climb is its own share of a real swell, so a lake's chop wets the waterline and nothing above it while a storm's wash climbs the row above. 0 leaves the water entirely - breakers with no run-up on the sand. The reach is also limited by how much sand a shoreline cell covers, which is up to one cell at block resolution.")
                .defineInRange("surfRunUpBlocks", 1.5D, 0.0D, 6.0D);
        OCEAN_WAVES_SURF_FOAM = BUILDER.comment("How white broken water goes, where a crest has been cut back or the water is inside the breaker line. Kept separate from foamStrength, which is the wind's own whitecapping on open-water crests.")
                .defineInRange("surfFoam", 0.85D, 0.0D, 1.0D);
        BUILDER.pop();

        BUILDER.push("thunder");
        IMMERSIVE_THUNDER_ENABLED = BUILDER.define("immersiveThunder", true);
        THUNDER_SECONDS_PER_MILE = BUILDER.defineInRange("secondsPerMile", 5.0D, 0.0D, 20.0D);
        THUNDER_MAX_DELAY_TICKS = BUILDER.defineInRange("maxDelayTicks", 20 * 35, 0, 20 * 120);
        BUILDER.pop();

        BUILDER.push("tornado_immersion");
        TORNADO_IMMERSION_ENABLED = BUILDER.comment("Adds client-side tornado dread effects tied to real circulation distance and windfield strength.")
                .define("enabled", true);
        TORNADO_IMMERSION_ROAR_SOUND = BUILDER.comment("Loops a tornado roar whose loudness is worked out from how far the funnel's WALL is, not from the engine's own distance falloff, so it can be genuinely loud up close and still be heard from a long way off - the way a tornado actually is. Measured from the edge of the vortex rather than its centre, a wide wedge roars at you from distances where a thin rope of the same strength would be silent. Off silences the whole roar stack, the distance layer included.")
                .define("roarSound", true);
        TORNADO_IMMERSION_ROAR_LOUDNESS = BUILDER.comment("Master loudness of the roar. The sound is loud on purpose - a tornado close enough to hear is a train going past - so this is the first knob to move if it overwhelms the rest of the mix. It scales the whole family, near and far.")
                .defineInRange("roarLoudness", 1.0D, 0.0D, 4.0D);
        TORNADO_IMMERSION_ROAR_RADIUS_BLOCKS = BUILDER.comment("How far the roar can be heard, in blocks. This is a real distance: the roar keeps its level out to a reference range, falls away past it, and is silent at this one, so a tornado far away is a low rumble on the horizon and one on top of you is deafening. The default is a working distance rather than the physics - air carries a real roar several kilometres - because a roar audible from the whole map stops meaning anything. Note that the storm sample this reads is only updated inside renderRangeBlocks, so that setting is an upper bound on this one.")
                .defineInRange("roarRadiusBlocks", 320.0D, 32.0D, 1600.0D);
        TORNADO_IMMERSION_ROAR_NEAR_RANGE_BLOCKS = BUILDER.comment("How close the funnel has to be, in blocks, for the bright roar to be the layer you hear. Inside half this distance it is the roar alone; out past one and a half times it, the distance layer has taken over completely and in between the two are crossfaded. They are the same waveform, one of them air-filtered, and they are level-matched, so the hand-over is a change of tone rather than a step - a train arriving, with the hiss washing out of it as it gets further away.")
                .defineInRange("roarNearRangeBlocks", 56.0D, 16.0D, 400.0D);
        TORNADO_IMMERSION_ROAR_DISTANCE_ROLLOFF = BUILDER.comment("How fast the roar fades with distance, as the exponent on an inverse-power falloff. 1.0 is inverse distance - the heavy, physical drop; below it the roar carries further, above it only the close approach is audible. The default is tuned so a monster wedge is still a real presence at a few hundred blocks.")
                .defineInRange("roarDistanceRolloff", 0.75D, 0.2D, 1.5D);
        TORNADO_IMMERSION_ROAR_DISTANCE_LAYER = BUILDER.comment("Fades the air-absorbed roar in with distance, so what carries across the map is the rumble with the hiss removed rather than the bright roar turned down. Off leaves the roar alone at every distance.")
                .define("roarDistanceLayer", true);
        TORNADO_IMMERSION_REPLACE_WEATHER2_WIND = BUILDER.comment("Stands Weather2's own tornado wind down so this mod's roar is the only one. Weather2 fires its wind as player-locked samples retriggered every two seconds, over a straight-line falloff that runs to nothing at 120 blocks (200 for a waterspout), while the roar here is a continuous loop at the funnel on its own falloff to the wall - played together they double up and their two distances disagree, which is the pulsing wind you hear under the roar. On by default: this is a replacement, not a layer. Off restores Weather2's wind and plays both. Weather2's siren blocks, destruction and sandstorm audio are played elsewhere and are not affected either way.")
                .define("replaceWeather2Wind", true);
        TORNADO_IMMERSION_CAMERA_RUMBLE = BUILDER.comment("Adds subtle wind-pressure camera rumble near dangerous tornado windfields.")
                .define("cameraRumble", true);
        TORNADO_IMMERSION_MAX_CAMERA_ROLL_DEGREES = BUILDER.defineInRange("maxCameraRollDegrees", 1.35D, 0.0D, 12.0D);
        TORNADO_IMMERSION_CAMERA_TUMBLE = BUILDER.comment("When captured by a violent core, continuously spins the camera yaw and rolls it hard so the view tumbles like the player is being rolled inside the tornado.")
                .define("cameraTumble", true);
        TORNADO_IMMERSION_MAX_TUMBLE_ROLL_DEGREES = BUILDER.defineInRange("maxTumbleRollDegrees", 14.0D, 0.0D, 60.0D);
        TORNADO_IMMERSION_TUMBLE_SPIN_DEGREES = BUILDER.comment("First-person yaw spin rate of the capture tumble, in degrees per second. This turns the CAMERA only: it is a view offset, so it never writes the player's head or body rotation, and captured mobs are never yaw-spun at all.")
                .defineInRange("tumbleSpinDegreesPerSecond", 40.0D, 0.0D, 180.0D);
        TORNADO_IMMERSION_TUMBLE_SPEED_MULTIPLIER = BUILDER.comment("Scales how fast captured players and mobs tumble - the end-over-end pitch flip plus the roll about the body axis, and the first-person camera tumble. 1.0 is the tuned base rate, where a full flip takes roughly two to three seconds; the default 2.5 brings that to about one second. Both sides scale together because the server flips mobs (their rotation reaches clients as vanilla rotation updates) while this client flips its own player - scaling only one would leave them tumbling at different speeds. Above roughly 4 the fastest mobs can start to look stepped, because vanilla only relays mob rotation a few times a second.")
                .defineInRange("tumbleSpeedMultiplier", 2.5D, 0.1D, 6.0D);
        TORNADO_IMMERSION_FOV_PRESSURE = BUILDER.comment("Slightly narrows/pressurizes FOV near the tornado core.")
                .define("fovPressure", true);
        TORNADO_IMMERSION_FOG_PRESSURE = BUILDER.comment("Darkens and tightens fog near violent or close tornado circulations.")
                .define("fogPressure", true);
        MORNING_FOG_ENABLED = BUILDER.comment("Adds cool, client-side morning fog around dawn, stronger near wet ground and water.")
                .define("morningFog", true);
        MORNING_FOG_DENSITY = BUILDER.comment("Scales morning fog thickness without affecting tornado-pressure fog.")
                .defineInRange("morningFogDensity", 0.62D, 0.0D, 1.0D);
        TORNADO_IMMERSION_CLOSE_DEBRIS = BUILDER.comment("Adds extra visual-only near-ground dust near the tornado core. Off by default because Weather2 already renders tornado debris.")
                .define("closeDebris", false);
        TORNADO_IMMERSION_INSIDE_CORE_EFFECTS = BUILDER.comment("Enables extra client-only effects when the player is inside a tornado damage path/core.")
                .define("insideCoreEffects", true);
        TORNADO_IMMERSION_INFLOW_SHADER_OVERLAY = BUILDER.comment("Draws a shader-style screen overlay with inflow streaks, dust veil, and tornado-eye ring when inside the tornado. Opt-in only - the whiteout no longer force-activates it, so the tornado stays visible by default.")
                .define("inflowShaderOverlay", false);
        TORNADO_IMMERSION_EYE_EFFECT = BUILDER.comment("Adds a brief calm hollow visual/audio pocket in the innermost tornado core.")
                .define("eyeEffect", true);
        TORNADO_IMMERSION_BLOWOUT_AUDIO = BUILDER.comment("Saturates and wobbles tornado roar audio near/inside the core.")
                .define("blowoutAudio", true);
        TORNADO_IMMERSION_INTENSE_EF3_EFFECTS = BUILDER.comment("Adds an extra roar and camera-pressure layer only when the sampled tornado wind reaches EF3 strength.")
                .define("intenseEf3Effects", true);
        TORNADO_IMMERSION_EF3_WIND_THRESHOLD_MPH = BUILDER.defineInRange("ef3WindThresholdMph", 136.0D, 0.0D, 500.0D);
        TORNADO_IMMERSION_HUM_SOUND = BUILDER.comment("Gives a monster tornado a hum: a deep, slowly swelling drone under the roar, heard only while a tornado's own wind is at or above humWindThresholdMph. A hum is not a louder roar - it is the note a vortex that size carries, because a column of air that tall turning at a coherent rate is a resonator. It fades in as the wind climbs past the threshold and carries much further than the roar does, so a violent tornado can be heard coming from a long way out. Off silences it without touching the roar.")
                .define("humSound", true);
        TORNADO_IMMERSION_HUM_WIND_THRESHOLD_MPH = BUILDER.comment("How fast a tornado's own wind has to be, in mph, before it hums. 250 is above the top of the EF5 scale, so this is deliberately rare: it is the sound of a tornado that has stopped being weather and started being a structure. Lower it to hear it more often.")
                .defineInRange("humWindThresholdMph", 250.0D, 60.0D, 400.0D);
        TORNADO_IMMERSION_HUM_VOLUME = BUILDER.comment("Loudness multiplier on the hum. It stacks over the roar rather than replacing it, so this is how far it sits above the wind noise.")
                .defineInRange("humVolume", 1.0D, 0.0D, 3.0D);
        TORNADO_IMMERSION_HUM_RADIUS_BLOCKS = BUILDER.comment("How far a humming tornado can be heard, in blocks. This is a real distance, not a gain: the sound is positioned at the tornado and falls off in a straight line to nothing at this range, so walking away from a monster visibly quietens it. Bigger than the close effect radius on purpose - the low drone is what carries across a county.")
                .defineInRange("humRadiusBlocks", 320.0D, 16.0D, 1200.0D);
        TORNADO_IMMERSION_WIND_BED = BUILDER.comment("Lays a broad wind bed under the roar: the air-absorbed wind loop carried at its own audible radius, breathing on slow gusts rather than sitting flat, so the storm has a low body of moving air around it and not only the close roar. It carries further than the roar and does not wait for the hum's threshold, so even an ordinary tornado is heard as weather rather than as a gap in the mix. Off silences the bed without touching the roar or the hum.")
                .define("windBed", true);
        TORNADO_IMMERSION_WIND_BED_VOLUME = BUILDER.comment("Loudness multiplier on the wind bed. It stacks under the roar, so this is how far the bed sits below the storm's own voice; 0 is a silent bed with the switch above still on.")
                .defineInRange("windBedVolume", 0.6D, 0.0D, 3.0D);
        TORNADO_IMMERSION_WIND_BED_RADIUS_BLOCKS = BUILDER.comment("How far the wind bed can be heard, in blocks. A real distance rather than a gain: the bed is positioned at the tornado and falls away to nothing across this range, so a storm on the horizon is a body of distant moving air that gets nearer. Set above the roar's own radius for it to be the layer that carries.")
                .defineInRange("windBedRadiusBlocks", 180.0D, 16.0D, 1200.0D);
        TORNADO_IMMERSION_EF3_ROAR_MULTIPLIER = BUILDER.defineInRange("ef3RoarMultiplier", 1.35D, 1.0D, 4.0D);
        TORNADO_IMMERSION_EF3_CAMERA_MULTIPLIER = BUILDER.defineInRange("ef3CameraMultiplier", 1.55D, 1.0D, 4.0D);
        TORNADO_IMMERSION_INSIDE_FOG_MULTIPLIER = BUILDER.defineInRange("insideFogMultiplier", 1.0D, 0.0D, 4.0D);
        TORNADO_IMMERSION_INFLOW_OVERLAY_OPACITY = BUILDER.defineInRange("inflowOverlayOpacity", 0.72D, 0.0D, 1.0D);
        TORNADO_IMMERSION_EYE_CLEAR_RADIUS = BUILDER.defineInRange("eyeClearRadius", 0.34D, 0.05D, 0.90D);
        TORNADO_IMMERSION_BLOWOUT_AUDIO_INTENSITY = BUILDER.defineInRange("blowoutAudioIntensity", 1.0D, 0.0D, 3.0D);
        TORNADO_IMMERSION_WHITEOUT_ENABLED = BUILDER.comment("Full dust whiteout whenever the player is inside a tornado funnel rather than only deep in a violent core: fog collapses to a few blocks, the fog colour goes to neutral dust grey, and a flat dust veil covers the screen - like being caught inside. It ramps in from the funnel wall and saturates in the core. It is its own switch, so it does not depend on insideCoreEffects or on the opt-in inflowShaderOverlay, which stays streaks-only.")
                .define("whiteout", true);
        TORNADO_IMMERSION_WHITEOUT_FOG_BLOCKS = BUILDER.defineInRange("whiteoutFogBlocks", 3.0D, 0.5D, 16.0D);
        TORNADO_IMMERSION_WHITEOUT_VEIL_OPACITY = BUILDER.defineInRange("whiteoutVeilOpacity", 0.96D, 0.0D, 1.0D);
        TORNADO_IMMERSION_WHITEOUT_RADIUS_FRACTION = BUILDER.comment("How far out from the vortex centre the whiteout begins, as a multiple of the funnel wall's radius (the radius of maximum wind). 1.0 starts it at the wall itself; lower keeps it deep in the inner core where it is hard to reach, higher lets it close in before you are fully engulfed.")
                .defineInRange("whiteoutRadiusFraction", 1.15D, 0.30D, 3.0D);
        TORNADO_IMMERSION_WHITEOUT_DEBRIS = BUILDER.comment("How much debris the whiteout veil carries: dust streaks tearing past, dark clumps of boards and sheet metal crossing the view, and a fine grit over the whole veil. All three scale with how violent the funnel is, so an EF1 you can see through and an EF4 you cannot. 0.0 restores the plain flat veil.")
                .defineInRange("whiteoutDebris", 1.0D, 0.0D, 1.5D);
        TORNADO_IMMERSION_WHITEOUT_STORM_TINT = BUILDER.comment("Tints the engulfing dust by the storm that is producing it and how wet it is: a rain-wrapped supercell reads as a washed, near-uniform grey, a snow storm or snow devil as a cold blue-white, a dust devil, landspout or sandstorm as tan, a waterspout as sea spray, and a fire whirl or lava whirl as dark smoke. The grit and the dark clumps thin out for the wet storms, so a rain-wrapped funnel is an even wall of grey rather than a screen of boards. Off restores the one warm-grey dust for every storm.")
                .define("whiteoutStormTint", true);
        TORNADO_IMMERSION_WHITEOUT_SHELTER = BUILDER.comment("Stands the whiteout down when the player is sheltered - under a roof, in a basement, in a cave - even while they are inside the funnel radius. Cover is read from whether the sky is visible above the player, so a barn or a house actually protects the view instead of being whitened through as if the walls were not there. Off whitens purely on geometric distance, as before.")
                .define("whiteoutShelter", true);
        TORNADO_IMMERSION_WHITEOUT_SHELTER_EXPOSURE = BUILDER.comment("How much whiteout survives under full cover, as a fraction of the open-air one. 0.10 keeps a faint haze so being in a basement during a tornado still reads as being in a storm, while the walls and the landscape stay visible. 0 makes cover total; 1 disables the shelter fade without turning the switch above off.")
                .defineInRange("whiteoutShelterExposure", 0.10D, 0.0D, 1.0D);
        TORNADO_IMMERSION_WHITEOUT_VOLUME_RADIUS = BUILDER.comment("How far the engulfing dust reaches from the eye, in blocks - the radius of the volume the whiteout is drawn as. Larger pushes the volume's own edge further out so the dust never looks like a box in front of you; smaller does the same job with less fill. This is a quality knob as much as a look knob.")
                .defineInRange("whiteoutVolumeRadius", 24, 8, 64);
        TORNADO_IMMERSION_WHITEOUT_VOLUME_SLICES = BUILDER.comment("How many camera-facing slices the dust volume is cut into. More is a smoother stack of dust and costs more fill; fewer is cheaper and can start to band. The whiteout is fill-bound, so this is the main performance knob.")
                .defineInRange("whiteoutVolumeSlices", 20, 4, 48);
        TORNADO_IMMERSION_WHITEOUT_EXTINCTION_PER_BLOCK = BUILDER.comment("Optical depth per block of dust at full density, before the veil opacity. With the other defaults this lands on a total optical depth around 3, i.e. a genuine brownout at full whiteout. Lower leaves the world's silhouettes readable through the wall; higher closes it to opaque sooner.")
                .defineInRange("whiteoutExtinctionPerBlock", 0.16D, 0.02D, 0.60D);
        TORNADO_IMMERSION_WHITEOUT_BLOCKS_PER_TILE = BUILDER.comment("How many blocks of world one tile of the fallback sheet covers. Only used when a shader pack owns the program and the slices are drawn as geometry. Smaller tiles put more structure between the nearest and farthest slice; larger tiles flatten the stack toward one repeated image.")
                .defineInRange("whiteoutBlocksPerTile", 3.0D, 0.5D, 8.0D);
        TORNADO_IMMERSION_WHITEOUT_DRIFT_BLOCKS_PER_SECOND = BUILDER.comment("How fast the fallback sheets are carried along the wind, in blocks per second. Only used with a shader pack, on the geometry path. 0 stops the dust drifting; higher tears it past faster.")
                .defineInRange("whiteoutDriftBlocksPerSecond", 24.0D, 0.0D, 96.0D);
        TORNADO_IMMERSION_CLOSE_EFFECT_RADIUS_BLOCKS = BUILDER.comment("Maximum radius for close tornado roar and camera shake. Keeps intense effects local to the tornado.")
                .defineInRange("closeEffectRadiusBlocks", 30, 4, 512);
        TORNADO_IMMERSION_RENDER_RANGE_BLOCKS = BUILDER.defineInRange("renderRangeBlocks", 900, 64, 4096);
        TORNADO_IMMERSION_MAX_PARTICLES_PER_TICK = BUILDER.defineInRange("maxParticlesPerTick", 90, 0, 1000);
        TORNADO_IMMERSION_ACCESSIBILITY_REDUCED_MOTION = BUILDER.comment("Reduces camera/FOV motion while keeping audio, fog, and debris cues.")
                .define("accessibilityReducedMotion", false);
        BUILDER.pop();

        BUILDER.push("mist");
        MIST_ENABLED = BUILDER.comment("Adds client-side mist: low, wind-drifted wisps that hug the ground over damp terrain, water and grass, plus the haze that washes out distance with them. Unlike morningFog this is not a dawn-only effect - it is the moisture that is already in the air, so it is strongest around dawn, present through the day wherever the ground is wet or rain has just fallen, and thickest in a rain-cooled storm's cold pool. Dry air (a desert, a clear hot noon) carries almost none. Stands down in the classic look, which runs Weather2's own ground fog instead.")
                .define("enabled", true);
        MIST_DENSITY = BUILDER.comment("Overall thickness of the mist, scaling how opaque each wisp is. 0 is off without turning the whole system off; above 1 stacks toward a genuine whiteout at ground level.")
                .defineInRange("density", 0.75D, 0.0D, 2.0D);
        MIST_RADIUS_BLOCKS = BUILDER.comment("How far from the player the ground wisps are drawn, in blocks. This is the visible mist volume only - the haze that hides distance is the far fog plane and is not limited by it. Bigger values push the wisp count up quadratically.")
                .defineInRange("radiusBlocks", 40.0D, 8.0D, 96.0D);
        MIST_HEIGHT_BLOCKS = BUILDER.comment("How high above the ground the wisps are drawn, in blocks. Ground mist is a shallow layer, not a sky effect; raising this turns it into a fog you are standing in, and 0 lays a film on the ground.")
                .defineInRange("heightBlocks", 6.0D, 0.0D, 24.0D);
        MIST_WIND_SPEED_MPH = BUILDER.comment("The wind speed, in mph, at which moving air has thinned the mist as far as it will: a breeze stirs the wisps and a strong wind scours the layer out, which is why mist is a calm-air effect. Above this value thickness stops falling. Below it, mist loses thickness in proportion, so a dead calm is the thickest mist there is.")
                .defineInRange("windSpeedMph", 30.0D, 5.0D, 120.0D);
        MIST_HAZE = BUILDER.comment("How far the mist pulls the distance fog in and washes the fog colour toward its pale grey-blue. This is what makes the air itself read as thick rather than only the wisps near you - the two together are the look. 0 leaves the mist as wisps only. It only ever moves fog closer, never further, and it yields to the tornado whiteout and to the underwater and lava fogs.")
                .defineInRange("haze", 0.55D, 0.0D, 1.0D);
        MIST_PUFF_SIZE = BUILDER.comment("Scales the wisps' own size. Small values are fine drifting haze, large values are billowing patches; the wisp count does not change, so this is how you trade detail for mass.")
                .defineInRange("puffSize", 1.0D, 0.3D, 3.0D);
        MIST_RAIN_MIST = BUILDER.comment("The visible mist the rain brings with it: the wet air of a downpour, as its own thickness rather than as part of the moisture above. This one is not weather-dependent the way the rest of the mist is - it appears when the rain does, it is thickest standing in a squall line's curtain or a hurricane's band, and unlike ground mist it is not thinned by wind or burned off by the sun, because the water is falling through the air rather than condensing out of it. Composited with the moisture mist by taking the thicker of the two, so a rainy dawn is not twice as thick as a dry one.")
                .defineInRange("rainMist", 0.90D, 0.0D, 2.0D);
        MIST_RAIN_FOG = BUILDER.comment("How far the rain itself pulls the distance fog in and greys the sky: the wall of a downpour, read from the same rain as the mist above but with its own strength, so it stays where it belongs even when the moisture mist is turned down. Heavy rain closes visibility toward 36 blocks and standing in the shaft closes it further; a light shower in the open barely touches it. Like the mist's haze it only ever moves fog closer, never further, and it yields to the tornado whiteout and to the underwater and lava fogs.")
                .defineInRange("rainFog", 0.85D, 0.0D, 1.0D);
        BUILDER.pop();

        BUILDER.push("storm_look");
        STORM_LOOK = BUILDER.comment("Which storm look to draw: COMBINED (the default), CLASSIC or MODERN. This is a look, not a behaviour - what the tornado does is the same in all three.",
                        "COMBINED - the modern look, with the 1.12.2 character. The layered funnel and every system this mod adds are exactly as MODERN draws them; what changes is the art the funnel samples. Ballpark, and this is the part that decides whether a tornado reads as a column of cloud or as a haze you can see through: the sheet the layered funnel draws, cloud256combined, is a single 256x256 frame of long soft fibres that fills its square - while Weather2's own 1.12.2 sheet, which the art switch used to hand this funnel, is 0.05 and 81. That gap is the whole reason this mode is the default: Weather2's own art cannot be made opaque by any amount of alpha, because the ceiling is the art. It is the 1.12.2 look's one real advantage (hundreds of soft billboards summing into cloud) added to the modern look, without taking Weather2's own 1.12.2 funnel geometry along with it: the layered funnel keeps its taper and its dynamic width, and the deck, the genesis, the dynamic size, the multi-vortex episodes and their sub-vortex funnels, the pre-touchdown whirls, the horizontal vortices, the debris cloud and its dirt kick-up, the stick debris, the satellite funnels, the supercell structure particles and the thick clouds are all unchanged from MODERN. Turn classicCollar on for Weather2's own 1.12.2 scatter around the funnel's base on top of that.",
                        "CLASSIC - Weather2's own 1.12.2 funnel instead of the layered one. The port never deleted it: the block that fills listParticlesFunnel is still compiled into StormObject.tickClient, but it is frozen behind a local boolean (tornadoV2) and the layered funnel draws in its place, so this mode restores that flag and stands the layered funnel down, leaving one funnel running Weather2's own code. The deck keeps its own flat layer placement rather than the cauliflower silhouette and the layer funnel would otherwise still be bent, roped, lumped into sub-vortex lobes and dust-tinted by this mod. Weather2's own deck, funnel and ground fog puffs draw the stock-faint sheet, the art the 1.12.2 geometry was built against - the regenerated cloud256classic sheet with originalTextures off, and Weather2's own cloud256 with it on. Localized volumetric rain volumes and the inside-the-funnel dust veil stand down with it, so precipitation is Weather2's own downfall particles again. Behaviour is untouched - dynamic size (the restored funnel's own puffs are scaled by the width multiplier, sub-linearly and within a narrow band, so a wider storm draws a wider column without any puff becoming a sky-filling slab), vortices, genesis whirls, horizontal vortices, debris cloud and dirt kick-up, stick debris, satellite funnels, the supercell structure particles and the thick clouds all stay on. Weather2 draws its own funnel, cloud deck, ground fog and block debris underneath them.",
                        "MODERN - this mod's layered funnel and storm layers over the dense sheets, which is what the mod shipped before the classic revival. Heaviest of the three; the deck draws the misty cloud256deck accumulate sheet instead of Weather2's own billboards.",
                        "Never covered by this switch, because each is gameplay or has its own setting: the hurricane and cyclone shield (storm_types), damage, scouring, movement and structure pickup, the addon weather events (wildfire, squall line, cyclone), lightning, ocean waves, foliage wind, and the inside-the-tornado immersion fog and whiteout (tornado_immersion).")
                .defineEnum("mode", StormLook.COMBINED);
        STORM_SOFT_PUFFS = BUILDER.comment("Draw Weather2's cloud and tornado puffs soft: blended in back-to-front order instead of cutting straight lines through each other, feathered where they meet the ground, and fading as you fly through them. They are the same particles either way. Off returns to vanilla's particle drawing, with its hard edges.")
                .define("softPuffs", true);
        STORM_LOOK_CLASSIC_COLLAR = BUILDER.comment("The combined look's optional extra: Weather2's own 1.12.2 scatter drawing around the funnel's base as well as the layered funnel. Off by default, because the layered funnel is the look - this adds a billowing mass around the condensation base, where a real funnel is a churning collar rather than a clean cone, and it is Weather2's own rig with no taper of its own, so it reads as volume around the funnel rather than as the funnel. Both systems are anchored on the storm's formation height - the layered funnel's position is that height over the tornado's ground column, and the scatter spawns its puffs around it - so they meet instead of stacking, and the collar sits where the condensation begins rather than at the ground under it, which already has this mod's dust cloud. It draws on the stock-faint sheet, because 600 to 1200 billboards of 22.5 to 37.5 blocks is what that art was built for. No effect in MODERN (nothing changes there) and no effect in CLASSIC, which runs this scatter as the funnel itself. Costs up to 600-1200 extra particles per storm, so it is the one part of the combined look with a real frame cost.")
                .define("classicCollar", false);
        STORM_LOOK_ORIGINAL_TEXTURES = BUILDER.comment("Draw Weather2's own particle art instead of the sheets this mod regenerated for the same names: cloud256 (the sheet behind the deck billboards, the ground fog and every loose dust puff), downfall3, rain_white and debris_1/2/3 (the rain streaks and the debris the funnel tears loose). Off by default, because this mod's own sheets are the modern, denser art and are what a storm is drawn in unless the old one is asked for; turning it on is the nostalgic look.",
                        "The two sets are not close, and the way they differ is the way a storm reads. Measured over one frame each: Weather2's cloud256 is a single 256x256 sheet with a mean alpha of 13.2 of 255 and a brightest texel of 81, which is faint by construction - the deck and the 1.12.2 funnel stack hundreds of 20 to 40 block billboards on top of each other, and at that density a faint puff sums into one soft mass while a dense one solidifies into a faceted slab with visible quad edges. This mod's cloud256 is one still 256x256 frame with a mean alpha of 74 of 255, spread across most of its square, which is several times the body of Weather2's own art. The deck, the structure particles and the loose dust draw that file, and its edge is a soft fall rather than a step: 28 levels of alpha a texel, where the disc it replaced stepped 64 levels in a single texel and the feathered profile before that lost 8.6. The funnel is drawn with a sheet of its own, in long vertical fibres. With this off you get the modern dense set everywhere it governs (the brighter rain streaks and debris, and this mod's cloud); with it on you get Weather2's original static art (except its debris, which stays this mod's whichever way this is set), which is a softer, flatter, less detailed storm. The funnel is the one thing it does not govern: a tornado draws its own dense sheet either way, because a funnel drawn on a sheet whose brightest texel is 81 of 255 cannot be opaque however much alpha it is given.",
                        "Neither the hurricane, the cyclone nor any instrument or GUI art is affected; those are this mod's own textures and were never replaced. Takes effect on the next resource reload after a change (F3+T).")
                .define("originalTextures", false);
        STORM_LOOK_BILLBOARD_PHASES = BUILDER.comment("Give each billboard its own phase of the cloud animation, so a storm stops breathing in lockstep. This is a property of how the game animates a texture rather than a preference: every frame of a strip is uploaded into the same atlas region and the sprite's UVs never move, so with one sheet behind it a whole storm shows the same frame at the same instant - a thousand deck puffs and a funnel's hundreds of rings all swinging between the same eight poses together, which reads as one large texture flipping instead of as cloud turning over. With this on, the deck and the funnel each draw one of four sibling sheets cut from the same boil with their frames rotated a quarter of a cycle apart, chosen per billboard - the deck puffs by where they are spawned, the funnel's by the puff itself - and each keeps its phase for its whole life. Off draws the first variant everywhere, which is exactly what the mod did before and what the three extra sheets per role are there for; the art, the alpha profile and the measured density are identical either way.",
                        "Off by default, because there is nothing left to phase: the sheets are single still frames, one picture each, so the four phases of a role resolve to the same file and the switch does nothing but cost a hash. The siblings and their atlas entries are not shipped any more - the generator writes one frame per role and the boilerplate's three extra files per role are gone - and every phase therefore answers its role's own master sheet, which is what the sprite provider's own fallback is for: a storm drawn at the wrong phase is a storm, and a storm drawn against a missing texture is a checkerboard. The mechanism is kept rather than deleted because it is the way back: if an animated set is ever shipped again, the four files per role are what a storm needs to stop breathing in lockstep, and the model, the generator's offsets and the harness all still agree on how they are cut.")
                .define("billboardPhases", false);
        BUILDER.pop();

        BUILDER.push("cycloidal_marks");
        CYCLOIDAL_MARKS_ENABLED = BUILDER.define("enabled", true);
        CYCLOIDAL_MIN_STAGE = BUILDER.defineInRange("minStage", 6, 4, 9);
        CYCLOIDAL_MAX_BLOCKS_PER_TICK = BUILDER.defineInRange("maxBlocksPerTick", 48, 0, 256);
        CYCLOIDAL_SWATH_SAMPLES = BUILDER.defineInRange("swathSamples", 18, 1, 96);
        CYCLOIDAL_COSMETIC_ONLY = BUILDER.comment("When true, only converts surface blocks; when false, also removes shallow crops/plants.")
                .define("cosmeticOnly", true);
        BUILDER.pop();

        BUILDER.push("advancements");
        ADVANCEMENTS_ENABLED = BUILDER.define("enabled", true);
        BUILDER.pop();

        BUILDER.push("multiplayer");
        MULTIPLAYER_SYNC_RANGE_BLOCKS = BUILDER.defineInRange("syncRangeBlocks", 1024, 64, 8192);
        MULTIPLAYER_STATE_SYNC_INTERVAL_TICKS = BUILDER.defineInRange("stateSyncIntervalTicks", 20, 5, 20 * 60);
        BUILDER.pop();

        BUILDER.push("lod_compat");
        LOD_COMPAT_ENABLED = BUILDER.comment("Distant Horizons and Voxy compatibility. Weather2 cuts its tornado funnel, debris and cloud particles off at four times the vanilla render distance - which is exactly the far plane vanilla clips at - so with an LOD mod, which normally means a LOW vanilla render distance and terrain drawn thousands of blocks further, tornadoes and their satellite funnels vanish long before the terrain they sit in. This raises that cutoff to the LOD renderer's own distance so the weather keeps up with the terrain. It also lets the tornado's own debris cloud, sub-vortex swirls, horizontal vortices and stick debris spawn out to the same distance, since those are gated by their own range setting and would otherwise leave a distant funnel with no debris underneath it. It does not touch vanilla, which never reads that value itself, and does not change any of the per-tick particle caps, so the extra work stays bounded by settings you already have.")
                .define("enabled", true);
        LOD_COMPAT_AUTO_DISTANCE = BUILDER.comment("Read the LOD renderer's own view distance: Distant Horizons exposes one through its public API. Voxy has no API, so when Voxy is the one rendering this uses maxViewDistanceBlocks instead. Turn off to always use viewDistanceBlocks.")
                .define("autoDistance", true);
        LOD_COMPAT_VIEW_DISTANCE_BLOCKS = BUILDER.comment("Manual LOD view distance in blocks, used when autoDistance cannot read one or is off. 0 = automatic. Set this if your LOD mod draws further than the automatic value guesses.")
                .defineInRange("viewDistanceBlocks", 0, 0, 16384);
        LOD_COMPAT_MAX_VIEW_DISTANCE_BLOCKS = BUILDER.comment("Upper bound on the extended distance, so a very large LOD setting cannot ask the weather renderers to submit geometry from absurdly far away. Note the camera's far clip plane follows this value at four times it (vanilla derives the far plane from the render distance that way, and Weather2's funnel cutoff matches it), so raising this a lot costs depth-buffer precision far from the camera. Lower it if you ever see distant z-fighting.")
                .defineInRange("maxViewDistanceBlocks", 4096, 256, 32768);
        LOD_COMPAT_EXTEND_FOG = BUILDER.comment("Also push the far fog plane out to the LOD distance while an LOD renderer is drawing, so a distant tornado is not drawn-but-invisible behind fog that still ends at the vanilla render distance. Only ever moves fog further away - never closer - and yields completely whenever the tornado fog or morning fog is active, so those effects look exactly as they do without an LOD mod.")
                .define("extendFog", true);
        LOD_COMPAT_EXTEND_STORMS = BUILDER.comment("Weather2 spawns its storms inside a box around the players (1024 blocks by default) and forgets one that has no player within 1124, so with an LOD mod the terrain being drawn reaches much further than the weather does and the sky can be clear over country that is plainly on screen. This widens that box to the LOD renderer's own distance, so storms are still being born - and still being kept alive - out over the terrain the renderer draws. It never narrows Weather2's own box and does nothing at all without an LOD mod.")
                .define("extendStormRange", true);
        LOD_COMPAT_STORM_DISTANCE_BLOCKS = BUILDER.comment("The radius, in blocks, that the storm box is widened to. 0 means the same LOD distance the rendering compat above uses (viewDistanceBlocks, or maxViewDistanceBlocks when that cannot be read), which is what most worlds want. Set it lower to cover less ground than the renderer draws, or higher to keep storms alive even further out than that - it is capped only by its own range, never clamped back down to the render distance.")
                .defineInRange("stormDistanceBlocks", 0, 0, 32768);
        BUILDER.pop();

        BUILDER.push("volumetric_downfall");
        VOLUMETRIC_DOWNFALL_ENABLED = BUILDER.comment("Renders localized Weather2 rain/hail volumes around active storm cells.")
                .define("enabled", true);
        VOLUMETRIC_DOWNFALL_BYPASS_VANILLA_NEAR_CELLS = BUILDER.comment("Cancels vanilla/global rain rendering only near active Weather2 precipitation cells.")
                .define("bypassVanillaNearCells", true);
        VOLUMETRIC_DOWNFALL_RENDER_RANGE_BLOCKS = BUILDER.defineInRange("renderRangeBlocks", 768, 64, 4096);
        VOLUMETRIC_DOWNFALL_MAX_CELLS_RENDERED = BUILDER.defineInRange("maxCellsRendered", 8, 0, 32);
        VOLUMETRIC_DOWNFALL_MAX_VERTICES_PER_FRAME = BUILDER.defineInRange("maxVerticesPerFrame", 18000, 0, 250000);
        VOLUMETRIC_DOWNFALL_HEIGHTMAP_SAMPLE_STEP_BLOCKS = BUILDER.defineInRange("heightmapSampleStepBlocks", 12, 4, 64);
        VOLUMETRIC_DOWNFALL_RAIN_OPACITY = BUILDER.defineInRange("rainOpacity", 0.46D, 0.0D, 1.0D);
        VOLUMETRIC_DOWNFALL_HAIL_OPACITY = BUILDER.defineInRange("hailOpacity", 0.62D, 0.0D, 1.0D);
        VOLUMETRIC_DOWNFALL_HAIL_TINT_THRESHOLD = BUILDER.defineInRange("hailTintThreshold", 0.5D, 0.0D, 1.0D);
        VOLUMETRIC_DOWNFALL_SHEET_SIZE_MULTIPLIER = BUILDER.comment("Size of the falling rain sheets, as a multiplier of their base width. These quads are how far a rain shaft reaches across the sky, and the sheet texture fills its whole width with falling threads and fades out only in the outermost tenth, so sheets tile into a wall of rain rather than showing as separate shafts. Bigger values mean broader, more enveloping rain; smaller values mean finer, sparser rain that shows more sky between sheets. 1.0 is the old full-size sheet.")
                .defineInRange("sheetSizeMultiplier", 0.72D, 0.10D, 3.0D);
        VOLUMETRIC_DOWNFALL_SHAFT_TEXTURES = BUILDER.comment("Draw the rain volumes with real falling-rain textures - fine vertical streaks at three densities chosen by how hard it is raining - instead of Weather2's single soft smudge sheet. This covers both the rain around a storm and the rain curtains of squall lines and hurricanes. Sheets stand upright while it is on, because a rain curtain is a vertical wall of falling water and the streak textures carry their threads vertically; the original soft sheet lay nearly flat, which is why its rain read as fog rather than rain. Turn off to go back to exactly the original look, orientation included.")
                .define("shaftTextures", true);
        VOLUMETRIC_DOWNFALL_SHADER_COMPATIBILITY_MODE = BUILDER.comment("Shader-pack weather signal mode: auto, on, or off. Auto enables compatibility when Oculus/Iris is loaded.")
                .define("shaderCompatibilityMode", "auto");
        BUILDER.pop();

        BUILDER.push("rain_volume");
        RAIN_VOLUME_ENABLED = BUILDER.comment("Draw a storm's rain as a shaded volume instead of as stacks of billboard sheets, so the rain has depth: fly through it and it moves past you, walk round a storm and it is a different shape from each side, and a curtain of rain between you and a tornado actually hides the tornado. The volume is cut into slices through the storm and shaded from the same precipitation field that shapes the rest of the storm - the forward-flank core, the hook wrapping the mesocyclone, the clear slot - so the rain you fly into is the rain the radar and the ground soak agree about. It is depth tested, so terrain, buildings and the funnel occlude it properly, and it stands down on its own when an Iris shader pack is active, because a pack replaces the programs this one is. Off returns to the particle sheets exactly as they were.")
                .define("enabled", true);
        RAIN_VOLUME_SLICES = BUILDER.comment("How many slices the volume is cut into along the view direction. This is a quality-and-cost dial, not a thickness one: the shader works out the light each slab takes out, so more slices is a smoother, finer rain at the same density, and fewer is a coarser, chunkier one. 12 is a good balance; 6 is cheap, 24 is smooth, and the cost is fill rate across whatever of the storm is on screen.")
                .defineInRange("slices", 12, 2, 48);
        RAIN_VOLUME_OPACITY = BUILDER.comment("How thick the rain is: the light a hundred blocks of the densest part of a storm takes out. 1.0 is heavy rain you can still see a long way through, 2.0 is a torrential curtain that hides everything behind it, 0.4 is a light shower. The rain-free notch stays clear at any value, because the density there is a fraction of the core's rather than a scaled-down core.")
                .defineInRange("opacity", 1.0D, 0.05D, 4.0D);
        RAIN_VOLUME_RANGE_BLOCKS = BUILDER.comment("How far away the volumetric rain is drawn. A volume costs fill rather than geometry, so every block of range is potential overdraw across the sky: 512 covers the storms around you, 800 covers what you can see, 256 is a tight setting for a weak machine.")
                .defineInRange("rangeBlocks", 512, 128, 1024);
        RAIN_VOLUME_FALL_SPEED = BUILDER.comment("How fast the falling threads inside the volume run down their lanes, in lanes per second. This is the visible motion of the rain, so it wants to be quick - rain falls fast - but past about 3 the eye reads it as flicker rather than as water.")
                .defineInRange("fallSpeed", 1.1D, 0.0D, 4.0D);
        RAIN_VOLUME_PACK_PATH = BUILDER.comment("Keep drawing the rain as a volume when a shader pack has replaced the custom program, by building the same slices as geometry and shading them on the CPU through a vanilla render type the pack handles itself. This is what stops a pack turning the rain back into flat sheets. Off means the particle sheets draw under a pack instead, which is cheaper and reads as individual quads.")
                .define("packPath", true);
        RAIN_VOLUME_PACK_SLICES = BUILDER.comment("How many slices the pack-safe mesh is cut into along the view direction. This path is shaded on the CPU thread rather than the GPU, so it is deliberately cheaper than the program: 8 is what the meshes ship at, and there is little to gain past about 12 or 16 because each slice is a coarse grid rather than a per-pixel sample.")
                .defineInRange("packSlices", 8, 2, 24);
        RAIN_VOLUME_PACK_DETAIL = BUILDER.comment("How finely each slice of the pack-safe mesh is divided, per side, before the field is sampled at its corners. The mesh resolves the storm's shape whatever this is set to; raising it sharpens how the storm's own edges and its clearance slots fall out, and 10 keeps the whole mesh at a few thousand vertices per storm. Past about 18 the grid is finer than the field needs and the vertices are wasted.")
                .defineInRange("packDetail", 10, 2, 24);
        RAIN_VOLUME_FOG = BUILDER.comment("Fade the rain into the fog it is standing in, so a distant curtain of rain dissolves into the same haze the landscape behind it has instead of staying crisp while the world goes grey. The fog used is the frame's own - the storm tint and the tornado's whiteout this mod drives, morning fog, a mist bank, the render distance, a shader pack's fog - so the rain disappears into whatever the fog actually is rather than into a horizon chosen here. On top of it the rain is misty in its own right, because rain is its own fog. Off draws the rain as it was before, with no fog on it at all.")
                .define("fog", true);
        RAIN_VOLUME_FOG_MIST = BUILDER.comment("How much of the light the storm's own air takes out of what is a hundred blocks away inside it. This is the fog the rain makes of itself, so it is what makes the far side of a rain curtain vanish while the near side is still clear - the reason you can stand under a downpour and not see the far end of the field. 0.55 is a heavy storm's own air; the rain-free notch is treated as the thin air it is and stays open at any value here.")
                .defineInRange("fogMist", 0.55D, 0.0D, 1.0D);
        RAIN_VOLUME_FOG_DENSITY = BUILDER.comment("How much that mist follows the rain's own density. At 1 the mist is as variable as the rain is - thickest in the forward-flank core and the hook, and as clear in the rain-free notch as the air outside the storm - which is what keeps the clear slot a slot instead of fogging it like everything else. At 0 every part of a storm's air is equally misty, which is the one setting that defeats the point of drawing a supercell's rain from its own field. The default leans towards the notch being clear, because a slot the eye cannot find is not a slot.")
                .defineInRange("fogDensity", 0.85D, 0.0D, 1.0D);
        RAIN_VOLUME_LIGHT = BUILDER.comment("Light the rain by the sky it is standing against, instead of drawing it at a fixed brightness. The sky carries the sun's own angle on the day - warm and full at midday, orange at sunset - and this mod's darkening of it under a storm, and the flash of a strike, so one reading makes the rain agree with everything above it: a night storm's rain is dark, rain under a heavy deck is flat and grey, and a strike lights the water the same instant it lights the deck. Off leaves the rain at the constant brightness it used to be drawn at.")
                .define("litBySky", true);
        RAIN_VOLUME_LIGHT_FLOOR = BUILDER.comment("How lit the rain is at its darkest, as a share of full daylight. Without a floor a midnight storm's rain is mathematically invisible on a black sky, which is right and unreadable; with one it stays a dark blue the eye can follow. Applied by scaling the sky's own colour rather than by mixing toward a grey, so a night storm's rain keeps its blue and a sunset's keeps its orange.")
                .defineInRange("lightFloor", 0.22D, 0.0D, 0.8D);
        RAIN_VOLUME_FLASH_LIGHT = BUILDER.comment("How much extra light a lightning strike gives the rain it is standing in. A strike lights the water nearest its channel more than it lights the storm a kilometre behind, so what is added here is only the difference between the light on a piece of rain and the light where the player is watching from - at 1 the rain under a close strike flashes blue-white while the rest of the storm barely moves, at 0 every part of the rain is lit equally by the sky's own flash, and above 1 it makes the near channel's rain the brightest thing on the screen. It follows the bolt's own reach, so it is only visible wherever the revamped bolt is drawn.")
                .defineInRange("flashLight", 1.0D, 0.0D, 2.0D);
        BUILDER.pop();

        BUILDER.push("vanilla_rain");
        VANILLA_RAIN_ENABLED = BUILDER.comment("Draw precipitation with vanilla's own rain and snow sheets instead of the addon's volumetric rain volumes. Vanilla's rain level is already driven by the storm here, so this is the rain you get: the sheets with their own fall, splash particles, light level and sound, and nothing layered over them. Both the volumetric volumes and Weather2's own rain particles stand down while it is on, because either would rain a second time over the same sky. Off puts the volumetric volumes back exactly as they were.")
                .define("enabled", true);
        VANILLA_RAIN_WIND_BEND = BUILDER.comment("Lean the vanilla rain sheets with the wind, from the same reading the foliage sway uses, so the rain in a storm's inflow comes at you sideways instead of straight down. A sheet is bent about the ground under it - its foot stays where it falls and its top is carried downwind - which is what makes the lean grow with height the way real rain does. The classic look leaves the sheets upright, since the rain it restores never leaned.")
                .define("windBend", true);
        VANILLA_RAIN_WIND_BEND_STRENGTH = BUILDER.comment("How much of the real lean to apply. 1.0 is physical: rain is carried across by the wind for every block it drops, so a 30 mph wind tips a sheet about 56 degrees off vertical, which is more than most people expect to see. 0.35 keeps it readable without the sheet lying flat, 0 stands every drop up straight, above 1 exaggerates it.")
                .defineInRange("windBendStrength", 0.35D, 0.0D, 2.0D);
        VANILLA_RAIN_WIND_BEND_MAX_LEAN_BLOCKS = BUILDER.comment("The furthest a sheet's top may be carried from the column it falls down, in blocks. Without a cap a violent inflow shears the rain into a flat smear, because the lean grows with height and the sheets are twenty blocks tall.")
                .defineInRange("windBendMaxLeanBlocks", 16.0D, 0.0D, 64.0D);
        BUILDER.pop();

        BUILDER.push("lightning");
        LIGHTNING_BOLT_REVAMP_ENABLED = BUILDER.comment("Draw lightning as the mod's own channel: a tapered, forked, billboarded bolt that strobes, runs the return stroke up it and lifts a hot core out of a cool halo, instead of vanilla's constant-width grey ribbons. With a shader pack active the same channel is drawn through the game's own lightning program, which the pack replaces with its own, so the strike keeps its shape, forks and flicker and the pack shades it.")
                .define("revampedBolt", true);
        LIGHTNING_BOLT_PACK_PATH = BUILDER.comment("Always draw the revamped bolt the way it is drawn under a shader pack: its glow and flicker worked out per vertex and drawn through the game's own lightning program. This happens on its own when Iris has a pack on; turn it on if another shader mod leaves the bolt missing or wrong.")
                .define("boltThroughGameLightningProgram", false);
        LIGHTNING_BOLT_HEIGHT_BLOCKS = BUILDER.comment("How far the channel climbs, in blocks. Vanilla draws 112, which is what this defaults to, so a strike stays the size it always was.")
                .defineInRange("heightBlocks", 112, 32, 320);
        LIGHTNING_BOLT_RANGE_BLOCKS = BUILDER.comment("How far the channel is drawn. The program fades it out from about 55% of this to none at this range, so a strike recedes into the sky flash instead of winking out, and past it a distant strike is the flash alone - which is what a real one a kilometre away is.")
                .defineInRange("rangeBlocks", 512, 64, 1024);
        LIGHTNING_BOLT_CORE_WIDTH = BUILDER.comment("Half-width of the hot core at its thickest, in blocks. This is the bright channel itself; the halo is a separate pass around it.")
                .defineInRange("coreWidthBlocks", 0.26D, 0.05D, 1.0D);
        LIGHTNING_BOLT_HALO_WIDTH = BUILDER.comment("Half-width of the glow around the core, in blocks. Roughly 2.5 to 5 times the core reads as a channel wrapped in ionised air rather than as a wide flat ribbon.")
                .defineInRange("haloWidthBlocks", 1.05D, 0.20D, 4.0D);
        LIGHTNING_BOLT_BRANCHES = BUILDER.comment("How many forks hang off the trunk. Real strikes carry anything from none to a handful of the same, depending on how long the channel is.")
                .defineInRange("branchCount", 3, 0, 6);
        LIGHTNING_BOLT_GROUND_GLOW_ENABLED = BUILDER.comment("Light the patch of ground a strike hits, from the same channel and the same strobe.")
                .define("groundGlow", true);
        LIGHTNING_BOLT_GROUND_GLOW_RADIUS = BUILDER.comment("Radius of that patch, in blocks.")
                .defineInRange("groundGlowRadiusBlocks", 7.0D, 1.0D, 24.0D);
        LIGHTNING_BOLT_BRIGHTNESS = BUILDER.comment("Overall brightness of the channel. Above 2 an overlapping core over-exposes to white rather than reading as a hot line.")
                .defineInRange("brightness", 1.25D, 0.20D, 3.0D);
        LIGHTNING_BOLT_CORE_HEAT = BUILDER.comment("How much of the cross-section is lifted toward white. Low values leave the channel its tint, high values burn the axis out to white.")
                .defineInRange("coreHeat", 1.15D, 0.0D, 2.0D);
        LIGHTNING_BOLT_FILAMENTS = BUILDER.comment("How much the channel breaks into filaments, so it reads as ionised air rather than as a solid painted line. The filaments are fixed for the life of a strike: a channel does not writhe, it flickers.")
                .defineInRange("filaments", 0.55D, 0.0D, 1.0D);
        LIGHTNING_BOLT_STROBES = BUILDER.comment("Flicker the channel the way a real flash does - three to five strokes tens of milliseconds apart, then an afterglow - instead of holding it steady for its whole life.")
                .define("strobes", true);
        LIGHTNING_DISCHARGE_TYPES_ENABLED = BUILDER.comment("Draw the mod's own lightning as more than one kind of discharge. A ground bolt is the channel it always was; an anvil crawler keeps the channel but at the cloud base races long horizontal tendrils out across the anvil - spider lightning - and a cloud-to-cloud flash is a horizontal channel that never reaches the ground and lights the cloud from inside. Which is which is decided from each strike's own seed, so every client draws the same strike the same way with nothing synced, and the shares below decide how often each turns up. Off leaves every strike the ground bolt it has always been.")
                .define("dischargeTypes", true);
        LIGHTNING_ANVIL_CRAWLER_CHANCE = BUILDER.comment("Share of strikes that are anvil crawlers, from 0 to 1. Taken out of the same roll as the cloud-to-cloud share below, so the ground share is whatever is left over and the two together cannot exceed 1. A crawler is rarest at the edge of a system and common once the anvil is overhead, but this is a single world-wide share.")
                .defineInRange("anvilCrawlerChance", 0.28D, 0.0D, 1.0D);
        LIGHTNING_CLOUD_TO_CLOUD_CHANCE = BUILDER.comment("Share of strikes drawn as cloud-to-cloud flashes, from 0 to 1. Intracloud is the most common discharge in a real storm, but drawn from below it is only the light in the cloud, so this is deliberately modest: it is the flash you see in the deck rather than a channel. Takes its share of the same roll as the crawler chance above, and a strike that is neither is a ground bolt.")
                .defineInRange("cloudToCloudChance", 0.12D, 0.0D, 1.0D);
        LIGHTNING_CRAWLER_LENGTH_BLOCKS = BUILDER.comment("How far an anvil crawler's or a cloud-to-cloud channel's tendrils reach from the strike, in blocks. Real crawlers cover miles, but drawn at full scale they would leave the render range and read as distant sheet lightning; this is the drawn reach, not the physical one.")
                .defineInRange("crawlerLengthBlocks", 190, 24, 512);
        LIGHTNING_CLOUD_BASE_OFFSET_BLOCKS = BUILDER.comment("How far above the strike point a cloud-to-cloud flash is drawn, in blocks. The bolt entities the game and Weather2 spawn sit at the ground, so a discharge that never reaches the ground has no ground point to be built from - this is the height it is lifted to instead, which should sit in the cloud base for the storm it belongs to.")
                .defineInRange("cloudBaseOffsetBlocks", 96, 24, 384);
        LIGHTNING_SKY_FLASH_ENABLED = BUILDER.comment("Let the mod light the sky, the fog and the cloud deck from the strike, instead of leaving that to vanilla's flash counter. Vanilla sets that counter to two on every bolt and takes one off it a tick, and the two places that read it both go wrong on the tick it is down to one: the sky colour sweeps its entire flash range inside that single tick, and the lightmap swaps the world's ambient light to full brightness and back. A storm that strikes every couple of seconds therefore strobes the deck and the fog twenty times a second rather than lighting them. This gives the light the same shape as the channel - one onset, then an afterglow of about a second - with the same peak brightness vanilla had, and the fog, whose colour is the sky colour, follows it. Needs the revamped bolt: with that off, or with a shader pack in charge of lightning, vanilla's flash runs exactly as before.")
                .define("skyFlash", true);
        LIGHTNING_SKY_FLASH_BRIGHTNESS = BUILDER.comment("How bright that flash is, as a multiple of vanilla's own peak. 1.0 lands on vanilla's ceiling exactly, so a strike is as bright as it always was and only the curve leading there changes; above 1.0 a close strike blows the sky whiter than vanilla ever did.")
                .defineInRange("skyFlashBrightness", 1.0D, 0.0D, 2.0D);
        LIGHTNING_SKY_FLASH_CLOUDS = BUILDER.comment("Light the cloud deck itself, not just the sky and the fog behind it. The deck's colour is a separate term on the client that vanilla never flashes, so before this a strike lit the sky and left the deck looking untouched - even though the deck is the surface the light lands on. Turn off to leave the clouds exactly as vanilla draws them.")
                .define("skyFlashClouds", true);
        LIGHTNING_SKY_FLASH_STROBES = BUILDER.comment("Make the sky light flicker with the channel - the same four strokes and afterglow the bolt is shaded with - rather than arriving once and cooling smoothly. Off by default: a whole cloud deck swelling and dimming four times in a fifth of a second is true to the strike, but the channel already carries that flicker and the light around it is what makes it readable.")
                .define("skyFlashStrobes", false);
        LIGHTNING_SKY_FLASH_DISTANCE_FALLOFF = BUILDER.comment("Fade a strike's light with distance, so a bolt overhead lights the deck harder than one a kilometre away. It never fades away entirely - distant lightning still glows across the whole sky - and a strike whose distance cannot be measured is treated as full strength.")
                .define("skyFlashDistanceFalloff", true);
        BUILDER.pop();

        BUILDER.push("celestial");
        CELESTIAL_ENABLED = BUILDER.comment("Draws the mod's own sun, moon and night sky instead of the game's. The game's sun and moon are a small square picture each; this replaces them with the mod's own round ones, adds the Milky Way across the night sky, and adds both kinds of eclipse. Off stops the mod drawing any of that and leaves the game's own sun and moon where the game puts them - but the art does not go with it: the mod's pictures of the sun and the moon are also shipped as the game's own two textures, so that a shader pack draws them, and the game's own sky draws those same files. What this switches off is the mod's own drawing of the sky, not the pictures of the two bodies in it.")
                .define("enabled", true);
        CELESTIAL_ROUND = BUILDER.comment("Replaces the game's square sun and moon with the mod's own: a round, limb-darkened sun and a moon drawn from the game's eight phases, both with their glare in the art. This is the switch that hides the vanilla squares, so turning it off (or having the program fail to load) hands the two bodies back to the game's own sky rather than leaving the sky without either of them. They are not the game's own pictures, though - the mod's art is shipped as the game's own sun and moon textures as well, which is how a shader pack comes to draw it - so this chooses which of the two draws the sun and the moon, and not which pictures they are.")
                .define("roundSunAndMoon", true);
        CELESTIAL_SUN_SIZE = BUILDER.comment("The sun's radius on the sky, in the same blocks-at-a-hundred the game draws its own sun with, so thirty is the game's own sun and about thirty-three degrees across. That default is large on purpose, exactly as the game's is: a disc the size of the real sun is a dot you have to look for, and the readability was chosen over the scale. The real sun is half a degree across, which at a hundred blocks is a radius of about 0.45, and the range goes down far enough to ask for it. This is a size, not a brightness.")
                .defineInRange("sunSize", 30.0D, 0.2D, 60.0D);
        CELESTIAL_MOON_SIZE = BUILDER.comment("The moon's radius on the sky, in the same units as the sun. The game's moon is twenty. The real moon is half a degree across - the same as the real sun, which is what makes a total solar eclipse possible - so a realistic pair is 0.45 here and 0.45 for the sun. The moon is drawn from the game's own eight phases, so it waxes and wanes as it always did and a new moon is a moon you cannot see.")
                .defineInRange("moonSize", 20.0D, 0.2D, 60.0D);
        CELESTIAL_SUN_BRIGHTNESS = BUILDER.comment("How bright the sun's own light is, as a multiplier on the disc and its glow. Above 1 the disc saturates to white and the halo reads stronger; below 1 it dims. This is light, not size - the size is sunSize above.")
                .defineInRange("sunBrightness", 1.0D, 0.0D, 2.0D);
        CELESTIAL_SUN_GLOW = BUILDER.comment("How strong the sun's glare is past its edge. The sun's texture gives it three parts - a tight bloom right at the limb, the forward-scattered aureole that falls away with distance, and a wide faint haze across the sky - so this scales all three together rather than trading one for another, and leaves the disc itself alone. 0 gives a clean hard-edged disc.")
                .defineInRange("sunGlow", 0.75D, 0.0D, 4.0D);
        CELESTIAL_MOON_BRIGHTNESS = BUILDER.comment("How bright the moon is, as a multiplier. The moon adds its light rather than painting over the sky, so a full moon lights the disc up and a new moon adds nothing at all.")
                .defineInRange("moonBrightness", 1.0D, 0.0D, 2.0D);
        CELESTIAL_MOON_GLOW = BUILDER.comment("How much the moon glows past its edge. Only the lit part glows, so a crescent carries a hint of halo on its lit limb and a new moon has none. The moon has almost no glare in reality, so this is small by default.")
                .defineInRange("moonGlow", 0.10D, 0.0D, 3.0D);
        CELESTIAL_ECLIPSE_MOON_SCALE = BUILDER.comment("How big the moon looks against the sun during an eclipse, as a ratio of the sun's radius. 1.0 makes them the same size, so totality is a perfect fit with a corona around it; above 1 covers the sun completely and hides the corona; below 1 leaves a ring of sun showing, which is an annular eclipse.")
                .defineInRange("eclipseMoonScale", 1.0D, 0.6D, 1.4D);
        CELESTIAL_ECLIPSE_CORONA = BUILDER.comment("How bright the sun's corona is once the moon is nearly across it. The corona is the ring of light still visible at totality, when the sun is gone and the sky is dark. 0 turns it off and leaves a black disc.")
                .defineInRange("eclipseCoronaBrightness", 1.0D, 0.0D, 3.0D);
        CELESTIAL_ECLIPSE_DIAMOND_RING = BUILDER.comment("Draws the diamond ring: at the moment the last sliver of sun is swallowed, or the first is revealed, the remaining light concentrates into one brilliant point on the limb. It is the moment an eclipse is famous for, and it only lasts an instant either side of totality.")
                .define("eclipseDiamondRing", true);
        CELESTIAL_MILKY_WAY = BUILDER.comment("Draws the Milky Way as a band across the night sky. It is only up when the sky is dark and it dims with cloud, rain and storms, so a clear night shows it and an overcast one does not.")
                .define("milkyWay", true);
        CELESTIAL_MILKY_WAY_BRIGHTNESS = BUILDER.comment("How strong the Milky Way is. 1.0 is the reference; 0 is off without turning the switch above off; above 1 makes it read as a genuine glow rather than a dusting.")
                .defineInRange("milkyWayBrightness", 1.0D, 0.0D, 2.0D);
        CELESTIAL_ECLIPSE_CHANCE = BUILDER.comment("The chance that any given Minecraft day hosts a solar eclipse, once, around noon. 0.02 is roughly one day in fifty - rare enough to be an event and common enough to be seen. The roll is derived from the day number, so every player in a world sees the same eclipse without it being synced. 0 turns eclipses off.")
                .defineInRange("eclipseChance", 0.02D, 0.0D, 0.5D);
        CELESTIAL_ECLIPSE_DURATION_SECONDS = BUILDER.comment("How long an eclipse lasts, in seconds, centred on noon. The moon slides across the sun over this window and the coverage rises and falls with a half-sine, so the darkening is fastest around totality.")
                .defineInRange("eclipseDurationSeconds", 240.0D, 20.0D, 1200.0D);
        CELESTIAL_ECLIPSE_DARKEN = BUILDER.comment("How far the world darkens during an eclipse - the fog and the sky behind the black disc - from 0 (the afternoon stays bright around the eclipse) to 1 (the sky dims as though into dusk). The moon itself is opaque whatever this is set to; this only decides how much the world notices.")
                .defineInRange("eclipseDarken", 0.65D, 0.0D, 1.0D);
        CELESTIAL_ECLIPSE_DIM = BUILDER.comment("Darkens the sky and the world while an eclipse is in progress, so a total eclipse dims the afternoon instead of leaving a black disc on a bright sky. The darkening is the mod's own shared sky reading - the same one a storm's deck uses - so it reaches the sky's colour, the cloud deck, the fog and the world's ambient light together, and it applies with or without a shader pack, because it is a reading the game itself consumes rather than a program this mod has to draw. It only ever darkens toward the eclipse, never brightens, and it does nothing outside the window.")
                .define("eclipseDimming", true);
        CELESTIAL_ECLIPSE_UNDER_SHADERS = BUILDER.comment("Keeps drawing the eclipse itself while a shader pack is drawing the sky: the black disc crossing the sun, its corona and the diamond ring. Without a pack the mod draws its own sun and moon and the eclipse comes with them; with a pack the whole sky is handed over - the pack draws its own sun and moon and the mod's sky program is one of the ones a pack replaces - so what is left for the mod to try is the eclipse, drawn on top of the pack's sky. It is off by default because a pack that does not provide a program for it draws it with its fallback instead, which can look wrong or not appear at all, and because the eclipse's own darkening of the sky and the world is a separate reading that happens with or without this, pack or not. Turn it on to see whether your pack cooperates.")
                .define("eclipseUnderShaderPacks", false);
        CELESTIAL_LUNAR_ECLIPSE = BUILDER.comment("Draws the Earth's shadow crossing the moon: a lunar eclipse, which a full moon can only have when the world is between it and the sun. The shadow is the real one's shape - a shadow wider than the moon, so totality lasts a while rather than passing in an instant - and it is drawn as sunlight filtered through the world's own air, which is why the eclipsed moon is red rather than black. Off leaves every full moon alone.")
                .define("lunarEclipse", true);
        CELESTIAL_LUNAR_ECLIPSE_CHANCE = BUILDER.comment("The chance that a full-moon night hosts a lunar eclipse. It is per full moon rather than per day, because a lunar eclipse is only possible when the moon is full; the roll is salted so a night and a day cannot be chosen by the same number, or a world with both kinds turned on would always have them on the same day. 0.25 works out at roughly one full moon in four; 0 turns it off without touching the switch above.")
                .defineInRange("lunarEclipseChance", 0.25D, 0.0D, 1.0D);
        CELESTIAL_LUNAR_ECLIPSE_DURATION_SECONDS = BUILDER.comment("How long a lunar eclipse lasts, in seconds, centred on midnight. Longer than a solar eclipse on purpose: the Earth's shadow at the moon's distance is two and a half times the moon across, so the moon takes a while to cross it and the middle of the event is a stretch of totality rather than a moment. A Minecraft night is only about ten minutes and the moon is up for that, so five minutes is half the night already - a real eclipse runs for hours, which here would be all of it.")
                .defineInRange("lunarEclipseDurationSeconds", 300.0D, 20.0D, 1800.0D);
        BUILDER.pop();

        BUILDER.push("debris_physics");
        DEBRIS_PHYSICS_ENABLED = BUILDER.comment("Gives the tornado's airborne block debris per-material physics instead of Weather2's one-model-for-everything: a light block (leaves, timber, glass) is held aloft and dragged by the air - the quadratic F = k*v^2 loss - while a heavy one (stone, iron) keeps its momentum and drops out of the circulation, and every piece is carried at a fraction of the wind rather than at the wind. Purely how the debris moves and reads; it changes no damage and no pickup. Off restores Weather2's own motion exactly.")
                .define("enabled", true);
        DEBRIS_WIND_MIN_FRACTION = BUILDER.comment("The floor of the wind clamp, as a fraction of the local wind speed the debris is riding. Debris in the circulation is carried at least this fast, so a piece that has been slowed by drag is still visibly being driven by the storm rather than hanging dead in the inflow. 0.5 is the reference look; 0 disables the floor and lets a piece wind down to nothing.")
                .defineInRange("windMinFraction", 0.50D, 0.0D, 1.0D);
        DEBRIS_WIND_MAX_FRACTION = BUILDER.comment("The ceiling of the wind clamp, as a fraction of the local wind speed. No single piece of debris is drawn moving faster than this share of the wind around it - the wind is the air, and debris is carried by it rather than being it. 0.8 keeps debris a step behind the wind, which is what makes the spray look like it is being thrown rather than flying itself. 1.0 turns the ceiling off.")
                .defineInRange("windMaxFraction", 0.80D, 0.0D, 1.0D);
        DEBRIS_DRAG_STRENGTH = BUILDER.comment("A multiplier on every material's air resistance. 1.0 is the mod's own coefficients (foliage sheds speed fastest, then timber and glass, then soil, with stone and metal barely dragged); 0 turns the material drag off and leaves only the wind clamp; above 1 makes light debris stall in the air and heavy debris fall out of the circulation faster still.")
                .defineInRange("dragStrength", 1.0D, 0.0D, 4.0D);
        DEBRIS_WIND_SAMPLE_RANGE_BLOCKS = BUILDER.comment("How far from a debris particle the local wind is sampled, in blocks. Smaller ties a piece to the storm nearest it; larger lets a fast storm farther away carry it. It is sampled per particle, so this is the cost knob as well as the look knob.")
                .defineInRange("windSampleRangeBlocks", 256, 32, 1024);
        BUILDER.pop();

        BUILDER.push("debris_entity");
        DEBRIS_ENTITY_ENABLED = BUILDER.comment("Draws the tornado's airborne debris as the mod's own entities instead of Weather2's block particles. Each piece is a real, server-authoritative entity that knows the block it is made of, so its motion is the material's (quadratic air resistance, a wind clamp, gravity) and it can land, shatter and hurt what it hits rather than being a client drawing. Off leaves Weather2's own debris exactly as it was; the switch also governs the material physics above, which only the entities use.")
                .define("enabled", true);
        DEBRIS_ENTITY_GRAVITY = BUILDER.comment("Downward acceleration per tick on a piece of debris, in blocks per tick squared. Vanilla's gravity for a falling block is about 0.04, and this is the pull on a piece that is not being carried by wind at all.")
                .defineInRange("gravity", 0.045D, 0.0D, 0.20D);
        DEBRIS_ENTITY_DRAG_STRENGTH = BUILDER.comment("The same material air resistance dial as the particle physics, applied to the entity's own motion. 0 makes every block fall ballistically; above 1 makes light debris stall in the air and heavy debris drop out of the circulation.")
                .defineInRange("dragStrength", 1.0D, 0.0D, 4.0D);
        DEBRIS_ENTITY_WIND_COUPLING = BUILDER.comment("How fast, per tick, a piece of debris converges on the wind that is carrying it, from 0 (ignores the wind and simply falls) to 1 (snaps to the wind immediately). 0.12 is a visible lag: debris accelerated into the funnel visibly trails the air rather than being welded to it.")
                .defineInRange("windCoupling", 0.12D, 0.0D, 1.0D);
        DEBRIS_ENTITY_LIFT = BUILDER.comment("Extra upward acceleration per tick debris gets inside a strong circulation, in blocks per tick squared, scaled by how strong the wind is. This is the updraft that actually holds debris aloft; without it gravity wins and even a leaf falls out of the funnel.")
                .defineInRange("lift", 0.035D, 0.0D, 0.20D);
        DEBRIS_ENTITY_LAUNCH_FRACTION = BUILDER.comment("How much of the local wind a freshly ripped block is thrown with, as a fraction, before its own physics take over. 0 drops it straight down; 1 throws it at the full wind.")
                .defineInRange("launchFraction", 0.65D, 0.0D, 1.0D);
        DEBRIS_ENTITY_LAUNCH_LIFT = BUILDER.comment("Upward velocity a freshly ripped block is thrown with, in blocks per tick, so debris leaves the ground rather than skidding along it.")
                .defineInRange("launchLift", 0.30D, 0.0D, 1.0D);
        DEBRIS_ENTITY_LIFETIME_SECONDS = BUILDER.comment("How long a piece of debris lives, in seconds, before it shatters wherever it is. A backstop for a piece caught in the circulation so it cannot circle forever and pile up the entity count.")
                .defineInRange("lifetimeSeconds", 45.0D, 3.0D, 300.0D);
        DEBRIS_ENTITY_SETTLE_INTO_BLOCKS = BUILDER.comment("Whether debris that comes to rest puts its block back down instead of shattering. Off by default: a violent tornado can throw hundreds of blocks, and letting them all land as real terrain would rebuild the landscape wherever the funnel passed. On is worth it only for a world where you want the debris to actually land.")
                .define("settleIntoBlocks", false);
        DEBRIS_ENTITY_IMPACT_DAMAGE = BUILDER.comment("Whether a fast-moving piece of debris hurts a living entity it passes through. It is a single hit per piece, not a grinding damage-per-tick, and it only applies above the minimum speed below - a gently tumbling block is not a weapon.")
                .define("impactDamage", true);
        DEBRIS_ENTITY_DAMAGE_MIN_SPEED_BLOCKS_PER_TICK = BUILDER.comment("The speed, in blocks per tick, below which debris does no impact damage at all. Around 0.4 is a brisk fall; raise it so only genuinely thrown debris hurts.")
                .defineInRange("damageMinSpeedBlocksPerTick", 0.4D, 0.0D, 4.0D);
        DEBRIS_ENTITY_DAMAGE_BASE = BUILDER.comment("Damage per block-per-tick of speed above the minimum, before any enchantment or armour. At the default a piece crossing at 2 blocks per tick above the threshold does about 6 damage. Set 0 to disable impact damage without turning the entities off.")
                .defineInRange("damageBase", 4.0D, 0.0D, 40.0D);
        DEBRIS_ENTITY_MAX_ACTIVE = BUILDER.comment("The most debris entities a single level may have alive at once. Past it the block still breaks, but as an untracked puff of dust rather than a new entity, so a storm in a village cannot spawn thousands of them. 0 means no limit.")
                .defineInRange("maxActive", 400, 0, 20000);
        DEBRIS_ENTITY_SPIN_DEGREES_PER_TICK = BUILDER.comment("How fast a piece of debris tumbles, in degrees per tick, once it is actually moving. Purely visual - it does not affect the motion.")
                .defineInRange("spinDegreesPerTick", 12.0D, 0.0D, 90.0D);
        BUILDER.pop();

        BUILDER.push("low_pressure");
        LOW_PRESSURE_ENABLED = BUILDER.comment("Real synoptic lows: broad cyclonic systems with a warm and a cold front of their own, which are born, deepen, occlude and fill over their life. They are broadcast to the forecaster and subtracted by the barometer, so turning this off removes low pressure from both.")
                .define("enabled", true);
        LOW_PRESSURE_SCAN_INTERVAL_TICKS = BUILDER.defineInRange("scanIntervalTicks", 100, 20, 20 * 60 * 10);
        LOW_PRESSURE_MIN_STORMS = BUILDER.comment("Organized storms within this reach of each other are the weather of one system, so their centre seeds a low. This replaces the old behaviour where the cluster itself was drawn as a low.")
                .defineInRange("minStorms", 2, 1, 32);
        LOW_PRESSURE_MAX_DISPLAY_RANGE_BLOCKS = BUILDER.defineInRange("maxDisplayRangeBlocks", 4096, 256, 32768);
        LOW_PRESSURE_NATURAL_SPAWN_ENABLED = BUILDER.comment("Lows also form on their own, from the atmosphere rather than from a storm cluster, so a calm region can have weather on the way. Each roll samples the sky for frontogenesis: a synoptic-scale gradient of instability and moisture with warm, moist air on one side.")
                .define("naturalSpawn", true);
        LOW_PRESSURE_MAX_NATURAL_LOWS = BUILDER.defineInRange("maxNaturalLows", 3, 1, 32);
        LOW_PRESSURE_GENESIS_INTERVAL_TICKS = BUILDER.comment("How often the sky is sampled for a new low to form in, in ticks. 6000 is five minutes.")
                .defineInRange("genesisIntervalTicks", 6000, 200, 20 * 60 * 60);
        LOW_PRESSURE_NATURAL_SPAWN_CHANCE = BUILDER.comment("Chance that a sampled sky which passes the frontogenesis test actually forms a low.")
                .defineInRange("naturalSpawnChance", 0.35D, 0.0D, 1.0D);
        LOW_PRESSURE_MIN_FRONTOGENESIS = BUILDER.comment("Frontogenesis score a piece of sky needs before a low can form in it, 0 to 1. Raising this makes lows rarer and makes them need a sharper air-mass boundary; lowering it lets them form nearly anywhere.")
                .defineInRange("minFrontogenesis", 0.30D, 0.0D, 1.0D);
        LOW_PRESSURE_GENESIS_WINDOW_BLOCKS = BUILDER.comment("How wide a window the sky is sampled over when scoring frontogenesis. A low is a synoptic system, so this is deliberately large - at least as wide as the low's own circulation.")
                .defineInRange("genesisWindowBlocks", 1400, 400, 8192);
        LOW_PRESSURE_STEERING_COEFFICIENT = BUILDER.comment("Fraction of the cloud-level wind a low drifts at. Lows ride the deep-layer flow, so they track slower than the thunderstorms they organise; the result is clamped to between 2.2 and 12.5 mph.")
                .defineInRange("steeringCoefficient", 0.55D, 0.0D, 2.0D);
        LOW_PRESSURE_LIFE_TICKS = BUILDER.comment("Life of a low in ticks before jitter, 24000 being twenty minutes of deepening, maturity, occlusion and filling.")
                .defineInRange("lifeTicks", 24000, 2400, 20 * 60 * 60 * 4);
        LOW_PRESSURE_FRONTAL_RISK = BUILDER.comment("A low's fronts decide what kind of severe weather the day can make, and the outlook is read against them. Warm fronts produce tornadoes and elevated hail storms; the warm sector between the fronts produces discrete supercells with every hazard at once; cold fronts produce linear damaging wind, QLCS tornadoes and large hail; cool and post-frontal air shuts convection down. Which of them a place is in scales the day's tornado, wind and hail probabilities, the conditional intensity beside them, and how readily supercells, lines and tornadoes spawn. Off leaves the outlook purely environmental, as it was before fronts mattered.")
                .define("frontalRisk", true);
        LOW_PRESSURE_FRONT_BAND_FRACTION = BUILDER.comment("How wide a low's fronts are treated as being, as a fraction of its circulation radius. Inside the band a place is on the front itself - which is what carries the warm-front tornadoes and the cold front's squall line - and outside it the place is in whichever air mass the fronts divide. A circulation runs 560 to 2600 blocks, so this is 48 to 143 blocks at the default.")
                .defineInRange("frontBandFraction", 0.055D, 0.01D, 0.30D);
        LOW_PRESSURE_MERGE_DISTANCE_BLOCKS = BUILDER.comment("Two lows closer than this are the same system; a new one will not form that close to an existing one.")
                .defineInRange("mergeDistanceBlocks", 1500, 256, 8192);
        BUILDER.pop();

        BUILDER.push("thermal_circulation");
        THERMAL_CIRCULATION_ENABLED = BUILDER.comment("The belts the world's weather comes from. Real air rises where the sun heats it hardest, returns poleward aloft, sinks again, and the boundary where the warm air it carried meets the cold air is where a synoptic low is born - so lows come from the rising air, not from the storms they later organise. A Minecraft world has no latitude to heat unevenly, so this draws the belts on the Z axis from the equator line the tropical family split already uses, and they rise and fall with the time of day: hottest and most convective in the afternoon, quiet at night. Turn this off and the atmosphere no longer has belts - lows fall back to sampling for a chance air-mass gradient anywhere, with no latitude in it at all.")
                .define("enabled", true);
        THERMAL_RISING_BELT_WIDTH_BLOCKS = BUILDER.comment("How far out from the equator line the rising air reaches, in blocks. This is the half-width of the belt where air is going up, so the whole belt is twice this. Rising air is what makes the equatorial rain and the deep convection; it is also the source of the warmth a low is later built out of, so widening it widens the supply.")
                .defineInRange("risingBeltWidthBlocks", 1800, 200, 20000);
        THERMAL_GENESIS_BELT_OFFSET_BLOCKS = BUILDER.comment("How far poleward of the equator line the low genesis corridor sits, in blocks. This is the baroclinic zone: the boundary where the warm air the rising belt lifted runs into the cold air on the far side, and it is the only place a real synoptic low forms. Move it out for a world with wide tropics, in for a cramped one; a low is born somewhere inside the belt, never at a random bearing.")
                .defineInRange("genesisBeltOffsetBlocks", 5200, 600, 40000);
        THERMAL_BELT_TRANSITION_BLOCKS = BUILDER.comment("How wide the boundary between two belts is, in blocks. A front is a line but a climate zone is not: this is the band over which one belt fades into the next, so nothing flips discontinuously as you walk north.")
                .defineInRange("beltTransitionBlocks", 1100, 100, 12000);
        THERMAL_RISING_SUPPLY_STRENGTH = BUILDER.comment("How much of a low's strength comes from the rising belt's supply, 0 to 2. This is the multiplier on the supply term in genesis and on how much a well-fed low deepens: at 0 the belt decides where a low may be born and nothing about how deep it gets, above 1 a strong afternoon supply over a sharp boundary makes deeper systems than the air alone ever did.")
                .defineInRange("risingSupplyStrength", 1.0D, 0.0D, 2.0D);
        THERMAL_TRADE_WIND_STRENGTH = BUILDER.comment("How strongly the belts steer what drifts through them, 0 to 1. The rising belt draws surface air in and the sinking one pushes it out, so the field adds a slow equatorward or poleward component on top of the ordinary steering flow. At 0 lows and storms ride the atmosphere exactly as they did before; at 1 the belt's own circulation is as strong as a modest synoptic flow.")
                .defineInRange("tradeWindStrength", 0.35D, 0.0D, 1.0D);
        BUILDER.pop();

        BUILDER.push("frontal_convection");
        FRONTAL_CONVECTION_ENABLED = BUILDER.comment("A low's fronts seed their own weather. A front is not a decoration on a chart: it is where the air is being forced up, so the cold front squalls a line, the warm sector between the fronts grows discrete supercells, and the warm front makes elevated, high-based cells on the cool side. Without this the fronts change what the day is forecast to do but no storm in the world is ever actually theirs.")
                .define("enabled", true);
        FRONTAL_CONVECTION_INTERVAL_TICKS = BUILDER.comment("How often a low is checked for the next cell to seed on its own fronts, in ticks. 1200 is a minute.")
                .defineInRange("intervalTicks", 1200, 100, 20 * 60 * 30);
        FRONTAL_CONVECTION_CELL_CHANCE = BUILDER.comment("Chance that a check on a low which is allowed to seed convection actually produces a cell.")
                .defineInRange("cellChance", 0.30D, 0.0D, 1.0D);
        FRONTAL_CONVECTION_MAX_CELLS_PER_LOW = BUILDER.comment("How many cells one low may have seeded at once. A low is a system that organises a few storms, not a factory: past this the front stops producing until its own cells have moved on.")
                .defineInRange("maxCellsPerLow", 5, 0, 24);
        BUILDER.pop();

        BUILDER.push("outbreak");
        OUTBREAK_ENABLED = BUILDER.comment("True outbreaks: not one storm that happens to be violent, but a corridor of tornado-capable supercells, or one long line spinning up short-lived ones, working across the map together. A real outbreak needs extreme conditions and nothing less - the day has to be issued at extreme or hyper extreme risk on the extension ladder, and a deep synoptic low has to be running with its warm sector over the same ground. Either of those alone is a bad day, not an outbreak.")
                .define("enabled", true);
        OUTBREAK_MIN_LOW_INTENSITY = BUILDER.comment("How deep a low has to be, 0 to 1, before it can carry an outbreak. Intensity is the same number the barometer reads and the chart draws, so this is a real depth rather than a separate dial: at the default, a low has to be most of the way through its deepening phase.")
                .defineInRange("minLowIntensity", 0.62D, 0.0D, 1.0D);
        OUTBREAK_CORRIDOR_BLOCKS = BUILDER.comment("How long the corridor of an outbreak is, in blocks, measured along the front. Cells are spread from one end of it to the other as the episode runs, so this is what makes an outbreak read as a line of storms crossing the map rather than one storm with a big number attached.")
                .defineInRange("corridorBlocks", 1400, 300, 12000);
        OUTBREAK_DURATION_TICKS = BUILDER.comment("How long an outbreak episode runs, in ticks. 12000 is ten minutes.")
                .defineInRange("durationTicks", 12000, 1200, 20 * 60 * 60);
        OUTBREAK_CELL_COUNT = BUILDER.comment("How many cells an outbreak seeds over its corridor in total. Each one is a real storm with an outlook behind it, so this is roughly how many tornado-capable circulations the episode will put in the air, not a promise of that many tornadoes.")
                .defineInRange("cellCount", 6, 1, 24);
        OUTBREAK_CELL_INTERVAL_TICKS = BUILDER.comment("How long the episode waits between cells, in ticks. The count and this interval together decide whether the corridor fills in as a family of storms stepping across the map or as several at once.")
                .defineInRange("cellIntervalTicks", 1500, 100, 20 * 60 * 30);
        BUILDER.pop();

        BUILDER.push("forecast_polygons");
        FORECAST_POLYGON_FILL_OPACITY = BUILDER.comment("How solid the areas drawn on a forecast display's plate are, 0 to 1. This spent its life as a solid wash, because an outlook is a map of nested areas - a slight risk area with an enhanced area inside it - and bands only read as bands when each fill hides the one under it. That worked and cost the display its plate: at three quarters of the way to opaque, an outlook covering the scope turns the whole sheet, and the ground around the block it stands on, into the outlook's own green. The nesting is carried by the outlines instead, which gain alpha and width as this comes down, so the fill is a tint by default and the plate stays an instrument surface with coloured areas drawn on it. Lower this for a glassier plate, raise it for flat map colours - and note that raising it back towards solid makes the outlines' own strength beside the point.")
                .defineInRange("fillOpacity", 0.30D, 0.05D, 1.0D);
        FORECAST_POLYGON_HATCHING = BUILDER.comment("Hatch the significant-hazard areas - the greatest tornado risk, the 2 inch hail risk, an outbreak setup - the way a real outlook hatches them, instead of drawing them as another outline. Hatching is what those areas mean on the products this display is modelled on, and unlike a translucent wash it stays readable drawn over a category area. Off leaves the dashed outline, which is all they had before.")
                .define("hatching", true);
        FORECAST_POLYGON_HATCH_SPACING = BUILDER.comment("Distance between the hatch lines, in plate units, where the whole plate is about three. This is a texture, so it wants to be fine enough not to read as stripes - 0.12 gives about twenty-five lines across the plate - and coarse enough to see from a few blocks away.")
                .defineInRange("hatchSpacing", 0.12D, 0.02D, 0.5D);
        FORECAST_POLYGON_CORNER_MARKERS = BUILDER.comment("Mark the corners of a warning polygon with a tick, the way warning polygons are drawn on a real display. Only corners the outline actually turns at are marked, so an outlook area's fourteen-sided ellipse does not come out as a dotted ring. Watches are left unmarked, since the dash is what tells them apart from warnings.")
                .define("cornerMarkers", true);
        BUILDER.pop();

        BUILDER.push("forecast_readout");
        FORECAST_READOUT_PANEL = BUILDER.comment("Stand a forecast display's readout up on a panel that turns to face the player, instead of printing it flat on the plate. The plate stays a map - the outlook's areas, the warnings and the storms are things you read by looking down at them - but text in that plane is foreshortened by the angle you read it from and is only legible from directly overhead. Off prints the same lines flat on the plate, which is what the display did before.")
                .define("readoutPanel", true);
        FORECAST_READOUT_SCALE = BUILDER.comment("How big to set that panel, against the size the mod works out from the distance to it. The mod grows the panel with distance so it stays about the same size on screen out to fourteen blocks and then shrinks away; this multiplies the result, so a player who wants it readable from further off can have it twice the size and a player who finds a hologram over the block distracting can have it half.")
                .defineInRange("readoutScale", 1.0D, ForecastPanel.MIN_MULTIPLIER, ForecastPanel.MAX_MULTIPLIER);
        BUILDER.pop();

        BUILDER.push("ai");
        AI_VILLAGER_TORNADO_SHELTER = BUILDER.comment("Villagers within tornado-threat range try to path into roofed shelter.")
                .define("villagerTornadoShelter", true);
        AI_VILLAGER_SHELTER_RADIUS_BLOCKS = BUILDER.defineInRange("villagerShelterRadiusBlocks", 384, 32, 4096);
        AI_VILLAGER_SHELTER_SCAN_INTERVAL_TICKS = BUILDER.defineInRange("villagerShelterScanIntervalTicks", 40, 5, 20 * 60);
        AI_VILLAGER_SHELTER_LEAD_TICKS = BUILDER.comment("How far ahead the shelter search projects the funnel's own drift when it decides whether a doorway is in the tornado's way. Too short and villagers shelter exactly where the funnel is about to arrive; too long and they refuse shelters they could have reached.")
                .defineInRange("villagerShelterLeadTicks", 20 * 30, 20, 20 * 300);
        AI_VILLAGER_SHELTER_SPREAD = BUILDER.comment("Villagers avoid the shelter another villager already claimed while a real alternative exists, instead of every one of them packing into the same doorway. With only one shelter in reach they still share it.")
                .define("villagerShelterSpread", true);
        AI_VILLAGER_SHELTER_WAKE_SLEEPERS = BUILDER.comment("A tornado warning wakes sleeping villagers so they can run for shelter. Off leaves them asleep, which is what they did before this and is why villages slept through touchdowns.")
                .define("villagerShelterWakeSleepers", true);
        AI_VILLAGER_SHELTER_AVOID_TRACK = BUILDER.comment("Villagers refuse a shelter that sits on the stretch of ground the funnel is about to cross, even when it is the closest roofed block, and prefer shelters on their own side of the track over ones that mean crossing it. Off returns the old nearest-roof behaviour.")
                .define("villagerShelterAvoidTrack", true);
        AI_VILLAGER_CLOSE_DOORS = BUILDER.comment("A villager that has got itself under a roof shuts the doors it can reach, and reaches further the closer the funnel is. This is what a household actually does first, and in this mod it is a real difference: an open doorway is a way in for everything the vortex is carrying. Only ever closes doors, never opens them, and only once the villager is inside - a villager still walking to shelter must not shut the door it is about to use.")
                .define("villagerCloseDoors", true);
        AI_VILLAGER_PANIC = BUILDER.comment("A frightened villager makes a noise while it is out in the open, faster and higher as the funnel closes, and goes quiet once it is under a roof. This is the audible half of the drill: the point is that you hear the village, which is the warning the mod's own sirens are named after.")
                .define("villagerPanic", true);
        AI_ANIMAL_HERDING = BUILDER.comment("Herd animals react to a funnel the way cattle do: they bunch up and the bunch drifts out of the funnel's way, across its track rather than down it. They aim at where the funnel is going rather than where it is, so a herd does not get run down by a storm it was outrunning. Water animals and tamed animals are left alone - one lives in something a funnel does not lift and the other answers to its owner.")
                .define("animalStormHerding", true);
        AI_ANIMAL_HERD_RADIUS_BLOCKS = BUILDER.comment("How far an animal looks for the herd it belongs to. This is what decides how tightly a scattered field collects, and animals of a different species are not part of the herd.")
                .defineInRange("animalHerdRadiusBlocks", 24, 4, 96);
        AI_ANIMAL_HERD_THREAT_BLOCKS = BUILDER.comment("How near a funnel has to be before animals begin to move for it. Warn them from further out and a field of cows walks away from every distant storm; warn them late and they move only when there is nowhere to move to.")
                .defineInRange("animalHerdThreatBlocks", 128, 16, 512);
        AI_ANIMAL_HERD_SCAN_INTERVAL_TICKS = BUILDER.comment("How often an animal looks up from what it is doing to check the weather. Every animal scans on its own timer, so a field is never all looking on the same tick.")
                .defineInRange("animalHerdScanIntervalTicks", 20, 5, 200);
        AI_ANIMAL_HERD_SPEED = BUILDER.comment("Speed multiplier on a herd's own pace. 1.0 is the pace the herd model asks for - a walk when the funnel is far, a run when it is near - so this is the dial for a world where animals should be able to save themselves more easily, or fail to.")
                .defineInRange("animalHerdSpeed", 1.0D, 0.2D, 3.0D);
        AI_VILLAGER_SURVEYOR_ENABLED = BUILDER.comment("Enables the surveyor villager profession: a villager working a Survey Station walks a recent tornado track, rates the damage with the same arithmetic the surveyor item uses, and files the rating on the track.")
                .define("villagerSurveyorEnabled", true);
        AI_VILLAGER_SURVEYOR_RANGE_BLOCKS = BUILDER.defineInRange("villagerSurveyorRangeBlocks", 256, 32, 2048);
        AI_VILLAGER_SURVEYOR_COOLDOWN_TICKS = BUILDER.defineInRange("villagerSurveyorCooldownTicks", 20 * 60, 20 * 5, 20 * 60 * 60);
        AI_VILLAGER_SURVEYOR_ARRIVAL_BLOCKS = BUILDER.comment("How close a surveyor has to get to the track before it takes a reading.")
                .defineInRange("villagerSurveyorArrivalBlocks", 12, 3, 64);
        AI_VILLAGER_SURVEYOR_SIGN = BUILDER.comment("A surveyor plants a sign where it filed its rating, reading the EF rating, the estimated wind speed, the confidence and the track's id. One sign per track: the track is marked surveyed when the sign goes up.")
                .define("villagerSurveyorSign", true);
        AI_VILLAGER_BUILDER_ENABLED = BUILDER.comment("Enables the builder villager profession: a villager working a Builders' Bench repairs storm damage near its job site - re-sodding scoured ground and unbarking stripped trunks - on a slow timer, one block at a time.")
                .define("villagerBuilderEnabled", true);
        AI_VILLAGER_BUILDER_RANGE_BLOCKS = BUILDER.defineInRange("villagerBuilderRangeBlocks", 96, 8, 1024);
        AI_VILLAGER_BUILDER_TRACK_DAMAGE = BUILDER.comment("Only repair damage that a recent storm's own track covered. This is on because a vanilla village is built out of the same two blocks a tornado leaves behind - dirt_path streets and stripped-log corners - so without it a builder spends its life unbarking its neighbours' houses and re-sodding their roads instead of putting up the buildings it was meant to build. Turn it off to have builders repair any torn ground they can see, whatever the weather history says.")
                .define("villagerBuilderTrackDamage", true);
        AI_VILLAGER_BUILDER_TICKS_PER_BLOCK = BUILDER.comment("Ticks of work per repaired block. This is the dial that keeps a builder from erasing a tornado track faster than the storm made it; a default of 40 is two seconds of work per block.")
                .defineInRange("villagerBuilderTicksPerBlock", 40, 5, 20 * 60);
        AI_VILLAGER_BUILDER_BLOCKS_PER_TRIP = BUILDER.defineInRange("villagerBuilderBlocksPerTrip", 16, 1, 512);
        AI_VILLAGER_BUILDER_COOLDOWN_TICKS = BUILDER.defineInRange("villagerBuilderCooldownTicks", 20 * 30, 20 * 5, 20 * 60 * 30);
        AI_VILLAGER_BUILDER_MATERIALS = BUILDER.comment("Builders take the block they are about to place out of a nearby chest and use it up, and refuse damage the village cannot pay for. The material a repair costs is the block it puts back - an oak log for an unbarked trunk, the very block a re-sodded patch of ground is put back to - so what leaves the chest is what appears in the world. Off is the old behaviour: repairing in place out of nothing.")
                .define("villagerBuilderMaterials", true);
        AI_VILLAGER_BUILDER_CARRY_LIMIT = BUILDER.comment("How many blocks a builder will take out of a chest in one trip. This is what a trip costs a village: a big limit lets one builder sweep a track, a small one makes it walk back and forth.")
                .defineInRange("villagerBuilderCarryLimit", 8, 1, 64);
        AI_VILLAGER_BUILDER_CHEST_RANGE_BLOCKS = BUILDER.comment("How far a builder will walk to a chest for materials. Chests beyond this are not a source, so a village that keeps its dirt where the village is stays repairable and one that hoards it elsewhere does not.")
                .defineInRange("villagerBuilderChestRangeBlocks", 24, 4, 128);
        AI_VILLAGER_BUILDER_CONSTRUCTION = BUILDER.comment("Enables village construction: a builder with no damage left to repair puts up its village's own buildings - a house, an office, then a station building for each job - one block at a time, out of the materials in nearby chests. It lays the ground layer before it starts, so a village that cannot afford a foundation does not begin a wall, and it builds out of whatever planks, logs and doors the village actually has rather than one named wood.")
                .define("villagerBuilderConstruction", true);
        AI_VILLAGER_BUILDER_BUILDINGS = BUILDER.comment("How many buildings a village will put up. Six is the plan: a house to live in, an office, then a station building for each job - a survey station for the surveyor, a workshop for the builder, a timber yard for the lumberjack and a weather station for the meteorologist. Higher keeps adding houses once those stand, and anything lower than six leaves the last station buildings unbuilt.")
                .defineInRange("villagerBuilderBuildings", 6, 0, 16);
        AI_VILLAGER_BUILDER_SITE_RANGE_BLOCKS = BUILDER.comment("How far from its bench a village will look for somewhere to build. A site has to be nearly level across the whole footprint and clear of anything standing in the space it needs.")
                .defineInRange("villagerBuilderSiteRangeBlocks", 32, 8, 128);
        AI_VILLAGER_BUILDER_PURCHASING = BUILDER.comment("A village short of a material buys it rather than waiting: the builder walks to a nearby villager holding the block and pays for it out of the village's own emeralds, at the price published on the builder's bench. This is what makes a village with trades able to afford to rebuild - and it only ever pays with money it was actually given, so a village that has never been paid anything still waits for a player. Players are never relieved of their belongings: a player sells to the village at the bench, deliberately.")
                .define("villagerBuilderPurchasing", true);
        AI_VILLAGER_BUILDER_PURCHASE_RANGE_BLOCKS = BUILDER.comment("How far a builder will look for a villager holding the block the village is short of, and - with the bench in range - how far a trade counts as happening in a village of this mod's rather than in a field. A village buys from its own neighbours, so this is the reach of its economy.")
                .defineInRange("villagerBuilderPurchaseRangeBlocks", 32, 4, 128);
        AI_VILLAGER_LUMBERJACK_ENABLED = BUILDER.comment("Enables the lumberjack villager profession: a villager working a Sawmill fells the trees a tornado laid down - logs lying on their side in a run, on ground a recent storm's track covered - and stocks the village's chests with the logs, sawing what the village is short of into planks on the way. This is the other half of what a storm leaves: the builder puts standing trunks back and the lumberjack takes the fallen ones away.")
                .define("villagerLumberjackEnabled", true);
        AI_VILLAGER_LUMBERJACK_RANGE_BLOCKS = BUILDER.defineInRange("villagerLumberjackRangeBlocks", 128, 16, 1024);
        AI_VILLAGER_LUMBERJACK_COOLDOWN_TICKS = BUILDER.defineInRange("villagerLumberjackCooldownTicks", 20 * 30, 20 * 5, 20 * 60 * 30);
        AI_VILLAGER_LUMBERJACK_LOGS_PER_TRIP = BUILDER.comment("How many logs a lumberjack takes off one tree before walking back to the mill - both what it can carry and what it puts in a chest in one trip. A big load clears a trunk in one visit; a small one makes it walk the track one armful at a time.")
                .defineInRange("villagerLumberjackLogsPerTrip", 16, 1, 256);
        AI_VILLAGER_LUMBERJACK_TICKS_PER_LOG = BUILDER.comment("Ticks of work per log taken off a trunk, the same dial villagerBuilderTicksPerBlock is for a repair: at the default of 20 a villager spends a second on each log, so a tree comes apart at a pace you can watch rather than blinking out of the world.")
                .defineInRange("villagerLumberjackTicksPerLog", 20, 5, 20 * 60);
        AI_VILLAGER_LUMBERJACK_TRACK_TIMBER = BUILDER.comment("Only fell trunks that lie on ground a recent storm's track covered. This is on because a village built out of logs has lying logs in it too - a woodpile, a low wall, a decorative pile by a door - and a flock of logs on the ground is exactly what a felled trunk looks like. A lumberjack that took a player's woodpile apart would be a worse neighbour than one that left a tree lying in a field, so with no storm history at all the answer is no. Turn it off to fell any trunk-shaped run of logs it can reach, wherever it came from.")
                .define("villagerLumberjackTrackTimber", true);
        AI_VILLAGER_LUMBERJACK_CHEST_RANGE_BLOCKS = BUILDER.comment("How far from its mill a lumberjack will stock chests. A lumberjack with no chest in reach does not fell at all, because the alternative is a villager carrying logs around a village that never gets them.")
                .defineInRange("villagerLumberjackChestRangeBlocks", 24, 4, 128);
        AI_VILLAGER_BUILDER_TRADE_INTAKE = BUILDER.comment("What a player pays a village's villagers stays with the village: emeralds go into its chests and the goods go to the villager that accepted them, instead of vanishing as they do in vanilla. Nothing is created - the payment is the offer's own cost, read as the trade completes - so a village's wealth is exactly what has been paid to it, and its builder can only spend what it was given. This is the income side of villagerBuilderPurchasing; without it a village has no money at all.")
                .define("villagerBuilderTradeIntake", true);
        BUILDER.pop();

        BUILDER.push("immersive_vehicles");
        IMMERSIVE_VEHICLES_ENABLED = BUILDER.comment("Optional Immersive Vehicles/MTS storm physics compatibility. Reflection-only and inactive unless mod id 'mts' is loaded.")
                .define("enabled", true);
        IMMERSIVE_VEHICLES_SCAN_INTERVAL_TICKS = BUILDER.defineInRange("scanIntervalTicks", 2, 1, 20 * 60);
        IMMERSIVE_VEHICLES_SCAN_RANGE_BLOCKS = BUILDER.defineInRange("scanRangeBlocks", 768, 32, 4096);
        IMMERSIVE_VEHICLES_MAX_VEHICLES_PER_TICK = BUILDER.defineInRange("maxVehiclesPerTick", 32, 0, 512);
        IMMERSIVE_VEHICLES_WIND_FORCE_SCALE = BUILDER.defineInRange("windForceScale", 0.38D, 0.0D, 4.0D);
        IMMERSIVE_VEHICLES_LIFT_FORCE_SCALE = BUILDER.defineInRange("liftForceScale", 0.24D, 0.0D, 4.0D);
        IMMERSIVE_VEHICLES_FLIP_TORQUE_SCALE = BUILDER.defineInRange("flipTorqueScale", 0.16D, 0.0D, 4.0D);
        IMMERSIVE_VEHICLES_MAX_MOTION_DELTA_PER_TICK = BUILDER.defineInRange("maxMotionDeltaPerTick", 0.32D, 0.0D, 4.0D);
        IMMERSIVE_VEHICLES_STALLING_ENABLED = BUILDER.comment("Lets dense tornado/rain cores stall MTS engines by reducing RPM/running state through MTS fields.")
                .define("stallingEnabled", true);
        IMMERSIVE_VEHICLES_STALL_WIND_MPH = BUILDER.defineInRange("stallWindMph", 95.0D, 0.0D, 500.0D);
        IMMERSIVE_VEHICLES_OPEN_COCKPIT_EJECT = BUILDER.comment("Pulls riders out of open-top MTS seats only under strong tornado suction.")
                .define("openCockpitEject", true);
        IMMERSIVE_VEHICLES_OPEN_COCKPIT_EJECT_MPH = BUILDER.defineInRange("openCockpitEjectMph", 145.0D, 0.0D, 500.0D);
        IMMERSIVE_VEHICLES_STRUCTURAL_STRESS_DAMAGE = BUILDER.comment("Applies small, accumulated MTS-native stress damage instead of flat vanilla damage when storm loads get extreme.")
                .define("structuralStressDamage", true);
        BUILDER.pop();

        BUILDER.push("tree_physics");
        TREE_PHYSICS_COMPAT_ENABLED = BUILDER.comment("Optional Tree Physics compat. Reflection-only and inactive unless mod id 'treephysics' is loaded.")
                .define("enabled", true);
        TREE_PHYSICS_SCAN_INTERVAL_TICKS = BUILDER.defineInRange("scanIntervalTicks", 2, 1, 20 * 60);
        TREE_PHYSICS_MAX_FALLING_TREE_ENTITIES = BUILDER.comment("Maximum Tree Physics sublevels processed by storm forces each tick.")
                .defineInRange("maxFallingTreeEntities", 32, 0, 512);
        TREE_PHYSICS_MIN_TPS = BUILDER.comment("Below this estimated TPS, complex tree orbit/crush logic is skipped and Tree Physics runs normally.")
                .defineInRange("minTps", 15.0D, 1.0D, 20.0D);
        TREE_PHYSICS_WEAK_ORBIT_SCALE = BUILDER.comment("Horizontal drag/orbit force scale for EF0-EF1 winds.")
                .defineInRange("weakOrbitScale", 0.055D, 0.0D, 2.0D);
        TREE_PHYSICS_STRONG_LIFT_SCALE = BUILDER.comment("Vertical lift/orbit force scale for EF2-EF3 winds.")
                .defineInRange("strongLiftScale", 0.095D, 0.0D, 3.0D);
        TREE_PHYSICS_VIOLENT_LIFT_SCALE = BUILDER.comment("Vertical/outward projectile force scale for EF4-EF5 winds.")
                .defineInRange("violentLiftScale", 0.165D, 0.0D, 4.0D);
        STRUCTURE_CRUSHER_ENABLED = BUILDER.comment("Lets high-energy thrown Tree Physics trunks punch through weak structures with capped block edits.")
                .define("structureCrusher", true);
        STRUCTURE_CRUSHER_MIN_KINETIC_ENERGY = BUILDER.defineInRange("structureCrusherMinKineticEnergy", 1.8D, 0.0D, 500.0D);
        STRUCTURE_CRUSHER_MAX_BLOCKS_PER_TICK = BUILDER.defineInRange("structureCrusherMaxBlocksPerTick", 36, 0, 512);
        BUILDER.pop();

        BUILDER.push("performance");
        PERFORMANCE_STORM_SCAN_INTERVAL_TICKS = BUILDER.defineInRange("stormScanIntervalTicks", 40, 5, 20 * 60);
        BUILDER.pop();

        BUILDER.push("volcano");
        VOLCANO_ENABLED = BUILDER.comment("Adds volcanoes. Weather2 grew them in 1.12.2 as a curiosity that built a cone, filled its throat and set to stone; these run the eruption instead. A volcano sleeps, shakes, blows steam, throws a column up, has that column collapse back down the mountain as a pyroclastic flow, then pours lava while ash falls over the region - and the rain afterwards turns the ash into lahars for years. Nothing here can happen without a volcano: this does not change the weather, it adds a hazard to the landscape that ignores the weather entirely.")
                .define("enabled", true);
        VOLCANO_NATURAL_SPAWNING = BUILDER.comment("Whether volcanoes are found in the world by themselves. Off leaves them to the /ewd volcano command only, which is the way to place one deliberately and see the whole ladder inside a few minutes.")
                .define("naturalSpawning", true);
        VOLCANO_SCAN_INTERVAL_TICKS = BUILDER.comment("How often a world looks for somewhere new to put a volcano, in ticks. A volcano is a permanent feature, so this is slow on purpose.")
                .defineInRange("scanIntervalTicks", 600, 20, 20 * 60 * 30);
        VOLCANO_SPAWN_CHANCE = BUILDER.comment("The chance a scan actually places one, when a candidate site passes every other test. Volcanic provinces cover a small fraction of any world and this multiplies that down further, so most scans correctly do nothing.")
                .defineInRange("spawnChance", 0.12D, 0.0D, 1.0D);
        VOLCANO_MIN_SPAWN_INTERVAL_TICKS = BUILDER.comment("The shortest time between two volcanoes being placed in the same world, in ticks. 24000 is one game day.")
                .defineInRange("minSpawnIntervalTicks", 24000, 20, 20 * 60 * 60 * 24 * 30);
        VOLCANO_SPAWN_MIN_DISTANCE_BLOCKS = BUILDER.comment("How close to a player a volcano is allowed to be placed, in blocks. A volcano that arrives inside your base is not a feature; this is the guard against it, and the eruption's own hazards reach much further than this.")
                .defineInRange("spawnMinDistanceBlocks", 900.0D, 128.0D, 8192.0D);
        VOLCANO_SPAWN_MAX_DISTANCE_BLOCKS = BUILDER.comment("The furthest from a player a new volcano is placed, in blocks. Beyond a few kilometres a volcano is scenery nobody walks to.")
                .defineInRange("spawnMaxDistanceBlocks", 2600.0D, 256.0D, 12000.0D);
        VOLCANO_MIN_SPACING_BLOCKS = BUILDER.comment("How far apart two volcanoes have to be, in blocks. Volcanoes cluster in real volcanic fields, but two cones inside each other's craters is a bug rather than a field.")
                .defineInRange("minSpacingBlocks", 1400, 256, 8192);
        VOLCANO_PROVINCE_THRESHOLD = BUILDER.comment("How volcanic a region has to be before a volcano can be placed in it, 0..1. This is a property of the world seed and the region's coordinates, so the same ground is volcanic (or not) every time the world is loaded, and a province is an area rather than a point. Raising this makes volcanoes rarer and more clustered; lowering it scatters them.")
                .defineInRange("provinceThreshold", 0.86D, 0.0D, 1.0D);
        VOLCANO_MIN_RELIEF_BLOCKS = BUILDER.comment("The minimum height difference, in blocks, across a 96 block span for a site to be considered at all. A volcano needs a landscape to sit in: this is what keeps one from being dropped onto a flat plain where the cone would read as a misplaced prop.")
                .defineInRange("minReliefBlocks", 24, 0, 256);
        VOLCANO_MAX_INTENSITY = BUILDER.comment("The top of the Volcanic Explosivity Index this world can produce, 0 to 6. Each rung is ten times the ash of the one below and doubles the column's height: a 4 is a Pinatubo, a 5 puts ash in the stratosphere, and a 6 is something the world does once. Every eruption's size is drawn below this from the volcano's own seed, weighted so the low rungs are the common ones.")
                .defineInRange("maxIntensity", 3.5D, 0.0D, 6.0D);
        VOLCANO_QUIESCENCE_MIN_TICKS = BUILDER.comment("The shortest sleep between two eruptions of the same volcano, in ticks. 24000 ticks is one game day.")
                .defineInRange("quiescenceMinTicks", 24000, 1200, 20 * 60 * 60 * 24 * 60);
        VOLCANO_QUIESCENCE_MAX_TICKS = BUILDER.comment("The longest sleep between two eruptions, in ticks, drawn from the volcano's own seed. Note that an eruption's whole ladder - unrest through to waning - runs a few minutes at the defaults, so this is the number that decides how often a given mountain does anything at all.")
                .defineInRange("quiescenceMaxTicks", 24000 * 6, 2400, 20 * 60 * 60 * 24 * 120);
        VOLCANO_WARNING_TICKS = BUILDER.comment("How long the warning phase lasts, in ticks: the earthquakes, the cracking ground and the fumaroles that make an eruption forecastable. This is the phase that makes the system fair, so it leads the eruption rather than accompanying it.")
                .defineInRange("warningTicks", 20 * 90, 20, 20 * 60 * 60);
        VOLCANO_PHREATIC_TICKS = BUILDER.comment("How long the steam-blast phase lasts, in ticks. These are real eruptions in their own right - groundwater flashing to steam, throwing old rock out - and they happen on volcanoes that never produce lava.")
                .defineInRange("phreaticTicks", 20 * 60, 20, 20 * 60 * 60);
        VOLCANO_COLUMN_TICKS = BUILDER.comment("How long the sustained column lasts, in ticks. This is the phase that spreads ash over a region and the one a player is most likely to be standing under, so it is also the longest.")
                .defineInRange("columnTicks", 20 * 150, 20, 20 * 60 * 60);
        VOLCANO_COLLAPSE_TICKS = BUILDER.comment("How long the column collapse lasts, in ticks. It is short, and it is the most dangerous thing in the system: the current leaves the mountain faster than anything can run and does not stop at a wall.")
                .defineInRange("collapseTicks", 20 * 50, 20, 20 * 60 * 60);
        VOLCANO_FLOW_TICKS = BUILDER.comment("How long the effusive phase lasts, in ticks. Real lava flows outlive their eruption by days, so this is long and slow rather than dramatic.")
                .defineInRange("flowTicks", 20 * 240, 20, 20 * 60 * 60 * 4);
        VOLCANO_WANING_TICKS = BUILDER.comment("How long the cooling phase lasts, in ticks: fumaroles, the lake crusting over, and the ash still loose enough for rain to turn it into mud.")
                .defineInRange("waningTicks", 20 * 120, 20, 20 * 60 * 60);
        VOLCANO_HAZARD_RADIUS_BLOCKS = BUILDER.comment("The radius the eruption is allowed to edit the world in, in blocks. Inside it, ash is laid down, blocks are scoured and lava is placed; outside it a volcano is represented instead - the cloud, the haze and the advisory, which cost nothing and reach as far as the physics says they should. This is the single most important performance knob in the section: the block work is bounded by its area.")
                .defineInRange("hazardRadiusBlocks", 384, 64, 2048);
        VOLCANO_ADVISORY_RANGE_BLOCKS = BUILDER.comment("How far from an erupting volcano a player is warned about it, in blocks. An ash cloud covers a province and the aviation products are issued over hundreds of kilometres, so this is deliberately much larger than the hazard radius.")
                .defineInRange("advisoryRangeBlocks", 3200, 256, 20000);
        VOLCANO_BLOCK_BUDGET = BUILDER.comment("How many blocks a volcano is allowed to place or destroy per tick, across everything it is doing at once. The cone, the ash, the flows and the surges all draw on this one allowance, so a big eruption is spread over more ticks rather than costing more per tick.")
                .defineInRange("blockBudget", 96, 0, 2048);
        VOLCANO_STATE_SYNC_INTERVAL_TICKS = BUILDER.comment("How often the server sends volcano state to clients, in ticks. The client needs the phase and the column height to draw the plume and to fade the ash haze in, and a volcano changes slowly, so this is infrequent.")
                .defineInRange("stateSyncIntervalTicks", 20, 5, 20 * 60);
        VOLCANO_LAVA_FLOWS = BUILDER.comment("Whether lava flows and lava bombs can set fire to what they reach. The flows themselves are placed regardless; this is whether they are also a fire hazard.")
                .define("lavaFlows", true);
        VOLCANO_LAVA_FLOW_COUNT = BUILDER.comment("How many arms of lava leave the vent during the effusive phase. Real flows are lobate and follow several valleys at once rather than spreading evenly.")
                .defineInRange("lavaFlowCount", 4, 1, 12);
        VOLCANO_LAVA_FLOW_ACCELERATION = BUILDER.comment("A time-scale factor for lava, because a real flow advances metres per minute and a two kilometre flow would otherwise take a real-time day to arrive. This does not make lava faster than a player - it makes it visible within a session.")
                .defineInRange("lavaFlowAcceleration", 14.0D, 1.0D, 64.0D);
        VOLCANO_LAVA_BOMBS = BUILDER.comment("Whether the vent throws incandescent blocks down the flanks. Ballistic ejecta are a real part of an explosive eruption and are what makes standing on the mountain a bad idea.")
                .define("lavaBombs", true);
        VOLCANO_ASH_DEPOSITS = BUILDER.comment("Whether ashfall leaves anything on the ground, as opposed to only being drawn as cloud and haze. Deep deposits land as tuff, dustings as powder that will not stay flat; a real VEI 4 buries a province and this deliberately places only the near field.")
                .define("ashDeposits", true);
        VOLCANO_ASH_RADIUS_BLOCKS = BUILDER.comment("How far downwind ash is laid on the ground, in blocks. Beyond this the deposit is below a millimetre and is left to the client's haze.")
                .defineInRange("ashRadiusBlocks", 384, 64, 2048);
        VOLCANO_ASH_INTERVAL_TICKS = BUILDER.comment("How often a volcano lays its ashfall, in ticks. Ash accumulates over hours, so this is slow.")
                .defineInRange("ashIntervalTicks", 100, 20, 20 * 60 * 10);
        VOLCANO_ASH_LIGHTNING = BUILDER.comment("Whether an erupting column makes its own lightning. Ash particles rubbing together in a Plinian column generate enormous charge separation, and volcanic lightning is one of the most photographed parts of an eruption.")
                .define("ashLightning", true);
        VOLCANO_PYROCLASTIC_DAMAGE = BUILDER.comment("Whether a pyroclastic density current hurts living things it runs over. It is a moving wall of ash at several hundred degrees, so there is no version of this that is survivable - but turning it off leaves the terrain damage without the deaths.")
                .define("pyroclasticDamage", true);
        VOLCANO_EARTHQUAKES = BUILDER.comment("Whether an eruption shakes the ground: camera shake for players, small blocks coming loose, and animals running. Volcanic tremor precedes the eruption by weeks, and it is the visible half of the warning phase.")
                .define("earthquakes", true);
        VOLCANO_LAHARS = BUILDER.comment("Whether rain on fresh ash turns into lahars - mudflows that follow the valleys the ash fell in and bury whatever they reach. This is the hazard that outlives the eruption, sometimes for years, and it is the reason a real volcano is still dangerous when it is quiet.")
                .define("lahars", true);
        VOLCANO_LAHAR_MIN_RAIN = BUILDER.comment("The rain intensity a lahar needs, 0 to 1. A drizzle does not mobilise ash; a downpour does.")
                .defineInRange("laharMinRain", 0.25D, 0.0D, 1.0D);
        VOLCANO_LAHAR_MIN_ASH_MM = BUILDER.comment("How much ash has to be on the ground, in millimetres, before rain can turn it into a lahar.")
                .defineInRange("laharMinAshMm", 12.0D, 0.0D, 1000.0D);
        VOLCANO_LAHAR_MAX_ASH_AGE_DAYS = BUILDER.comment("How old the ash can be, in game days, and still be mobilised by rain. Fresh ash is loose and turns to slurry immediately; after a few years of weather it has consolidated and the hazard fades. This is what makes a volcano dangerous after its eruption rather than during it.")
                .defineInRange("laharMaxAshAgeDays", 40.0D, 0.0D, 3650.0D);
        VOLCANO_ALERTS = BUILDER.comment("Whether eruptions feed the mod's alert products: an ash advisory while the column stands, an ash warning close in, and a pyroclastic flow warning during a collapse. They go through the same siren stations, radio, banner and redstone as the storm products.")
                .define("alerts", true);
        VOLCANO_SOUNDS = BUILDER.comment("Whether an eruption is audible as explosions and a rumbling vent. The deep boom reuses the game's own thunder, which is what a volcanic blast heard from kilometres away actually sounds like; no new audio is shipped for this.")
                .define("sounds", true);
        VOLCANO_SHAKE = BUILDER.comment("Whether a player near an erupting volcano feels it: a low, slow camera shake that rises with the eruption's intensity and falls off with distance. The client's own half of the earthquake system.")
                .define("shake", true);
        VOLCANO_ASH_HAZE = BUILDER.comment("Whether the ash cloud washes out the distance on the client, so an eruption is visible as dirty air from tens of kilometres away rather than only as a column.")
                .define("ashHaze", true);
        VOLCANO_ASH_HAZE_STRENGTH = BUILDER.comment("How thick the ash haze gets at its worst, close to an erupting vent. Above about 1 the horizon disappears entirely.")
                .defineInRange("ashHazeStrength", 0.85D, 0.0D, 2.0D);
        VOLCANO_RENDER_RANGE_BLOCKS = BUILDER.comment("How far from a player the ash column and its fallout are drawn, in blocks. A stratospheric plume is visible from hundreds of kilometres, so this is generous; the plume is a handful of billboards rather than a particle cloud, so the cost is small.")
                .defineInRange("renderRangeBlocks", 12000, 256, 48000);
        BUILDER.pop();

        BUILDER.push("derecho");
        DERECHOS_ENABLED = BUILDER.comment("Recognises a squall line that has earned the name derecho: long-lived, fast-moving and blowing hard enough to lay down a swath of wind damage rather than a gust front. Nothing new spawns - a line is classified by measurements of itself - and a classified line warns as a derecho, raises its own shelf, and drives the swath pass below. Off leaves every line an ordinary squall line, exactly as before.")
                .define("enabled", true);
        DERECHO_SWATH_BLOCKS = BUILDER.comment("How far a line has to have travelled, in blocks, before it counts as having laid a swath down. SPC's criterion is a 400 km damage swath, which is 400,000 blocks and larger than any world this mod runs in, so the default is the largest distance a line here can actually cover: at the mod's own line speeds this is reached about fifty in-game minutes after a line forms, and a common line lives three to eight hours. Raise it for a rarer classification, lower it to see one sooner.")
                .defineInRange("swathBlocks", 6000.0D, 1000.0D, 60000.0D);
        DERECHO_MIN_LINE_LENGTH_BLOCKS = BUILDER.comment("How long a line has to be to be a derecho rather than a single bow. A line that is barely organised is not one however the numbers read, so this is the floor on the line's own length.")
                .defineInRange("minLineLengthBlocks", 480.0D, 120.0D, 4000.0D);
        DERECHO_TREE_FLATTENING = BUILDER.comment("Whether a derecho lays standing trunks down along the swath. A derecho is the one wind in this mod that leaves a forest combed rather than scattered: the trunk goes over and its logs lie on the ground downwind of the stump, and the canopy above is left to vanilla's own leaf decay, which is what actually happens afterwards. Off leaves trees entirely to the mod's existing wind damage.")
                .define("treeFlattening", true);
        DERECHO_ROOF_STRIPPING = BUILDER.comment("Whether a derecho takes weak roof covering - shingles, planks, slabs, panes, carpet - off the top of a building and leaves the walls standing. Stone, brick, deepslate and metal are never touched, which is the reason a player can build against this.")
                .define("roofStripping", true);
        DERECHO_MAX_EDITS_PER_PASS = BUILDER.comment("How many blocks one pass of the swath may change. A derecho crosses a chunk in seconds, so the pass runs on the interval below and this is the cost ceiling on it.")
                .defineInRange("maxEditsPerPass", 22, 0, 256);
        DERECHO_PASS_INTERVAL_TICKS = BUILDER.comment("How often the swath pass runs, in ticks (10 = half a second).")
                .defineInRange("passIntervalTicks", 10, 2, 200);
        BUILDER.pop();

        BUILDER.push("monsoon");
        MONSOON_ENABLED = BUILDER.comment("Gives the world a wet season. A monsoon here is not a storm: it is a season that arrives on a date, lasts several game days, ramps up and withdraws, and rains without any storm being responsible for it. It is read from the world's own day counter and its seed, so two worlds have their wet seasons in different months and the same world always has them on the same date, and it lands hardest in the mod's own monsoon belt - savanna, badlands and desert country with a moisture boundary across it - and almost not at all in dry or frozen air. Rain in the season floods harder because the ground is already wet. Off removes the season entirely.")
                .define("enabled", true);
        MONSOON_SEASON_DAYS = BUILDER.comment("How long the wet season runs, in game days. Eight days is a season a player will live through; the real thing runs for months, which no world here does.")
                .defineInRange("seasonDays", 8, 1, 120);
        MONSOON_PERIOD_DAYS = BUILDER.comment("How often a wet season comes round, in game days. The season is the first of these days and the rest of the cycle is the dry season, so the ratio of the two is how wet the climate is.")
                .defineInRange("periodDays", 32, 2, 730);
        MONSOON_FLOOD_MULTIPLIER = BUILDER.comment("How much harder rain floods at the peak of the season, where it is raining hardest. A downpour onto ground that has been wet for three days runs off instead of soaking in, and this is that. 1.0 switches the coupling off and leaves flooding exactly as it was.")
                .defineInRange("floodMultiplier", 1.4D, 1.0D, 3.0D);
        MONSOON_RAIN_INTENSITY = BUILDER.comment("How hard the season rains at its peak, on the scale the mod's own rain and floods are read on. This is the season's own rain, present with no storm anywhere near it.")
                .defineInRange("rainIntensity", 1.15D, 0.0D, 3.0D);
        MONSOON_SKY_SHARE = BUILDER.comment("How much of the sky the season takes at its peak, as a share of the mod's own storm tint. A wet season is grey rather than black: this is deliberately short of the darkening a severe storm brings.")
                .defineInRange("skyShare", 0.78D, 0.0D, 1.5D);
        BUILDER.pop();

        BUILDER.push("lava_whirls");
        LAVA_WHIRLS_ENABLED = BUILDER.comment("Whether a volcano with magma at its vent can spin a whirl of fire off it: a lava whirl, which is a fire whirl whose heat source is a mountain instead of a forest. It burns what it crosses and can spill lava, and it lifts rather than pushes, because that is what a fire whirl does.")
                .define("enabled", true);
        STEAM_DEVILS_ENABLED = BUILDER.comment("Whether lava meeting water makes a steam devil: a rotating column of hot steam and ash thrown up by a phreatic reaction, which is the one vortex in this mod that cannot move a single block. It scalds anything standing inside it and throws nothing, and unlike every weather whirl it does not care whether it is raining - it can do it in the rain, and rain is what it is standing in the middle of making.")
                .define("enabled", true);
        LAVA_WHIRL_MIN_INTENSITY = BUILDER.comment("How hard a volcano has to be erupting before it can put a whirl up, on the same intensity scale the volcano system uses. Below this there is not enough magma at the vent to spin anything.")
                .defineInRange("minIntensity", 0.35D, 0.0D, 10.0D);
        LAVA_WHIRL_DAMAGE = BUILDER.comment("Whether a lava whirl burns what it crosses and can spill lava from the vent it came off. Off leaves the whirl as a visual and a wind field.")
                .define("damage", true);
        LAVA_WHIRL_SPILL_CHANCE = BUILDER.comment("How freely a lava whirl spills lava, as a multiplier on its own odds. The whirl's odds rise with the eruption, from about one in fifty per pass at a weak eruption to about one in eight at a violent one; this scales that whole ladder. Lava in open country is a permanent change to it, so 0 switches spilling off altogether and the whirl only burns what it crosses.")
                .defineInRange("spillChanceScale", 1.0D, 0.0D, 3.0D);
        STEAM_DEVIL_SCALD_DAMAGE = BUILDER.comment("Whether a steam devil scalds anything standing inside it. Steam is hot but has no mass, so the damage is a burn and never a block: there is nothing here that can move a wall, and a steam devil that did would be a different phenomenon.")
                .define("scaldDamage", true);
        BUILDER.pop();

        BUILDER.push("handheld_instruments");
        HANDHELD_INSTRUMENTS_ENABLED = BUILDER.comment("The three handheld instruments: an anemometer that reports the wind where you are standing and remembers its own peak gust, a thermometer that reports the air on a stated scale with height, and a barometer that reports the column and the tendency its own last reading gives. Every one of them is the same reading the block instrument next to it takes, asked from wherever the holder is standing, so the two can never disagree. Off leaves the items inert rather than unregistering them, so a world that already has some does not lose them.")
                .define("handheldInstruments", true);
        HANDHELD_READOUT_COOLDOWN_TICKS = BUILDER.comment("How long a handheld instrument takes to settle between readings, in ticks (20 = one second). A reading is a message in the chat, so this is what stops holding the button from filling it.")
                .defineInRange("handheldReadoutCooldownTicks", 15, 0, 200);
        BUILDER.pop();

        BUILDER.push("sensor_network");
        SENSOR_NETWORK_ENABLED = BUILDER.comment("Whether weather instruments can be wired back to a weather computer with the mod's own cable, so the computer reports the network rather than only the air at its own four walls. An instrument within a block of any wire on the run is on the network; the run is walked from the computer, and one longer than the limit below counts as no connection at all.")
                .define("sensorNetwork", true);
        SENSOR_WIRE_MAX_LENGTH = BUILDER.comment("How many wire blocks the walk from a weather computer to its instruments may cross. A cable is meant to run across a base, not across a province: past this length the instruments are not on the network, which is what stops one computer from reading every instrument in a loaded world.")
                .defineInRange("sensorWireMaxLength", 64, 4, 1024);
        BUILDER.pop();

        BUILDER.push("defence");
        REINFORCED_GLASS_ENABLED = BUILDER.comment("Whether obsidian reinforced glass resists what ordinary glass does not. It is glass a tornado and a hail core cannot break - the mod's own ingest, wind and hail passes refuse it exactly as they refuse bedrock - which is what makes a window something a player can keep through a storm. Off leaves it an ordinary pane with an expensive recipe.")
                .define("reinforcedGlass", true);
        REBAR_ANCHORING_ENABLED = BUILDER.comment("Whether structural rebars anchor what they are driven into. A rebar that is set into solid ground protects itself and the blocks around it from being pulled out, lifted or stripped - the wind, the hail and the derecho's roof pass all refuse an anchored block - which is what turns a roof into something a player can hold on with. A rebar with nothing under it anchors nothing, so it has to be planted rather than waved at a wall.")
                .define("rebarAnchoring", true);
        REBAR_ANCHOR_HEIGHT = BUILDER.comment("How many blocks above a rebar the anchoring reaches. A rebar is driven in at the bottom of what it holds: at 3 it holds a wall three blocks tall around it, and the blocks beside it are held at every height it reaches.")
                .defineInRange("rebarAnchorHeight", 3, 1, 8);
        BUILDER.pop();

        BUILDER.push("meteorologist");
        METEOROLOGIST_ENABLED = BUILDER.comment("Whether a village weather station gives a villager the meteorologist profession: a villager who claims the station and trades in what the sky leaves behind - storm logs, sensor data and storm photographs in, instruments and supplies out. Off leaves the block a decoration with no job attached, which is what to do if another mod already owns that villager slot.")
                .define("meteorologistProfession", true);
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    public static double milesToBlocks(double miles) {
        return miles * BLOCKS_PER_MILE;
    }

    /**
     * Effective capture-tumble rate scale, clamped to the configured range. Read every tick by the
     * server's mob spin and by every capture effect on the client (player tumble, camera somersault,
     * render roll), so capture physics, camera and model all turn over at the same speed.
     */
    public static float tumbleSpeedMultiplier() {
        return (float) Math.max(0.1D, Math.min(6.0D, TORNADO_IMMERSION_TUMBLE_SPEED_MULTIPLIER.get()));
    }

    /** Free-lift ascent rate in blocks per tick. */
    public static double balloonAscentBlocksPerTick() {
        return Math.max(0.025D, WEATHER_BALLOON_ASCENT_BLOCKS_PER_SECOND.get() / 20.0D);
    }

    /** Parachute descent rate in blocks per tick. */
    public static double balloonDescentBlocksPerTick() {
        return Math.max(0.025D, WEATHER_BALLOON_DESCENT_BLOCKS_PER_SECOND.get() / 20.0D);
    }

    public static double effectiveMinDamageWidthBlocks() {
        double miles = TORNADO_MIN_DAMAGE_WIDTH_MILES.get();
        double min = miles > 0.0D ? milesToBlocks(miles) : TORNADO_MIN_DAMAGE_WIDTH_BLOCKS.get();
        return SimpleSettings.width(min, simpleStormSize());
    }

    public static double effectiveMaxDamageWidthBlocks() {
        double miles = TORNADO_MAX_DAMAGE_WIDTH_MILES.get();
        double max = miles > 0.0D ? milesToBlocks(miles) : TORNADO_MAX_DAMAGE_WIDTH_BLOCKS.get();
        // The simple section's size dial rides on the widths, both of them, so a size setting grows the
        // funnel and the swath that flattens the town by the same factor rather than only one of them.
        return SimpleSettings.width(Math.max(effectiveMinDamageWidthBlocks(), max), simpleStormSize());
    }

    public static double effectiveMaxVisibleFunnelWidthBlocks() {
        double miles = TORNADO_MAX_VISIBLE_FUNNEL_WIDTH_MILES.get();
        double max = miles > 0.0D ? milesToBlocks(miles) : TORNADO_MAX_VISIBLE_FUNNEL_WIDTH_BLOCKS.get();
        return SimpleSettings.width(Math.max(3.0D, max), simpleStormSize());
    }

    // ----------------------------------------------------------------------------------------- climate

    /** How big a piece of the map shares one air mass, in blocks. */
    public static int climateRegionSizeBlocks() {
        return Math.max(256, Math.min(4096, CLIMATE_REGION_SIZE_BLOCKS.get()));
    }

    /** How far out the biome mixture around a region's centre is read, in blocks. */
    public static int climateSampleRadiusBlocks() {
        return Math.max(64, Math.min(512, CLIMATE_SAMPLE_RADIUS_BLOCKS.get()));
    }

    /**
     * How strongly the country's own character moves spawn odds, 0 (nowhere) to 1 (the full table).
     *
     * <p>Read here so {@link com.imbet.weather.BiomeClimateService} can apply it once, at the one place
     * every spawn site calls, rather than each caller scaling a multiplier of its own.
     */
    public static double climateOddsStrength() {
        return Math.max(0.0D, Math.min(1.0D, CLIMATE_ODDS_STRENGTH.get()));
    }

    /**
     * How stormy one family's air is, on top of the family's own character.
     *
     * <p>Read here rather than at each caller, so the twelve weights are one table and the air is built
     * in one place. A family's moisture is deliberately not scaled by this: a turned-down jungle should
     * still be wet country that cannot make storms, rather than a desert.
     */
    public static double climateFamilyWeight(BiomeClimate.Family family) {
        if (family == null) {
            return 1.0D;
        }
        ModConfigSpec.DoubleValue value = switch (family) {
            case OCEAN -> CLIMATE_WEIGHT_OCEAN;
            case COAST -> CLIMATE_WEIGHT_COAST;
            case DESERT -> CLIMATE_WEIGHT_DESERT;
            case BADLANDS -> CLIMATE_WEIGHT_BADLANDS;
            case SAVANNA -> CLIMATE_WEIGHT_SAVANNA;
            case PLAINS -> CLIMATE_WEIGHT_PLAINS;
            case FOREST -> CLIMATE_WEIGHT_FOREST;
            case JUNGLE -> CLIMATE_WEIGHT_JUNGLE;
            case SWAMP -> CLIMATE_WEIGHT_SWAMP;
            case TAIGA -> CLIMATE_WEIGHT_TAIGA;
            case SNOWY -> CLIMATE_WEIGHT_SNOWY;
            case MOUNTAIN -> CLIMATE_WEIGHT_MOUNTAIN;
        };
        return Math.max(0.0D, Math.min(2.0D, value.get()));
    }

    // --------------------------------------------------------------------------------- lod storms

    /**
     * The radius, in blocks, that an LOD renderer lets the storm box reach - or 0 when it should not be
     * widened at all.
     *
     * <p>Read here rather than at each caller so the three knobs are one number: the compat has to be on,
     * the storm extension has to be on, and the distance is the explicit {@code stormDistanceBlocks} if it
     * was set, otherwise the same manual LOD view distance the rendering compat uses, otherwise the
     * configured ceiling. Zero is "leave Weather2's own box alone", which is what a world without an LOD
     * renderer, or with this switched off, gets.
     */
    public static int lodStormDistanceBlocks() {
        if (!LOD_COMPAT_ENABLED.get() || !LOD_COMPAT_EXTEND_STORMS.get()) {
            return 0;
        }
        int distance = LOD_COMPAT_STORM_DISTANCE_BLOCKS.get();
        if (distance <= 0) {
            distance = LOD_COMPAT_VIEW_DISTANCE_BLOCKS.get();
        }
        if (distance <= 0) {
            distance = LOD_COMPAT_MAX_VIEW_DISTANCE_BLOCKS.get();
        }
        return Math.max(0, distance);
    }

    // --------------------------------------------------------------------------------- simple settings

    /**
     * The simple settings, as the numbers the rest of the mod reads.
     *
     * <p>Each is a single place a dial is applied, and every caller uses these rather than the config value
     * directly. That is not tidiness: a dial read in fifteen places is a dial that means something slightly
     * different in each of them, and the whole point of this section is that one number says one thing.
     */

    /** How often storms come, on every chance the mod rolls for one. */
    public static double simpleStormFrequency() {
        return SIMPLE_STORM_FREQUENCY.get();
    }

    /** How big tornadoes get, on both the damaging and the visible width. */
    public static double simpleStormSize() {
        return SIMPLE_STORM_SIZE.get();
    }

    /** How violent storms are, on every wind speed read out of a storm. */
    public static double simpleTornadoStrength() {
        return SIMPLE_TORNADO_STRENGTH.get();
    }

    /**
     * Whether anything may be broken, with the destruction dial and the damage system's own switch together.
     *
     * <p>Every damage path asks this rather than {@code DAMAGE_ENABLED}, so the simple switch is the master
     * off the config screen promises and not a second opinion that two dozen systems can each ignore.
     */
    public static boolean damageEnabled() {
        return SimpleSettings.destructionEnabled(SIMPLE_DESTRUCTION.get(), DAMAGE_ENABLED.get());
    }

    /** How much the world's own calendar moves the weather, on the whole year at once. */
    public static double simpleSeasonStrength() {
        return SIMPLE_SEASON_STRENGTH.get();
    }

    /** How strongly a season applies, with the simple dial on it. */
    public static double seasonStrength(double strength) {
        return SimpleSettings.seasonStrength(strength, simpleSeasonStrength());
    }

    /**
     * Whether a land tornado may only be born over plains country, with the simple switch and the genesis
     * section's own switch together.
     *
     * <p>The same shape as the destruction switch: the simple one can only ever take the rule away, so a player
     * who turns the plains rule off in the detailed section cannot have it forced back on by a dial - and every
     * genesis path asks this one place rather than either switch on its own.
     */
    public static boolean tornadoGenesisPlainsOnly() {
        return SimpleSettings.plainsOnly(SIMPLE_ONLY_SPAWN_TORNADOES_IN_PLAINS.get(), TORNADO_GENESIS_PLAINS_ONLY.get());
    }

    /**
     * The deadly-storm reading's own knobs, in one object the arithmetic can be handed.
     *
     * <p>Built here rather than read at each use so that the model never sees a config value: a floor above
     * its own full mark, a share out of its range or a shear of zero are all things a hand-edited file can
     * say, and {@link DeadlyStormModel.Settings} is where they are made to mean something.
     */
    public static DeadlyStormModel.Settings deadlyStormSettings() {
        return new DeadlyStormModel.Settings(
                DEADLY_STORM_CAPE_FLOOR_JKG.get(),
                DEADLY_STORM_CAPE_FULL_JKG.get(),
                DEADLY_STORM_SHEAR_SHARE.get(),
                DEADLY_STORM_FULL_SHEAR_MPS.get());
    }

    /** Whether a cell may be promoted into a deadly storm, which is the air's question while it is switched on. */
    public static boolean deadlyStormFromAir() {
        return DEADLY_STORM_FROM_AIR.get();
    }

    /** A storm spawn chance with the frequency dial on it. */
    public static double naturalSpawnChance(double chance) {
        return SimpleSettings.spawnChance(chance * DAILY_SPC_SPAWN_MULTIPLIER_SCALE.get(), simpleStormFrequency());
    }

    /** A wind speed with the strength dial on it. */
    public static double windMph(double mph) {
        return SimpleSettings.windMph(mph, simpleTornadoStrength());
    }

    /** How thick rain is drawn, with the rain dial on it. */
    public static double rainOpacity(double opacity) {
        return SimpleSettings.rainOpacity(opacity, SIMPLE_RAIN_STRENGTH.get());
    }

    /** Whether the mod has as many storms going near a player as its limit allows. */
    public static boolean atStormLimit(int active) {
        return SimpleSettings.atStormLimit(active, SIMPLE_MAX_STORMS.get());
    }

    /** How near a storm has to be to count against that limit. */
    public static int stormLimitRangeBlocks() {
        return SIMPLE_STORM_LIMIT_RANGE_BLOCKS.get();
    }

    /**
     * The foliage sway ceiling that shipped before 1.06, at a size that turned out to be invisible.
     *
     * <p>It is named rather than inlined because {@link #migrateStaleFoliageDefaults()} has to recognise it
     * exactly: the safety of lifting a value out of a saved file rests entirely on being sure of which value
     * it is lifting.
     */
    /** Pre-cauliflower structure particle budget; files still carrying it get lifted to the new default. */
    private static final int STALE_STRUCTURE_PARTICLES_PER_TICK = 70;

    /** Width ceilings that predate the 3-mile monster support; stale saved values are lifted to the new defaults. */
    private static final double STALE_MAX_DAMAGE_WIDTH_BLOCKS = 420.0D;
    private static final double STALE_MAX_VISIBLE_WIDTH_BLOCKS = 320.0D;
    private static final double CURRENT_MAX_WIDTH_BLOCKS = 4828.032D;

    private static final double STALE_FOLIAGE_MAX_OFFSET_BLOCKS = 0.18D;

    /** The ceiling the sway was retuned to, and what a saved file still holding the old one is moved to. */
    private static final double CURRENT_FOLIAGE_MAX_OFFSET_BLOCKS = 0.45D;

    private static final int CURRENT_STRUCTURE_PARTICLES_PER_TICK = 220;

    /**
     * Corrects a foliage setting that a saved config can only be holding because it was the old default.
     *
     * <p>A saved file beating the code is the right behaviour, and it is the reason this exists rather than a
     * changed default: the sway ceiling shipped at 0.18 blocks, which tips a one block blade about ten
     * degrees. The grass was being drawn exactly as asked and simply could not be seen, and nothing in the
     * file says so - it looks like any other setting. A file still holding precisely that value therefore
     * holds no choice, so it is moved to the current ceiling.
     *
     * <p>Anything else is left alone: a value that has been touched is never overruled, and a file already at
     * the new value is left where it is rather than being reset to it.
     */
    public static void migrateStaleFoliageDefaults() {
        if (Math.abs(FOLIAGE_WIND_MAX_OFFSET_BLOCKS.get() - STALE_FOLIAGE_MAX_OFFSET_BLOCKS) > 1.0E-9D) {
            return;
        }
        FOLIAGE_WIND_MAX_OFFSET_BLOCKS.set(CURRENT_FOLIAGE_MAX_OFFSET_BLOCKS);
        ExpandedWeather2Dynamics.LOGGER.info(
                "Foliage sway ceiling was still the old default of {} blocks, which is too slight to see against"
                        + " a one block blade; raised to {}.",
                STALE_FOLIAGE_MAX_OFFSET_BLOCKS, CURRENT_FOLIAGE_MAX_OFFSET_BLOCKS);
        // The cauliflower storm reads as one coherent mass only when the per-tick budget can
        // actually cover its lobe rows plus the skirt sheets; a saved pre-cauliflower budget of
        // 70 would throttle it back to a loose scatter, so files still carrying that stale
        // default are lifted to the new one (touched values win, as above).
        if (SUPERCELL_STRUCTURE_MAX_PARTICLES_PER_TICK.get() == STALE_STRUCTURE_PARTICLES_PER_TICK) {
            SUPERCELL_STRUCTURE_MAX_PARTICLES_PER_TICK.set(CURRENT_STRUCTURE_PARTICLES_PER_TICK);
            ExpandedWeather2Dynamics.LOGGER.info(
                    "Structure particle budget was still the old default of {}, which starves the cauliflower"
                            + " storm mass; raised to {}.",
                    STALE_STRUCTURE_PARTICLES_PER_TICK, CURRENT_STRUCTURE_PARTICLES_PER_TICK);
        }
        // The dynamic size system can now draw tornadoes up to three miles wide, but a saved file
        // still carrying the old 1-mile-era defaults would silently keep capping it. Lift stale
        // defaults (a deliberately chosen value is never overruled).
        if (TORNADO_MAX_DAMAGE_WIDTH_BLOCKS.get() == STALE_MAX_DAMAGE_WIDTH_BLOCKS) {
            TORNADO_MAX_DAMAGE_WIDTH_BLOCKS.set(CURRENT_MAX_WIDTH_BLOCKS);
            ExpandedWeather2Dynamics.LOGGER.info(
                    "Max damage-path width was still the old default of {} blocks; raised to {} to allow"
                            + " three-mile monsters.",
                    STALE_MAX_DAMAGE_WIDTH_BLOCKS, CURRENT_MAX_WIDTH_BLOCKS);
        }
        if (TORNADO_MAX_VISIBLE_FUNNEL_WIDTH_BLOCKS.get() == STALE_MAX_VISIBLE_WIDTH_BLOCKS) {
            TORNADO_MAX_VISIBLE_FUNNEL_WIDTH_BLOCKS.set(CURRENT_MAX_WIDTH_BLOCKS);
            ExpandedWeather2Dynamics.LOGGER.info(
                    "Max visible funnel width was still the old default of {} blocks; raised to {} to allow"
                            + " three-mile monsters.",
                    STALE_MAX_VISIBLE_WIDTH_BLOCKS, CURRENT_MAX_WIDTH_BLOCKS);
        }
    }

    /**
     * Runs the config migrations when this mod's config is read off disk.
     *
     * <p>Unloading is skipped deliberately: {@link ModConfigSpec.ConfigValue#get()} throws once a config has
     * been unloaded, and a server stopping would otherwise trip it on the way out.
     */
    public static void onConfigLoaded(net.neoforged.fml.event.config.ModConfigEvent event) {
        if (event instanceof net.neoforged.fml.event.config.ModConfigEvent.Unloading
                || event.getConfig().getSpec() != SPEC) {
            return;
        }
        migrateStaleFoliageDefaults();
    }

    /**
     * The selected look, and the three questions the render path actually asks. Read through here rather than
     * at the raw enum so a gate reads as what it means - and so the "either classic or combined" sites, which
     * are the ones that concern Weather2's own geometry, cannot drift apart from each other.
     */
    public static StormLook stormLook() {
        return STORM_LOOK.get();
    }

    /** True when Weather2's own 1.12.2 funnel replaces the layered one. */
    public static boolean classicLook() {
        return STORM_LOOK.get() == StormLook.CLASSIC;
    }

    /** True when the combined look is selected - the layered funnel plus Weather2's own 1.12.2 scatter. */
    public static boolean combinedLook() {
        return STORM_LOOK.get() == StormLook.COMBINED;
    }

    /**
     * True when Weather2's own 1.12.2 scatter is drawing - as the funnel in CLASSIC, or as the optional collar
     * in COMBINED. Those puffs are the ones that sample the stock-faint sheet and that need the width pass, so
     * the art routing and the width pass both ask this rather than the classic test alone.
     */
    /**
     * True when Weather2's own 1.12.2 sheets are the art every storm particle samples, rather than the sheets
     * this mod regenerated for the same names. Read through here rather than at the raw value so the art routing
     * has one answer to ask.
     */
    public static boolean originalParticleArt() {
        return STORM_LOOK_ORIGINAL_TEXTURES.get();
    }

    /**
     * True when a storm's cloud deck and its funnel draw a phase-shifted sheet each rather than one shared
     * sheet, so the billboards are not all on the same frame of the animation.
     */
    public static boolean billboardPhases() {
        return STORM_LOOK_BILLBOARD_PHASES.get();
    }

    public static boolean weather2OwnScatter() {
        if (STORM_LOOK.get() == StormLook.CLASSIC) {
            return true;
        }
        return STORM_LOOK.get() == StormLook.COMBINED && STORM_LOOK_CLASSIC_COLLAR.get();
    }

    private Config() {
    }
}
