# Proposal

## Why

WI-11 queues one `SEPARATE_FILE` message per stored file, but nothing handles them yet, so every batch stays
`PROCESSING`. Separation is process 1 of the pipeline (§9 of `docs/design/2026-11-20-document-reader-design.md`). It
finds where each document starts inside an uploaded file, by voting over several model runs, creates the documents,
and queues classification (WI-13) for each one. This is WI-12 of `docs/workitems/`.

## What Changes

- **A `SEPARATE_FILE` handler.** It marks the file `SEPARATING`, then converts its pages to PNG once.
  - The pages are stored in S3 and recorded as page rows, one page at a time, so a 100-page file never holds every
    rendered page in memory.
  - A retry finds the pages already recorded and doesn't convert again.
- **A single-page file** becomes one document without any model call.
- **A longer file:**
  - It is sent in overlapping windows (`separation.window-size` 15, `window-overlap` 2).
  - Each window gets `separation.runs` (3) separation calls, made outside any transaction. Each run is saved as it
    finishes, so a retry reuses the runs that already succeeded.
- **Voting:**
  - Each answer is cleaned: page 1, pages outside the window and duplicates are dropped, and the rest is sorted
    (Review Focus #2).
  - Each gap between two pages is a boundary when more than half of the successful runs of one window put a
    document start there. That window is the one covering both pages where the gap sits furthest from an edge.
  - Page 1 always starts a document.
  - Fewer than a majority of successful runs in a window fails the attempt, so the inbox retries it.
- **One transaction then:**
  - creates the documents, numbered from 1 within the file;
  - marks the file `SEPARATED`;
  - queues one `CLASSIFY_DOCUMENT` message per document;
  - marks the inbox message done.
- **A file whose message is exhausted** is marked `FAILED`, and the batch completion check runs (WI-10), so one bad
  file never leaves its batch hanging.
- **A message for a file that is already separated or failed** does nothing, so a retry or a second delivery can't
  separate a file twice.
- **The separation prompt** now says the images are consecutive pages numbered from 1, so it reads correctly for a
  window.
- **A configuration check from the WI-01 review:** the window overlap must be at least 1 and less than the window
  size. An overlap equal to the window size would make windowing loop forever, and an overlap of 0 would leave the
  gap between two windows undecided.

## Capabilities

### New Capabilities

- `document-separation`: how a stored file is split into documents. This covers the page conversion it reuses, the
  windows, the runs and how they're cleaned, the vote on each gap, the single-page shortcut, and what happens on
  success, on retry, and when the file fails for good.

### Modified Capabilities

- `processing-configuration`: a new requirement, that the separation window overlap is at least 1 and less than the
  window size.

## Impact

- **New code:**
  - `service/SeparationService` and `service/SeparationServiceImpl` (also the `SEPARATE_FILE`
    `InboxMessageHandler`);
  - `processors/BoundaryVoteProcessor`;
  - `dto/event/ClassifyDocumentPayload`.
- **Changed code:**
  - `processors/ImageConversionProcessor` gains a page-at-a-time variant, and `toPngPages` uses it;
  - `config/DocumentReaderProperties` gets the overlap check;
  - `prompts/separate.st`.
- **Tests:**
  - `processors/BoundaryVoteProcessorTests` and `service/SeparationServiceTests`;
  - a new case in `config/DocumentReaderPropertiesValidationTests`, and one in `ImageConversionProcessorTests`.
- **Dependencies and schema:** none new. `separation_run`, `source_file_page` and `document` already exist.
- **Later work items:** WI-13 handles `CLASSIFY_DOCUMENT`. Until then, a separated batch stays `PROCESSING`.
