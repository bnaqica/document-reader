# Proposal

## Why

OpenAI vision accepts PNG, JPEG, WEBP and GIF, but not PDF pages or HEIC, and callers upload PDFs, phone photos
(HEIC, often sideways), TIFF scans, JPEG and WEBP. Intake (WI-11) must reject a file it can't read before anything is
stored, and separation (WI-12) needs every file as upright, size-capped PNG pages (§3, §7.1 and §8 of
`docs/design/2026-11-20-document-reader-design.md`). This is WI-08 of `docs/workitems/`. It needs only WI-01.

## What Changes

- **Content detection:** the real type of an upload comes from its bytes, never its name or declared type. PDF, PNG,
  JPEG, HEIC/HEIF, TIFF and WEBP are supported; anything else is refused as unsupported (415 at intake).
- **Page count:** each supported type reports its page count without rendering. An encrypted or corrupt file, or an
  image too large to decode safely, is refused as unreadable (422 at intake).
- **Conversion to PNG pages:** one PNG per page, in order: PDF pages, TIFF frames, or the single image. Every page is
  upright (EXIF orientation applied, HEIC rotation honoured), at most `conversion.max-dimension` on its longest side,
  RGB with no alpha.
- **HEIC:** converted through ImageMagick, a native tool, because Java has no reliable HEIC decoder.
- **Container image:** the Jib base image gains ImageMagick with HEIC support, and CI installs it so the HEIC tests
  run there.

## Capabilities

### New Capabilities

- `document-conversion`: detecting an upload's type, counting its pages, and turning it into upright, size-capped PNG
  pages; and which inputs are refused as unsupported or unreadable.

### Modified Capabilities

None.

## Impact

- **Code:** `processors/ContentTypeDetector`, `processors/ImageConversionProcessor`, `processors/DetectedType`,
  `processors/ConvertedPage`, `exception/UnsupportedDocumentException`, `exception/UnreadableDocumentException`.
- **Build:** TwelveMonkeys `imageio-jpeg` added (CMYK and ICC-profiled JPEGs), same pinned version as the TIFF and WEBP
  readers.
- **Image:** `src/main/docker/base/Dockerfile` (Temurin 25 JRE plus ImageMagick and libheif), and Jib's `from.image`
  points at it.
- **CI:** `.github/workflows/build.yml` installs ImageMagick with HEIC support before the build.
- **Later work items:** WI-11 (intake checks) and WI-12 (separation) use these processors.
