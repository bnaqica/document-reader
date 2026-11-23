package com.bnaqica.documentreader.processors;

import com.bnaqica.documentreader.exception.UnreadableDocumentException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/** Counts an upload's pages and turns them into upright, size-capped RGB PNG pages. */
@Slf4j
@RequiredArgsConstructor
public class ImageConversionProcessor {

    /** Above this, decoding an image could exhaust memory (a decompression bomb), so it is refused from the header. */
    static final long MAX_PIXELS = 100_000_000L;

    private static final float POINTS_PER_INCH = 72f;

    private final int dpi;
    private final int maxDimension;
    private final HeicConverter heic;

    /**
     * Pages of a PDF, frames of a TIFF, or 1 for any other image, read without rendering.
     *
     * @throws UnreadableDocumentException for a password-protected, corrupt or oversized file
     */
    public int pageCount(byte[] bytes, DetectedType type) {
        return switch (type) {
            case PDF -> pdfPageCount(bytes);
            case HEIC -> heicPageCount(bytes);
            default -> imageFrameCount(bytes, type);
        };
    }

    /**
     * One upright RGB PNG per page, at most {@code max-dimension} on its longest side.
     *
     * @throws UnreadableDocumentException for a password-protected, corrupt or oversized file
     */
    public List<ConvertedPage> toPngPages(byte[] bytes, DetectedType type) {
        long started = System.nanoTime();
        List<ConvertedPage> pages = switch (type) {
            case PDF -> pdfPages(bytes);
            case HEIC -> List.of(heicPage(bytes));
            default -> imagePages(bytes, type);
        };
        log.info(
                "Converted a {} into {} pages in {} ms",
                type,
                pages.size(),
                Duration.ofNanos(System.nanoTime() - started).toMillis());
        return pages;
    }

