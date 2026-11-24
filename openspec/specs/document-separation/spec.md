# document-separation Specification

## Purpose

Defines how a stored source file is split into documents: converting its pages once, asking the model in windows
for where documents start, cleaning and voting on the answers, creating the documents and queueing their
classification, and what happens when a file is retried or fails for good.

## Requirements

### Requirement: A file's pages are converted once
When separation starts on a stored file, the file SHALL become `SEPARATING`. Its pages SHALL be converted to PNG,
stored in S3 and recorded, one page at a time, before any model call. A retry that finds all of the file's pages
recorded SHALL reuse them without converting again.

#### Scenario: First attempt
- **GIVEN** a stored 3-page PDF
- **WHEN** it is separated
- **THEN** pages 1 to 3 are stored as PNGs and recorded, and the file passes through `SEPARATING`

#### Scenario: Retry after the pages were stored
- **GIVEN** a file whose pages were converted on an earlier attempt that then failed
- **WHEN** separation runs again
- **THEN** the pages are not converted again

### Requirement: A single page is one document
A file with one page SHALL become one document covering page 1, with no separation call.

#### Scenario: One-page photo
- **WHEN** a one-page file is separated
- **THEN** no separation call is made, and the file has one document covering pages 1–1

### Requirement: Long files are sent in overlapping windows
A file with more pages than `separation.window-size` SHALL be sent in windows of that many pages, each starting
`window-size − window-overlap` pages after the previous one, the last ending at the file's last page. A file within
the window size SHALL be sent whole.

#### Scenario: 40 pages
- **WHEN** a 40-page file is separated with a window of 15 and an overlap of 2
- **THEN** the windows are pages 1–15, 14–28 and 27–40

#### Scenario: Within one window
- **WHEN** a 6-page file is separated with a window of 15
- **THEN** one window covers pages 1–6

### Requirement: Each window gets several runs, saved as they finish
Each window SHALL get `separation.runs` separation calls, made outside any database transaction, with the window's
pages in order, the loan purpose and the catalog's type names. Each run SHALL be saved with its window and its
answer, or as failed. A retry SHALL count a window's successful runs already saved and make only the calls still
needed.

#### Scenario: Runs saved
- **WHEN** a 6-page file is separated with 3 runs
- **THEN** three runs for window 1–6 are saved

#### Scenario: Retry reuses successful runs
- **GIVEN** a window with two successful saved runs out of 3
- **WHEN** separation runs again
- **THEN** one more call is made for that window

### Requirement: Too few successful runs is retried
When fewer than a majority of `separation.runs` runs of a window succeed, the attempt SHALL fail, so the message is
retried. Nothing beyond the saved runs SHALL be written.

#### Scenario: Two of three runs fail
- **WHEN** two of a window's three calls fail
- **THEN** the attempt fails, no document is created, and the file stays `SEPARATING`

### Requirement: Run answers are cleaned before voting
Each successful answer's start pages SHALL be read as page numbers within its window, counted from 1. Page 1 of the
window, pages outside the window and duplicates SHALL be dropped, and the rest sorted. An answer left empty means the
window holds no new document start.

#### Scenario: Out of range, duplicate and page 1
- **WHEN** a run over a 5-page window answers `[0, 3, 3, 9, 2]`
- **THEN** its cleaned start pages are 2 and 3

#### Scenario: Empty answer
- **WHEN** every run over a 4-page file answers `[]`
- **THEN** the file has one document covering pages 1–4

### Requirement: Each gap is decided by a majority of one window's runs
For each gap between page p−1 and page p, the deciding window SHALL be the one holding both pages where the gap sits
furthest from the window's edges (the fewer of the pages before p−1 and after p within it), the earlier window on a
tie. The gap SHALL be a boundary when more than half of that
window's successful runs start a document at p. Page 1 SHALL always start a document.

#### Scenario: Majority of three
- **WHEN** three runs over a 6-page file give start pages {1, 3}, {1, 3, 5} and {1}
- **THEN** the documents are pages 1–2 and 3–6

#### Scenario: Gap seen by two windows
- **GIVEN** windows 1–10 and 6–15, where window 1–10's runs all start a document at page 9 and window 6–15's runs don't
- **WHEN** the gap between pages 8 and 9 is decided
- **THEN** window 6–15 decides it, because the gap sits 2 pages from its edges but only 1 from window 1–10's, and it
  is not a boundary

#### Scenario: Gap on the edge of both windows
- **GIVEN** windows 1–15 and 14–28, where window 1–15's runs all start a document at page 15 and window 14–28's runs
  don't
- **WHEN** the gap between pages 14 and 15 is decided
- **THEN** the earlier window 1–15 decides it, and it is a boundary

### Requirement: Separating a file creates its documents and queues classification
When the vote is done, one transaction SHALL create a document for each segment, numbered from 1 within the file, set
the file to `SEPARATED`, queue one `CLASSIFY_DOCUMENT` message per document, and mark the message done. Files of one
batch SHALL be separated independently.

#### Scenario: Two documents
- **WHEN** a 6-page file is separated into pages 1–2 and 3–6
- **THEN** it has two documents with sequences 1 and 2, it is `SEPARATED`, and there is one `CLASSIFY_DOCUMENT` message
  for each document

#### Scenario: Two files of one batch
- **WHEN** both files of a batch are separated
- **THEN** each file's documents carry that file's id, and each file's sequences start at 1

### Requirement: A file is separated at most once
Separation of a file that is already `SEPARATED` or `FAILED` SHALL change nothing and make no call. A file that becomes
`SEPARATED` or `FAILED` while it is being separated SHALL keep that status, and the late attempt SHALL create no
documents and queue no messages.

#### Scenario: Message delivered again
- **GIVEN** a file that is already `SEPARATED` with its documents
- **WHEN** its message is handled again
- **THEN** no call is made, and no document or message is added

#### Scenario: Failed while being separated
- **GIVEN** an attempt still calling the model after its lease expired, whose message another attempt then exhausted
- **WHEN** the late attempt finishes its vote
- **THEN** the file stays `FAILED`, and no document or `CLASSIFY_DOCUMENT` message is created

### Requirement: A file that can't be separated fails without stopping its batch
When a file's separation message is exhausted, the file SHALL become `FAILED` with the reason "Separation failed",
unless it is already `SEPARATED`, and the batch completion check SHALL run in the same transaction. The batch's other
files SHALL carry on.

#### Scenario: Exhausted message
- **WHEN** a file's separation message fails for the last time
- **THEN** the file is `FAILED` with "Separation failed", and the batch completion check runs for it
