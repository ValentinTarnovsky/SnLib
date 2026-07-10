package com.sn.lib.reload;

/** A component that can attach to and detach from a server-side registry. */
public interface Registrable {

    /** Attaches the component to its registry. */
    void register();

    /** Detaches the component from its registry; safe to call when not registered. */
    void unregister();
}
