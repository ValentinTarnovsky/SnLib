package com.sn.lib.scheduler;

/**
 * Cancelable handle over a scheduled task, uniform across the Bukkit and Folia schedulers.
 */
public interface TaskHandle {

    /** Cancels the task if still pending or repeating. */
    void cancel();

    /** True when the task has been cancelled. */
    boolean isCancelled();
}
