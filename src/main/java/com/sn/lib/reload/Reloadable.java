package com.sn.lib.reload;

/** A component able to rebuild its state from its sources (files, registries, caches). */
public interface Reloadable {

    /** Rebuilds the component state; invoked by the reload flow of its owning context. */
    void reload();
}
