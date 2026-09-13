package com.imbet;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    public static final double BLOCKS_PER_MILE = 1609.344D;
    public static final double MAX_CONFIG_WIDTH_MILES = 50_000.0D;
    public static final double MAX_CONFIG_WIDTH_BLOCKS = BLOCKS_PER_MILE * MAX_CONFIG_WIDTH_MILES;

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
    public static final ModConfigSpec.IntValue TORNADO_ELEPHANT_TRUNK_TRANSITION_TICKS;
    public static final ModConfigSpec.BooleanValue TORNADO_DRILL_BIT_ENABLED;
    public static final ModConfigSpec.BooleanValue TORNADO_ADDITIONAL_SHAPES_ENABLED;
    public static final ModConfigSpec.DoubleValue TORNADO_ADDITIONAL_SHAPES_CHANCE;
    public static final ModConfigSpec.DoubleValue TORNADO_DRILL_BIT_CHANCE;
    public static final ModConfigSpec.IntValue TORNADO_DRILL_BIT_MIN_STAGE;
    public static final ModConfigSpec.BooleanValue NO_PLAYER_AIM_ON_SPAWN;
    public static final ModConfigSpec.BooleanValue ENVIRONMENTAL_INTENSITY_ENABLED;
    public static final ModConfigSpec.IntValue ENVIRONMENT_SCAN_INTERVAL_TICKS;
    public static final ModConfigSpec.DoubleValue ENVIRONMENT_REINTENSIFY_THRESHOLD;
    public static final ModConfigSpec.DoubleValue ENVIRONMENT_DEINTENSIFY_THRESHOLD;
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
    public static final ModConfigSpec.DoubleValue TRACK_MAX_TURN_DEGREES_PER_TICK;
    public static final ModConfigSpec.DoubleValue TRACK_DEVIANT_MOTION_MAX_DEGREES;
    public static final ModConfigSpec.DoubleValue TRACK_CURVATURE_DEGREES;
    public static final ModConfigSpec.DoubleValue TRACK_SCALLOP_DEGREES;

    public static final ModConfigSpec.BooleanValue VARIABLE_WIDTHS_ENABLED;
    public static final ModConfigSpec.BooleanValue TORNADO_REALISTIC_WIDTH_MODEL;
    public static final ModConfigSpec.DoubleValue TORNADO_WIDTH_MIN_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue TORNADO_WIDTH_MAX_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue TORNADO_WIDTH_SMOOTHING;
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

    public static final ModConfigSpec.BooleanValue DAMAGE_ENABLED;
    public static final ModConfigSpec.IntValue DAMAGE_MAX_BLOCKS_PER_STORM_PER_TICK;
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

    public static final ModConfigSpec.BooleanValue WINDFIELD_ENABLED;
    public static final ModConfigSpec.DoubleValue WINDFIELD_FORMING_MPH;
    public static final ModConfigSpec.DoubleValue WINDFIELD_STAGE1_MPH;
    public static final ModConfigSpec.DoubleValue WINDFIELD_STAGE2_MPH;
    public static final ModConfigSpec.DoubleValue WINDFIELD_STAGE3_MPH;
    public static final ModConfigSpec.DoubleValue WINDFIELD_STAGE4_MPH;
    public static final ModConfigSpec.DoubleValue WINDFIELD_STAGE5_MPH;
    public static final ModConfigSpec.BooleanValue WINDFIELD_DYNAMIC_WIND_SPEEDS;
    public static final ModConfigSpec.DoubleValue WINDFIELD_DYNAMIC_WIND_SMOOTHING;
    public static final ModConfigSpec.DoubleValue WINDFIELD_DYNAMIC_GUSTINESS;
    public static final ModConfigSpec.DoubleValue WINDFIELD_RADIAL_INFLOW_FRACTION;
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
    public static final ModConfigSpec.BooleanValue HORIZONTAL_VORTICES_ENABLED;
    public static final ModConfigSpec.IntValue HORIZONTAL_VORTICES_MAX_COUNT;
    public static final ModConfigSpec.IntValue HORIZONTAL_VORTICES_MIN_STAGE;
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
    public static final ModConfigSpec.BooleanValue SUPERCELL_STRUCTURE_CLIENT_VISUALS;
    public static final ModConfigSpec.BooleanValue SUPERCELL_STRUCTURE_RADAR;
    public static final ModConfigSpec.BooleanValue SUPERCELL_STRUCTURE_WINDFIELD_INFLUENCE;
    public static final ModConfigSpec.IntValue SUPERCELL_STRUCTURE_RENDER_RANGE_BLOCKS;
    public static final ModConfigSpec.IntValue SUPERCELL_STRUCTURE_MAX_PARTICLES_PER_TICK;
    public static final ModConfigSpec.BooleanValue SUPERCELL_STRUCTURE_ANVIL_PARTICLES;
    public static final ModConfigSpec.BooleanValue SUPERCELL_STRUCTURE_WALL_CLOUD_PARTICLES;
    public static final ModConfigSpec.DoubleValue SUPERCELL_STRUCTURE_WALL_CLOUD_PARTICLE_SIZE;
    public static final ModConfigSpec.BooleanValue SUPERCELL_STRUCTURE_WALL_CLOUD_LOWERING;
    public static final ModConfigSpec.DoubleValue SUPERCELL_STRUCTURE_WALL_CLOUD_MIN_MESOCYCLONE_STRENGTH;
    public static final ModConfigSpec.IntValue SUPERCELL_STRUCTURE_WALL_CLOUD_MIN_PERSISTENCE_TICKS;
    public static final ModConfigSpec.IntValue SUPERCELL_STRUCTURE_WALL_CLOUD_MAX_DIAMETER_BLOCKS;
    public static final ModConfigSpec.IntValue SUPERCELL_STRUCTURE_WALL_CLOUD_MAX_LOWERING_BLOCKS;
    public static final ModConfigSpec.BooleanValue SUPERCELL_STRUCTURE_WALL_CLOUD_ROTATION_PARTICLES;
    public static final ModConfigSpec.DoubleValue SUPERCELL_STRUCTURE_WALL_CLOUD_SCUD_CHANCE;
    public static final ModConfigSpec.BooleanValue SUPERCELL_STRUCTURE_WALL_CLOUD_SCUD_FEED_PARTICLES;
    public static final ModConfigSpec.BooleanValue SUPERCELL_STRUCTURE_WALL_CLOUD_RISK_BOOST;
    public static final ModConfigSpec.BooleanValue SUPERCELL_PRECIPITATION_ENABLED;
    public static final ModConfigSpec.DoubleValue SUPERCELL_PRECIPITATION_VARIABILITY;
    public static final ModConfigSpec.DoubleValue SUPERCELL_PRECIPITATION_LP_THRESHOLD;
    public static final ModConfigSpec.DoubleValue SUPERCELL_PRECIPITATION_HP_THRESHOLD;
    public static final ModConfigSpec.DoubleValue SUPERCELL_PRECIPITATION_DRIFT_PER_TICK;
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
    public static final ModConfigSpec.BooleanValue CYCLONES_ENABLED;
    public static final ModConfigSpec.BooleanValue LANDSPOUTS_ENABLED;
    public static final ModConfigSpec.BooleanValue DUST_DEVILS_ENABLED;
    public static final ModConfigSpec.BooleanValue GUSTNADOES_ENABLED;
    public static final ModConfigSpec.BooleanValue HURRICANES_ENABLED;
    public static final ModConfigSpec.BooleanValue FLOATER_TORNADOES_ENABLED;
    public static final ModConfigSpec.BooleanValue SNOW_DEVILS_ENABLED;
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
    public static final ModConfigSpec.DoubleValue LANDSPOUT_SPAWN_CHANCE;
    public static final ModConfigSpec.DoubleValue WATERSPOUT_SPAWN_CHANCE;
    public static final ModConfigSpec.DoubleValue DUST_DEVIL_SPAWN_CHANCE;
    public static final ModConfigSpec.DoubleValue GUSTNADO_SPAWN_CHANCE;
    public static final ModConfigSpec.DoubleValue HURRICANE_SPAWN_CHANCE;
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
    public static final ModConfigSpec.DoubleValue FOLIAGE_WIND_THRASH_MPH;
    public static final ModConfigSpec.DoubleValue FOLIAGE_WIND_SMOOTHING;

    public static final ModConfigSpec.BooleanValue IMMERSIVE_THUNDER_ENABLED;
    public static final ModConfigSpec.DoubleValue THUNDER_SECONDS_PER_MILE;
    public static final ModConfigSpec.IntValue THUNDER_MAX_DELAY_TICKS;

    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_ENABLED;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_ROAR_SOUND;
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
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_CLOSE_DEBRIS;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_INSIDE_CORE_EFFECTS;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_INFLOW_SHADER_OVERLAY;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_EYE_EFFECT;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_BLOWOUT_AUDIO;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_INTENSE_EF3_EFFECTS;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_EF3_WIND_THRESHOLD_MPH;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_EF3_ROAR_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_EF3_CAMERA_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_INSIDE_FOG_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_INFLOW_OVERLAY_OPACITY;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_EYE_CLEAR_RADIUS;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_BLOWOUT_AUDIO_INTENSITY;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_WHITEOUT_ENABLED;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_WHITEOUT_FOG_BLOCKS;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_WHITEOUT_VEIL_OPACITY;
    public static final ModConfigSpec.IntValue TORNADO_IMMERSION_CLOSE_EFFECT_RADIUS_BLOCKS;
    public static final ModConfigSpec.IntValue TORNADO_IMMERSION_RENDER_RANGE_BLOCKS;
    public static final ModConfigSpec.IntValue TORNADO_IMMERSION_MAX_PARTICLES_PER_TICK;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_ACCESSIBILITY_REDUCED_MOTION;

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

    public static final ModConfigSpec.BooleanValue LOW_PRESSURE_ENABLED;
    public static final ModConfigSpec.IntValue LOW_PRESSURE_SCAN_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue LOW_PRESSURE_MIN_STORMS;
    public static final ModConfigSpec.IntValue LOW_PRESSURE_MAX_DISPLAY_RANGE_BLOCKS;

    public static final ModConfigSpec.BooleanValue AI_VILLAGER_TORNADO_SHELTER;
    public static final ModConfigSpec.IntValue AI_VILLAGER_SHELTER_RADIUS_BLOCKS;
    public static final ModConfigSpec.IntValue AI_VILLAGER_SHELTER_SCAN_INTERVAL_TICKS;

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

    static {
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
        TORNADO_ELEPHANT_TRUNK_TRANSITION_TICKS = BUILDER.defineInRange("elephantTrunkTransitionTicks", 55, 1, 20 * 60);
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
        NO_PLAYER_AIM_ON_SPAWN = BUILDER.comment("Prevents natural Weather2 tornadoes from steering directly at the nearest player when they form.")
                .define("noPlayerAimOnSpawn", true);
        ENVIRONMENTAL_INTENSITY_ENABLED = BUILDER.comment("Allows tornadoes to reintensify or deintensify based on nearby environment.")
                .define("environmentalIntensity", true);
        ENVIRONMENT_SCAN_INTERVAL_TICKS = BUILDER.defineInRange("environmentScanIntervalTicks", 100, 20, 20 * 60 * 5);
        ENVIRONMENT_REINTENSIFY_THRESHOLD = BUILDER.defineInRange("environmentReintensifyThreshold", 0.68D, 0.0D, 1.0D);
        ENVIRONMENT_DEINTENSIFY_THRESHOLD = BUILDER.defineInRange("environmentDeintensifyThreshold", 0.28D, 0.0D, 1.0D);
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
        GENESIS_DURATION_SECONDS = BUILDER.comment("Nominal seconds from the wall cloud starting to organize to a mature tornado on the ground. Real genesis runs from about 30 seconds to 15 minutes.")
                .defineInRange("durationSeconds", 75.0D, 5.0D, 900.0D);
        GENESIS_INTENSITY_SCALE = BUILDER.comment("Violent tornadoes tighten up and reach the ground sooner than weak ones, which can churn for many minutes without putting a visible funnel down. Scales the duration from 1.18x at the weakest tornado stage down to 0.62x at the strongest.")
                .define("intensityScalesDuration", true);
        GENESIS_JITTER = BUILDER.comment("Per-storm variation in genesis length, as a fraction. 0.25 means each storm forms somewhere between 25% faster and 25% slower than nominal, so no two storms form in lockstep.")
                .defineInRange("durationJitter", 0.25D, 0.0D, 0.9D);
        GENESIS_START_WIND_FRACTION = BUILDER.comment("Fraction of the storm's stage wind present the moment it starts organizing. This is the weak circulation that turns below a lowering wall cloud before any condensation appears.")
                .defineInRange("startWindFraction", 0.05D, 0.0D, 0.9D);
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
        TORNADO_WIDTH_SMOOTHING = BUILDER.defineInRange("tornadoWidthSmoothing", 0.035D, 0.001D, 1.0D);
        TORNADO_WIDTH_MIN_RETARGET_TICKS = BUILDER.defineInRange("widthRetargetMinTicks", 200, 1, 20 * 60 * 10);
        TORNADO_WIDTH_MAX_RETARGET_TICKS = BUILDER.defineInRange("widthRetargetMaxTicks", 700, 1, 20 * 60 * 10);
        TORNADO_MIN_DAMAGE_WIDTH_BLOCKS = BUILDER.comment("Minimum realistic damage-path width in blocks when minDamageWidthMiles is 0.")
                .defineInRange("minDamageWidthBlocks", 12.0D, 3.0D, MAX_CONFIG_WIDTH_BLOCKS);
        TORNADO_MAX_DAMAGE_WIDTH_BLOCKS = BUILDER.comment("Maximum realistic damage-path width in blocks when maxDamageWidthMiles is 0. One mile is 1609.344 blocks.")
                .defineInRange("maxDamageWidthBlocks", 420.0D, 3.0D, MAX_CONFIG_WIDTH_BLOCKS);
        TORNADO_MIN_DAMAGE_WIDTH_MILES = BUILDER.comment("Optional minimum damage-path width in miles. Set to 0 to use minDamageWidthBlocks.")
                .defineInRange("minDamageWidthMiles", 0.0D, 0.0D, MAX_CONFIG_WIDTH_MILES);
        TORNADO_MAX_DAMAGE_WIDTH_MILES = BUILDER.comment("Optional maximum damage-path width in miles. Set to 5.0 for a five-mile max path, which converts to 8046.72 blocks. Set to 0 to use maxDamageWidthBlocks.")
                .defineInRange("maxDamageWidthMiles", 1.0D, 0.0D, MAX_CONFIG_WIDTH_MILES);
        TORNADO_MAX_VISIBLE_FUNNEL_WIDTH_BLOCKS = BUILDER.comment("Safety cap for the rendered Weather2 funnel base size in blocks when maxVisibleFunnelWidthMiles is 0. Damage-path width can still be larger.")
                .defineInRange("maxVisibleFunnelWidthBlocks", 320.0D, 3.0D, MAX_CONFIG_WIDTH_BLOCKS);
        TORNADO_MAX_VISIBLE_FUNNEL_WIDTH_MILES = BUILDER.comment("Optional rendered funnel cap in miles. Set to 0 to use maxVisibleFunnelWidthBlocks. Huge values can be heavy because Weather2 also uses funnel size internally.")
                .defineInRange("maxVisibleFunnelWidthMiles", 0.0D, 0.0D, MAX_CONFIG_WIDTH_MILES);
        TORNADO_VISIBLE_FUNNEL_FRACTION_MIN = BUILDER.defineInRange("visibleFunnelFractionMin", 0.35D, 0.05D, 1.0D);
        TORNADO_VISIBLE_FUNNEL_FRACTION_MAX = BUILDER.defineInRange("visibleFunnelFractionMax", 0.75D, 0.05D, 1.0D);
        BUILDER.pop();

        BUILDER.push("damage");
        DAMAGE_ENABLED = BUILDER.comment("Enables balanced tornado damage limits and strength checks.")
                .define("enabled", true);
        DAMAGE_MAX_BLOCKS_PER_STORM_PER_TICK = BUILDER.comment("Base block-damage budget per storm per tick. Scales up with tornado stage (x1.2 EF2, x1.55 EF3, x2.1 EF4, x2.8 EF5) so violent tornadoes tear far more than weak ones.")
                .defineInRange("maxBlocksPerStormPerTick", 18, 0, 1000);
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

        BUILDER.push("windfield");
        WINDFIELD_ENABLED = BUILDER.comment("Enables modified Rankine-style tornado wind sampling for gameplay systems.")
                .define("enabled", true);
        WINDFIELD_FORMING_MPH = BUILDER.defineInRange("formingMph", 75.0D, 0.0D, 400.0D);
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
        WINDFIELD_RADIAL_INFLOW_FRACTION = BUILDER.defineInRange("radialInflowFraction", 0.36D, 0.0D, 2.0D);
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
        FUNNEL_LUMPS_ENABLED = BUILDER.comment("Deforms the parent condensation funnel into lumps that travel around it with the orbiting sub-vortices - the multi-lobed, clefted base that defines the look of a multi-vortex tornado. Purely visual: it never changes wind, damage or state.")
                .define("funnelLumps", true);
        FUNNEL_LUMP_STRENGTH = BUILDER.comment("How far the funnel wall bulges where a sub-vortex passes. 0.28 is a clearly lobed base; 0.5 is extreme; 0 leaves the funnel a clean surface of revolution.")
                .defineInRange("funnelLumpStrength", 0.28D, 0.0D, 0.80D);
        BUILDER.pop();

        BUILDER.push("horizontal_vortices");
        HORIZONTAL_VORTICES_ENABLED = BUILDER.comment("Adds short horizontal vortex tubes around the tornado base and RFD-side outer circulation.")
                .define("enabled", true);
        HORIZONTAL_VORTICES_MAX_COUNT = BUILDER.defineInRange("maxCount", 4, 0, 10);
        HORIZONTAL_VORTICES_MIN_STAGE = BUILDER.defineInRange("minStage", 5, 4, 9);
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
        SUPERCELL_STRUCTURE_CLIENT_VISUALS = BUILDER.define("clientVisuals", true);
        SUPERCELL_STRUCTURE_RADAR = BUILDER.define("radarStructure", true);
        SUPERCELL_STRUCTURE_WINDFIELD_INFLUENCE = BUILDER.comment("Lets supercell inflow, forward-flank downdraft, and rear-flank downdraft affect anemometers and radar velocity.")
                .define("windfieldInfluence", true);
        SUPERCELL_STRUCTURE_RENDER_RANGE_BLOCKS = BUILDER.defineInRange("renderRangeBlocks", 900, 64, 4096);
        SUPERCELL_STRUCTURE_MAX_PARTICLES_PER_TICK = BUILDER.defineInRange("maxStructureParticlesPerTick", 70, 0, 800);
        SUPERCELL_STRUCTURE_ANVIL_PARTICLES = BUILDER.define("anvilParticles", true);
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
        SUPERCELL_STRUCTURE_WALL_CLOUD_SCUD_FEED_PARTICLES = BUILDER.comment("Adds upward scud/fractus feeding into the wall cloud from the inflow side.")
                .define("wallCloudScudFeedParticles", false);
        SUPERCELL_STRUCTURE_WALL_CLOUD_RISK_BOOST = BUILDER.comment("Lets persistent rotating wall clouds modestly increase SPC/radar tornado potential without triggering TOR warnings by themselves.")
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
        BUILDER.pop();

        BUILDER.push("storm_types");
        WATERSPOUTS_ENABLED = BUILDER.define("waterspouts", true);
        FAIR_WEATHER_WATERSPOUTS_ENABLED = BUILDER.comment("Spawns addon-owned fair-weather waterspout vortices over water near developing Weather2 showers/thunderstorms.")
                .define("fairWeatherWaterspouts", true);
        CYCLONES_ENABLED = BUILDER.define("cyclones", false);
        HURRICANES_ENABLED = BUILDER.comment("Allows addon-derived broad maritime hurricane phenomena in late-summer/early-autumn thermodynamic windows.")
                .define("hurricanes", false);
        LANDSPOUTS_ENABLED = BUILDER.comment("Spawns addon-owned non-supercell landspout vortices near developing Weather2 thunderstorms over land.")
                .define("landspouts", true);
        DUST_DEVILS_ENABLED = BUILDER.comment("Spawns small fair-weather dust devils on clear, dry, hot terrain.")
                .define("dustDevils", true);
        GUSTNADOES_ENABLED = BUILDER.comment("Spawns brief shallow vortices along thunderstorm and squall-line outflow boundaries.")
                .define("gustnadoes", true);
        FLOATER_TORNADOES_ENABLED = BUILDER.comment("Allows rare Weather2 tornadoes to lock into a floater morphology tag that keeps the funnel visually detached from the ground.")
                .define("floaterTornadoes", true);
        SNOW_DEVILS_ENABLED = BUILDER.comment("Spawns very rare fair-weather snow devils over cold snow or ice surfaces.")
                .define("snowDevils", true);
        WILDFIRES_ENABLED = BUILDER.comment("Allows dry, hot fire-weather outbreaks that ignite and spread small wildfire cores.")
                .define("wildfires", true);
        FIRE_WHIRLS_ENABLED = BUILDER.comment("Allows intense wildfire cores to spin up short-lived fire whirls with damaging windfields.")
                .define("fireWhirls", true);
        FLAMMAGENITUS_CLOUDS_ENABLED = BUILDER.comment("Allows large wildfire heat plumes to produce flammagenitus/pyrocumulus cloud columns.")
                .define("flammagenitusClouds", true);
        FIRE_WEATHER_RISK_ENABLED = BUILDER.comment("Includes wildfire, fire whirl, and flammagenitus signals in SPC-style categorical outlooks.")
                .define("fireWeatherRisk", true);
        LANDSPOUT_SPAWN_CHANCE = BUILDER.defineInRange("landspoutSpawnChance", 0.018D, 0.0D, 1.0D);
        WATERSPOUT_SPAWN_CHANCE = BUILDER.defineInRange("waterspoutSpawnChance", 0.024D, 0.0D, 1.0D);
        DUST_DEVIL_SPAWN_CHANCE = BUILDER.defineInRange("dustDevilSpawnChance", 0.010D, 0.0D, 1.0D);
        GUSTNADO_SPAWN_CHANCE = BUILDER.defineInRange("gustnadoSpawnChance", 0.016D, 0.0D, 1.0D);
        HURRICANE_SPAWN_CHANCE = BUILDER.defineInRange("hurricaneSpawnChance", 0.006D, 0.0D, 1.0D);
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
        EXTENDED_RENDERING_ENABLED = BUILDER.comment("Uses Weather2/ExtendedRenderer EntityRotFX particles for addon storm visuals. Disable only if a renderer or shader pack conflicts.")
                .define("extendedRendering", true);
        SEVERE_SKY_TINT_STRENGTH = BUILDER.defineInRange("severeSkyTintStrength", 0.8D, 0.0D, 1.5D);
        CLEAR_INTERVALS_ENABLED = BUILDER.define("clearIntervals", true);
        CLEAR_INTERVAL_RAIN_REDUCTION = BUILDER.defineInRange("clearIntervalRainReduction", 0.03D, 0.0D, 1.0D);
        SKY_SMOOTHING = BUILDER.defineInRange("skySmoothing", 0.04D, 0.001D, 1.0D);
        MIN_SKY_UPDATE_TICKS = BUILDER.defineInRange("minSkyUpdateTicks", 4, 1, 200);
        STICK_DEBRIS_ENABLED = BUILDER.comment("Spawns visual-only stick item particles in tornadoes, sub-vortices, satellites, landspouts, dust devils, waterspouts, and squall-line winds.")
                .define("stickDebris", true);
        STICK_DEBRIS_RENDER_RANGE_BLOCKS = BUILDER.defineInRange("stickDebrisRenderRangeBlocks", 512, 32, 4096);
        MAX_STICK_DEBRIS_PER_TICK = BUILDER.defineInRange("maxStickDebrisPerTick", 80, 0, 1000);
        SUB_VORTEX_STICK_DENSITY = BUILDER.defineInRange("subVortexStickDensity", 0.55D, 0.0D, 1.0D);
        LANDSPOUT_STICK_DENSITY = BUILDER.defineInRange("landspoutStickDensity", 0.42D, 0.0D, 1.0D);
        DUST_DEVIL_STICK_DENSITY = BUILDER.defineInRange("dustDevilStickDensity", 0.55D, 0.0D, 1.0D);
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
        FOLIAGE_WIND_MAX_OFFSET_BLOCKS = BUILDER.defineInRange("foliageWindMaxOffsetBlocks", 0.18D, 0.0D, 1.0D);
        FOLIAGE_WIND_THRASH_MPH = BUILDER.defineInRange("foliageWindThrashMph", 95.0D, 1.0D, 500.0D);
        FOLIAGE_WIND_SMOOTHING = BUILDER.defineInRange("foliageWindSmoothing", 0.12D, 0.001D, 1.0D);
        BUILDER.pop();

        BUILDER.push("thunder");
        IMMERSIVE_THUNDER_ENABLED = BUILDER.define("immersiveThunder", true);
        THUNDER_SECONDS_PER_MILE = BUILDER.defineInRange("secondsPerMile", 5.0D, 0.0D, 20.0D);
        THUNDER_MAX_DELAY_TICKS = BUILDER.defineInRange("maxDelayTicks", 20 * 35, 0, 20 * 120);
        BUILDER.pop();

        BUILDER.push("tornado_immersion");
        TORNADO_IMMERSION_ENABLED = BUILDER.comment("Adds client-side tornado dread effects tied to real circulation distance and windfield strength.")
                .define("enabled", true);
        TORNADO_IMMERSION_ROAR_SOUND = BUILDER.comment("Loops a low tornado roar near active tornado circulations. Uses custom CC0 audio with vanilla fallback accents.")
                .define("roarSound", true);
        TORNADO_IMMERSION_CAMERA_RUMBLE = BUILDER.comment("Adds subtle wind-pressure camera rumble near dangerous tornado windfields.")
                .define("cameraRumble", true);
        TORNADO_IMMERSION_MAX_CAMERA_ROLL_DEGREES = BUILDER.defineInRange("maxCameraRollDegrees", 1.35D, 0.0D, 12.0D);
        TORNADO_IMMERSION_CAMERA_TUMBLE = BUILDER.comment("When captured by a violent core, continuously spins the camera yaw and rolls it hard so the view tumbles like the player is being rolled inside the tornado.")
                .define("cameraTumble", true);
        TORNADO_IMMERSION_MAX_TUMBLE_ROLL_DEGREES = BUILDER.defineInRange("maxTumbleRollDegrees", 14.0D, 0.0D, 60.0D);
        TORNADO_IMMERSION_TUMBLE_SPIN_DEGREES = BUILDER.comment("First-person yaw spin rate of the capture tumble, in degrees per second. The client spins its own player; the server spins captured mobs.")
                .defineInRange("tumbleSpinDegreesPerSecond", 40.0D, 0.0D, 180.0D);
        TORNADO_IMMERSION_TUMBLE_SPEED_MULTIPLIER = BUILDER.comment("Scales how fast captured players and mobs tumble on all three axes - the yaw spin, the end-over-end pitch flip, and the render roll. 1.0 is the tuned base rate, where a full revolution takes roughly two to three seconds; the default 2.5 brings that to about one second. Both sides scale together because the server spins mobs (their rotation reaches clients as vanilla rotation updates) while this client spins its own player - scaling only one would leave them tumbling at different speeds. Above roughly 4 the fastest mobs can start to look stepped, because vanilla only relays mob rotation a few times a second.")
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
        TORNADO_IMMERSION_EF3_ROAR_MULTIPLIER = BUILDER.defineInRange("ef3RoarMultiplier", 1.35D, 1.0D, 4.0D);
        TORNADO_IMMERSION_EF3_CAMERA_MULTIPLIER = BUILDER.defineInRange("ef3CameraMultiplier", 1.55D, 1.0D, 4.0D);
        TORNADO_IMMERSION_INSIDE_FOG_MULTIPLIER = BUILDER.defineInRange("insideFogMultiplier", 1.0D, 0.0D, 4.0D);
        TORNADO_IMMERSION_INFLOW_OVERLAY_OPACITY = BUILDER.defineInRange("inflowOverlayOpacity", 0.72D, 0.0D, 1.0D);
        TORNADO_IMMERSION_EYE_CLEAR_RADIUS = BUILDER.defineInRange("eyeClearRadius", 0.34D, 0.05D, 0.90D);
        TORNADO_IMMERSION_BLOWOUT_AUDIO_INTENSITY = BUILDER.defineInRange("blowoutAudioIntensity", 1.0D, 0.0D, 3.0D);
        TORNADO_IMMERSION_WHITEOUT_ENABLED = BUILDER.comment("Full dust whiteout when the player is deep inside a violent tornado core: fog collapses to near-zero and colors go dust-tan, like being caught inside. The screen-space inflow overlay is no longer force-activated by it - enable inflowShaderOverlay separately if you want the full-screen veil.")
                .define("whiteout", true);
        TORNADO_IMMERSION_WHITEOUT_FOG_BLOCKS = BUILDER.defineInRange("whiteoutFogBlocks", 3.0D, 0.5D, 16.0D);
        TORNADO_IMMERSION_WHITEOUT_VEIL_OPACITY = BUILDER.defineInRange("whiteoutVeilOpacity", 0.96D, 0.0D, 1.0D);
        TORNADO_IMMERSION_CLOSE_EFFECT_RADIUS_BLOCKS = BUILDER.comment("Maximum radius for close tornado roar and camera shake. Keeps intense effects local to the tornado.")
                .defineInRange("closeEffectRadiusBlocks", 30, 4, 512);
        TORNADO_IMMERSION_RENDER_RANGE_BLOCKS = BUILDER.defineInRange("renderRangeBlocks", 900, 64, 4096);
        TORNADO_IMMERSION_MAX_PARTICLES_PER_TICK = BUILDER.defineInRange("maxParticlesPerTick", 90, 0, 1000);
        TORNADO_IMMERSION_ACCESSIBILITY_REDUCED_MOTION = BUILDER.comment("Reduces camera/FOV motion while keeping audio, fog, and debris cues.")
                .define("accessibilityReducedMotion", false);
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
        VOLUMETRIC_DOWNFALL_SHEET_SIZE_MULTIPLIER = BUILDER.comment("Size of the falling rain sheets, as a multiplier of their base width. These quads are how far a rain shaft reaches across the sky, and the rain textures only fill a narrow vertical band down the middle of each one - roughly a third of its width, most of its height - so a sheet renders as a tall thin shaft rather than a square. Bigger values mean taller, more distinct shafts; smaller values mean finer, sparser rain. 1.0 is the old full-size sheet.")
                .defineInRange("sheetSizeMultiplier", 0.72D, 0.10D, 3.0D);
        VOLUMETRIC_DOWNFALL_SHAFT_TEXTURES = BUILDER.comment("Draw the rain volumes with real falling-rain textures - fine vertical streaks at three densities chosen by how hard it is raining - instead of Weather2's single soft smudge sheet. This covers both the rain around a storm and the rain curtains of squall lines and hurricanes. Sheets stand upright while it is on, because a rain curtain is a vertical wall of falling water and the streak textures carry their threads vertically; the original soft sheet lay nearly flat, which is why its rain read as fog rather than rain. Turn off to go back to exactly the original look, orientation included.")
                .define("shaftTextures", true);
        VOLUMETRIC_DOWNFALL_SHADER_COMPATIBILITY_MODE = BUILDER.comment("Shader-pack weather signal mode: auto, on, or off. Auto enables compatibility when Oculus/Iris is loaded.")
                .define("shaderCompatibilityMode", "auto");
        BUILDER.pop();

        BUILDER.push("low_pressure");
        LOW_PRESSURE_ENABLED = BUILDER.comment("Derives broad low-pressure centers from clustered Weather2 severe storms for forecaster/radar context.")
                .define("enabled", true);
        LOW_PRESSURE_SCAN_INTERVAL_TICKS = BUILDER.defineInRange("scanIntervalTicks", 100, 20, 20 * 60 * 10);
        LOW_PRESSURE_MIN_STORMS = BUILDER.defineInRange("minStorms", 2, 1, 32);
        LOW_PRESSURE_MAX_DISPLAY_RANGE_BLOCKS = BUILDER.defineInRange("maxDisplayRangeBlocks", 4096, 256, 32768);
        BUILDER.pop();

        BUILDER.push("ai");
        AI_VILLAGER_TORNADO_SHELTER = BUILDER.comment("Villagers within tornado-threat range try to path into roofed shelter.")
                .define("villagerTornadoShelter", true);
        AI_VILLAGER_SHELTER_RADIUS_BLOCKS = BUILDER.defineInRange("villagerShelterRadiusBlocks", 384, 32, 4096);
        AI_VILLAGER_SHELTER_SCAN_INTERVAL_TICKS = BUILDER.defineInRange("villagerShelterScanIntervalTicks", 40, 5, 20 * 60);
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
        return miles > 0.0D ? milesToBlocks(miles) : TORNADO_MIN_DAMAGE_WIDTH_BLOCKS.get();
    }

    public static double effectiveMaxDamageWidthBlocks() {
        double miles = TORNADO_MAX_DAMAGE_WIDTH_MILES.get();
        double max = miles > 0.0D ? milesToBlocks(miles) : TORNADO_MAX_DAMAGE_WIDTH_BLOCKS.get();
        return Math.max(effectiveMinDamageWidthBlocks(), max);
    }

    public static double effectiveMaxVisibleFunnelWidthBlocks() {
        double miles = TORNADO_MAX_VISIBLE_FUNNEL_WIDTH_MILES.get();
        double max = miles > 0.0D ? milesToBlocks(miles) : TORNADO_MAX_VISIBLE_FUNNEL_WIDTH_BLOCKS.get();
        return Math.max(3.0D, max);
    }

    private Config() {
    }
}
