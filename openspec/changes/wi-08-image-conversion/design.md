# Design

## Context

WI-01 added PDFBox 3.0.8, TwelveMonkeys `imageio-tiff` and `imageio-webp` 3.15.3, and `tika-core` 4.2.0, and bound
`conversion.dpi` (200) and `conversion.max-dimension` (2048). Jib built from `eclipse-temurin:25-jre`, which now
tracks Ubuntu 26.04 (ImageMagick 7), and CI runs `./gradlew build` on `ubuntu-latest` (24.04, ImageMagick 6). ImageMagick isn't installed on the developer's machine.
See `proposal.md` for why, and the spec for behaviour.

Rules that shape this:
- `processors` holds processing that isn't a service; exceptions live in `exception`;
- logs carry ids and counts only, never file names or content.

## Goals / Non-Goals

**Goals:**
- The interfaces in `docs/workitems/WI-08-content-detection-and-conversion-to-png.md`, which WI-11 and WI-12 use.
- Review focus #1 (unreadable PDFs refused) and #5 (sideways photos upright), each pinned by a test.

**Non-Goals:**
- Comparing the detected type with the declared one, the page cap (`file.max-pages`), and HTTP statuses: intake
  (WI-11) owns those, using these processors. No exception handler is added here.
- Tiling very tall pages (§8 leaves it to testing on real statements).
- Animated WEBP or multi-image HEIC: the first image is used.

## Decisions

### 1. Types and results

- `processors/DetectedType` enum: `PDF`, `PNG`, `JPEG`, `HEIC`, `TIFF`, `WEBP`, each with its MIME type and file
  extension (`pdf`, `png`, `jpg`, `heic`, `tiff`, `webp`), which WI-11 passes to `S3Keys.original`.
- `processors/ConvertedPage(int pageNumber, byte[] png, int width, int height)`.
- `exception/UnsupportedDocumentException` and `exception/UnreadableDocumentException`: runtime exceptions whose
  messages name the type and the reason (for example "PDF is password-protected"), never content.

### 2. `processors/ContentTypeDetector`

Tika's `MimeTypes.getDefaultMimeTypes()` detects from the bytes alone (no name hint), then a fixed map turns
`application/pdf`, `image/png`, `image/jpeg`, `image/heic`, `image/heif`, `image/tiff` and `image/webp` into a
`DetectedType`. Anything else, including `application/zip` (what `tika-core` reports for DOCX without the parser
modules) and empty input, throws `UnsupportedDocumentException`.

### 3. `processors/ImageConversionProcessor`

Built by `config/ConversionConfig` from `conversion.dpi`, `conversion.max-dimension` and the `HeicConverter`.

- **`pageCount(bytes, type)`:**
  - PDF: `Loader.loadPDF(bytes)`; `InvalidPasswordException` → "password-protected"; any `IOException` →
    "corrupt". A PDF that opens without a password but restricts printing is readable.
  - TIFF: an `ImageReader` `getNumImages(true)`; images: 1 after reading the header. HEIC: 1, after ImageMagick reads
    its size with `-ping` (no pixel decode).
  - Every image's declared width × height is checked from the header against 100 million pixels before any pixels
    are decoded (decompression bombs).
- **`toPngPages(bytes, type)`:**
  - **PDF:** each page rendered with `PDFRenderer.renderImageWithDPI(page, dpi, ImageType.RGB)`, where `dpi` is the
    lower of `conversion.dpi` and the DPI at which the page's longest side is exactly `max-dimension`. Rendering
    straight to the target size costs less than rendering large and shrinking, and gives the same pixels. *(The work
    item says "at 200 DPI"; a Letter page at 200 DPI is 2200 px high, so it would be shrunk anyway.)*
  - **TIFF, JPEG, PNG, WEBP:** decoded with ImageIO (TwelveMonkeys readers, including `imageio-jpeg` for CMYK and
    ICC-profiled JPEGs), one page per TIFF frame.
  - **Orientation:** the EXIF `Orientation` tag (1–8) is read with TwelveMonkeys' metadata reader for JPEG, WEBP and
    TIFF, and applied with an `AffineTransform`.
  - **HEIC:** `HeicConverter` turns the bytes into PNG bytes, already upright, then they go through the same resize.
  - **Then for every page:** draw onto a white `TYPE_INT_RGB` canvas (drops alpha and odd colour models), shrink with
    bicubic interpolation so the longest side is at most `max-dimension` (never enlarge; rounding to the nearest
    pixel, so 4000 × 6000 becomes 1365 × 2048), and encode as PNG.
  - Logs carry the type, the page count and the elapsed time only.

