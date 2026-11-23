package com.bnaqica.documentreader.processors;

import com.bnaqica.documentreader.exception.UnsupportedDocumentException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MimeTypes;
import org.apache.tika.parser.ParseContext;
import org.springframework.stereotype.Component;

/**
 * Detects an upload's type from its bytes alone. The file name and declared type are deliberately not passed to
 * Tika, so neither can change the answer.
 */
@Component
public class ContentTypeDetector {

    private static final Map<String, DetectedType> SUPPORTED = Map.of(
            "application/pdf", DetectedType.PDF,
            "image/png", DetectedType.PNG,
            "image/jpeg", DetectedType.JPEG,
            "image/heic", DetectedType.HEIC,
            "image/heif", DetectedType.HEIC,
            "image/tiff", DetectedType.TIFF,
            "image/webp", DetectedType.WEBP);

    private final MimeTypes mimeTypes = MimeTypes.getDefaultMimeTypes();

    /** @throws UnsupportedDocumentException for any other content, including empty input */
    public DetectedType detect(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new UnsupportedDocumentException("(empty)");
        }
        MediaType mediaType;
        try (TikaInputStream stream = TikaInputStream.get(bytes)) {
            mediaType = mimeTypes.detect(stream, new Metadata(), new ParseContext());
        } catch (IOException e) {
            throw new UncheckedIOException(e); // reading a byte array doesn't fail
        }
        DetectedType type = SUPPORTED.get(mediaType.getBaseType().toString());
        if (type == null) {
            throw new UnsupportedDocumentException(mediaType.getBaseType().toString());
        }
        return type;
    }
}
