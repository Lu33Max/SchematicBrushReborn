package de.eldoria.schematicbrush.commands;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.transform.AffineTransform;
import com.sk89q.worldedit.regions.Polygonal2DRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.ClipboardHolder;

import de.eldoria.eldoutilities.commands.command.AdvancedCommand;
import de.eldoria.eldoutilities.commands.command.CommandMeta;
import de.eldoria.eldoutilities.commands.command.util.Arguments;
import de.eldoria.eldoutilities.commands.exceptions.CommandException;
import de.eldoria.eldoutilities.commands.executor.IPlayerTabExecutor;
import de.eldoria.schematicbrush.commands.util.CommandUtils;
import de.eldoria.schematicbrush.schematics.Schematic;
import de.eldoria.schematicbrush.schematics.SchematicCache;
import de.eldoria.schematicbrush.schematics.SchematicRegistry;
import de.eldoria.schematicbrush.util.Permissions;
import de.eldoria.schematicbrush.util.WoodPlacement;

public class Wood extends AdvancedCommand implements IPlayerTabExecutor {
    private static final Random RANDOM = new Random();
    private final SchematicRegistry schematics;

    public Wood(Plugin plugin, SchematicRegistry schematics) {
        super(plugin, CommandMeta.builder("wood")
            .withPermission(Permissions.Wood.USE)
            .addUnlocalizedArgument("path", true)
            .addUnlocalizedArgument("surface_blocks", true)
            .addUnlocalizedArgument("distance", false)
            .addUnlocalizedArgument("adjacent", false)
            .build());
        this.schematics = schematics;
    }

    @Override
    public void onCommand(
            @NotNull Player player,
            @NotNull String alias,
            @NotNull Arguments args) throws CommandException {
        if (args.size() < 2) {
            messageSender().sendError(player, "Usage: /wood <path> <valid surface blocks> [distance]");
            return;
        }

        String path = CommandUtils.stripDollar(args.asString(0));
        List<String> surfaceBlocks = CommandUtils.parseBlockNames(args.asString(1));
        if (!validateLegacyBlockIds(player, surfaceBlocks)) {
            return;
        }

        float distance = 6f;
        if (args.size() >= 3) {
            try {
                distance = Float.parseFloat(args.asString(2));
            } catch (NumberFormatException e) {
                messageSender().sendError(player, "Distance must be a number greater than zero.");
                return;
            }
        }

        if (distance <= 0) {
            messageSender().sendError(player, "Distance must be greater than zero.");
            return;
        }

        boolean requireAdjacent = false;
        if (args.size() >= 4) {
            String raw = args.asString(3).toLowerCase(Locale.ROOT);
            switch (raw) {
                case "true", "1", "yes", "y" -> requireAdjacent = true;
                case "false", "0", "no", "n" -> requireAdjacent = false;
                default -> {
                    messageSender().sendError(player, "Adjacent flag must be true or false.");
                    return;
                }
            }
        }

        Set<Schematic> treeSchematics = WoodPlacement.selectSchematics(player, this.schematics, path);
        if (treeSchematics.isEmpty()) {
            messageSender().sendError(player, "No schematics found for path: " + path);
            return;
        }

        var worldEdit = WorldEdit.getInstance();
        var manager = worldEdit.getSessionManager();
        var actor = BukkitAdapter.adapt(player);
        LocalSession localSession = manager.get(actor);
        var selectionWorld = localSession.getSelectionWorld();

        if (selectionWorld == null) {
            messageSender().sendError(player, "Please make a selection first.");
            return;
        }

        Region region;
        try {
            region = localSession.getSelection(selectionWorld);
        } catch (IncompleteRegionException e) {
            messageSender().sendError(player, "Please make a valid selection first.");
            return;
        }

        if (!(region instanceof Polygonal2DRegion)) {
            messageSender().sendError(player, "Wood placement requires a polygonal 2D selection.");
            return;
        }

        try (var editSession = WorldEdit.getInstance().newEditSessionBuilder()
                .world(BukkitAdapter.adapt(player.getWorld()))
                .actor(BukkitAdapter.adapt(player))
                .build()) {
            editSession.setMask(localSession.getMask());
            List<WoodPlacement.Site> sites = WoodPlacement.sampleTrees(editSession, region, treeSchematics, surfaceBlocks,
                    distance);

            if (sites.isEmpty()) {
                messageSender().sendError(player, "No valid tree positions found in selection.");
                return;
            }

            int pasted = 0;
            for (WoodPlacement.Site site : sites) {
                // Validate surface at placement time; if adjacent spacing is
                // required, ensure all neighbors pass the check (with up/down
                // fallback), otherwise validate only the center surface.
                boolean valid;
                if (requireAdjacent) {
                    valid = WoodPlacement.hasAdjacentValidSurface(editSession, site.position(), surfaceBlocks);
                } else {
                    valid = WoodPlacement.isValidSurfacePosition(editSession, site.position(), surfaceBlocks);
                }
                if (!valid) {
                    continue;
                }

                try (ClipboardHolder clipboardHolder = new ClipboardHolder(site.clipboard())) {
                    AffineTransform transform = new AffineTransform();
                    int rotateAngle = RANDOM.nextInt(4) * 90;
                    clipboardHolder.setTransform(transform.rotateY(rotateAngle));

                    try {
                        Operations.completeLegacy(clipboardHolder.createPaste(editSession)
                                .to(site.position().add(0, 1, 0))
                                .ignoreAirBlocks(true)
                                .build());
                        pasted++;
                    } catch (MaxChangedBlocksException e) {
                        messageSender().sendError(player, "Clipboard paste failed: " + e.getMessage());
                        return;
                    }
                }
            }

            if (pasted == 0) {
                messageSender().sendError(player, "No valid tree positions found in selection.");
                return;
            }

            localSession.remember(editSession);
            messageSender().sendMessage(player, "Planted " + pasted + " schematics in selection.");
        } catch (IOException e) {
            messageSender().sendError(player, "Failed to load one or more schematics.");
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull Player player,
            @NotNull String alias,
            @NotNull Arguments args) {
        if (args.size() == 1) {
            String directory = args.asString(0);
            if (directory.startsWith("$")) {
                directory = directory.substring(1);
            }
            return schematics.registry()
                    .get(SchematicCache.STORAGE)
                    .getMatchingPatternDirectories(player, directory, 50)
                    .stream()
                    .map(dir -> "$" + dir)
                    .collect(Collectors.toList());
        }
        if (args.size() == 2) {
            return CommandUtils.completeBlockIdNames(args.asString(1));
        }
        if (args.size() == 4) {
            return List.of("true", "false").stream()
                    .filter(value -> value.startsWith(args.asString(3).toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private boolean validateLegacyBlockIds(@NotNull Player player, List<String> surfaceBlocks) {
        for (String block : surfaceBlocks) {
            if (block.isEmpty()) {
                continue;
            }
            if (CommandUtils.isLegacyId(block)) {
                try {
                    CommandUtils.parseLegacyId(block);
                } catch (NumberFormatException e) {
                    messageSender().sendError(player, "Invalid legacy block id: " + block);
                    return false;
                }
            }
        }
        return true;
    }
}
