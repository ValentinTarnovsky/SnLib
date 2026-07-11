package com.sn.lib;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks API that is shipped but NOT yet frozen: it is excluded from the japicmp gate and
 * from the {@link SnApi#LEVEL} contract, and MAY change or disappear between releases
 * without a major version bump.
 *
 * <p>Purely documentational: the actual gate exclusion lives in the pom's japicmp
 * {@code <excludes>} patterns (package based). This annotation exists so a consumer
 * reading the source or javadoc knows the surface is experimental before depending on it.
 * When a module graduates, the annotation is removed, the pom exclude is dropped and
 * {@code SnApi.LEVEL} is bumped in the same release.</p>
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.PACKAGE, ElementType.METHOD, ElementType.FIELD})
public @interface SnExperimental {
}
