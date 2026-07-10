package com.sn.lib.command;

import java.util.List;
import java.util.function.Function;

import org.bukkit.command.CommandSender;

import net.kyori.adventure.text.Component;

/**
 * Generic chat-text paginator over an immutable item list; backs the generated command
 * help and is reusable by consumers for any paged listing.
 *
 * <p>Pages are 1-based and out-of-range requests clamp to the nearest valid page, so an
 * empty list still exposes one (empty) page. Rendering goes through Adventure: one
 * {@link Component} line per item via {@link #send(CommandSender, int, Function)}.</p>
 *
 * @param <T> item type
 */
public final class Page<T> {

    private final List<T> items;
    private final int pageSize;

    private Page(List<T> items, int pageSize) {
        this.items = items;
        this.pageSize = pageSize;
    }

    /** Paginates {@code items} in pages of {@code pageSize} entries (minimum 1). */
    public static <T> Page<T> of(List<T> items, int pageSize) {
        return new Page<>(List.copyOf(items), Math.max(1, pageSize));
    }

    /** Total item count. */
    public int size() {
        return items.size();
    }

    /** Entries per page. */
    public int pageSize() {
        return pageSize;
    }

    /** Total page count, at least 1. */
    public int totalPages() {
        return items.isEmpty() ? 1 : (items.size() + pageSize - 1) / pageSize;
    }

    /** Clamps a requested page into {@code [1, totalPages()]}. */
    public int clamp(int page) {
        return Math.max(1, Math.min(page, totalPages()));
    }

    /** Items of the given 1-based page, clamped into range. */
    public List<T> page(int page) {
        int current = clamp(page);
        int from = (current - 1) * pageSize;
        int to = Math.min(from + pageSize, items.size());
        return from >= to ? List.of() : items.subList(from, to);
    }

    /** Renders and sends the given page, one component line per item. */
    public void send(CommandSender sender, int page, Function<T, Component> renderer) {
        for (T item : page(page)) {
            sender.sendMessage(renderer.apply(item));
        }
    }
}
