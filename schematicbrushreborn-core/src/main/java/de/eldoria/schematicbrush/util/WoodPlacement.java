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
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Polygonal2DRegion;
import com.sk89q.worldedit.regions.Region;

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
            Mask surfaceMask,
            float distance) throws IOException {

        if (schematics.isEmpty()
                || distance <= 0
                || !(region instanceof Polygonal2DRegion)) {

            return Collections.emptyList();
        }

        /*
         * Surface positions remain spatially indexed by X/Z.
         */
        Map<Long, BlockVector3> surfacePositions =
            collectSurfacePositions(
                editSession,
                region,
                surfaceMask
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
        float cellSize = distance / (float) Math.sqrt(2.0);

        int cellsWidth = Math.max(1, (int) Math.ceil(width / cellSize));
        int cellsLength = Math.max(1, (int) Math.ceil(length / cellSize));

        SamplePoint[][] grid = new SamplePoint[cellsWidth][cellsLength];
        List<Site> points = new ArrayList<>();

        // Active points are geometric sampling points. They can represent valid or invalid terrain.
        List<SamplePoint> active = new ArrayList<>();

        //Pick the initial position directly from the valid surface map.
        BlockVector3 firstPosition = randomSurfacePosition(surfacePositions);

        if (firstPosition == null) {
            return Collections.emptyList();
        }

        Site firstSite = createSite(firstPosition, schematics);
        SamplePoint firstPoint = new SamplePoint(firstPosition, firstSite);

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
            int index = RANDOM.nextInt(active.size());
            SamplePoint sample =active.get(index);

            boolean added = false;

            for (int attempt = 0;
                 attempt < MAX_TRIES;
                 attempt++) {

                double theta = RANDOM.nextDouble() * Math.PI * 2.0;
                double radius = distance + RANDOM.nextDouble() * distance;

                int x = (int) Math.round(
                        sample.position().x() + radius* Math.cos(theta)
                    );

                int z = (int) Math.round(
                        sample.position().z() + radius * Math.sin(theta)
                    );

                if (!isInsideRegionXZ(x, z, region)) {
                    continue;
                }

                /*
                 * Check the minimum distance against ALL previous
                 * geometric sampling points.
                 *
                 * This includes points on invalid terrain.
                 */
                BlockVector3 candidatePosition = BlockVector3.at(x, sample.position().y(), z);

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
                 */
                BlockVector3 surface = surfacePositions.get(key(x, z));
                Site site = null;

                if (surface != null) {
                    site = createSite(surface, schematics);
                    points.add(site);
                }

                /*
                 * The SamplePoint is inserted regardless of whether
                 * a Site could be created.
                 */
                SamplePoint next = new SamplePoint( candidatePosition, site);
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
                int lastIndex = active.size() - 1;

                active.set(index,active.get(lastIndex));
                active.remove(lastIndex);
            }
        }

        return points;
    }

    /**
     * Collects the highest valid surface position for every X/Z column.
     * The map contains exactly one possible surface position per X/Z.
     */
    private static Map<Long, BlockVector3> collectSurfacePositions(
            EditSession editSession,
            Region region,
            Mask surfaceMask) {

        if (!(region instanceof Polygonal2DRegion polygon)) {
            return Collections.emptyMap();
        }

        if (surfaceMask == null) {
            return Collections.emptyMap();
        }

        Map<Long, BlockVector3> surfacePositions =new HashMap<>();

        int minY = polygon.getMinimumPoint().y();
        int maxY = polygon.getMaximumPoint().y();

        for (var column : polygon.asFlatRegion()) {
            int x = column.x();
            int z = column.z();

            /*
            * Search from the top of the selection downward.
            * The first valid block with air above it is the highest usable surface for this X/Z column.
            */
            for (int y = maxY; y >= minY; y--) {
                BlockVector3 position = BlockVector3.at(x, y, z);
                if (!surfaceMask.test(position)) {
                    continue;
                }

                BlockVector3 above = BlockVector3.at(x, y + 1, z);

                if (!editSession.getBlock(above).getBlockType().getMaterial().isAir()) {
                    continue;
                }

                surfacePositions.put(key(x, z), position);
                break;
            }
        }

        return surfacePositions;
    }

    public static boolean isValidSurfacePosition(
            EditSession editSession,
            BlockVector3 position,
            Mask surfaceMask) {
        if (surfaceMask == null) {
            return false;
        }

        return surfaceMask.test(position)
                && editSession.getBlock(position.add(0, 1, 0)).getBlockType().getMaterial().isAir();
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
            Mask surfaceMask) {
        if (!isValidSurfacePosition(editSession, position, surfaceMask)) {
            return false;
        }

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }

                BlockVector3 neighbor = position.add(dx, 0, dz);
                if (isValidSurfacePosition(editSession, neighbor, surfaceMask)
                    || isValidSurfacePosition(editSession, neighbor.add(0, 1, 0), surfaceMask)
                    || isValidSurfacePosition(editSession, neighbor.add(0, -1, 0), surfaceMask)) {
                    continue;
                }

                return false;
            }
        }

        return true;
    }

    public static List<Site> replaceTrees(
        EditSession editSession,
        Region region,
        Set<Schematic> schematics,
            Mask mask) throws IOException {

        if (schematics.isEmpty()
                || !(region instanceof Polygonal2DRegion)
                || mask == null) {

            return Collections.emptyList();
        }

        List<Site> sites = new ArrayList<>();

        for (BlockVector3 position : region) {
            if (!mask.test(position)) {
                continue;
            }

            sites.add(createSite( position, schematics));
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

        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();

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

        grid[xIndex][zIndex] = point;
    }

    private static Site createSite(
            BlockVector3 position,
            Set<Schematic> schematics)
            throws IOException {

        Schematic schematic = randomSchematic(schematics);
        Clipboard clipboard = schematic.loadSchematic();

        return new Site(
                position,
                clipboard
        );
    }

    private static Schematic randomSchematic(
            Set<Schematic> schematics) {

        int index = RANDOM.nextInt(schematics.size());
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
     * 'site' is null when the position is not a valid surface.
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