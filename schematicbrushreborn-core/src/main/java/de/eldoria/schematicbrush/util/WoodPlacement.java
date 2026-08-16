package de.eldoria.schematicbrush.util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.bukkit.entity.Player;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Polygonal2DRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.registry.LegacyMapper;

import de.eldoria.schematicbrush.brush.config.selector.PathSelector;
import de.eldoria.schematicbrush.schematics.Schematic;
import de.eldoria.schematicbrush.schematics.SchematicRegistry;

public final class WoodPlacement {

    private static final Random RANDOM = new Random();

    private static final int MAX_TRIES = 30;

    private WoodPlacement() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static Set<Schematic> selectSchematics(
            Player player,
            SchematicRegistry registry,
            String path) {

        var selector = new PathSelector(path, "*");
        return selector.select(player, registry);
    }

    /**
     * Samples tree positions using a Poisson-disc-like distribution.
     *
     * Important:
     *
     * The geometric sampling is completely independent from whether
     * a position contains a valid surface block.
     *
     * This means that invalid areas do NOT stop the sampling process.
     * They simply produce SamplePoints without a Site.
     *
     * The minimum distance is still respected between ALL SamplePoints,
     * including points on invalid terrain.
     */
    public static List<Site> sampleTrees(
            EditSession editSession,
            Region region,
            Set<Schematic> schematics,
            List<String> validSurfaceBlocks,
            float distance) throws IOException {

        if (schematics.isEmpty()
                || distance <= 0
                || !(region instanceof Polygonal2DRegion)) {

            return Collections.emptyList();
        }

        /*
         * Surface positions remain spatially indexed by X/Z.
         *
         * There is intentionally NO fallback to the nearest surface.
         *
         * A candidate either has a valid surface at exactly this X/Z
         * position or it does not.
         */
        Map<Long, BlockVector3> surfacePositions =
                collectSurfacePositions(
                        editSession,
                        region,
                        validSurfaceBlocks
                );

        if (surfacePositions.isEmpty()) {
            return Collections.emptyList();
        }

        int width =
                region.getMaximumPoint().x()
                        - region.getMinimumPoint().x()
                        + 1;

        int length =
                region.getMaximumPoint().z()
                        - region.getMinimumPoint().z()
                        + 1;

        /*
         * Poisson-disc grid.
         *
         * distance / sqrt(2) allows a 3x3 neighborhood to contain
         * all possible points that could be closer than 'distance'.
         */
        float cellSize =
                distance / (float) Math.sqrt(2.0);

        int cellsWidth =
                Math.max(
                        1,
                        (int) Math.ceil(width / cellSize)
                );

        int cellsLength =
                Math.max(
                        1,
                        (int) Math.ceil(length / cellSize)
                );

        /*
         * The grid now contains SamplePoints instead of Sites.
         *
         * This is important because invalid terrain still needs to
         * participate in the geometric sampling.
         */
        SamplePoint[][] grid =
                new SamplePoint[cellsWidth][cellsLength];

        /*
         * Only actual trees are returned to the caller.
         */
        List<Site> points =
                new ArrayList<>();

        /*
         * Active points are geometric sampling points.
         *
         * They can represent valid or invalid terrain.
         */
        List<SamplePoint> active =
                new ArrayList<>();

        /*
         * Pick the initial position directly from the valid surface map.
         *
         * The first position is guaranteed to be placeable.
         */
        BlockVector3 firstPosition =
                randomSurfacePosition(surfacePositions);

        if (firstPosition == null) {
            return Collections.emptyList();
        }

        Site firstSite =
                createSite(
                        firstPosition,
                        schematics
                );

        SamplePoint firstPoint =
                new SamplePoint(
                        firstPosition,
                        firstSite
                );

        insertPoint(
                grid,
                region.getMinimumPoint(),
                cellSize,
                firstPoint
        );

        points.add(firstSite);
        active.add(firstPoint);

        /*
         * Poisson-disc sampling.
         */
        while (!active.isEmpty()) {

            int index =
                    RANDOM.nextInt(active.size());

            SamplePoint sample =
                    active.get(index);

            boolean added =
                    false;

            for (int attempt = 0;
                 attempt < MAX_TRIES;
                 attempt++) {

                /*
                 * Generate a new point around the active point.
                 *
                 * The radius is between:
                 *
                 *     distance
                 *     2 * distance
                 */
                double theta =
                        RANDOM.nextDouble()
                                * Math.PI
                                * 2.0;

                double radius =
                        distance
                                + RANDOM.nextDouble()
                                * distance;

                int x =
                        (int) Math.round(
                                sample.position().x()
                                        + radius
                                        * Math.cos(theta)
                        );

                int z =
                        (int) Math.round(
                                sample.position().z()
                                        + radius
                                        * Math.sin(theta)
                        );

                /*
                 * First make sure the geometric candidate is inside
                 * the selection's X/Z bounds.
                 *
                 * We deliberately do NOT require a valid surface here.
                 */
                if (!isInsideRegionXZ(
                        x,
                        z,
                        region)) {

                    continue;
                }

                /*
                 * Check the minimum distance against ALL previous
                 * geometric sampling points.
                 *
                 * This includes points on invalid terrain.
                 */
                BlockVector3 candidatePosition =
                        BlockVector3.at(
                                x,
                                sample.position().y(),
                                z
                        );

                if (!isValidPoint(
                        candidatePosition,
                        region.getMinimumPoint(),
                        cellSize,
                        grid,
                        distance)) {

                    continue;
                }

                /*
                 * Now determine whether the candidate can actually
                 * receive a tree.
                 *
                 * IMPORTANT:
                 *
                 * This lookup does NOT move the candidate to another
                 * position. It only checks the exact X/Z coordinate.
                 */
                BlockVector3 surface =
                        surfacePositions.get(
                                key(x, z)
                        );

                Site site =
                        null;

                if (surface != null) {

                    site =
                            createSite(
                                    surface,
                                    schematics
                            );

                    points.add(site);
                }

                /*
                 * The SamplePoint is inserted regardless of whether
                 * a Site could be created.
                 *
                 * This is the core change that allows the algorithm
                 * to continue through invalid areas.
                 */
                SamplePoint next =
                        new SamplePoint(
                                candidatePosition,
                                site
                        );

                insertPoint(
                        grid,
                        region.getMinimumPoint(),
                        cellSize,
                        next
                );

                active.add(next);

                added = true;
                break;
            }

            if (!added) {

                /*
                 * The order of active points does not matter.
                 *
                 * Swap with the last element to make removal O(1).
                 */
                int lastIndex =
                        active.size() - 1;

                active.set(
                        index,
                        active.get(lastIndex)
                );

                active.remove(lastIndex);
            }
        }

        return points;
    }

