package com.bnaqica.documentreader.processors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bnaqica.documentreader.exception.UnreadableDocumentException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives the converter with small shell scripts standing in for ImageMagick, so the process handling is tested on any
 * machine. Real HEIC decoding is tested in ImageConversionProcessorTests where ImageMagick is installed.
 */
class HeicConverterTests {

    @TempDir
    private Path work;

    @TempDir
    private Path tempRoot;

    private Path script(String body) throws IOException {
        Path script = work.resolve("fake-magick-" + System.nanoTime());
        Files.writeString(script, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script;
    }

    /** Like ImageMagick: the last argument is the output, `png:<path>`, or `info:` to print the size. */
    private HeicConverter fake(String body) throws IOException {
        return new HeicConverter(script(body), tempRoot, Duration.ofSeconds(5));
    }

    private boolean tempRootIsEmpty() throws IOException {
        try (Stream<Path> entries = Files.list(tempRoot)) {
            return entries.findAny().isEmpty();
        }
    }

    @Test
    void convertsThroughTheToolAndCleansUp() throws IOException {
        Path png = work.resolve("page.png");
        Files.write(png, ConversionFixtures.markerPng());
        HeicConverter converter = fake("for last; do :; done; cp '" + png + "' \"${last#png:}\"");

        byte[] result = converter.toPng(new byte[] {1, 2, 3});

        assertThat(result).isEqualTo(ConversionFixtures.markerPng());
        assertThat(tempRootIsEmpty()).isTrue();
    }

    @Test
    void passesTheInputAsHeicAndAsksForUpright() throws IOException {
        Path args = work.resolve("args.txt");
        HeicConverter converter = fake("echo \"$@\" > '" + args + "'; exit 1");

        assertThatThrownBy(() -> converter.toPng(new byte[] {1})).isInstanceOf(UnreadableDocumentException.class);

        String passed = Files.readString(args);
        assertThat(passed).contains("-limit area 100MP").contains("-limit time 60");
        // only the first image of a multi-image HEIC, as HEIC, made upright, written as PNG
        assertThat(passed).containsPattern("heic:\\S+\\[0\\] -auto-orient png:\\S+");
    }

    @Test
    void sizeIsReadWithoutDecodingTheFirstImage() throws IOException {
        Path args = work.resolve("args.txt");
        HeicConverter converter = fake("echo \"$@\" > '" + args + "'; echo '300 200'");

        converter.dimensions(new byte[] {1});

        assertThat(Files.readString(args)).containsPattern("-ping heic:\\S+\\[0\\] -format %w %h info:");
    }

    /** A tool that never closes its output must not hold the caller past the timeout. */
    @Test
    void slowSizeReadIsStoppedAtTheTimeout() throws IOException {
        HeicConverter converter = new HeicConverter(script("sleep 10"), tempRoot, Duration.ofMillis(500));

        long started = System.nanoTime();
        assertThatThrownBy(() -> converter.dimensions(new byte[] {1})).isInstanceOf(UnreadableDocumentException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertThat(tempRootIsEmpty()).isTrue();
    }

    @Test
    void readsTheSizeFromTheTool() throws IOException {
        HeicConverter converter = fake("echo '300 200'");

        assertThat(converter.dimensions(new byte[] {1})).containsExactly(300, 200);
        assertThat(tempRootIsEmpty()).isTrue();
    }

    @Test
    void failingToolIsUnreadableAndCleansUp() throws IOException {
        HeicConverter converter = fake("echo 'no decode delegate' >&2; exit 1");

        assertThatThrownBy(() -> converter.toPng(new byte[] {1}))
                .isInstanceOf(UnreadableDocumentException.class)
                .hasMessageNotContaining("delegate");
        assertThat(tempRootIsEmpty()).isTrue();
    }

    @Test
    void slowToolIsStoppedAtTheTimeout() throws IOException {
        HeicConverter converter = new HeicConverter(script("sleep 10"), tempRoot, Duration.ofMillis(500));

        long started = System.nanoTime();
        assertThatThrownBy(() -> converter.toPng(new byte[] {1})).isInstanceOf(UnreadableDocumentException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertThat(tempRootIsEmpty()).isTrue();
    }

    @Test
    void missingToolIsUnreadable() {
        HeicConverter converter = new HeicConverter(null, tempRoot, Duration.ofSeconds(5));

        assertThat(converter.isAvailable()).isFalse();
        assertThatThrownBy(() -> converter.toPng(new byte[] {1}))
                .isInstanceOf(UnreadableDocumentException.class)
                .hasMessageContaining("HEIC");
    }

    @Test
    void findsAToolOnThePathOrReportsNone() {
        HeicConverter converter = HeicConverter.onPath();

        assertThat(converter.isAvailable())
                .isEqualTo(HeicConverter.findOnPath("magick") != null || HeicConverter.findOnPath("convert") != null);
    }
}
