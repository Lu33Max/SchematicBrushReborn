/*
 *     SPDX-License-Identifier: AGPL-3.0-only
 *
 *     Copyright (C) EldoriaRPG Team and Contributor
 */

package de.eldoria.schematicbrush.rendering;

import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.util.Map;

/** API for client-side block previews supplied by other plugins. All methods should be called on the server thread. */
public interface PreviewService {
    /** Start or replace this player's external preview. Values are the block states to display. */
    PreviewHandle show(Player player, Map<Location, BlockData> blocks);

    interface PreviewHandle extends AutoCloseable {
        /** Replace the preview with a new set of displayed block states. */
        void update(Map<Location, BlockData> blocks);
        /** Restore the real block states and release this preview. */
        @Override void close();
    }
}