    /**
     * Collects the highest valid surface position for every X/Z column.
     *
     * The map contains exactly one possible surface position per X/Z.
     */
    private static Map<Long, BlockVector3> collectSurfacePositions(
        EditSession editSession,
        Region region,
        List<String> validSurfaceBlocks) {

    if (!(region instanceof Polygonal2DRegion polygon)) {
        return Collections.emptyMap();
    }

    Map<Long, BlockVector3> surfacePositions =
            new HashMap<>();

    BlockVector3 minimum =
            polygon.getMinimumPoint();

    BlockVector3 maximum =
            polygon.getMaximumPoint();

    int minY = minimum.y();
    int maxY = maximum.y();

    /*
     * Polygonal2DRegion.asFlatRegion() iterates only over the X/Z
     * columns of the polygon instead of every block in the 3D region.
     *
     * This is the important performance optimization.
     */
    for (var column : polygon.asFlatRegion()) {

        int x = column.x();
        int z = column.z();

        /*
         * Search from the top of the selection downward.
         *
         * The first valid block with air above it is the surface
         * for this X/Z column.
         */
        for (int y = maxY; y >= minY; y--) {

            BlockVector3 position =
                    BlockVector3.at(
                            x,
                            y,
                            z
                    );

            BlockType blockType =
                    editSession
                            .getBlock(position)
                            .getBlockType();

            if (!matchesSurface(
                    blockType,
                    validSurfaceBlocks)) {

                continue;
            }

            BlockVector3 above =
                    BlockVector3.at(
                            x,
                            y + 1,
                            z
                    );

            if (!editSession
                    .getBlock(above)
                    .getBlockType()
                    .getMaterial()
                    .isAir()) {

                continue;
            }

            /*
             * We found the highest valid surface for this X/Z.
             * Nothing further down the column needs to be checked.
             */
            surfacePositions.put(
                    key(x, z),
                    position
            );

            break;
        }
    }

    return surfacePositions;
}

