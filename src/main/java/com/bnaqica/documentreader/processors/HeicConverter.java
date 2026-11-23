package com.bnaqica.documentreader.processors;

import com.bnaqica.documentreader.exception.UnreadableDocumentException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns HEIC into an upright PNG with ImageMagick, because Java has no reliable HEIC decoder: {@code magick} if it is
 * on the {@code PATH}, otherwise {@code convert} (ImageMagick 6). Through libheif, ImageMagick honours the rotation a
 * HEIC declares, and {@code -auto-orient} applies any EXIF orientation too. Only the first image of a multi-image
 * HEIC is used.
 *
 * <p>Each call works in a fresh owner-only temp directory, deleted afterwards, under a timeout and ImageMagick
 * resource limits. The tool's messages are discarded, not logged, because they can echo file metadata.
 */
@Slf4j
@RequiredArgsConstructor
public class HeicConverter {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    /**
     * ImageMagick's own limits, passed before the input so they hold whatever the installed {@code policy.xml} says
     * (Homebrew's sets none). {@code area} matches the 100-megapixel cap applied to other images.
     */
    private static final List<String> RESOURCE_LIMITS = List.of(
            "-limit", "width", "16KP", "-limit", "height", "16KP", "-limit", "area", "100MP", "-limit", "memory",
            "512MiB", "-limit", "disk", "1GiB", "-limit", "time", "60");

    /** May be null, meaning no decoder is installed. */
    private final Path tool;

    private final Path tempRoot;
    private final Duration timeout;

    /** Finds ImageMagick on the {@code PATH}, working in the JVM's temp directory. */
    public static HeicConverter onPath() {
        Path found = findOnPath("magick");
        if (found == null) {
            found = findOnPath("convert");
        }
        if (found == null) {
            log.warn("ImageMagick is not on the PATH; HEIC uploads will be refused as unreadable");
        }
        return new HeicConverter(found, Path.of(System.getProperty("java.io.tmpdir")), DEFAULT_TIMEOUT);
    }

    static Path findOnPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String directory : path.split(File.pathSeparator)) {
            Path candidate = Path.of(directory, name);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    public boolean isAvailable() {
        return tool != null;
    }

    /** Width and height of the first image, read without decoding its pixels ({@code -ping}). */
    public int[] dimensions(byte[] heic) {
        return inWorkDirectory(heic, input -> {
            String output = run(
                    command("-ping", "heic:" + input + "[0]", "-format", "%w %h", "info:"),
                    input.resolveSibling("info.txt"));
            String[] parts = output.trim().split("\\s+");
            if (parts.length != 2) {
                throw new UnreadableDocumentException("HEIC is corrupt");
            }
            try {
                return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
            } catch (NumberFormatException e) {
                throw new UnreadableDocumentException("HEIC is corrupt", e);
            }
        });
    }

    /** The first image as an upright PNG. */
    public byte[] toPng(byte[] heic) {
        return inWorkDirectory(heic, input -> {
            Path output = input.resolveSibling("page.png");
            run(command("heic:" + input + "[0]", "-auto-orient", "png:" + output), input.resolveSibling("out.txt"));
            if (!Files.isRegularFile(output)) {
                throw new UnreadableDocumentException("HEIC is corrupt");
            }
            return Files.readAllBytes(output);
        });
    }

    private List<String> command(String... arguments) {
        List<String> command = new ArrayList<>(List.of(tool.toString()));
        command.addAll(RESOURCE_LIMITS);
        command.addAll(List.of(arguments));
        return command;
    }

    private interface Work<T> {
        T apply(Path input) throws IOException, InterruptedException;
    }

    private <T> T inWorkDirectory(byte[] heic, Work<T> work) {
        if (tool == null) {
            throw new UnreadableDocumentException("HEIC can't be decoded: ImageMagick is not installed");
        }
        Path directory = null;
        try {
            directory = createPrivateDirectory();
            Path input = directory.resolve("input.heic");
            Files.write(input, heic);
            return work.apply(input);
        } catch (IOException e) {
            throw new UnreadableDocumentException("HEIC is corrupt", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnreadableDocumentException("HEIC conversion was interrupted", e);
        } finally {
            deleteQuietly(directory);
        }
    }

    private Path createPrivateDirectory() throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return Files.createTempDirectory(
                    tempRoot,
                    "heic-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        return Files.createTempDirectory(tempRoot, "heic-");
    }

    /**
     * Runs the tool with its standard output in a file, so the timeout holds even if the tool never closes it, and
     * returns that output. Error output is discarded. The process is always gone before the work directory is
     * deleted, so it can't write customer data back afterwards.
     */
    private String run(List<String> command, Path stdout) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(stdout.toFile())
                .start();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new UnreadableDocumentException("HEIC conversion timed out after " + timeout.toMillis() + " ms");
            }
            if (process.exitValue() != 0) {
                log.info("ImageMagick exited with {} on a HEIC upload", process.exitValue());
                throw new UnreadableDocumentException("HEIC is corrupt or unsupported");
            }
            return Files.readString(stdout, StandardCharsets.US_ASCII);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static void deleteQuietly(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(HeicConverter::deleteOrWarn);
        } catch (IOException e) {
            log.warn("Could not delete a HEIC work directory; it may hold customer image data", e);
        }
    }

    private static void deleteOrWarn(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not delete a file in a HEIC work directory; it may hold customer image data", e);
        }
    }
}
