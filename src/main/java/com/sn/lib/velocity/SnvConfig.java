package com.sn.lib.velocity;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Minimal managed YAML config for the Velocity base, backed by snakeyaml (SnLib bundles it;
 * Velocity, unlike Bukkit/Paper, does not put {@code org.yaml.snakeyaml} on the plugin
 * classpath). It loads the user file, deep-merges any keys missing versus the bundled
 * defaults, writes the merged file back when it changed, and exposes dot-path getters
 * ({@code getString("a.b.c")}) - the same managed-config idea as the Paper side, kept small.
 *
 * <p>Deliberately not a full config framework: no comment preservation on rewrite and only
 * the common scalar/list/section getters. For anything richer, read the tree with snakeyaml
 * directly. Never throws: I/O and parse errors are logged and fall back to defaults.</p>
 */
public final class SnvConfig {

    private final Map<String, Object> data;

    private SnvConfig(Map<String, Object> data) {
        this.data = data;
    }

    /**
     * Loads {@code file}, deep-merges keys missing versus {@code defaults} (the config.yml
     * bundled in the consumer jar; may be null), rewrites the file when it changed or was
     * absent, and returns an immutable view.
     */
    public static SnvConfig load(Path file, InputStream defaults, Logger logger) {
        Map<String, Object> defaultsMap = parse(defaults, logger);
        Map<String, Object> userMap = new LinkedHashMap<>();
        if (file != null && Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Object loaded = new Yaml().load(reader);
                if (loaded instanceof Map<?, ?> map) {
                    userMap = castMap(map);
                }
            } catch (IOException | RuntimeException e) {
                logger.warn("SnvConfig: could not read {} ({}); using defaults", file, e.toString());
            }
        }
        boolean changed = deepMerge(userMap, defaultsMap);
        if (file != null && (changed || !Files.exists(file))) {
            write(file, userMap, logger);
        }
        return new SnvConfig(userMap);
    }

    private static Map<String, Object> parse(InputStream in, Logger logger) {
        if (in == null) {
            return new LinkedHashMap<>();
        }
        try (InputStream stream = in) {
            Object loaded = new Yaml().load(stream);
            return loaded instanceof Map<?, ?> map ? castMap(map) : new LinkedHashMap<>();
        } catch (IOException | RuntimeException e) {
            logger.warn("SnvConfig: could not parse bundled defaults ({})", e.toString());
            return new LinkedHashMap<>();
        }
    }

    /** Adds every default key absent from {@code target} (recursing into sections). */
    private static boolean deepMerge(Map<String, Object> target, Map<String, Object> defaults) {
        boolean changed = false;
        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            String key = entry.getKey();
            if (!target.containsKey(key)) {
                target.put(key, entry.getValue());
                changed = true;
            } else if (target.get(key) instanceof Map<?, ?> targetSub
                    && entry.getValue() instanceof Map<?, ?> defaultSub) {
                changed |= deepMerge(castMap(targetSub), castMap(defaultSub));
            }
        }
        return changed;
    }

    private static void write(Path file, Map<String, Object> map, Logger logger) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                new Yaml(options).dump(map, out);
            }
        } catch (IOException | RuntimeException e) {
            logger.warn("SnvConfig: could not write {} ({})", file, e.toString());
        }
    }

    // ---- dot-path getters ----

    public boolean contains(String path) {
        return resolve(path) != null;
    }

    public String getString(String path, String def) {
        Object value = resolve(path);
        return value == null ? def : String.valueOf(value);
    }

    public int getInt(String path, int def) {
        return resolve(path) instanceof Number number ? number.intValue() : def;
    }

    public long getLong(String path, long def) {
        return resolve(path) instanceof Number number ? number.longValue() : def;
    }

    public double getDouble(String path, double def) {
        return resolve(path) instanceof Number number ? number.doubleValue() : def;
    }

    public boolean getBoolean(String path, boolean def) {
        return resolve(path) instanceof Boolean bool ? bool : def;
    }

    public List<String> getStringList(String path) {
        List<String> out = new ArrayList<>();
        if (resolve(path) instanceof List<?> list) {
            for (Object element : list) {
                out.add(String.valueOf(element));
            }
        }
        return out;
    }

    /** The raw nested map at {@code path} (empty when absent or not a section). */
    public Map<String, Object> getSection(String path) {
        return resolve(path) instanceof Map<?, ?> map ? castMap(map) : new LinkedHashMap<>();
    }

    /** Top-level keys of this config (or of a section obtained via {@link #getSection}). */
    public Set<String> keys() {
        return data.keySet();
    }

    private Object resolve(String path) {
        Object current = data;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
