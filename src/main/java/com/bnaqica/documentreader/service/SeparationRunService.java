package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.NewSeparationRun;
import com.bnaqica.documentreader.dto.response.SeparationRunRecord;
import java.util.List;
import java.util.UUID;

/** Separation runs of a source file. Rows are written once; a duplicate run number is refused by the database. */
public interface SeparationRunService {

    /** @throws IllegalArgumentException if the window ends past the file's last page */
    SeparationRunRecord save(UUID sourceFileId, NewSeparationRun run);

    /** Ordered by window start, then run number. */
    List<SeparationRunRecord> findBySourceFile(UUID sourceFileId);
}
