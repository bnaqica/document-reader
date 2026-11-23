package com.bnaqica.documentreader.processors;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

/**
 * Test inputs built in code, so each one shows what it holds and no binary fixtures are needed. The marker image is
 * 300 × 200 white with a red 30 × 30 square in its top-left corner, to check where a rotation moves it.
 */
public final class ConversionFixtures {

    public static final int MARKER_WIDTH = 300;
    public static final int MARKER_HEIGHT = 200;
    public static final int MARKER_SIZE = 30;

    private ConversionFixtures() {}

    /** US Letter pages, each with a black bar so it isn't blank. */
    public static byte[] pdf(int pages) {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.addRect(72, 600, 300, 20 + 10 * i);
                    content.fill();
                }
            }
            return save(document);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Needs the user password "secret" to open. */
    public static byte[] passwordProtectedPdf() {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(PDRectangle.LETTER));
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner", "secret", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            return save(document);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Opens without a password; an owner password only restricts printing. */
    public static byte[] ownerPasswordOnlyPdf() {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(PDRectangle.LETTER));
            AccessPermission permissions = new AccessPermission();
            permissions.setCanPrint(false);
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner", "", permissions);
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            return save(document);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Starts like a PDF, then holds nothing a parser can recover. */
    public static byte[] corruptPdf() {
        byte[] garbage = new byte[2048];
        Arrays.fill(garbage, (byte) 0x7f);
        return concat("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII), garbage);
    }

    public static byte[] png(int width, int height) {
        return encode(solid(width, height, BufferedImage.TYPE_INT_RGB, Color.LIGHT_GRAY), "png");
    }

    /** Fully transparent except a blue square in the middle. */
    public static byte[] transparentPng() {
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(40, 40, 20, 20);
        g.dispose();
        return encode(image, "png");
    }

    /** A PNG whose header declares 20,000 × 20,000 pixels; it has no image data, so it can't be decoded. */
    public static byte[] hugePngHeader() {
        ByteBuffer ihdr = ByteBuffer.allocate(13);
        ihdr.putInt(20_000)
                .putInt(20_000)
                .put((byte) 8)
                .put((byte) 2)
                .put((byte) 0)
                .put((byte) 0)
                .put((byte) 0);
        return concat(
                new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'},
                chunk("IHDR", ihdr.array()),
                chunk("IEND", new byte[0]));
    }

    public static BufferedImage marker() {
        BufferedImage image = solid(MARKER_WIDTH, MARKER_HEIGHT, BufferedImage.TYPE_INT_RGB, Color.WHITE);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, MARKER_SIZE, MARKER_SIZE);
        g.dispose();
        return image;
    }

    public static byte[] markerPng() {
        return encode(marker(), "png");
    }

    /** The marker image as a JPEG with an EXIF Orientation tag: 8 means "rotate 90° anticlockwise to view". */
    public static byte[] markerJpeg(int orientation) {
        byte[] jpeg = encode(marker(), "jpg");
        return concat(Arrays.copyOf(jpeg, 2), exifOrientation(orientation), Arrays.copyOfRange(jpeg, 2, jpeg.length));
    }

    /** Two frames, 200 × 100 then 100 × 200. */
    public static byte[] twoFrameTiff() {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("tiff").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.prepareWriteSequence(null);
            writer.writeToSequence(
                    new IIOImage(solid(200, 100, BufferedImage.TYPE_INT_RGB, Color.GRAY), null, null), null);
            writer.writeToSequence(
                    new IIOImage(solid(100, 200, BufferedImage.TYPE_INT_RGB, Color.DARK_GRAY), null, null), null);
            writer.endWriteSequence();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    public static byte[] webp() {
        // ImageIO has no WEBP writer; a minimal lossless WEBP (1 × 1 white pixel) is enough to be recognised
        return java.util.HexFormat.of().parseHex("524946461a000000574542505650384c0d0000002f0000001007101111888808");
    }

    /** What a .docx is underneath: a ZIP with Word's parts. */
    public static byte[] docx() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("<w:document/>".getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    public static boolean isRed(int rgb) {
        Color color = new Color(rgb);
        return color.getRed() > 180 && color.getGreen() < 90 && color.getBlue() < 90;
    }

    private static BufferedImage solid(int width, int height, int type, Color color) {
        BufferedImage image = new BufferedImage(width, height, type);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
    }

    private static byte[] encode(BufferedImage image, String format) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("No ImageIO writer for " + format);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static byte[] save(PDDocument document) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.save(out);
        return out.toByteArray();
    }

    /** An APP1 "Exif" segment holding one big-endian IFD with a single Orientation (0x0112) entry. */
    private static byte[] exifOrientation(int orientation) {
        ByteBuffer tiff = ByteBuffer.allocate(26);
        tiff.put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(8); // header, IFD at offset 8
        tiff.putShort((short) 1); // one entry
        tiff.putShort((short) 0x0112)
                .putShort((short) 3)
                .putInt(1)
                .putShort((short) orientation)
                .putShort((short) 0);
        tiff.putInt(0); // no next IFD
        byte[] payload = concat("Exif\0\0".getBytes(StandardCharsets.US_ASCII), tiff.array());
        ByteBuffer segment = ByteBuffer.allocate(4 + payload.length);
        segment.put((byte) 0xFF)
                .put((byte) 0xE1)
                .putShort((short) (payload.length + 2))
                .put(payload);
        return segment.array();
    }

    private static byte[] chunk(String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        ByteBuffer chunk = ByteBuffer.allocate(12 + data.length);
        chunk.putInt(data.length).put(typeBytes).put(data).putInt((int) crc.getValue());
        return chunk.array();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
