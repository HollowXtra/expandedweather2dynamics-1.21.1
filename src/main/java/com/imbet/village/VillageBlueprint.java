package com.imbet.village;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a village builds, as arithmetic.
 *
 * <p>A building is a list of pieces in its own space: x runs right along the front, y runs up, z runs
 * from the front face inward. Small numbers (a house is seven by seven), but a piece list is the one
 * honest way to say "the builder really builds" - a blueprint that is checked can be wrong in ways a
 * textured mesh cannot, and every one of those ways is testable here: nothing outside the box, nothing
 * overlapping, no wall standing on air, a roof over the whole footprint, one door on the front, windows
 * above head height, and every fixture inside the walls where a villager can reach it.
 *
 * <p>Minecraft-free on purpose. The goal turns these pieces into blocks and items, and the harness runs
 * the same list through the same arithmetic without the game - rotation included, which is why a house
 * built facing north and the same house facing west are the same building by construction rather than by
 * hope.
 *
 * <p>Three of the five buildings a village knows how to build exist to be *job sites*: the station holds a
 * survey station, the workshop a builders' bench and the timber yard a sawmill, and those three blocks are
 * exactly what the three professions claim. A village that has built them therefore staffs its own jobs
 * instead of waiting for a player to put a workstation down.
 */
public final class VillageBlueprint {
    /** North, east, south, west: which way the front of the building looks. */
    public static final int NORTH = 0;
    public static final int EAST = 1;
    public static final int SOUTH = 2;
    public static final int WEST = 3;

    /**
     * What a piece is for, rather than which wood it happens to be.
     *
     * <p>Which block a material becomes is decided at build time from what the village actually has, so a
     * plan written once builds in oak, spruce or cherry without the plan knowing the difference.
     */
    public enum Material {
        /** Foundations, and anything that has to hold weight up. */
        STONE,
        /** Walls, floors and the roof. */
        PLANKS,
        /** Corners and framing: a log's grain runs up, so it belongs at a corner. */
        LOG,
        /** Windows. */
        GLASS,
        /** The way in. Two cells tall for the price of one door. */
        DOOR(2),
        /** Light. Stands on something solid. */
        TORCH,
        /** Somewhere to sleep. Two cells deep, head away from the door. */
        BED(2),
        /** Somewhere to keep things, which is how a village restocks. */
        CHEST,
        /** The surveyor's own workstation: what makes a station building a station. */
        STATION,
        /** The builder's own workstation: what makes a workshop a workshop. */
        BENCH,
        /** The lumberjack's own workstation: what makes a timber yard a timber yard. */
        SAWMILL,
        /** The meteorologist's own workstation: what makes a weather station a weather station. */
        WEATHER_STATION;

        private final int cells;

        Material() {
            this(1);
        }

        Material(int cells) {
            this.cells = cells;
        }

        /** How many blocks one piece of this material fills. */
        public int cells() {
            return cells;
        }
    }

    /** One piece, in the building's own space: {@code z == 0} is the front face, {@code y == 0} the ground. */
    public record Piece(int x, int y, int z, Material material) {
    }

    /** One block to place, in the building's space, with the offsets already rotated into the world. */
    public record Step(int dx, int dy, int dz, Material material) {
    }

    /** A whole building. */
    public record Building(String name, int width, int depth, int height, List<Piece> pieces) {
        /** What one of this costs, by material: one piece, one item - a door is one door. */
        public Map<Material, Integer> cost() {
            Map<Material, Integer> cost = new LinkedHashMap<>();
            for (Piece piece : pieces) {
                cost.merge(piece.material(), 1, Integer::sum);
            }
            return cost;
        }

        /** The pieces of the lowest layer, which is what a village has to be able to lay before it starts. */
        public Map<Material, Integer> foundationCost() {
            Map<Material, Integer> cost = new LinkedHashMap<>();
            for (Piece piece : pieces) {
                if (piece.y() == 0) {
                    cost.merge(piece.material(), 1, Integer::sum);
                }
            }
            return cost;
        }

        /** How many blocks the finished building is. */
        public int blocks() {
            int blocks = 0;
            for (Piece piece : pieces) {
                blocks += piece.material().cells();
            }
            return blocks;
        }

