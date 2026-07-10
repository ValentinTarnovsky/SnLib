package com.sn.lib.action;

/**
 * Pagination controls that the page actions ({@code [next-page]}, {@code [previous-page]},
 * {@code [set-page]}, {@code [refresh-page]}, {@code [refresh-menu]}) delegate to;
 * implemented by GUI sessions.
 *
 * <p>Pagination is opt-in per menu: when {@link #paginationEnabled()} is false the
 * {@link ActionEngine} turns every page action into a no-op with a debug note.</p>
 */
public interface PageTarget {

    /** Advances to the next page. */
    void nextPage();

    /** Goes back to the previous page. */
    void previousPage();

    /** Jumps to the given page; implementations clamp out-of-range values. */
    void setPage(int page);

    /** Re-renders the current page. */
    void refreshPage();

    /** Re-renders the whole menu. */
    void refreshMenu();

    /** True when the backing menu declared pagination; page actions no-op otherwise. */
    boolean paginationEnabled();
}