    public static boolean isValidSurfacePosition(
            EditSession editSession,
            BlockVector3 position,
            List<String> surfaceBlocks) {

        BlockVector3 above =
                position.add(0, 1, 0);

        if (!editSession
                .getBlock(above)
                .getBlockType()
                .getMaterial()
                .isAir()) {

            return false;
        }

        return matchesSurface(
                editSession
                        .getBlock(position)
                        .getBlockType(),
                surfaceBlocks
        );
    }

    /**
     * Ensures the center position is valid and all eight horizontal
     * neighbors are valid surfaces as well.
     *
     * For each neighbor:
     *
     * 1. same Y
     * 2. one block above
     * 3. one block below
     */
    public static boolean hasAdjacentValidSurface(
            EditSession editSession,
            BlockVector3 position,
            List<String> surfaceBlocks) {

        if (!isValidSurfacePosition(
                editSession,
                position,
                surfaceBlocks)) {

            return false;
        }

        for (int dx = -1; dx <= 1; dx++) {

            for (int dz = -1; dz <= 1; dz++) {

                if (dx == 0 && dz == 0) {
                    continue;
                }

                BlockVector3 neighbor =
                        position.add(dx, 0, dz);

                if (isValidSurfacePosition(
                        editSession,
                        neighbor,
                        surfaceBlocks)) {

                    continue;
                }

                if (isValidSurfacePosition(
                        editSession,
                        neighbor.add(0, 1, 0),
                        surfaceBlocks)) {

                    continue;
                }

                if (isValidSurfacePosition(
                        editSession,
                        neighbor.add(0, -1, 0),
                        surfaceBlocks)) {

                    continue;
                }

                return false;
            }
        }

        return true;
    }

    private static boolean matchesSurface(
            BlockType blockType,
            List<String> surfaceBlocks) {

        if (surfaceBlocks == null
                || surfaceBlocks.isEmpty()) {

            return false;
        }

        String id =
                blockType.id().toLowerCase();

        String simple =
                id.contains(":")
                        ? id.substring(
                                id.indexOf(':') + 1
                        )
                        : id;

        for (String block : surfaceBlocks) {

            if (block == null || block.isEmpty()) {
                continue;
            }

            String normalized =
                    block.toLowerCase();

            if (isLegacyId(normalized)) {

                BlockState legacyState =
                        legacyBlockState(normalized);

                if (legacyState != null
                        && legacyState
                                .getBlockType()
                                .id()
                                .equals(id)) {

                    return true;
                }
            }

            if (simple.equals(normalized)
                    || id.equals(normalized)
                    || id.endsWith(
                            ":" + normalized)) {

                return true;
            }
        }

        return false;
    }

    private static boolean isLegacyId(
            String block) {

        return block.matches(
                "^[0-9]+(:[0-9]+)?$"
        );
    }

    private static BlockState legacyBlockState(
            String legacyId) {

        try {

            int id =
                    legacyId.contains(":")
                            ? Integer.parseInt(
                                    legacyId.substring(
                                            0,
                                            legacyId.indexOf(':')
                                    )
                            )
                            : Integer.parseInt(
                                    legacyId
                            );

            int data = 0;

            if (legacyId.contains(":")) {

                data =
                        Integer.parseInt(
                                legacyId.substring(
                                        legacyId.indexOf(':') + 1
                                )
                        );
            }

            return LegacyMapper
                    .getInstance()
                    .getBlockFromLegacy(
                            id,
                            data
                    );

        } catch (NumberFormatException e) {

            return null;
        }
    }

    public static List<Site> replaceTrees(
            EditSession editSession,
            Region region,
            Set<Schematic> schematics,
            List<String> validBlocks) throws IOException {

        if (schematics.isEmpty()
                || !(region instanceof Polygonal2DRegion)) {

            return Collections.emptyList();
        }

        List<Site> sites =
                new ArrayList<>();

        /*
         * No Clipboard cache here.
         *
         * FAWE's DiskOptimizedClipboard must not be treated as a
         * permanently reusable Clipboard instance.
         */
        for (BlockVector3 position : region) {

            BlockType blockType =
                    editSession
                            .getBlock(position)
                            .getBlockType();

            if (!matchesSurface(
                    blockType,
                    validBlocks)) {

                continue;
            }

            sites.add(
                    createSite(
                            position,
                            schematics
                    )
            );
        }

        return sites;
    }

