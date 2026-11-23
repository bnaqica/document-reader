package com.bnaqica.documentreader.processors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.bnaqica.documentreader.exception.UnreadableDocumentException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ImageConversionProcessorTests {

    private final ImageConversionProcessor processor = new ImageConversionProcessor(200, 2048, HeicConverter.onPath());

    /** Where ImageMagick is installed and can write HEIC, the marker image as a HEIC rotated 90° clockwise. */
    private static final byte[] HEIC = ImageMagick.markerHeicRotatedClockwise();

    static boolean imageMagickCanWriteHeic() {
        return HEIC != null;
    }

    @Test
    void pdfCountsItsPages() {
        assertThat(processor.pageCount(ConversionFixtures.pdf(3), DetectedType.PDF))
                .isEqualTo(3);
    }

    @Test
    void tiffCountsItsFrames() {
        assertThat(processor.pageCount(ConversionFixtures.twoFrameTiff(), DetectedType.TIFF))
                .isEqualTo(2);
    }

    @Test
    void singleImagesCountOne() {
        assertThat(processor.pageCount(ConversionFixtures.png(10, 10), DetectedType.PNG))
                .isEqualTo(1);
        assertThat(processor.pageCount(ConversionFixtures.markerJpeg(1), DetectedType.JPEG))
                .isEqualTo(1);
    }

    @Test
    void encryptedPdfIsUnreadable() {
        assertThatThrownBy(() -> processor.pageCount(ConversionFixtures.passwordProtectedPdf(), DetectedType.PDF))
                .isInstanceOf(UnreadableDocumentException.class)
                .hasMessageContaining("password");
    }

    @Test
    void corruptPdfIsUnreadable() {
        assertThatThrownBy(() -> processor.pageCount(ConversionFixtures.corruptPdf(), DetectedType.PDF))
                .isInstanceOf(UnreadableDocumentException.class)
                .hasMessageContaining("corrupt");
    }

    @Test
    void corruptImageIsUnreadable() {
        byte[] truncated = java.util.Arrays.copyOf(ConversionFixtures.png(10, 10), 20);

        assertThatThrownBy(() -> processor.pageCount(truncated, DetectedType.PNG))
                .isInstanceOf(UnreadableDocumentException.class);
    }

    /** Refused from the header alone: the fixture has no pixel data, so decoding it would fail differently. */
    @Test
    void decompressionBombIsUnreadable() {
        assertThatThrownBy(() -> processor.pageCount(ConversionFixtures.hugePngHeader(), DetectedType.PNG))
                .isInstanceOf(UnreadableDocumentException.class)
                .hasMessageContaining("pixels");
    }

    private static BufferedImage decode(ConvertedPage page) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(page.png()));
            assertThat(image)
                    .as("page %d is a decodable PNG", page.pageNumber())
                    .isNotNull();
            assertThat(image.getWidth()).isEqualTo(page.width());
            assertThat(image.getHeight()).isEqualTo(page.height());
            return image;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void pdfRendersOnePngPerPage() {
        List<ConvertedPage> pages = processor.toPngPages(ConversionFixtures.pdf(3), DetectedType.PDF);

        assertThat(pages).extracting(ConvertedPage::pageNumber).containsExactly(1, 2, 3);
        assertThat(pages).allSatisfy(page -> {
            BufferedImage image = decode(page);
            assertThat(Math.max(image.getWidth(), image.getHeight())).isLessThanOrEqualTo(2048);
            assertThat((double) image.getWidth() / image.getHeight()).isCloseTo(612.0 / 792.0, within(0.01));
        });
    }

    @Test
    void tiffFramesBecomePages() {
        List<ConvertedPage> pages = processor.toPngPages(ConversionFixtures.twoFrameTiff(), DetectedType.TIFF);

        assertThat(pages).extracting(ConvertedPage::pageNumber).containsExactly(1, 2);
        assertThat(pages).extracting(ConvertedPage::width).containsExactly(200, 100);
        assertThat(pages).extracting(ConvertedPage::height).containsExactly(100, 200);
    }

    @Test
    void resizesToMaxDimension() {
        ConvertedPage page = processor
                .toPngPages(ConversionFixtures.png(4000, 6000), DetectedType.PNG)
                .getFirst();

        assertThat(decode(page).getWidth()).isEqualTo(1365);
        assertThat(page.height()).isEqualTo(2048);
    }

    @Test
    void smallImageIsNotEnlarged() {
        ConvertedPage page = processor
                .toPngPages(ConversionFixtures.png(800, 600), DetectedType.PNG)
                .getFirst();

        assertThat(page.width()).isEqualTo(800);
        assertThat(page.height()).isEqualTo(600);
    }

    @Test
    void dropsAlpha() {
        BufferedImage image = decode(processor
                .toPngPages(ConversionFixtures.transparentPng(), DetectedType.PNG)
                .getFirst());

        assertThat(image.getColorModel().hasAlpha()).isFalse();
        assertThat(image.getRGB(5, 5) & 0xFFFFFF).as("transparent corner").isEqualTo(0xFFFFFF);
        assertThat(image.getRGB(50, 50) & 0xFFFFFF).as("blue centre").isEqualTo(0x0000FF);
    }

    /** Orientation 8: rotate 90° anticlockwise to view, so the stored top-left marker ends bottom-left. */
    @Test
    void jpegWithExif8IsUpright() {
        BufferedImage image = decode(processor
                .toPngPages(ConversionFixtures.markerJpeg(8), DetectedType.JPEG)
                .getFirst());

        assertThat(image.getWidth()).isEqualTo(ConversionFixtures.MARKER_HEIGHT);
        assertThat(image.getHeight()).isEqualTo(ConversionFixtures.MARKER_WIDTH);
        assertThat(ConversionFixtures.isRed(image.getRGB(10, image.getHeight() - 10)))
                .isTrue();
        assertThat(ConversionFixtures.isRed(image.getRGB(10, 10))).isFalse();
    }

    @Test
    void jpegWithoutRotationKeepsItsLayout() {
        BufferedImage image = decode(processor
                .toPngPages(ConversionFixtures.markerJpeg(1), DetectedType.JPEG)
                .getFirst());

        assertThat(image.getWidth()).isEqualTo(ConversionFixtures.MARKER_WIDTH);
        assertThat(ConversionFixtures.isRed(image.getRGB(10, 10))).isTrue();
    }

    @Test
    void conversionRefusesWhatCountingRefuses() {
        assertThatThrownBy(() -> processor.toPngPages(ConversionFixtures.passwordProtectedPdf(), DetectedType.PDF))
                .isInstanceOf(UnreadableDocumentException.class);
        assertThatThrownBy(() -> processor.toPngPages(ConversionFixtures.hugePngHeader(), DetectedType.PNG))
                .isInstanceOf(UnreadableDocumentException.class);
    }

    /** Rotated 90° clockwise to view, so the stored top-left marker ends top-right. */
    @Test
    @EnabledIf("imageMagickCanWriteHeic")
    void heicWithExif6IsUpright() {
        BufferedImage image =
                decode(processor.toPngPages(HEIC, DetectedType.HEIC).getFirst());

        assertThat(image.getWidth()).isEqualTo(ConversionFixtures.MARKER_HEIGHT);
        assertThat(image.getHeight()).isEqualTo(ConversionFixtures.MARKER_WIDTH);
        assertThat(ConversionFixtures.isRed(image.getRGB(image.getWidth() - 10, 10)))
                .isTrue();
        assertThat(ConversionFixtures.isRed(image.getRGB(10, 10))).isFalse();
    }

    @Test
    @EnabledIf("imageMagickCanWriteHeic")
    void heicCountsOnePage() {
        assertThat(processor.pageCount(HEIC, DetectedType.HEIC)).isEqualTo(1);
    }

    @Test
    void heicWithoutADecoderIsUnreadable() {
        ImageConversionProcessor noDecoder = new ImageConversionProcessor(
                200,
                2048,
                new HeicConverter(null, Path.of(System.getProperty("java.io.tmpdir")), Duration.ofSeconds(5)));

        assertThatThrownBy(() -> noDecoder.toPngPages(new byte[] {1}, DetectedType.HEIC))
                .isInstanceOf(UnreadableDocumentException.class);
        assertThatThrownBy(() -> noDecoder.pageCount(new byte[] {1}, DetectedType.HEIC))
                .isInstanceOf(UnreadableDocumentException.class);
    }

    /**
     * Every EXIF orientation, on the 300 × 200 marker image: the upright size, and the corner the stored top-left
     * marker lands in (L/R, T/B).
     */
    @ParameterizedTest(name = "orientation {0}")
    @CsvSource({
        "1, 300, 200, L, T",
        "2, 300, 200, R, T",
        "3, 300, 200, R, B",
        "4, 300, 200, L, B",
        "5, 200, 300, L, T",
        "6, 200, 300, R, T",
        "7, 200, 300, R, B",
        "8, 200, 300, L, B"
    })
    void everyExifOrientationIsMadeUpright(int orientation, int width, int height, char side, char edge) {
        BufferedImage image = decode(processor
                .toPngPages(ConversionFixtures.markerJpeg(orientation), DetectedType.JPEG)
                .getFirst());

        assertThat(image.getWidth()).isEqualTo(width);
        assertThat(image.getHeight()).isEqualTo(height);
        int x = side == 'L' ? 10 : width - 10;
        int y = edge == 'T' ? 10 : height - 10;
        assertThat(ConversionFixtures.isRed(image.getRGB(x, y))).isTrue();
    }

    /** Only an owner password (printing restricted): it opens without one, so it is readable. */
    @Test
    void pdfWithOnlyAnOwnerPasswordIsReadable() {
        byte[] restricted = ConversionFixtures.ownerPasswordOnlyPdf();

        assertThat(processor.pageCount(restricted, DetectedType.PDF)).isEqualTo(1);
        assertThat(processor.toPngPages(restricted, DetectedType.PDF)).hasSize(1);
    }
}
