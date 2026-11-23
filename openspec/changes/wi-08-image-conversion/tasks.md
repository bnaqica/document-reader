# Tasks

Every task with behaviour is test-first (`superpowers:test-driven-development`): write the failing test, run it and
watch it fail for the expected reason, implement, then run it and watch it pass. New files are staged as they're
created; nothing is committed. The developer commits WI-08 as one commit.

## 1. Detection

- [x] 1.1 Write `processors/ConversionFixtures` (test helper, design decision 5) and `processors/ContentTypeDetectorTests`: each supported fixture is detected as its type; `detectsBySniffingNotExtension()` (the PDF is detected as `PDF` whatever name it would have); `rejectsDocx()`, plain text and empty bytes give `UnsupportedDocumentException`. Verify it fails, implement `DetectedType`, `UnsupportedDocumentException` and `ContentTypeDetector` (design decisions 1 and 2), then verify it passes.

## 2. Page counts

- [x] 2.1 Write `processors/ImageConversionProcessorTests` page-count cases: the 3-page PDF counts 3; the 2-frame TIFF counts 2; a PNG and a JPEG count 1; `encryptedPdfIsUnreadable()` and `corruptPdfIsUnreadable()` give `UnreadableDocumentException` naming the reason; the 20,000 × 20,000 PNG gives `UnreadableDocumentException`. Verify it fails, implement `UnreadableDocumentException` and `pageCount` (design decision 3), then verify it passes.

## 3. Conversion

- [x] 3.1 Extend `processors/ImageConversionProcessorTests`: `pdfRendersOnePngPerPage()` (3 pages numbered 1–3, each a decodable PNG with its longest side ≤ 2048 and the Letter aspect ratio); `tiffFramesBecomePages()`; `resizesToMaxDimension()` (4000 × 6000 → 1365 × 2048); a small 800 × 600 image is not enlarged; `dropsAlpha()` (no alpha channel, transparent areas white); `jpegWithExif8IsUpright()` (200 × 300, the marker in the bottom-left corner). Verify it fails, add `imageio-jpeg`, implement `ConvertedPage` and `toPngPages` with orientation, white canvas and bicubic resize, then verify it passes.
- [x] 3.2 Write `processors/HeicConverterTests` and the HEIC cases in `ImageConversionProcessorTests`, enabled only when ImageMagick is present and can write HEIC: `heicWithExif6IsUpright()` (200 × 300, the marker in the top-right corner) and a HEIC page count of 1; always run: a converter pointed at a missing tool gives `UnreadableDocumentException`, and its temp directory is gone afterwards. Verify they fail, implement `HeicConverter` (design decision 4) and wire it into the processor, then verify they pass (the ImageMagick cases skip on this machine; they run in CI).

## 4. Image and CI

- [x] 4.1 Add `src/main/docker/base/Dockerfile`, point Jib's `from.image` at the `baseImage` property (default `document-reader-base:1`) keeping `configurationName`, install ImageMagick and libheif in `.github/workflows/build.yml`, and document building the base image in the README. Verify `./gradlew help -q` still configures and the workflow YAML parses.

## 5. Verification

- [x] 5.1 Update `README.md`: supported types, what is refused, how pages are made, and the base image. Verify it matches the design.
- [x] 5.2 Run `./gradlew spotlessApply build`, and verify BUILD SUCCESSFUL with every test green, including the WI-01 to WI-07 tests, and the HEIC tests reported as skipped here.
- [x] 5.3 Run a code review through `superpowers:requesting-code-review`, using the `code-reviewer` agent with `openspec/changes/wi-08-image-conversion/` as the requirements. Fix every Critical and Important finding, and verify the build again.
- [x] 5.4 Tick the boxes in `docs/workitems/WI-08-content-detection-and-conversion-to-png.md` (except the developer review and commit), and set its status.

## Workflow follow-up

- The developer reviews the diff, checks the HEIC tests ran in CI, and commits WI-08 as one commit.
- Then archive `wi-08-image-conversion`.