    /**
     * Checks only whether the X/Z coordinate is inside the selection.
     *
     * The Y coordinate is intentionally ignored because the sampling
     * itself happens in the horizontal plane.
     */
    private static boolean isInsideRegionXZ(
            int x,
            int z,
            Region region) {

        BlockVector3 min =
                region.getMinimumPoint();

        BlockVector3 max =
                region.getMaximumPoint();

        return x >= min.x()
                && x <= max.x()
                && z >= min.z()
                && z <= max.z();
    }

    /**
     * Checks the minimum distance between the candidate and all nearby
     * SamplePoints.
     *
     * SamplePoints can represent invalid terrain as well as valid
     * tree positions.
     */
    private static boolean isValidPoint(
            BlockVector3 position,
            BlockVector3 minimumPoint,
            float cellSize,
            SamplePoint[][] grid,
            float distance) {

        int xIndex =
                (int) Math.floor(
                        (position.x()
                                - minimumPoint.x())
                                / cellSize
                );

        int zIndex =
                (int) Math.floor(
                        (position.z()
                                - minimumPoint.z())
                                / cellSize
                );

        if (xIndex < 0
                || zIndex < 0
                || xIndex >= grid.length
                || zIndex >= grid[0].length) {

            return false;
        }

        int i0 =
                Math.max(
                        xIndex - 1,
                        0
                );

        int i1 =
                Math.min(
                        xIndex + 1,
                        grid.length - 1
                );

        int j0 =
                Math.max(
                        zIndex - 1,
                        0
                );

        int j1 =
                Math.min(
                        zIndex + 1,
                        grid[0].length - 1
                );

        double distanceSq =
                (double) distance * distance;

        for (int i = i0; i <= i1; i++) {

            for (int j = j0; j <= j1; j++) {

                SamplePoint neighbor =
                        grid[i][j];

                if (neighbor == null) {
                    continue;
                }

                long dx =
                        (long) neighbor.position().x()
                                - position.x();

                long dz =
                        (long) neighbor.position().z()
                                - position.z();

                /*
                 * Compare squared distances.
                 *
                 * Math.sqrt() is deliberately avoided.
                 */
                if (dx * dx + dz * dz < distanceSq) {
                    return false;
                }
            }
        }

        return true;
    }

    private static void insertPoint(
            SamplePoint[][] grid,
            BlockVector3 minimumPoint,
            float cellSize,
            SamplePoint point) {

        int xIndex =
                (int) Math.floor(
                        (point.position().x()
                                - minimumPoint.x())
                                / cellSize
                );

        int zIndex =
                (int) Math.floor(
                        (point.position().z()
                                - minimumPoint.z())
                                / cellSize
                );

        if (xIndex < 0
                || zIndex < 0
                || xIndex >= grid.length
                || zIndex >= grid[0].length) {

            return;
        }

        grid[xIndex][zIndex] =
                point;
    }

    private static Site createSite(
            BlockVector3 position,
            Set<Schematic> schematics)
            throws IOException {

        Schematic schematic =
                randomSchematic(schematics);

        Clipboard clipboard =
                schematic.loadSchematic();

        return new Site(
                position,
                clipboard
        );
    }

    private static Schematic randomSchematic(
            Set<Schematic> schematics) {

        int index =
                RANDOM.nextInt(
                        schematics.size()
                );

        int i = 0;

        for (Schematic schematic : schematics) {

            if (i == index) {
                return schematic;
            }

            i++;
        }

        return schematics.iterator().next();
    }

    private static BlockVector3 randomSurfacePosition(
            Map<Long, BlockVector3> surfacePositions) {

        if (surfacePositions.isEmpty()) {
            return null;
        }

        int index =
                RANDOM.nextInt(
                        surfacePositions.size()
                );

        int i = 0;

        for (BlockVector3 position :
                surfacePositions.values()) {

            if (i == index) {
                return position;
            }

            i++;
        }

        return null;
    }

    private static long key(
            int x,
            int z) {

        return ((long) x << 32)
                | (z & 0xffffffffL);
    }

    /**
     * A geometric sampling point.
     *
     * 'site' is null when the position is not a valid surface.
     *
     * Such points still participate in the Poisson-disc distribution.
     */
    private record SamplePoint(
            BlockVector3 position,
            Site site) {
    }

    /**
     * A real tree that can be placed by the caller.
     */
    public record Site(
            BlockVector3 position,
            Clipboard clipboard) {
    }
}