package com.bnaqica.documentreader.processors;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Builds the HEIC fixture with the installed ImageMagick, or gives null where it can't. */
final class ImageMagick {

    private ImageMagick() {}

    /** The marker image as HEIC with orientation "right-top": rotate 90° clockwise to view. */
    static byte[] markerHeicRotatedClockwise() {
        Path tool = HeicConverter.findOnPath("magick");
        if (tool == null) {
            tool = HeicConverter.findOnPath("convert");
        }
        if (tool == null) {
            return null;
        }
        try {
            Path dir = Files.createTempDirectory("heic-fixture-");
            Path png = dir.resolve("marker.png");
            Path heic = dir.resolve("marker.heic");
            Files.write(png, ConversionFixtures.markerPng());
            Process process = new ProcessBuilder(tool.toString(), png.toString(), "-orient", "RightTop", "heic:" + heic)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            byte[] result =
                    finished && process.exitValue() == 0 && Files.size(heic) > 0 ? Files.readAllBytes(heic) : null;
            Files.deleteIfExists(png);
            Files.deleteIfExists(heic);
            Files.deleteIfExists(dir);
            return result;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
