package com.sn.lib.tenant;

import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;

/**
 * Marker holder for every inventory the library creates on behalf of a consumer plugin.
 *
 * <p>The tenant sweeper and the quit cleanup listener compile against this interface to
 * identify library inventories and their owner without depending on the GUI module; the
 * GUI holder implements it.</p>
 */
public interface OwnedHolder extends InventoryHolder {

    /** Plugin the inventory belongs to. */
    Plugin owner();
}
