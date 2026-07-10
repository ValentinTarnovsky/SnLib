package com.sn.lib;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Bootstrap plugin of the SnLib runtime. Loaded at STARTUP before every consumer.
 */
public final class SnLibPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("SnLib " + getPluginMeta().getVersion()
                + " enabled (API level " + SnApi.LEVEL + ")");
    }

    @Override
    public void onDisable() {
    }
}
