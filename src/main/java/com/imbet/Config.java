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
    public static final ModConfigSpec.DoubleValue TORNADO_FUNNEL_BEND_MAX_BLOCKS;
    public static final ModConfigSpec.DoubleValue TORNADO_FUNNEL_BEND_SWAY_BLOCKS;
    public static final ModConfigSpec.DoubleValue TORNADO_FUNNEL_BEND_SMOOTHING;
    public static final ModConfigSpec.DoubleValue TORNADO_FUNNEL_BEND_ANCHOR_FRACTION;
    public static final ModConfigSpec.DoubleValue TORNADO_FUNNEL_BEND_MAX_HEIGHT_FRACTION;
    public static final ModConfigSpec.BooleanValue NO_PLAYER_AIM_ON_SPAWN;
    public static final ModConfigSpec.BooleanValue ENVIRONMENTAL_INTENSITY_ENABLED;
    public static final ModConfigSpec.IntValue ENVIRONMENT_SCAN_INTERVAL_TICKS;
    public static final ModConfigSpec.DoubleValue ENVIRONMENT_REINTENSIFY_THRESHOLD;
    public static final ModConfigSpec.DoubleValue ENVIRONMENT_DEINTENSIFY_THRESHOLD;
    public static final ModConfigSpec.BooleanValue TORNADO_WIND_SPEED_SPIN_ENABLED;
    public static final ModConfigSpec.DoubleValue TORNADO_WIND_SPEED_SPIN_MIN;
    public static final ModConfigSpec.DoubleValue TORNADO_WIND_SPEED_SPIN_MAX;
    public static final ModConfigSpec.DoubleValue TORNADO_WIND_SPEED_SPIN_SMOOTHING;
    public static final ModConfigSpec.DoubleValue TORNADO_WIND_SPEED_SPIN_REFERENCE_MIN_MPH;
    public static final ModConfigSpec.DoubleValue TORNADO_WIND_SPEED_SPIN_REFERENCE_MAX_MPH;

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
    public static final ModConfigSpec.DoubleValue DAMAGE_TREE_DEBARK_MPH;
    public static final ModConfigSpec.DoubleValue DAMAGE_HEAVY_TREE_DEBARK_MPH;
    public static final ModConfigSpec.IntValue DAMAGE_LIGHT_SCOURING_STAGE;
    public static final ModConfigSpec.IntValue DAMAGE_MEDIUM_SCOURING_STAGE;
    public static final ModConfigSpec.IntValue DAMAGE_HEAVY_SCOURING_STAGE;

    public static final ModConfigSpec.BooleanValue WINDFIELD_ENABLED;
    public static final ModConfigSpec.DoubleValue WINDFIELD_FORMING_MPH;
    public static final ModConfigSpec.DoubleValue WINDFIELD_STAGE1_MPH;
    public static final ModConfigSpec.DoubleValue WINDFIELD_STAGE2_MPH;
    public static final ModConfigSpec.DoubleValue WINDFIELD_STAGE3_MPH;
    public static final ModConfigSpec.DoubleValue WINDFIELD_STAGE4_MPH;
    public static final ModConfigSpec.DoubleValue WINDFIELD_STAGE5_MPH;
    public static final ModConfigSpec.DoubleValue WINDFIELD_RADIAL_INFLOW_FRACTION;
    public static final ModConfigSpec.DoubleValue WINDFIELD_VERTICAL_LIFT_FRACTION;
    public static final ModConfigSpec.DoubleValue WINDFIELD_ENTITY_PUSH_SCALE;

    public static final ModConfigSpec.BooleanValue MULTIVORTEX_ENABLED;
    public static final ModConfigSpec.IntValue MULTIVORTEX_MAX_COUNT;
    public static final ModConfigSpec.IntValue MULTIVORTEX_MIN_STAGE;
    public static final ModConfigSpec.DoubleValue MULTIVORTEX_DAMAGE_MULTIPLIER;

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
    public static final ModConfigSpec.BooleanValue SUPERCELL_STRUCTURE_WALL_CLOUD_LOWERING;
    public static final ModConfigSpec.DoubleValue SUPERCELL_STRUCTURE_WALL_CLOUD_MIN_MESOCYCLONE_STRENGTH;
    public static final ModConfigSpec.IntValue SUPERCELL_STRUCTURE_WALL_CLOUD_MIN_PERSISTENCE_TICKS;
    public static final ModConfigSpec.IntValue SUPERCELL_STRUCTURE_WALL_CLOUD_MAX_DIAMETER_BLOCKS;
    public static final ModConfigSpec.IntValue SUPERCELL_STRUCTURE_WALL_CLOUD_MAX_LOWERING_BLOCKS;
    public static final ModConfigSpec.BooleanValue SUPERCELL_STRUCTURE_WALL_CLOUD_ROTATION_PARTICLES;
    public static final ModConfigSpec.BooleanValue SUPERCELL_STRUCTURE_WALL_CLOUD_SCUD_FEED_PARTICLES;
    public static final ModConfigSpec.BooleanValue SUPERCELL_STRUCTURE_WALL_CLOUD_RISK_BOOST;
    public static final ModConfigSpec.BooleanValue SUPERCELL_STRUCTURE_GUST_FRONT_PARTICLES;
    public static final ModConfigSpec.BooleanValue SUPERCELL_STRUCTURE_FLANKING_LINE_PARTICLES;
    public static final ModConfigSpec.BooleanValue SUPERCELL_STRUCTURE_VIRGA_MAMMATUS_PARTICLES;
    public static final ModConfigSpec.BooleanValue SUPERCELL_STRUCTURE_RFD_STRAIGHT_LINE_WIND;

    public static final ModConfigSpec.BooleanValue RADAR_ENABLED;
    public static final ModConfigSpec.IntValue RADAR_RANGE_BLOCKS;
    public static final ModConfigSpec.IntValue RADAR_UPDATE_INTERVAL_TICKS;
    public static final ModConfigSpec.BooleanValue RADAR_WORLD_PANEL_ENABLED;
    public static final ModConfigSpec.BooleanValue RADAR_STABLE_NORTH_UP;
    public static final ModConfigSpec.BooleanValue RADAR_CONFIRMED_CIRCULATION_MARKER;

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

    public static final ModConfigSpec.BooleanValue WATERSPOUTS_ENABLED;
    public static final ModConfigSpec.BooleanValue FAIR_WEATHER_WATERSPOUTS_ENABLED;
    public static final ModConfigSpec.BooleanValue CYCLONES_ENABLED;
    public static final ModConfigSpec.BooleanValue LANDSPOUTS_ENABLED;
    public static final ModConfigSpec.BooleanValue DUST_DEVILS_ENABLED;
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
    public static final ModConfigSpec.BooleanValue ANTI_CYCLONIC_TORNADOES_ENABLED;
    public static final ModConfigSpec.DoubleValue ANTI_CYCLONIC_TORNADO_CHANCE;
    public static final ModConfigSpec.BooleanValue ANTI_CYCLONIC_INVERT_WEATHER2_SPIN;
    public static final ModConfigSpec.IntValue MAX_ADDON_VORTICES;
    public static final ModConfigSpec.BooleanValue SQUALL_LINES_ENABLED;
    public static final ModConfigSpec.IntValue SQUALL_LINE_MIN_CELLS;
    public static final ModConfigSpec.IntValue SQUALL_LINE_MAX_THICKNESS_BLOCKS;
    public static final ModConfigSpec.IntValue SQUALL_LINE_MAX_AGE_TICKS;
    public static final ModConfigSpec.BooleanValue ANEMOMETER_ENABLED;

    public static final ModConfigSpec.BooleanValue INFLOW_JETS_ENABLED;
    public static final ModConfigSpec.IntValue INFLOW_JET_LENGTH_BLOCKS;
    public static final ModConfigSpec.IntValue INFLOW_JET_PARTICLES_PER_TICK;

    public static final ModConfigSpec.BooleanValue VISUALS_ENABLED;
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

    public static final ModConfigSpec.BooleanValue IMMERSIVE_THUNDER_ENABLED;
    public static final ModConfigSpec.DoubleValue THUNDER_SECONDS_PER_MILE;
    public static final ModConfigSpec.IntValue THUNDER_MAX_DELAY_TICKS;

    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_ENABLED;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_ROAR_SOUND;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_CAMERA_RUMBLE;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_MAX_CAMERA_ROLL_DEGREES;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_FOV_PRESSURE;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_FOG_PRESSURE;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_CLOSE_DEBRIS;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_INSIDE_CORE_EFFECTS;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_INFLOW_SHADER_OVERLAY;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_EYE_EFFECT;
    public static final ModConfigSpec.BooleanValue TORNADO_IMMERSION_BLOWOUT_AUDIO;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_INSIDE_FOG_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_INFLOW_OVERLAY_OPACITY;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_EYE_CLEAR_RADIUS;
    public static final ModConfigSpec.DoubleValue TORNADO_IMMERSION_BLOWOUT_AUDIO_INTENSITY;
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

    public static final ModConfigSpec.IntValue PERFORMANCE_STORM_SCAN_INTERVAL_TICKS;

    static {
        BUILDER.push("persistence");
        PERSISTENCE_ENABLED = BUILDER.comment("Persists addon storm state such as tornado width, bend, alert cooldowns, and satellite tornadoes through Weather2 storm NBT.")
                .define("enabled", true);
        BUILDER.pop();

        BUILDER.push("dynamics");
        DYNAMICS_ENABLED = BUILDER.comment("Enables unpredictable Weather2 tornado motion.")
                .define("enabled", true);
        TORNADO_WANDER_MAX_DEGREES = BUILDER.comment("Maximum random steering angle added to Weather2 tornado movement.")
                .defineInRange("tornadoWanderMaxDegrees", 38.0D, 0.0D, 120.0D);
        TORNADO_WANDER_SMOOTHING = BUILDER.comment("How quickly tornadoes turn toward the next random steering target.")
                .defineInRange("tornadoWanderSmoothing", 0.045D, 0.001D, 1.0D);
        TORNADO_WANDER_MIN_RETARGET_TICKS = BUILDER.defineInRange("tornadoWanderMinRetargetTicks", 60, 1, 20 * 60 * 10);
        TORNADO_WANDER_MAX_RETARGET_TICKS = BUILDER.defineInRange("tornadoWanderMaxRetargetTicks", 180, 1, 20 * 60 * 10);
        TORNADO_FUNNEL_BEND_ENABLED = BUILDER.comment("Makes Weather2 tornado funnels visibly lean, bend, and curve by offsetting funnel layers.")
                .define("tornadoFunnelBend", true);
        TORNADO_FUNNEL_BEND_MAX_BLOCKS = BUILDER.defineInRange("tornadoFunnelBendMaxBlocks", 22.0D, 0.0D, 80.0D);
        TORNADO_FUNNEL_BEND_SWAY_BLOCKS = BUILDER.defineInRange("tornadoFunnelBendSwayBlocks", 7.0D, 0.0D, 40.0D);
        TORNADO_FUNNEL_BEND_SMOOTHING = BUILDER.defineInRange("tornadoFunnelBendSmoothing", 0.04D, 0.001D, 1.0D);
        TORNADO_FUNNEL_BEND_ANCHOR_FRACTION = BUILDER.defineInRange("funnelBendAnchorFraction", 0.22D, 0.0D, 0.95D);
        TORNADO_FUNNEL_BEND_MAX_HEIGHT_FRACTION = BUILDER.defineInRange("funnelBendMaxHeightFraction", 0.35D, 0.0D, 1.0D);
        NO_PLAYER_AIM_ON_SPAWN = BUILDER.comment("Prevents natural Weather2 tornadoes from steering directly at the nearest player when they form.")
                .define("noPlayerAimOnSpawn", true);
        ENVIRONMENTAL_INTENSITY_ENABLED = BUILDER.comment("Allows tornadoes to reintensify or deintensify based on nearby environment.")
                .define("environmentalIntensity", true);
        ENVIRONMENT_SCAN_INTERVAL_TICKS = BUILDER.defineInRange("environmentScanIntervalTicks", 100, 20, 20 * 60 * 5);
        ENVIRONMENT_REINTENSIFY_THRESHOLD = BUILDER.defineInRange("environmentReintensifyThreshold", 0.68D, 0.0D, 1.0D);
        ENVIRONMENT_DEINTENSIFY_THRESHOLD = BUILDER.defineInRange("environmentDeintensifyThreshold", 0.28D, 0.0D, 1.0D);
        TORNADO_WIND_SPEED_SPIN_ENABLED = BUILDER.comment("Scales Weather2 tornado visual spin speed from the addon tornado windfield mph.")
                .define("windSpeedSpin", true);
        TORNADO_WIND_SPEED_SPIN_MIN = BUILDER.defineInRange("windSpeedSpinMin", 0.055D, 0.0D, 2.0D);
        TORNADO_WIND_SPEED_SPIN_MAX = BUILDER.defineInRange("windSpeedSpinMax", 0.36D, 0.0D, 2.0D);
        TORNADO_WIND_SPEED_SPIN_SMOOTHING = BUILDER.defineInRange("windSpeedSpinSmoothing", 0.12D, 0.001D, 1.0D);
        TORNADO_WIND_SPEED_SPIN_REFERENCE_MIN_MPH = BUILDER.defineInRange("windSpeedSpinReferenceMinMph", 65.0D, 0.0D, 500.0D);
        TORNADO_WIND_SPEED_SPIN_REFERENCE_MAX_MPH = BUILDER.defineInRange("windSpeedSpinReferenceMaxMph", 250.0D, 1.0D, 500.0D);
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
                .defineInRange("maxDamageWidthMiles", 0.0D, 0.0D, MAX_CONFIG_WIDTH_MILES);
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
        DAMAGE_MAX_BLOCKS_PER_STORM_PER_TICK = BUILDER.defineInRange("maxBlocksPerStormPerTick", 18, 0, 1000);
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
        DAMAGE_TREE_DEBARK_MPH = BUILDER.defineInRange("treeDebarkMph", 135.0D, 0.0D, 500.0D);
        DAMAGE_HEAVY_TREE_DEBARK_MPH = BUILDER.defineInRange("heavyTreeDebarkMph", 170.0D, 0.0D, 500.0D);
        DAMAGE_LIGHT_SCOURING_STAGE = BUILDER.comment("Weather2 stage for light scouring. 5 is F1 / stage 1.")
                .defineInRange("lightScouringStage", 6, 4, 9);
        DAMAGE_MEDIUM_SCOURING_STAGE = BUILDER.defineInRange("mediumScouringStage", 7, 4, 9);
        DAMAGE_HEAVY_SCOURING_STAGE = BUILDER.defineInRange("heavyScouringStage", 8, 4, 9);
        BUILDER.pop();

        BUILDER.push("windfield");
        WINDFIELD_ENABLED = BUILDER.comment("Enables modified Rankine-style tornado wind sampling for gameplay systems.")
                .define("enabled", true);
        WINDFIELD_FORMING_MPH = BUILDER.defineInRange("formingMph", 70.0D, 0.0D, 400.0D);
        WINDFIELD_STAGE1_MPH = BUILDER.defineInRange("stage1Mph", 105.0D, 0.0D, 400.0D);
        WINDFIELD_STAGE2_MPH = BUILDER.defineInRange("stage2Mph", 135.0D, 0.0D, 400.0D);
        WINDFIELD_STAGE3_MPH = BUILDER.defineInRange("stage3Mph", 170.0D, 0.0D, 400.0D);
        WINDFIELD_STAGE4_MPH = BUILDER.defineInRange("stage4Mph", 220.0D, 0.0D, 450.0D);
        WINDFIELD_STAGE5_MPH = BUILDER.defineInRange("stage5Mph", 280.0D, 0.0D, 500.0D);
        WINDFIELD_RADIAL_INFLOW_FRACTION = BUILDER.defineInRange("radialInflowFraction", 0.36D, 0.0D, 2.0D);
        WINDFIELD_VERTICAL_LIFT_FRACTION = BUILDER.defineInRange("verticalLiftFraction", 0.22D, 0.0D, 2.0D);
        WINDFIELD_ENTITY_PUSH_SCALE = BUILDER.comment("Converts sampled wind mph into small per-tick entity velocity changes.")
                .defineInRange("entityPushScale", 0.00055D, 0.0D, 0.02D);
        BUILDER.pop();

        BUILDER.push("multi_vortex");
        MULTIVORTEX_ENABLED = BUILDER.define("enabled", true);
        MULTIVORTEX_MAX_COUNT = BUILDER.defineInRange("maxCount", 6, 0, 6);
        MULTIVORTEX_MIN_STAGE = BUILDER.defineInRange("minStage", 6, 4, 9);
        MULTIVORTEX_DAMAGE_MULTIPLIER = BUILDER.defineInRange("damageMultiplier", 1.35D, 1.0D, 5.0D);
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
        SUPERCELL_STRUCTURE_WALL_CLOUD_SCUD_FEED_PARTICLES = BUILDER.comment("Adds upward scud/fractus feeding into the wall cloud from the inflow side.")
                .define("wallCloudScudFeedParticles", true);
        SUPERCELL_STRUCTURE_WALL_CLOUD_RISK_BOOST = BUILDER.comment("Lets persistent rotating wall clouds modestly increase SPC/radar tornado potential without triggering TOR warnings by themselves.")
                .define("wallCloudRiskBoost", true);
        SUPERCELL_STRUCTURE_GUST_FRONT_PARTICLES = BUILDER.define("gustFrontParticles", true);
        SUPERCELL_STRUCTURE_FLANKING_LINE_PARTICLES = BUILDER.define("flankingLineParticles", true);
        SUPERCELL_STRUCTURE_VIRGA_MAMMATUS_PARTICLES = BUILDER.define("virgaMammatusParticles", true);
        SUPERCELL_STRUCTURE_RFD_STRAIGHT_LINE_WIND = BUILDER.comment("Optional entity-push hook for severe rear-flank/forward-flank outflow. Off by default; anemometers still read the wind when windfieldInfluence=true.")
                .define("rfdStraightLineWind", false);
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
        BUILDER.pop();

        BUILDER.push("storm_types");
        WATERSPOUTS_ENABLED = BUILDER.define("waterspouts", true);
        FAIR_WEATHER_WATERSPOUTS_ENABLED = BUILDER.comment("Spawns addon-owned fair-weather waterspout vortices over water near developing Weather2 showers/thunderstorms.")
                .define("fairWeatherWaterspouts", true);
        CYCLONES_ENABLED = BUILDER.define("cyclones", true);
        LANDSPOUTS_ENABLED = BUILDER.comment("Spawns addon-owned non-supercell landspout vortices near developing Weather2 thunderstorms over land.")
                .define("landspouts", true);
        DUST_DEVILS_ENABLED = BUILDER.comment("Spawns small fair-weather dust devils on clear, dry, hot terrain.")
                .define("dustDevils", true);
        LANDSPOUT_SPAWN_CHANCE = BUILDER.defineInRange("landspoutSpawnChance", 0.018D, 0.0D, 1.0D);
        WATERSPOUT_SPAWN_CHANCE = BUILDER.defineInRange("waterspoutSpawnChance", 0.024D, 0.0D, 1.0D);
        DUST_DEVIL_SPAWN_CHANCE = BUILDER.defineInRange("dustDevilSpawnChance", 0.035D, 0.0D, 1.0D);
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
        SATELLITE_TORNADOES_MAX_ACTIVE = BUILDER.defineInRange("maxActive", 1, 0, 4);
        SATELLITE_TORNADOES_MIN_PARENT_STAGE = BUILDER.defineInRange("minParentStage", 7, 4, 9);
        SATELLITE_TORNADOES_MAX_WIND_FRACTION = BUILDER.defineInRange("maxWindFraction", 0.60D, 0.05D, 1.0D);
        SATELLITE_TORNADOES_SPAWN_CHANCE = BUILDER.defineInRange("spawnChance", 0.24D, 0.0D, 1.0D);
        SATELLITE_TORNADOES_DAMAGE_BLOCKS_PER_TICK = BUILDER.defineInRange("damageBlocksPerTick", 6, 0, 128);
        BUILDER.pop();

        BUILDER.push("winter");
        WINTER_ALERTS_ENABLED = BUILDER.define("alerts", true);
        BUILDER.pop();

        BUILDER.push("squall_lines");
        SQUALL_LINES_ENABLED = BUILDER.comment("Detects actual lines of Weather2 thunderstorm cells and creates a straight-line wind hazard.")
                .define("enabled", true);
        SQUALL_LINE_MIN_CELLS = BUILDER.defineInRange("minCells", 3, 2, 16);
        SQUALL_LINE_MAX_THICKNESS_BLOCKS = BUILDER.defineInRange("maxThicknessBlocks", 220, 40, 1200);
        SQUALL_LINE_MAX_AGE_TICKS = BUILDER.defineInRange("maxAgeTicks", 20 * 60 * 8, 20 * 20, 20 * 60 * 30);
        BUILDER.pop();

        BUILDER.push("anemometer");
        ANEMOMETER_ENABLED = BUILDER.define("enabled", true);
        BUILDER.pop();

        BUILDER.push("inflow_jets");
        INFLOW_JETS_ENABLED = BUILDER.define("enabled", true);
        INFLOW_JET_LENGTH_BLOCKS = BUILDER.defineInRange("lengthBlocks", 100, 10, 512);
        INFLOW_JET_PARTICLES_PER_TICK = BUILDER.defineInRange("particlesPerTick", 5, 0, 80);
        BUILDER.pop();

        BUILDER.push("visuals");
        VISUALS_ENABLED = BUILDER.define("enabled", true);
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
        TORNADO_IMMERSION_FOV_PRESSURE = BUILDER.comment("Slightly narrows/pressurizes FOV near the tornado core.")
                .define("fovPressure", true);
        TORNADO_IMMERSION_FOG_PRESSURE = BUILDER.comment("Darkens and tightens fog near violent or close tornado circulations.")
                .define("fogPressure", true);
        TORNADO_IMMERSION_CLOSE_DEBRIS = BUILDER.comment("Adds extra visual-only near-ground dust near the tornado core. Off by default because Weather2 already renders tornado debris.")
                .define("closeDebris", false);
        TORNADO_IMMERSION_INSIDE_CORE_EFFECTS = BUILDER.comment("Enables extra client-only effects when the player is inside a tornado damage path/core.")
                .define("insideCoreEffects", true);
        TORNADO_IMMERSION_INFLOW_SHADER_OVERLAY = BUILDER.comment("Draws a shader-style screen overlay with inflow streaks, dust veil, and tornado-eye ring when inside the tornado.")
                .define("inflowShaderOverlay", true);
        TORNADO_IMMERSION_EYE_EFFECT = BUILDER.comment("Adds a brief calm hollow visual/audio pocket in the innermost tornado core.")
                .define("eyeEffect", true);
        TORNADO_IMMERSION_BLOWOUT_AUDIO = BUILDER.comment("Saturates and wobbles tornado roar audio near/inside the core.")
                .define("blowoutAudio", true);
        TORNADO_IMMERSION_INSIDE_FOG_MULTIPLIER = BUILDER.defineInRange("insideFogMultiplier", 1.0D, 0.0D, 4.0D);
        TORNADO_IMMERSION_INFLOW_OVERLAY_OPACITY = BUILDER.defineInRange("inflowOverlayOpacity", 0.72D, 0.0D, 1.0D);
        TORNADO_IMMERSION_EYE_CLEAR_RADIUS = BUILDER.defineInRange("eyeClearRadius", 0.34D, 0.05D, 0.90D);
        TORNADO_IMMERSION_BLOWOUT_AUDIO_INTENSITY = BUILDER.defineInRange("blowoutAudioIntensity", 1.0D, 0.0D, 3.0D);
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

        BUILDER.push("performance");
        PERFORMANCE_STORM_SCAN_INTERVAL_TICKS = BUILDER.defineInRange("stormScanIntervalTicks", 40, 5, 20 * 60);
        BUILDER.pop();
    }

    static final ModConfigSpec SPEC = BUILDER.build();

    public static double milesToBlocks(double miles) {
        return miles * BLOCKS_PER_MILE;
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