        /**
         * The build order: bottom up, and within a layer from the front inward, so nothing is ever placed
         * on a block that is not there yet and the builder walks forward into its own work.
         */
        public List<Piece> order() {
            List<Piece> order = new ArrayList<>(pieces);
            order.sort((a, b) -> a.y() != b.y() ? Integer.compare(a.y(), b.y())
                    : a.z() != b.z() ? Integer.compare(a.z(), b.z()) : Integer.compare(a.x(), b.x()));
            return order;
        }

        /**
         * The cells that say this building is standing: the door and the middle of the roof. A village
         * checks them to know that a work it recorded is still there, and to leave a ruin alone rather
         * than rebuild on top of somebody else's salvage.
         */
        public List<Piece> signature() {
            List<Piece> marks = new ArrayList<>();
            for (Piece piece : pieces) {
                if (piece.material() == Material.DOOR) {
                    marks.add(piece);
                }
            }
            marks.add(new Piece(width / 2, height - 1, depth / 2, Material.PLANKS));
            return marks;
        }

        /** The pieces rotated to face {@code facing}, in build order, with the offsets normalised to 0. */
        public List<Step> placedSteps(int facing) {
            List<Step> steps = new ArrayList<>();
            int minX = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            for (Piece piece : order()) {
                int[] offset = VillageBlueprint.offset(piece.x(), piece.z(), facing);
                steps.add(new Step(offset[0], piece.y(), offset[1], piece.material()));
                minX = Math.min(minX, offset[0]);
                minZ = Math.min(minZ, offset[1]);
            }
            List<Step> normalised = new ArrayList<>(steps.size());
            for (Step step : steps) {
                normalised.add(new Step(step.dx() - minX, step.dy(), step.dz() - minZ, step.material()));
            }
            return normalised;
        }

        /** How wide and deep the footprint is once rotated to face {@code facing}. */
        public int[] placedSize(int facing) {
            int maxX = 0;
            int maxZ = 0;
            int minX = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            for (Piece piece : pieces) {
                int[] offset = VillageBlueprint.offset(piece.x(), piece.z(), facing);
                minX = Math.min(minX, offset[0]);
                maxX = Math.max(maxX, offset[0]);
                minZ = Math.min(minZ, offset[1]);
                maxZ = Math.max(maxZ, offset[1]);
            }
            return new int[] {maxX - minX + 1, maxZ - minZ + 1};
        }

        /** The door's own cell, rotated and normalised the way {@link #placedSteps(int)} normalises. */
        public int[] doorAt(int facing) {
            List<Step> steps = placedSteps(facing);
            for (Step step : steps) {
                if (step.material() == Material.DOOR) {
                    return new int[] {step.dx(), step.dy(), step.dz()};
                }
            }
            return new int[] {0, 0, 0};
        }
    }

    private VillageBlueprint() {
    }

    /**
     * The village house: seven by seven, a stone ring for a foundation, logs at the corners, one door at
     * the front, three windows, a torch inside, and a bed with its head away from the door.
     */
    public static Building house() {
        int width = 7;
        int depth = 7;
        int height = 5;
        List<Piece> pieces = new ArrayList<>();
        addFoundationRing(pieces, width, depth);
        addWalls(pieces, width, depth, height, 3);
        addRoof(pieces, width, depth, height);
        pieces.add(new Piece(3, 1, 3, Material.TORCH));
        pieces.add(new Piece(1, 1, 4, Material.BED));
        return new Building("house", width, depth, height, List.copyOf(pieces));
    }

    /**
     * The village office: nine by seven, which is the same walls with a longer room, and fitted out with a
     * survey station, a chest to keep the village's stock in and two torches over the desk side.
     */
    public static Building office() {
        int width = 9;
        int depth = 7;
        int height = 5;
        List<Piece> pieces = new ArrayList<>();
        addFoundationRing(pieces, width, depth);
        addWalls(pieces, width, depth, height, 4);
        addRoof(pieces, width, depth, height);
        pieces.add(new Piece(4, 1, 2, Material.STATION));
        pieces.add(new Piece(7, 1, 5, Material.CHEST));
        pieces.add(new Piece(2, 1, 3, Material.TORCH));
        pieces.add(new Piece(6, 1, 3, Material.TORCH));
        return new Building("office", width, depth, height, List.copyOf(pieces));
    }

