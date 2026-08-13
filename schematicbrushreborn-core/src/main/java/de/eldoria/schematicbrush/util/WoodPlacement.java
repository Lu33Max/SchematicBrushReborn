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

    public static Set<Schematic> selectSchematics(Player player, SchematicRegistry registry, String path) {
        var selector = new PathSelector(path, "*");
        return selector.select(player, registry);
    }

    public static List<Site> sampleTrees(
            EditSession editSession,
            Region region,
            Set<Schematic> schematics,
            List<String> validSurfaceBlocks,
            float distance) throws IOException {
        if (schematics.isEmpty() || distance <= 0 || !(region instanceof Polygonal2DRegion)) {
            return Collections.emptyList();
        }

        var surfacePositions = collectSurfacePositions(editSession, region);
        if (surfacePositions.isEmpty()) {
            return Collections.emptyList();
        }

        int width = region.getMaximumPoint().x() - region.getMinimumPoint().x() + 1;
        int length = region.getMaximumPoint().z() - region.getMinimumPoint().z() + 1;
        float cellSize = distance / (float) Math.sqrt(2);
        int cellsWidth = Math.max(1, (int) Math.ceil(width / cellSize));
        int cellsLength = Math.max(1, (int) Math.ceil(length / cellSize));
        Site[][] grid = new Site[cellsWidth][cellsLength];

        List<Site> points = new ArrayList<>();
        List<Site> active = new ArrayList<>();

        Site first = createSite(surfacePositions.get(RANDOM.nextInt(surfacePositions.size())), schematics);
        insertPoint(grid, region.getMinimumPoint(), cellSize, first);
        points.add(first);
        active.add(first);

        while (!active.isEmpty()) {
            int index = RANDOM.nextInt(active.size());
            Site site = active.get(index);
            boolean added = false;

            for (int attempt = 0; attempt < MAX_TRIES; attempt++) {
                double theta = RANDOM.nextDouble() * Math.PI * 2;
                double radius = distance + RANDOM.nextDouble() * distance;
                int x = (int) Math.round(site.position().x() + radius * Math.cos(theta));
                int z = (int) Math.round(site.position().z() + radius * Math.sin(theta));

                BlockVector3 candidatePosition = findValidPosition(surfacePositions, x, z, region);
                if (candidatePosition == null) {
                    continue;
                }

                if (!isValidPoint(candidatePosition, region.getMinimumPoint(), cellSize, grid, distance)) {
                    continue;
                }

                Site next = createSite(candidatePosition, schematics);
                insertPoint(grid, region.getMinimumPoint(), cellSize, next);
                points.add(next);
                active.add(next);
                added = true;
                break;
            }

            if (!added) {
                active.remove(index);
            }
        }

        return points;
    }

    private static List<BlockVector3> collectSurfacePositions(
            EditSession editSession,
            Region region) {
        Map<Long, BlockVector3> surfacePositions = new HashMap<>();

        for (BlockVector3 position : region) {
            BlockVector3 above = BlockVector3.at(position.x(), position.y() + 1, position.z());
            if (!editSession.getBlock(above).getBlockType().getMaterial().isAir()) {
                continue;
            }

            long key = key(position.x(), position.z());
            BlockVector3 existing = surfacePositions.get(key);
            if (existing == null || position.y() > existing.y()) {
                surfacePositions.put(key, position);
            }
        }

        return new ArrayList<>(surfacePositions.values());
    }

    public static boolean isValidSurfacePosition(
            EditSession editSession,
            BlockVector3 position,
            List<String> surfaceBlocks
    ) {
        BlockVector3 above = position.add(0, 1, 0);
        if (!editSession.getBlock(above).getBlockType().getMaterial().isAir()) {
            return false;
        }
        return matchesSurface(editSession.getBlock(position).getBlockType(), surfaceBlocks);
    }

    /**
     * Ensure the center position is valid and all eight horizontal neighbors
     * are valid surfaces as well. For each neighbor, if the neighbor at the
     * same Y is not valid, one block up or one block down is tried and
     * accepted if valid.
     */
    public static boolean hasAdjacentValidSurface(
            EditSession editSession,
            BlockVector3 position,
            List<String> surfaceBlocks
    ) {
        if (!isValidSurfacePosition(editSession, position, surfaceBlocks)) {
            return false;
        }

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockVector3 neighbor = position.add(dx, 0, dz);
                if (isValidSurfacePosition(editSession, neighbor, surfaceBlocks)) continue;
                if (isValidSurfacePosition(editSession, neighbor.add(0, 1, 0), surfaceBlocks)) continue;
                if (isValidSurfacePosition(editSession, neighbor.add(0, -1, 0), surfaceBlocks)) continue;
                return false;
            }
        }
        return true;
    }

    private static boolean matchesSurface(BlockType blockType, List<String> surfaceBlocks) {
        String id = blockType.id().toLowerCase();
        String simple = id.contains(":") ? id.substring(id.indexOf(":") + 1) : id;

        for (String block : surfaceBlocks) {
            if (block.isEmpty()) {
                continue;
            }
            if (isLegacyId(block)) {
                BlockState legacyState = legacyBlockState(block);
                if (legacyState != null && legacyState.getBlockType().id().equals(id)) {
                    return true;
                }
            }
            if (simple.equals(block) || id.equals(block) || id.endsWith(':' + block)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLegacyId(String block) {
        return block.matches("^[0-9]+(:[0-9]+)?$");
    }

    private static BlockState legacyBlockState(String legacyId) {
        try {
            int id = legacyId.contains(":") ? Integer.parseInt(legacyId.substring(0, legacyId.indexOf(':')))
                    : Integer.parseInt(legacyId);
            int data = 0;
            if (legacyId.contains(":")) {
                data = Integer.parseInt(legacyId.substring(legacyId.indexOf(':') + 1));
            }
            return LegacyMapper.getInstance().getBlockFromLegacy(id, data);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static List<Site> replaceTrees(
            EditSession editSession,
            Region region,
            Set<Schematic> schematics,
            List<String> validBlocks) throws IOException {
        if (schematics.isEmpty() || !(region instanceof Polygonal2DRegion)) {
            return Collections.emptyList();
        }

        List<Site> sites = new ArrayList<>();
        for (BlockVector3 position : region) {
            BlockType blockType = editSession.getBlock(position).getBlockType();
            if (!matchesSurface(blockType, validBlocks)) {
                continue;
            }
            sites.add(createSite(position, schematics));
        }
        return sites;
    }

    private static BlockVector3 findValidPosition(List<BlockVector3> surfacePositions, int x, int z, Region region) {
        // Find the closest candidate position within the region. Returning
        // the nearest available position (even if farther than the original
        // search radius) allows the sampler to jump across invalid gaps; the
        // surface material is validated later when placing the schematic.
        BlockVector3 closest = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (BlockVector3 position : surfacePositions) {
            if (!region.contains(position)) {
                continue;
            }
            double dx = position.x() - x;
            double dz = position.z() - z;
            double distanceSq = dx * dx + dz * dz;
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                closest = position;
            }
        }

        return closest;
    }

    private static boolean isValidPoint(
            BlockVector3 position,
            BlockVector3 minimumPoint,
            float cellSize,
            Site[][] grid,
            float distance) {
        int xIndex = (int) Math.floor((position.x() - minimumPoint.x()) / cellSize);
        int zIndex = (int) Math.floor((position.z() - minimumPoint.z()) / cellSize);
        if (xIndex < 0 || zIndex < 0 || xIndex >= grid.length || zIndex >= grid[0].length) {
            return false;
        }

        int i0 = Math.max(xIndex - 1, 0);
        int i1 = Math.min(xIndex + 1, grid.length - 1);
        int j0 = Math.max(zIndex - 1, 0);
        int j1 = Math.min(zIndex + 1, grid[0].length - 1);

        for (int i = i0; i <= i1; i++) {
            for (int j = j0; j <= j1; j++) {
                Site neighbor = grid[i][j];
                if (neighbor == null) {
                    continue;
                }
                double dx = neighbor.position().x() - position.x();
                double dz = neighbor.position().z() - position.z();
                if (Math.sqrt(dx * dx + dz * dz) < distance) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void insertPoint(Site[][] grid, BlockVector3 minimumPoint, float cellSize, Site site) {
        int xIndex = (int) Math.floor((site.position().x() - minimumPoint.x()) / cellSize);
        int zIndex = (int) Math.floor((site.position().z() - minimumPoint.z()) / cellSize);
        if (xIndex < 0 || zIndex < 0 || xIndex >= grid.length || zIndex >= grid[0].length) {
            return;
        }
        grid[xIndex][zIndex] = site;
    }

    private static Site createSite(BlockVector3 position, Set<Schematic> schematics) throws IOException {
        var clipboard = randomSchematic(schematics).loadSchematic();
        return new Site(position, clipboard);
    }

    private static Schematic randomSchematic(Set<Schematic> schematics) {
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

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    public record Site(BlockVector3 position, Clipboard clipboard) {
    }
}
