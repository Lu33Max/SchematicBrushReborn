package de.eldoria.schematicbrush.commands.brush.legacy;

import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import de.eldoria.eldoutilities.commands.command.AdvancedCommand;
import de.eldoria.eldoutilities.commands.command.CommandMeta;
import de.eldoria.eldoutilities.commands.command.util.Arguments;
import de.eldoria.eldoutilities.commands.exceptions.CommandException;
import de.eldoria.eldoutilities.commands.executor.IPlayerTabExecutor;
import de.eldoria.schematicbrush.brush.config.BrushSettingsRegistry;
import de.eldoria.schematicbrush.brush.config.builder.BrushBuilder;
import de.eldoria.schematicbrush.commands.brush.Sessions;
import de.eldoria.schematicbrush.schematics.SchematicCache;
import de.eldoria.schematicbrush.schematics.SchematicRegistry;
import de.eldoria.schematicbrush.util.WorldEditBrush;

public class Simple extends AdvancedCommand
        implements IPlayerTabExecutor {

    private final Sessions sessions;
    private final BrushSettingsRegistry registry;
    private final SchematicRegistry schematics;

    public Simple(
            Plugin plugin,
            Sessions sessions,
            BrushSettingsRegistry registry,
            SchematicRegistry schematics
    ) {

        super(plugin,
                CommandMeta.builder("simple")
                        .addUnlocalizedArgument(
                                "path",
                                true
                        )
                        .build()
        );

        this.sessions = sessions;
        this.registry = registry;
        this.schematics = schematics;
    }

    @Override
    public void onCommand(
            @NotNull Player player,
            @NotNull String alias,
            @NotNull Arguments args
    ) throws CommandException {

        String input = args.asString(0);

        BrushBuilder builder = sessions.getOrCreateSession(player);
        builder.clear();

        SimpleBrushBuilder.apply(
                plugin(),
                player,
                builder,
                registry,
                schematics,
                input
        );

        var brush = builder.build(plugin(), player);

        if (!WorldEditBrush.setBrush(player, brush)) {
            return;
        }

        var schematicCount = brush.settings().getSchematicCount();

        messageSender()
            .sendMessage(player,
                String.format(
                    "Brush bound. Using <value>%d<default> Schematics.",
                    schematicCount
                )
            );
    }

    @Override
    public List<String> onTabComplete(
        @NotNull Player player,
        @NotNull String alias,
        @NotNull Arguments args
    ) {
        if(args.size() > 1) return Collections.emptyList();

        String input = args.asString(0);

        long rotationCount = input.chars().filter(c -> c == '@').count();
        long flipCount = input.chars().filter(c -> c == '!').count();

        int lastRotation = input.lastIndexOf('@');
        int lastFlip = input.lastIndexOf('!');

        // only complete rotation if no more than one @ entry exists
        if (rotationCount == 1 && lastRotation > lastFlip) {
            String prefix = input.substring(0, lastRotation + 1);
            String current = input.substring(lastRotation + 1);

            return Stream.of("*", "0", "90", "180", "270")
                    .filter(s -> s.startsWith(current))
                    .map(s -> prefix + s)
                    .toList();
        }

        // only complete flip if no more than one ! entry exists
        if (flipCount == 1 && lastFlip > lastRotation) {
            String prefix = input.substring(0, lastFlip + 1);
            String current = input.substring(lastFlip + 1);

            return Stream.of("*", "x", "y", "z")
                    .filter(s -> s.startsWith(current))
                    .map(s -> prefix + s)
                    .toList();
        }

        // only complete path if no modifier was started
        if (rotationCount == 0 && flipCount == 0) {
            String directory = input.startsWith("$")
                    ? input.substring(1)
                    : input;

            return schematics.registry()
                    .get(SchematicCache.STORAGE)
                    .getMatchingPatternDirectories(player, directory, 50)
                    .stream()
                    .map(dir -> "$" + dir)
                    .toList();
        }

        return Collections.emptyList();
    }
}
