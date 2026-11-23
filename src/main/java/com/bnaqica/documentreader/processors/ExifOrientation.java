package com.bnaqica.documentreader.processors;

import com.twelvemonkeys.imageio.metadata.CompoundDirectory;
import com.twelvemonkeys.imageio.metadata.Directory;
import com.twelvemonkeys.imageio.metadata.Entry;
import com.twelvemonkeys.imageio.metadata.jpeg.JPEG;
import com.twelvemonkeys.imageio.metadata.jpeg.JPEGSegment;
import com.twelvemonkeys.imageio.metadata.jpeg.JPEGSegmentUtil;
import com.twelvemonkeys.imageio.metadata.tiff.TIFF;
import com.twelvemonkeys.imageio.metadata.tiff.TIFFReader;
import java.awt.geom.AffineTransform;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;

/**
 * Reads the EXIF {@code Orientation} tag (1–8) of a JPEG, TIFF or WEBP, and gives the transform that makes the image
 * upright. Missing or unreadable metadata means 1 (as stored): orientation is a correction, not a reason to refuse.
 */
final class ExifOrientation {

    static final int UPRIGHT = 1;

    private ExifOrientation() {}

    static int read(byte[] bytes, DetectedType type) {
        try {
            byte[] tiff = switch (type) {
                case JPEG -> jpegExif(bytes);
                case TIFF -> bytes;
                case WEBP -> webpExif(bytes);
                default -> null;
            };
            return tiff == null ? UPRIGHT : orientation(tiff);
        } catch (IOException | RuntimeException e) {
            return UPRIGHT;
        }
    }

    /** True for orientations 5–8, which swap width and height. */
    static boolean swapsDimensions(int orientation) {
        return orientation >= 5 && orientation <= 8;
    }

    /** Maps a stored {@code width × height} image onto its upright canvas. */
    static AffineTransform transform(int orientation, int width, int height) {
        return switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, width, 0); // mirrored
            case 3 -> new AffineTransform(-1, 0, 0, -1, width, height); // upside down
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, height); // mirrored vertically
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0); // transposed
            case 6 -> new AffineTransform(0, 1, -1, 0, height, 0); // needs 90° clockwise
            case 7 -> new AffineTransform(0, -1, -1, 0, height, width); // transverse
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, width); // needs 90° anticlockwise
            default -> new AffineTransform();
        };
    }

    private static byte[] jpegExif(byte[] bytes) throws IOException {
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            List<JPEGSegment> segments = JPEGSegmentUtil.readSegments(stream, JPEG.APP1, "Exif");
            if (segments.isEmpty()) {
                return null;
            }
            try (InputStream data = segments.getFirst().data()) {
                byte[] exif = data.readAllBytes();
                // the segment data starts after the "Exif" identifier; skip its padding byte if present
                return exif.length > 0 && exif[0] == 0 ? Arrays.copyOfRange(exif, 1, exif.length) : exif;
            }
        }
    }

    /** WEBP keeps EXIF in a RIFF chunk named "EXIF". */
    private static byte[] webpExif(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int position = 12; // "RIFF", size, "WEBP"
        while (position + 8 <= bytes.length) {
            String id = new String(bytes, position, 4, StandardCharsets.US_ASCII);
            int size = buffer.getInt(position + 4);
            if (size < 0 || position + 8 + size > bytes.length) {
                return null;
            }
            if (id.equals("EXIF")) {
                byte[] exif = Arrays.copyOfRange(bytes, position + 8, position + 8 + size);
                return startsWith(exif, "Exif\0\0") ? Arrays.copyOfRange(exif, 6, exif.length) : exif;
            }
            position += 8 + size + (size & 1);
        }
        return null;
    }

    private static int orientation(byte[] tiff) throws IOException {
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(tiff))) {
            Directory directory = new TIFFReader().read(stream);
            if (directory instanceof CompoundDirectory compound && compound.directoryCount() > 0) {
                directory = compound.getDirectory(0);
            }
            Entry entry = directory.getEntryById(TIFF.TAG_ORIENTATION);
            if (entry == null || !(entry.getValue() instanceof Number value)) {
                return UPRIGHT;
            }
            int orientation = value.intValue();
            return orientation >= 1 && orientation <= 8 ? orientation : UPRIGHT;
        }
    }

    private static boolean startsWith(byte[] bytes, String prefix) {
        byte[] start = prefix.getBytes(StandardCharsets.US_ASCII);
        return bytes.length >= start.length && Arrays.equals(Arrays.copyOf(bytes, start.length), start);
    }
}
