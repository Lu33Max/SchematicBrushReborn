package de.eldoria.schematicbrush.commands.brush.legacy;

import java.util.List;

public record LegacyBrushDefinition(
        List<String> selectorArguments,
        List<List<String>> modifierArguments
) {}
