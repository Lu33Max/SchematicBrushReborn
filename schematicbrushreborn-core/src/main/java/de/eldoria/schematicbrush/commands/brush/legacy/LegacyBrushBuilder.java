/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package de.eldoria.schematicbrush.commands.brush.legacy;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import de.eldoria.eldoutilities.commands.command.util.Arguments;
import de.eldoria.eldoutilities.commands.exceptions.CommandException;
import de.eldoria.schematicbrush.brush.config.BrushSettingsRegistry;
import de.eldoria.schematicbrush.brush.config.builder.BrushBuilder;
import de.eldoria.schematicbrush.brush.config.builder.SchematicSetBuilder;
import de.eldoria.schematicbrush.brush.config.selector.Selector;
import de.eldoria.schematicbrush.schematics.SchematicRegistry;

public class LegacyBrushBuilder {

    public static void apply(
        Plugin plugin,
        Player player,
        BrushBuilder builder,
        BrushSettingsRegistry registry,
        SchematicRegistry schematics,
        String input
    ) throws CommandException {

        String data = input.substring(1);

        String selector;
        String flip = null;
        String rotation = null;

        // find the position of the first modifier
        int flipPos = data.indexOf('!');
        int rotationPos = data.indexOf('@');

        int selectorEnd = data.length();

        if (flipPos >= 0) {
            selectorEnd = Math.min(selectorEnd, flipPos);
        }

        if (rotationPos >= 0) {
            selectorEnd = Math.min(selectorEnd, rotationPos);
        }

        // trim modifiers from the file path
        selector = data.substring(0, selectorEnd);

        // extract modifier values without leading char
        if (flipPos >= 0) {
            int end = (rotationPos > flipPos) ? rotationPos : data.length();
            flip = data.substring(flipPos + 1, end);
        }

        if (rotationPos >= 0) {
            int end = (flipPos > rotationPos) ? flipPos : data.length();
            rotation = data.substring(rotationPos + 1, end);
        }

        // create empty set with given directory
        int id = builder.createSchematicSet();
        SchematicSetBuilder set = builder.getSchematicSet(id).orElseThrow();

        Arguments selectorArgs = Arguments.create(
            plugin,
            player,
            new String[]{
                "Directory",
                selector
            }
        );

        Selector parsedSelector = registry.parseSelector(selectorArgs);

        set.selector(parsedSelector);
        set.refreshSchematics(player, schematics);

        // set flip modifier on set
        if (flip != null && !flip.isBlank()) {
            Arguments flipArgs = Arguments.create(
                plugin,
                player,
                createFlipArguments(flip)
            );

            var modifier = registry.parseSchematicModifier(flipArgs);

            set.withMutator(
                modifier.first,
                modifier.second
            );
        }

        // set rotation modifier on set
        if (rotation != null && !rotation.isBlank()) {
            Arguments rotationArgs = Arguments.create(
                plugin,
                player,
                createRotationArguments(rotation)
            );

            var modifier = registry.parseSchematicModifier(rotationArgs);

            set.withMutator(
                modifier.first,
                modifier.second
            );
        }
    }

    private static String[] createRotationArguments(String rotation) {

        if (rotation.equals("*")) {
            return new String[]{
                "Rotation",
                "Random"
            };
        }

        if (rotation.contains(",")) {

            List<String> args = new ArrayList<>();
            args.add("Rotation");
            args.add("List");

            for (String value : rotation.split(",")) {
                args.add(value.trim());
            }

            return args.toArray(String[]::new);
        }

        return new String[]{
            "Rotation",
            "Fixed",
            rotation
        };      
    }


    private static String[] createFlipArguments(String flip) {

        if (flip.equals("*")) {
            return new String[]{
                "Flip",
                "Random"
            };
        }

        if (flip.contains(",")) {

            List<String> args = new ArrayList<>();
            args.add("Flip");
            args.add("List");

            for (String value : flip.split(",")) {
                args.add(mapFlip(value.trim()));
            }

            return args.toArray(String[]::new);
        }

        return new String[]{
                "Flip",
                "Fixed",
                mapFlip(flip)
        };
    }

    private static String mapFlip(String flip) {
        return switch (flip.toLowerCase()) {
            case "x" -> "east";
            case "y" -> "up";
            case "z" -> "north";
            default -> flip.toLowerCase();
        };
    }
}
