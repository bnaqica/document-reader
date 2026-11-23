package com.bnaqica.documentreader.processors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bnaqica.documentreader.exception.UnsupportedDocumentException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ContentTypeDetectorTests {

    private final ContentTypeDetector detector = new ContentTypeDetector();

    @Test
    void detectsEverySupportedType() {
        assertThat(detector.detect(ConversionFixtures.pdf(1))).isEqualTo(DetectedType.PDF);
        assertThat(detector.detect(ConversionFixtures.png(10, 10))).isEqualTo(DetectedType.PNG);
        assertThat(detector.detect(ConversionFixtures.markerJpeg(1))).isEqualTo(DetectedType.JPEG);
        assertThat(detector.detect(ConversionFixtures.twoFrameTiff())).isEqualTo(DetectedType.TIFF);
        assertThat(detector.detect(ConversionFixtures.webp())).isEqualTo(DetectedType.WEBP);
    }

    /** HEIC starts with an ISO box `ftyp` whose brand is `heic`; the bytes after it don't matter for detection. */
    @Test
    void detectsHeicByItsBrand() {
        byte[] heic = new byte[64];
        byte[] box = {
            0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c', 0, 0, 0, 0, 'm', 'i', 'f', '1', 'h', 'e', 'i', 'c'
        };
        System.arraycopy(box, 0, heic, 0, box.length);

        assertThat(detector.detect(heic)).isEqualTo(DetectedType.HEIC);
    }

    /** The detector sees only bytes, so a PDF's name or declared type can't change what it is. */
    @Test
    void detectsBySniffingNotExtension() {
        assertThat(detector.detect(ConversionFixtures.pdf(3))).isEqualTo(DetectedType.PDF);
    }

    @Test
    void rejectsDocx() {
        assertThatThrownBy(() -> detector.detect(ConversionFixtures.docx()))
                .isInstanceOf(UnsupportedDocumentException.class);
    }

    @Test
    void rejectsText() {
        assertThatThrownBy(() -> detector.detect("Pay stub for October".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(UnsupportedDocumentException.class);
    }

    @Test
    void rejectsEmptyInput() {
        assertThatThrownBy(() -> detector.detect(new byte[0])).isInstanceOf(UnsupportedDocumentException.class);
        assertThatThrownBy(() -> detector.detect(null)).isInstanceOf(UnsupportedDocumentException.class);
    }

    @Test
    void typesKnowTheirExtensionAndMimeType() {
        assertThat(DetectedType.JPEG.extension()).isEqualTo("jpg");
        assertThat(DetectedType.PDF.mimeType()).isEqualTo("application/pdf");
    }
}
