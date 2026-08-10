package de.eldoria.schematicbrush.commands.util;

import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

public final class CommandUtils {
    private static final List<String> BLOCK_TYPE_NAMES = loadBlockTypeNames();

    private CommandUtils() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static List<String> getBlockTypeNames() {
        return BLOCK_TYPE_NAMES;
    }

    public static List<String> completeBlockIdNames(String current) {
        int commaIndex = current.lastIndexOf(',');
        final String prefix;
        String token = current;
        if (commaIndex >= 0) {
            prefix = current.substring(0, commaIndex + 1);
            token = current.substring(commaIndex + 1);
        } else {
            prefix = "";
        }

        int whitespaceEnd = 0;
        while (whitespaceEnd < token.length() && Character.isWhitespace(token.charAt(whitespaceEnd))) {
            whitespaceEnd++;
        }
        final String whitespace = token.substring(0, whitespaceEnd);
        String search = token.substring(whitespaceEnd).toLowerCase(Locale.ROOT);
        if (search.startsWith("minecraft:")) {
            search = search.substring("minecraft:".length());
        }
        final String normalizedSearch = search;
        if (normalizedSearch.isEmpty()) {
            return BLOCK_TYPE_NAMES.stream()
                    .map(name -> prefix + whitespace + name)
                    .collect(Collectors.toList());
        }
        return BLOCK_TYPE_NAMES.stream()
                .filter(name -> name.startsWith(normalizedSearch))
                .map(name -> prefix + whitespace + name)
                .collect(Collectors.toList());
    }

    public static List<String> parseBlockNames(String blockNames) {
        return blockNames.lines()
                .flatMap(line -> List.of(line.split(",")).stream())
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .collect(Collectors.toList());
    }

    public static boolean isLegacyId(String block) {
        return block.matches("^[0-9]+(:[0-9]+)?$");
    }

    public static int[] parseLegacyId(String legacyId) {
        int id = legacyId.contains(":") ? Integer.parseInt(legacyId.substring(0, legacyId.indexOf(':'))) : Integer.parseInt(legacyId);
        int data = 0;
        if (legacyId.contains(":")) {
            data = Integer.parseInt(legacyId.substring(legacyId.indexOf(':') + 1));
        }
        return new int[]{id, data};
    }

    public static String stripDollar(String path) {
        return path.startsWith("$") ? path.substring(1) : path;
    }

    private static List<String> loadBlockTypeNames() {
        try {
            return Arrays.stream(BlockTypes.class.getFields())
                    .filter(field -> BlockType.class.isAssignableFrom(field.getType()))
                    .map(field -> {
                        try {
                            Object value = field.get(null);
                            if (value instanceof BlockType blockType) {
                                return blockType.id();
                            }
                        } catch (IllegalAccessException ignored) {
                        }
                        return null;
                    })
                    .filter(Objects::nonNull)
                    .map(String::toLowerCase)
                    .map(name -> name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name)
                    .filter(name -> !name.equals("__reserved__"))
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }
}
