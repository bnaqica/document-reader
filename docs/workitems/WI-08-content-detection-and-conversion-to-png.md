# WI-08: Content detection and conversion to PNG

| | |
|---|---|
| **Status** | Built, awaiting developer review |
| **Depends on** | [WI-01](WI-01-foundation.md) (each committed first) |
| **Design** | `docs/design/2026-11-20-document-reader-design.md` §3, §8 |
| **Source** | `docs/superpowers/plans/2026-11-20-document-reader.md`, Task 9 |

**Goal:** Detect each upload's real type and convert PDF, HEIC, TIFF, JPEG, WEBP and PNG into upright, resized PNG pages.

**Before you start:** read the [global constraints](README.md#global-constraints), which apply to every work item.
Work on this item alone, and don't start the next one until this one is committed.

---

**Files:**
- Create: `processors/ContentTypeDetector`, `processors/ImageConversionProcessor`, `exception/UnsupportedDocumentException`, `exception/UnreadableDocumentException`, and a Jib base image with ImageMagick and libheif (`src/main/jib/` or a custom `from.image`)
- Test: `processors/ContentTypeDetectorTests`, `processors/ImageConversionProcessorTests`, with fixtures in `src/test/resources/fixtures/`: a 3-page PDF, an encrypted PDF, a corrupt PDF, a multi-frame TIFF, a HEIC with EXIF 6, a JPEG with EXIF 8, a 4000×6000 PNG, and an RGBA PNG

**Interfaces:**
- Produces:
  - `ContentTypeDetector.detect(byte[]) → DetectedType`, one of `PDF`, `PNG`, `JPEG`, `HEIC`, `TIFF` or `WEBP`. It throws `UnsupportedDocumentException`, which maps to 415.
  - `ImageConversionProcessor.pageCount(byte[], DetectedType) → int`, which throws `UnreadableDocumentException` (422).
  - `ImageConversionProcessor.toPngPages(byte[], DetectedType) → List<ConvertedPage(int pageNumber, byte[] png, int width, int height)>`.

- [x] **Step 1: Write the failing tests.**
  - `detectsBySniffingNotExtension()`: a PDF named `.png` is detected as `PDF`.
  - `rejectsDocx()` throws `UnsupportedDocumentException`.
  - `pdfRendersOnePngPerPage()`: the 3-page PDF gives 3 pages at 200 DPI, each with its longest side at most 2048.
  - `tiffFramesBecomePages()`
  - `resizesToMaxDimension()`: 4000×6000 becomes 1365×2048.
  - `dropsAlpha()`: the output PNG has no alpha channel.
  - Review Focus #5: `heicWithExif6IsUpright()` and `jpegWithExif8IsUpright()`. The output width and height are swapped relative to the raw pixel grid, and a marker pixel in the fixture lands in the expected corner.
  - Review Focus #1: `encryptedPdfIsUnreadable()` and `corruptPdfIsUnreadable()` both throw `UnreadableDocumentException` from `pageCount`.
- [x] **Step 2: Run** the tests. Expected: FAIL. Mark the HEIC tests with `@EnabledIf` on ImageMagick being present, and make sure CI's runner installs it.
- [x] **Step 3: Implement.** Use Tika `Detector` for sniffing. Render PDFs with PDFBox `PDFRenderer.renderImageWithDPI(page, dpi, ImageType.RGB)`. Read TIFF and WEBP through TwelveMonkeys. Convert HEIC with ImageMagick (`magick input.heic -auto-orient png:-`) through `ProcessBuilder`, using temp files in a private temp directory. Apply EXIF orientation for JPEG and WEBP. Resize with bicubic scaling.
- [x] **Step 4: Update the Jib configuration** with a base image that contains ImageMagick and libheif, keeping `configurationName = 'productionRuntimeClasspath'`. Add the ImageMagick install to `.github/workflows/build.yml`.
- [x] **Step 5: Run** the tests and the build. Expected: PASS.

---

## Done when

- [x] Every step above is ticked.
- [x] `./gradlew spotlessApply build` passes, with all tests green.
- [ ] `git status` shows only the files this work item names, plus their tests and fixtures.
- [x] A code review (`superpowers:requesting-code-review`, using the `code-reviewer` agent) has no open findings.
- [ ] The developer has reviewed the diff.

## Commit

Claude never commits in this repo (`.claude/rules/git-workflow.md`). The developer commits this work item as one
commit, with this suggested message:

```text
feat: add content detection and PNG page conversion
```

Then set **Status** above to `Done`, and do the same for this row in [README.md](README.md#order).