    /**
     * The surveyor's station: seven by five, which is a small building on purpose - one desk, one place
     * to keep the records, and a light, so a village that grew a surveyor has somewhere to put one.
     */
    public static Building station() {
        int width = 7;
        int depth = 5;
        int height = 4;
        List<Piece> pieces = new ArrayList<>();
        addFoundationRing(pieces, width, depth);
        addWalls(pieces, width, depth, height, 3);
        addRoof(pieces, width, depth, height);
        pieces.add(new Piece(3, 1, 2, Material.STATION));
        pieces.add(new Piece(1, 1, 2, Material.CHEST));
        pieces.add(new Piece(5, 1, 2, Material.TORCH));
        return new Building("station", width, depth, height, List.copyOf(pieces));
    }

    /**
     * The builder's workshop: seven by seven, the bench along the middle of the room with a chest on each
     * side of it. The chests matter as much as the bench - they sit where the builder already looks for
     * materials, so a village's own workshop is the depot it rebuilds out of.
     */
    public static Building workshop() {
        int width = 7;
        int depth = 7;
        int height = 4;
        List<Piece> pieces = new ArrayList<>();
        addFoundationRing(pieces, width, depth);
        addWalls(pieces, width, depth, height, 3);
        addRoof(pieces, width, depth, height);
        pieces.add(new Piece(3, 1, 2, Material.BENCH));
        pieces.add(new Piece(1, 1, 2, Material.CHEST));
        pieces.add(new Piece(5, 1, 2, Material.CHEST));
        pieces.add(new Piece(3, 1, 5, Material.TORCH));
        return new Building("workshop", width, depth, height, List.copyOf(pieces));
    }

    /**
     * The lumberjack's timber yard: seven by five, one mill, and a chest on each side of it. The chests are
     * the point of the building - a lumberjack fells a trunk and then has to put the logs somewhere, and a
     * yard whose chests are within its own walls is the store it stocks rather than a promise of one.
     */
    public static Building timberYard() {
        int width = 7;
        int depth = 5;
        int height = 4;
        List<Piece> pieces = new ArrayList<>();
        addFoundationRing(pieces, width, depth);
        addWalls(pieces, width, depth, height, 3);
        addRoof(pieces, width, depth, height);
        pieces.add(new Piece(3, 1, 2, Material.SAWMILL));
        pieces.add(new Piece(1, 1, 2, Material.CHEST));
        pieces.add(new Piece(5, 1, 2, Material.CHEST));
        pieces.add(new Piece(3, 1, 3, Material.TORCH));
        return new Building("timber_yard", width, depth, height, List.copyOf(pieces));
    }

    /**
     * The meteorologist's weather station: a small office with the station itself on the desk side and a chest
     * for the village's records beside it.
     *
     * <p>It is the smallest building a village knows how to build, on purpose. A weather station is one instrument,
     * one person and a place to write things down, and a village that built a hall for it would be building a hall
     * for a job site - which is the mistake the station and the workshop are already sized against.
     */
    public static Building weatherStation() {
        int width = 7;
        int depth = 5;
        int height = 4;
        List<Piece> pieces = new ArrayList<>();
        addFoundationRing(pieces, width, depth);
        addWalls(pieces, width, depth, height, 3);
        addRoof(pieces, width, depth, height);
        pieces.add(new Piece(3, 1, 2, Material.WEATHER_STATION));
        pieces.add(new Piece(1, 1, 2, Material.CHEST));
        pieces.add(new Piece(5, 1, 2, Material.CHEST));
        pieces.add(new Piece(3, 1, 3, Material.TORCH));
        return new Building("weather_station", width, depth, height, List.copyOf(pieces));
    }

    /**
     * The order a village builds in: somewhere to live, somewhere to work, then a station building for
     * each of the jobs - a survey station for the surveyor, a workshop for the builder, a timber yard for
     * the lumberjack and a weather station for the meteorologist - so the village supplies its own job sites
     * instead of waiting for a player to put one down.
     *
     * <p>The weather station is last because it is the only one of the four that is not needed to build the
     * other three: a village can house, feed and repair itself without ever looking at the sky, and it only
     * gets a meteorologist once it has a reason to write down what the sky did.
     */
    public static List<Building> villagePlan() {
        return List.of(house(), office(), station(), workshop(), timberYard(), weatherStation());
    }

