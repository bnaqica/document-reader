package com.bnaqica.documentreader.processors;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** The SHA-256 of a file's bytes, as lowercase hex. Streams, so a large upload isn't held in memory to hash it. */
public final class Sha256 {

    private Sha256() {}

    /** Reads the stream to its end; the caller closes it. */
    public static String hex(InputStream in) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream digesting = new DigestInputStream(in, digest)) {
                digesting.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JVM provides SHA-256", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
