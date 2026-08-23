package com.sn.lib.yml;

import java.io.File;

/**
 * Test-only bridge to {@link SnYml}'s package-private constructor, so tests living in other
 * packages can parse a REAL yml file headlessly instead of hand-building the objects a
 * parser would have produced. Fixtures that go through the actual parser are what make a
 * yml-key test worth writing: a hand-built definition proves the resolution and nothing
 * about whether the key is read at all.
 *
 * <p>The null context is deliberate and is the whole reason this class has to live in this
 * package. On the happy path - the file exists, its YAML is valid and it carries no
 * indentation tabs - {@code SnYml} loads from disk without touching the context once. A
 * future change that makes the load path use the context fails every test built on this
 * bridge with an NPE pointing straight at the new coupling, which is the intended alarm.</p>
 */
public final class SnYmlTestAccess {

    private SnYmlTestAccess() {
    }

    /**
     * A context-less {@link SnYml} over {@code file}, which must already exist and parse.
     * Nothing on this instance is safe to call beyond the raw readers - no placeholder
     * resolution, no save, no reload.
     */
    public static SnYml of(File file) {
        return new SnYml(null, file);
    }
}