    /**
     * The perimeter at ground level: the foundation is a ring rather than a slab, because the ground inside
     * a village house is the ground and the village should not spend a stack of stone flooring it.
     */
    private static void addFoundationRing(List<Piece> pieces, int width, int depth) {
        for (int x = 0; x < width; x++) {
            pieces.add(new Piece(x, 0, 0, Material.STONE));
            pieces.add(new Piece(x, 0, depth - 1, Material.STONE));
        }
        for (int z = 1; z < depth - 1; z++) {
            pieces.add(new Piece(0, 0, z, Material.STONE));
            pieces.add(new Piece(width - 1, 0, z, Material.STONE));
        }
    }

    /**
     * Three layers of wall: logs at the corners, planks between them, a door two cells tall in the middle
     * of the front, and one window in the middle of each other wall at head height.
     */
    private static void addWalls(List<Piece> pieces, int width, int depth, int height, int doorX) {
        int topWall = height - 2;
        for (int y = 1; y <= topWall; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    boolean edge = x == 0 || z == 0 || x == width - 1 || z == depth - 1;
                    if (!edge) {
                        continue;
                    }
                    boolean corner = (x == 0 || x == width - 1) && (z == 0 || z == depth - 1);
                    if (corner) {
                        pieces.add(new Piece(x, y, z, Material.LOG));
                        continue;
                    }
                    if (x == doorX && z == 0 && y == 1) {
                        // The door is one piece covering its own cell and the one above it.
                        pieces.add(new Piece(x, y, z, Material.DOOR));
                        continue;
                    }
                    if (x == doorX && z == 0 && y == 2) {
                        continue;
                    }
                    if (y == 2 && isWindow(x, z, width, depth, doorX)) {
                        pieces.add(new Piece(x, y, z, Material.GLASS));
                        continue;
                    }
                    pieces.add(new Piece(x, y, z, Material.PLANKS));
                }
            }
        }
    }

    /** One window in the middle of the back wall and of each side wall, at the height a villager's eye is. */
    private static boolean isWindow(int x, int z, int width, int depth, int doorX) {
        if (z == depth - 1 && x == doorX) {
            return true;
        }
        if (x == 0 && z == depth / 2) {
            return true;
        }
        return x == width - 1 && z == depth / 2;
    }

    /** A flat plank roof over the whole footprint, so the inside is inside. */
    private static void addRoof(List<Piece> pieces, int width, int depth, int height) {
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                pieces.add(new Piece(x, height - 1, z, Material.PLANKS));
            }
        }
    }

    /**
     * Where a piece on the front-left corner of a building facing {@code facing} lands, in world offsets.
     *
     * <p>The building's own z runs inward from the front, so the door faces {@code facing} and the room
     * extends the other way. Turning the building is then a rotation of one vector, and a house built
     * facing north and the same house facing west cannot disagree about their own shape.
     */
    public static int[] offset(int x, int z, int facing) {
        int[] inward = inward(facing);
        // Side runs along local +x: inward turned a quarter turn, which is what keeps the building's own
        // handedness under rotation instead of mirroring it.
        int sideX = inward[1];
        int sideZ = -inward[0];
        return new int[] {x * sideX + z * inward[0], x * sideZ + z * inward[1]};
    }

    /** The unit vector pointing into the building from its front face. */
    public static int[] inward(int facing) {
        return switch (Math.floorMod(facing, 4)) {
            case NORTH -> new int[] {0, 1};
            case EAST -> new int[] {-1, 0};
            case SOUTH -> new int[] {0, -1};
            default -> new int[] {1, 0};
        };
    }

    /** The unit vector the building's front face looks out along. */
    public static int[] outward(int facing) {
        int[] inward = inward(facing);
        return new int[] {-inward[0], -inward[1]};
    }

    /**
     * Which way a building at this offset should face, so its door looks at whatever it is being aimed at -
     * the bench, in practice, so a village's houses open onto its own square instead of onto a hillside.
     *
     * <p>Ties keep the first of the four, so the same two points always give the same answer.
     */
    public static int facingToward(int dx, int dz) {
        int facing = NORTH;
        int best = Integer.MIN_VALUE;
        for (int candidate = NORTH; candidate <= WEST; candidate++) {
            int[] out = outward(candidate);
            int dot = out[0] * dx + out[1] * dz;
            if (dot > best) {
                best = dot;
                facing = candidate;
            }
        }
        return facing;
    }
}
