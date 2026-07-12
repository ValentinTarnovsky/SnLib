package com.sn.lib.bridge.wire;

import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Frame authentication: HMAC-SHA256 over {@code header-sans-tag + sessionNonce + body},
 * truncated to {@link WireProtocol#HMAC_TAG_LENGTH} bytes. The default key is the
 * Velocity modern-forwarding secret both sides already share; a dedicated secret can be
 * configured instead (see the spec, section 5).
 *
 * <p>Verification uses {@link MessageDigest#isEqual} (constant time). A frame that fails
 * verification is DISCARDED and counted, never processed: this is the floor that closes
 * client-spoofed frames on unclaimed channels in both directions.</p>
 *
 * <p>Thread-safe: a fresh {@link Mac} instance is created per call (Mac is not
 * thread-safe and frames are signed from both platform threads).</p>
 */
public final class HmacSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    /**
     * @param secret shared secret bytes; MUST NOT be empty (an empty key would silently
     *               authenticate everything derived from an unconfigured setup)
     */
    public HmacSigner(byte[] secret) {
        if (secret == null || secret.length == 0) {
            throw new SnWireException("Empty HMAC secret: configure the forwarding secret or a dedicated secret");
        }
        this.key = new SecretKeySpec(secret, ALGORITHM);
    }

    /**
     * Computes the truncated tag for one frame.
     *
     * @param headerSansTag the first 11 header bytes (offsets 0..10)
     * @param sessionNonce  negotiated nonce, {@link WireProtocol#HANDSHAKE_NONCE} pre-handshake
     * @param body          chunk body
     * @param bodyOff       body offset
     * @param bodyLen       body length
     */
    public byte[] tag(byte[] headerSansTag, long sessionNonce, byte[] body, int bodyOff, int bodyLen) {
        Mac mac = newMac();
        mac.update(headerSansTag, 0, WireProtocol.HEADER_LENGTH - WireProtocol.HMAC_TAG_LENGTH);
        byte[] nonce = new byte[8];
        for (int i = 0; i < 8; i++) {
            nonce[i] = (byte) (sessionNonce >>> (56 - 8 * i));
        }
        mac.update(nonce, 0, 8);
        mac.update(body, bodyOff, bodyLen);
        byte[] full = mac.doFinal();
        byte[] truncated = new byte[WireProtocol.HMAC_TAG_LENGTH];
        System.arraycopy(full, 0, truncated, 0, WireProtocol.HMAC_TAG_LENGTH);
        return truncated;
    }

    /** Constant-time comparison of an expected tag against the received one. */
    public static boolean tagsEqual(byte[] expected, byte[] received) {
        return MessageDigest.isEqual(expected, received);
    }

    private Mac newMac() {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac;
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA256 is mandatory in every JRE; init only fails on an empty key, rejected in the constructor
            throw new SnWireException("Could not initialize HmacSHA256", e);
        }
    }
}
