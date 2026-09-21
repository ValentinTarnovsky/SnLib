package com.sn.lib.yml.internal;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Validation shared by the modules that accept an extra, consumer-registered resource folder
 * ({@code GuiManager.loadFolder}, {@code SnLang.addSource}). Internal: not part of the
 * public API.
 */
public final class ResourceFolders {

    private ResourceFolders() {
    }

    /**
     * Normalizes a data-folder-relative resource folder: trims it, turns {@code \} into
     * {@code /}, drops empty and {@code .} segments (so leading, trailing and repeated
     * separators disappear) and resolves {@code ..} against the preceding segment. The result
     * doubles as the jar resource prefix and the relative folder on disk.
     *
     * @param folder   the folder as the consumer passed it
     * @param reserved the module's own folder ({@code guis}, {@code lang}), which may not be
     *                 registered as an extra one; compared case-insensitively
     * @param kind     noun for the exception messages, e.g. {@code Menu} or {@code Lang}
     * @return the normalized folder, e.g. {@code modules/party/guis}
     * @throws IllegalArgumentException when the folder is null or blank, holds a {@code :}
     *         (a drive or other absolute form), climbs out of the data folder through
     *         {@code ..}, or resolves to the reserved folder
     */
    public static String normalize(String folder, String reserved, String kind) {
        if (folder == null) {
            throw new IllegalArgumentException(kind + " folder is null");
        }
        Deque<String> parts = new ArrayDeque<>();
        for (String segment : folder.trim().replace('\\', '/').split("/")) {
            String part = segment.trim();
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (parts.isEmpty()) {
                    throw new IllegalArgumentException(
                            kind + " folder '" + folder + "' climbs out of the data folder");
                }
                parts.removeLast();
                continue;
            }
            if (part.indexOf(':') >= 0) {
                throw new IllegalArgumentException(
                        kind + " folder '" + folder + "' must be relative to the data folder");
            }
            parts.addLast(part);
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException(kind + " folder is blank");
        }
        String path = String.join("/", parts);
        if (path.equalsIgnoreCase(reserved)) {
            throw new IllegalArgumentException(reserved
                    + "/ is loaded by the module already; register a different folder");
        }
        return path;
    }
}
