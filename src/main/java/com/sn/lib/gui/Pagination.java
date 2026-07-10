package com.sn.lib.gui;

import java.util.List;

/**
 * Immutable 1-based pager over a fixed data snapshot (port of the SnGens pagination
 * helper): the data list is copied once at creation and sliced per page on demand.
 *
 * <p>Pages are 1-based and always clamped: asking for a page below 1 returns the first
 * page and asking beyond {@link #totalPages()} returns the last one. An empty data set
 * still reports one (empty) page, so navigation math never divides by zero.</p>
 *
 * @param <T> element type
 */
public final class Pagination<T> {

    private final List<T> data;
    private final int pageSize;

    private Pagination(List<T> data, int pageSize) {
        this.data = data;
        this.pageSize = pageSize;
    }

    /**
     * Creates a pager over a snapshot of {@code data} (null means empty; elements must be
     * non-null) with {@code pageSize} entries per page (values below 1 are raised to 1).
     */
    public static <T> Pagination<T> of(List<T> data, int pageSize) {
        return new Pagination<>(data == null ? List.of() : List.copyOf(data),
                Math.max(1, pageSize));
    }

    /** Entries per page. */
    public int pageSize() {
        return pageSize;
    }

    /** Total elements in the snapshot. */
    public int size() {
        return data.size();
    }

    /** Total pages; at least 1 even when the snapshot is empty. */
    public int totalPages() {
        return Math.max(1, (data.size() + pageSize - 1) / pageSize);
    }

    /** Slice of the given 1-based page, clamped into range; possibly shorter than pageSize. */
    public List<T> page(int page) {
        int clamped = Math.min(Math.max(1, page), totalPages());
        int from = (clamped - 1) * pageSize;
        int to = Math.min(from + pageSize, data.size());
        return from >= to ? List.of() : data.subList(from, to);
    }
}
