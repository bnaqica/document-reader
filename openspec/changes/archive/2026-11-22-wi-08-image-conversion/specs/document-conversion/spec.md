# Spec Delta

## Purpose

Defines how an uploaded file is recognised, counted and turned into the PNG pages every later step sends to the
vision model: which types are supported, which files are refused and why, and what each page looks like.

## ADDED Requirements

### Requirement: An upload's type comes from its content
The type of an upload SHALL be detected from its bytes alone, ignoring its file name and declared type. PDF, PNG, JPEG,
HEIC/HEIF, TIFF and WEBP SHALL be supported. Any other content, including Office documents, archives, text and empty
input, SHALL be refused as unsupported.

#### Scenario: Misnamed PDF
- **WHEN** a PDF uploaded as `scan.png` is detected
- **THEN** its type is PDF

#### Scenario: Word document
- **WHEN** a `.docx` file is detected
- **THEN** it is refused as unsupported

#### Scenario: Empty upload
- **WHEN** zero bytes are detected
- **THEN** they are refused as unsupported

### Requirement: Unreadable files are refused before any work
Counting a file's pages SHALL succeed for every readable file of a supported type, without rendering it: the number of
pages for a PDF, the number of frames for a TIFF, and 1 for any other image. A password-protected PDF, a corrupt or
truncated file, or an image larger than 100 million pixels SHALL be refused as unreadable, with a reason that names no
file content.

#### Scenario: Three-page PDF
- **WHEN** the pages of a 3-page PDF are counted
- **THEN** the count is 3

#### Scenario: Password-protected PDF
- **WHEN** the pages of a PDF that needs a password to open are counted
- **THEN** it is refused as unreadable

#### Scenario: Corrupt PDF
- **WHEN** the pages of a truncated PDF are counted
- **THEN** it is refused as unreadable

#### Scenario: Decompression bomb
- **WHEN** the pages of a PNG declaring 20,000 × 20,000 pixels are counted
- **THEN** it is refused as unreadable, without decoding the pixels

### Requirement: Each page becomes one PNG, in order
Converting a file SHALL give one PNG per page, numbered from 1 in document order: each PDF page, each TIFF frame, or
the single image. Each page SHALL report its width and height.

#### Scenario: PDF
- **WHEN** a 3-page PDF is converted
- **THEN** there are 3 PNG pages, numbered 1 to 3

#### Scenario: Multi-frame TIFF
- **WHEN** a TIFF with 2 frames is converted
- **THEN** there are 2 PNG pages, in frame order

### Requirement: Pages are upright
A converted page SHALL be upright: the EXIF orientation of a JPEG, WEBP or TIFF SHALL be applied, and the rotation a
HEIC image declares SHALL be honoured, before the page is encoded.

#### Scenario: JPEG rotated 90° anticlockwise
- **GIVEN** a JPEG whose pixels are stored 300 wide by 200 high, with EXIF orientation 8 and a marker in its top-left
  stored corner
- **WHEN** it is converted
- **THEN** the page is 200 wide by 300 high, and the marker is in its bottom-left corner

#### Scenario: HEIC phone photo
- **GIVEN** a HEIC image stored 300 wide by 200 high, marked as rotated 90° clockwise, with a marker in its top-left
  stored corner
- **WHEN** it is converted
- **THEN** the page is 200 wide by 300 high, and the marker is in its top-right corner

### Requirement: Pages are size-capped RGB
Every page SHALL be at most `conversion.max-dimension` (2048) on its longest side, keeping its aspect ratio, and SHALL
never be enlarged. PDF pages SHALL be rendered at `conversion.dpi` (200), or less where that would exceed the size cap.
Every page SHALL be RGB with no alpha channel; transparent areas SHALL become white.

#### Scenario: Large photo
- **WHEN** a 4000 × 6000 PNG is converted
- **THEN** the page is 1365 × 2048

#### Scenario: Small image
- **WHEN** an 800 × 600 PNG is converted
- **THEN** the page is 800 × 600

#### Scenario: Letter-size PDF page
- **WHEN** a US Letter PDF page is converted
- **THEN** the page is at most 2048 high and keeps the page's aspect ratio

#### Scenario: Transparent PNG
- **WHEN** an RGBA PNG with a transparent background is converted
- **THEN** the page has no alpha channel and its background is white

### Requirement: The service image can convert HEIC
The container image the service ships in SHALL include a native HEIC decoder, so HEIC uploads convert in every
environment. When the decoder is missing or fails, converting a HEIC file SHALL be refused as unreadable, not crash.

#### Scenario: Decoder missing
- **GIVEN** no HEIC decoder is installed
- **WHEN** a HEIC file is converted
- **THEN** it is refused as unreadable
