package com.sn.lib.action;

/**
 * Surface of a world click (physical items): the interaction hit a block or the air.
 *
 * <p>GUI clicks have no surface and leave {@link ActionContext#clickSurface()} null, so
 * the positional guards {@code [click-block]} / {@code [click-air]} skip their line
 * there.</p>
 */
public enum ClickSurface {
    BLOCK,
    AIR
}
