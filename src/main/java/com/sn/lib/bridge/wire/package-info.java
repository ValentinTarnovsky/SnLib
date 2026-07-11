/**
 * Platform-neutral wire core of SnBridge: buffers, typed message codecs, frame layout,
 * HMAC authentication, chunking and the HELLO handshake messages.
 *
 * <p><b>ZERO platform imports allowed here</b>: no Bukkit, no Velocity, no Adventure.
 * This package is literally shared by the Paper side ({@code com.sn.lib.bridge}) and the
 * Velocity side ({@code com.sn.lib.velocity}); a CI test scans its bytecode and fails the
 * build if a platform reference appears.</p>
 *
 * <p><b>Wire rules (frozen discipline, enforced by tests):</b></p>
 * <ul>
 *   <li>A wireId is used ONCE in history; deprecating means "stop emitting", never reuse.</li>
 *   <li>Fields are ADDITIVE-ONLY at the end of a body; never reorder, never retype. The
 *       body is length-prefixed so an old decoder skips trailing new fields.</li>
 *   <li>An incompatible change is a NEW wireId, not a version that breaks old decoders.</li>
 *   <li>Every message type ships with a golden byte fixture and a
 *       {@link com.sn.lib.bridge.wire.SnWireType#selfTest selfTest} in the same commit.</li>
 *   <li>Codecs are explicit positional lambdas; reflection over records is forbidden
 *       (the sn-obfuscate/ProGuard pipeline breaks reflection).</li>
 * </ul>
 *
 * <p>See {@code docs/SNBRIDGE-SPEC.md} for the full design and the wireId ledger.</p>
 */
@SnExperimental
package com.sn.lib.bridge.wire;

import com.sn.lib.SnExperimental;