### 4. `processors/HeicConverter`

- Runs ImageMagick through `ProcessBuilder`: `magick` if it is on the `PATH`, otherwise `convert` (ImageMagick 6). The
  commands are `<tool> <limits> heic:<in>[0] -auto-orient png:<out>` and `<tool> <limits> -ping heic:<in>[0]
  -format "%w %h" info:`. `[0]` takes the first image of a multi-image HEIC. Through libheif, ImageMagick honours the
  rotation a HEIC declares (`irot`), and `-auto-orient` applies any EXIF orientation as well; checked on both
  ImageMagick 6.9.12 and 7.1.2, without rotating twice.
- `<limits>` are `-limit width 16KP -limit height 16KP -limit area 100MP -limit memory 512MiB -limit disk 1GiB
  -limit time 60`, so a crafted file is bounded whatever the installed `policy.xml` says.
- Input, output and the tool's standard output go in files in a fresh directory from `Files.createTempDirectory` with
  owner-only permissions, deleted afterwards (a failed delete is logged). Standard output goes to a file so the 60 s
  timeout holds even if the tool never closes it. A timeout, a non-zero exit or a missing tool throws
  `UnreadableDocumentException`, and a still-running process is killed and waited for before the directory is
  deleted. Error output is discarded, because it can echo file metadata.
- `isAvailable()` tells whether a tool was found; tests use it with `@EnabledIf`.

### 5. Fixtures

The work item lists binary fixtures in `src/test/resources/fixtures/`. Instead, a test helper `ConversionFixtures`
builds them in code, so each one shows what it contains:
- PDFs with PDFBox: 3 pages, one password-protected (`StandardProtectionPolicy`), and one truncated (corrupt);
- a 2-frame TIFF through the TwelveMonkeys writer;
- PNGs: 4000 × 6000, 800 × 600, RGBA with a transparent background, and a header declaring 20,000 × 20,000;
- a JPEG 300 × 200 with a red marker in its top-left corner and an EXIF APP1 segment carrying orientation 8, inserted
  after the SOI marker;
- a HEIC made at test time by ImageMagick from the marker image with `-orient RightTop` (rotated 90° clockwise), only
  when ImageMagick is installed;
- a DOCX-like ZIP and empty bytes.

*Alternative:* committing binaries. Generated fixtures need no tools on the developer's machine, except for HEIC.

### 6. The image and CI

- `src/main/docker/base/Dockerfile`: `FROM eclipse-temurin:25-jre-noble` (pinned to Ubuntu 24.04), then
  `imagemagick libheif1 libheif-plugin-libde265`. `libheif1` without recommends brings only an AV1 decoder; the
  libde265 plugin is the HEVC decoder iPhone photos need. The build writes and reads back a real HEIC with a
  temporary x265 encoder, then removes the encoder, so a missing decoder fails the image build. Verified here.
- Jib's `from.image` is the property `baseImage`, defaulting to `docker://document-reader-base:1`: without
  `docker://`, Jib pulls from a registry instead of the local daemon. `configurationName =
  'productionRuntimeClasspath'` stays. Verified: `jibDockerBuild` builds on it.
- `.github/workflows/build.yml` installs `imagemagick libheif1 libheif-plugin-libde265 libheif-plugin-x265` before the
  build, so the HEIC tests run there (the encoder is only needed to create the test fixture).

### 7. Changes after code review

- The three deployment fixes above (HEVC decoder, `docker://`, the CI decoder), the `-ping`, `[0]` and `-limit`
  arguments, and the timeout covering the size read.
- Images more than twice the target size are decoded subsampled (`ImageReadParam.setSourceSubsampling`), and PDFBox
  may subsample embedded images (`setSubsamplingAllowed`), so a large photo or scan is never in memory at full
  resolution. A PDFBox runtime error is reported as unreadable, not a server error.
- Tests now cover all eight EXIF orientations and a PDF with only an owner password.
- Every frame of a multi-frame TIFF takes IFD0's orientation.
- `toPngPages` still returns every page at once (about 2 MB per PNG, so 200 MB for 100 pages). WI-12 should take pages
  one at a time if that matters; its work item notes it.

## Risks / Trade-offs

- [The HEIC tests skip where ImageMagick can't create the fixture, as on this machine] → CI installs the encoder and
  decoder; the developer checks the CI report shows them run, not skipped.
- [ImageMagick has a large attack surface] → It only ever sees HEIC, in a private temp directory, under a timeout.
- [All PNG pages of a 100-page PDF held at once] → About 200 MB; rendering and encoding still go page by page.

## Migration Plan

Build the base image once per environment (or push it to the registry), then `jib` as before.
