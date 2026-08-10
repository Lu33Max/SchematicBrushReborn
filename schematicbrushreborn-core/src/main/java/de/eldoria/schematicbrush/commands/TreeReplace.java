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

import com.sk89q.worldedit.LocalSession;
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
import de.eldoria.eldoutilities.commands.executor.IPlayerTabExecutor;
import de.eldoria.eldoutilities.commands.exceptions.CommandException;
import de.eldoria.schematicbrush.commands.util.CommandUtils;
import de.eldoria.schematicbrush.schematics.Schematic;
import de.eldoria.schematicbrush.schematics.SchematicCache;
import de.eldoria.schematicbrush.schematics.SchematicRegistry;
import de.eldoria.schematicbrush.util.Permissions;
import de.eldoria.schematicbrush.util.WoodPlacement;

public class TreeReplace extends AdvancedCommand implements IPlayerTabExecutor {
    private static final Random RANDOM = new Random();
    private final SchematicRegistry schematics;

    public TreeReplace(Plugin plugin, SchematicRegistry schematics) {
        super(plugin, CommandMeta.builder("treereplace")
                .withPermission(Permissions.TreeReplace.USE)
                .addUnlocalizedArgument("path", true)
                .addUnlocalizedArgument("blocks", true)
                .addUnlocalizedArgument("mode", false)
                .build());
        this.schematics = schematics;
    }

    @Override
    public void onCommand(
            @NotNull Player player,
            @NotNull String alias,
            @NotNull Arguments args
    ) throws CommandException {
        if (args.size() < 2) {
            messageSender().sendError(player, "Usage: /treereplace <path> <blocks> [above|replace]");
            return;
        }

        String path = CommandUtils.stripDollar(args.asString(0));
        List<String> blocks = CommandUtils.parseBlockNames(args.asString(1));
        if (!validateLegacyBlockIds(player, blocks)) {
            return;
        }

        Mode mode = Mode.REPLACE;
        if (args.size() >= 3) {
            mode = Mode.parse(args.asString(2));
            if (mode == null) {
                messageSender().sendError(player, "Mode must be above or replace.");
                return;
            }
        }

        Set<Schematic> schematics = WoodPlacement.selectSchematics(player, this.schematics, path);
        if (schematics.isEmpty()) {
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
        } catch (Exception e) {
            messageSender().sendError(player, "Please make a valid selection first.");
            return;
        }

        if (!(region instanceof Polygonal2DRegion)) {
            messageSender().sendError(player, "Tree replace requires a polygonal 2D selection.");
            return;
        }

        try (var editSession = WorldEdit.getInstance().newEditSessionBuilder()
                .world(BukkitAdapter.adapt(player.getWorld()))
                .actor(BukkitAdapter.adapt(player))
                .build()) {
            editSession.setMask(localSession.getMask());
            List<WoodPlacement.Site> sites = WoodPlacement.replaceTrees(editSession, region, schematics, blocks);
            if (sites.isEmpty()) {
                messageSender().sendError(player, "No matching blocks found in selection.");
                return;
            }

            int pasted = 0;
            for (WoodPlacement.Site site : sites) {
                try (ClipboardHolder clipboardHolder = new ClipboardHolder(site.clipboard())) {
                    AffineTransform transform = new AffineTransform();
                    int rotateAngle = RANDOM.nextInt(4) * 90;
                    clipboardHolder.setTransform(transform.rotateY(rotateAngle));
                    var targetPosition = mode == Mode.ABOVE ? site.position().add(0, 1, 0) : site.position();

                    try {
                        Operations.completeLegacy(clipboardHolder.createPaste(editSession)
                                .to(targetPosition)
                                .ignoreAirBlocks(true)
                                .build());
                        pasted++;
                    } catch (Exception e) {
                        messageSender().sendError(player, "Clipboard paste failed: " + e.getMessage());
                        return;
                    }
                }
            }

            localSession.remember(editSession);
            messageSender().sendMessage(player, "Replaced " + pasted + " blocks with schematics.");
        } catch (IOException e) {
            messageSender().sendError(player, "Failed to load one or more schematics.");
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull Player player,
            @NotNull String alias,
            @NotNull Arguments args
    ) {
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
        if (args.size() == 3) {
            return List.of("above", "replace").stream()
                    .filter(value -> value.startsWith(args.asString(2).toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private boolean validateLegacyBlockIds(@NotNull Player player, List<String> blocks) {
        for (String block : blocks) {
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

    private enum Mode {
        ABOVE,
        REPLACE;

        public static Mode parse(String input) {
            if (input == null) {
                return REPLACE;
            }
            String normalized = input.toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "above" -> ABOVE;
                case "replace" -> REPLACE;
                default -> null;
            };
        }
    }
}
