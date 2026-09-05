package de.eldoria.schematicbrush.brush.config.selector;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.bukkit.configuration.serialization.SerializableAs;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import de.eldoria.eldoutilities.serialization.SerializationUtil;
import de.eldoria.schematicbrush.schematics.Schematic;
import de.eldoria.schematicbrush.schematics.SchematicCache;
import de.eldoria.schematicbrush.schematics.SchematicRegistry;

@SerializableAs("sbrPathSelector")
public class PathSelector extends BaseSelector {
    // the given directory path upon selector creation
    private final String path;

    @JsonCreator
    public PathSelector(@JsonProperty("path") String path,
                        @JsonProperty("term") @Nullable String term) {
        super(term);
        this.path = path;
    }

    public PathSelector(Map<String, Object> objectMap) {
        super(objectMap);

        var map = SerializationUtil.mapOf(objectMap);
        path = map.getValue("path");
    }

    @Override
    @NotNull
    public Map<String, Object> serialize() {
        return SerializationUtil.newBuilder(super.serialize())
                .add("path", path)
                .build();
    }

    @Override
    public Set<Schematic> select(Player player, SchematicRegistry registry) {

        Set<Schematic> schematics =
                registry.get(SchematicCache.STORAGE)
                        .getSchematicsByName(player, term() == null ? "*" : term());

        return schematics.stream()
                .filter(schematic -> matchesPath(schematic.directory(), schematic.name()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // check the given relative path of a schematic against this selectors directory pattern
    private boolean matchesPath(String schematicPath, String schematicName) {
        String[] selectorParts = path.split("/");
        String[] directoryParts = schematicPath.isBlank() ? new String[0] : schematicPath.split("/");

        if (selectorParts.length > directoryParts.length + 1) {
            return false;
        }

        for (int i = 0; i < Math.min(selectorParts.length, directoryParts.length); i++) {
            if (!matchesSegment(selectorParts[i], directoryParts[i])) {
                return false;
            }
        }

        return selectorParts.length <= directoryParts.length
            || matchesSegment(selectorParts[selectorParts.length - 1], schematicName);
    }

    private boolean matchesSegment(String selector, String value) {
        // the wildcard character allows for any directory
        if (selector.equals("*")) {
            return true;
        }

        // since the old directory syntax allowed a * character at the end of the path to indicate that
        // all subdirectories should be searched, users might still be tempted to include it
        return Arrays.stream(selector.split(","))
            .anyMatch(option -> option.replace("*", "").equalsIgnoreCase(value));
    }

    @Override
    public String name() {
        return "Path";
    }

    @Override
    public String descriptor() {
        if (term() != null && !term().isBlank()) {
            return path + " - " + term();
        }

        return path;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PathSelector selector)) return false;
        if (!super.equals(o)) return false;

        return Objects.equals(path, selector.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), path);
    }
}