    private List<ConvertedPage> pdfPages(byte[] bytes) {
        try (PDDocument document = openPdf(bytes)) {
            PDFRenderer renderer = new PDFRenderer(document);
            // embedded images are decoded only as finely as the page needs, so a huge scan can't exhaust memory
            renderer.setSubsamplingAllowed(true);
            List<ConvertedPage> pages = new ArrayList<>();
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                float renderDpi = renderDpi(document.getPage(index));
                BufferedImage rendered = renderer.renderImageWithDPI(index, renderDpi, ImageType.RGB);
                pages.add(page(index + 1, rendered, ExifOrientation.UPRIGHT));
            }
            return pages;
        } catch (IOException | RuntimeException e) {
            if (e instanceof UnreadableDocumentException unreadable) {
                throw unreadable;
            }
            throw new UnreadableDocumentException("PDF is corrupt", e);
        }
    }

    /**
     * {@code conversion.dpi}, or less when that would make the page's longest side exceed {@code max-dimension}:
     * rendering straight to the target size gives the same pixels as rendering large and shrinking.
     */
    private float renderDpi(PDPage page) {
        PDRectangle box = page.getCropBox();
        float longestPoints = Math.max(box.getWidth(), box.getHeight());
        return Math.min(dpi, maxDimension * POINTS_PER_INCH / longestPoints);
    }

    private List<ConvertedPage> imagePages(byte[] bytes, DetectedType type) {
        int frames = imageFrameCount(bytes, type); // refuses corrupt and oversized images before decoding
        int orientation = ExifOrientation.read(bytes, type);
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            ImageReader reader = reader(stream, type);
            try {
                reader.setInput(stream);
                List<ConvertedPage> pages = new ArrayList<>();
                for (int frame = 0; frame < frames; frame++) {
                    pages.add(page(frame + 1, reader.read(frame, subsampled(reader, frame)), orientation));
                }
                return pages;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof UnreadableDocumentException unreadable) {
                throw unreadable;
            }
            throw new UnreadableDocumentException(type + " is corrupt", e);
        }
    }

    /**
     * Decodes only every n-th pixel when the image is more than twice the target size, so a large photo never sits in
     * memory at full resolution; the final bicubic shrink still has twice the pixels it needs.
     */
    private ImageReadParam subsampled(ImageReader reader, int frame) throws IOException {
        int longest = Math.max(reader.getWidth(frame), reader.getHeight(frame));
        int step = Math.max(1, longest / (2 * maxDimension));
        ImageReadParam param = reader.getDefaultReadParam();
        param.setSourceSubsampling(step, step, 0, 0);
        return param;
    }

    /** Upright, flattened onto white (no alpha, plain RGB), shrunk to fit, encoded as PNG. */
    ConvertedPage page(int pageNumber, BufferedImage source, int orientation) {
        int uprightWidth = ExifOrientation.swapsDimensions(orientation) ? source.getHeight() : source.getWidth();
        int uprightHeight = ExifOrientation.swapsDimensions(orientation) ? source.getWidth() : source.getHeight();
        BufferedImage upright = new BufferedImage(uprightWidth, uprightHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = upright.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, uprightWidth, uprightHeight);
            g.drawImage(source, ExifOrientation.transform(orientation, source.getWidth(), source.getHeight()), null);
        } finally {
            g.dispose();
        }
        BufferedImage fitted = shrinkToFit(upright);
        return new ConvertedPage(pageNumber, encodePng(fitted), fitted.getWidth(), fitted.getHeight());
    }

    /** Never enlarges. Halves first while far too big, so the final bicubic step doesn't skip pixels. */
    private BufferedImage shrinkToFit(BufferedImage image) {
        int longest = Math.max(image.getWidth(), image.getHeight());
        if (longest <= maxDimension) {
            return image;
        }
        double scale = (double) maxDimension / longest;
        int targetWidth = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int targetHeight = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage current = image;
        while (current.getWidth() / 2 >= targetWidth && current.getHeight() / 2 >= targetHeight) {
            current = scale(current, current.getWidth() / 2, current.getHeight() / 2);
        }
        return scale(current, targetWidth, targetHeight);
    }

    private static BufferedImage scale(BufferedImage image, int width, int height) {
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(image, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return scaled;
    }

    private static byte[] encodePng(BufferedImage image) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // writing to memory doesn't fail
        }
        return out.toByteArray();
    }

    private int heicPageCount(byte[] bytes) {
        int[] size = heic.dimensions(bytes);
        checkPixels(size[0], size[1], DetectedType.HEIC);
        return 1;
    }

    /** ImageMagick returns the image already upright, so only flattening and shrinking remain. */
    private ConvertedPage heicPage(byte[] bytes) {
        heicPageCount(bytes); // refuses an oversized image before decoding it
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(heic.toPng(bytes)));
            if (image == null) {
                throw new UnreadableDocumentException("HEIC is corrupt");
            }
            return page(1, image, ExifOrientation.UPRIGHT);
        } catch (IOException e) {
            throw new UnreadableDocumentException("HEIC is corrupt", e);
        }
    }

    private static int pdfPageCount(byte[] bytes) {
        try (PDDocument document = openPdf(bytes)) {
            return document.getNumberOfPages();
        } catch (IOException e) {
            throw new UnreadableDocumentException("PDF is corrupt", e);
        }
    }

    private static PDDocument openPdf(byte[] bytes) {
        try {
            return Loader.loadPDF(bytes);
        } catch (InvalidPasswordException e) {
            throw new UnreadableDocumentException("PDF is password-protected", e);
        } catch (IOException e) {
            throw new UnreadableDocumentException("PDF is corrupt", e);
        }
    }

    private static int imageFrameCount(byte[] bytes, DetectedType type) {
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            ImageReader reader = reader(stream, type);
            try {
                reader.setInput(stream);
                int frames = type == DetectedType.TIFF ? reader.getNumImages(true) : 1;
                for (int frame = 0; frame < frames; frame++) {
                    checkPixels(reader.getWidth(frame), reader.getHeight(frame), type);
                }
                return frames;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof UnreadableDocumentException unreadable) {
                throw unreadable;
            }
            throw new UnreadableDocumentException(type + " is corrupt", e);
        }
    }

    private static ImageReader reader(ImageInputStream stream, DetectedType type) {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
        if (!readers.hasNext()) {
            throw new UnreadableDocumentException(type + " is corrupt");
        }
        return readers.next();
    }

    private static void checkPixels(long width, long height, DetectedType type) {
        if (width * height > MAX_PIXELS) {
            throw new UnreadableDocumentException(
                    type + " is " + width + " x " + height + " pixels, more than " + MAX_PIXELS + " pixels");
        }
    }
}
