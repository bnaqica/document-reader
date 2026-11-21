package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.PageImage;
import com.bnaqica.documentreader.dto.response.PageRecord;
import java.util.List;
import java.util.UUID;

/** CRUD for a source file's rendered pages. */
public interface SourceFilePageService {

    /**
     * Replaces the file's pages with these, so a retried conversion is safe. The bulk delete clears the persistence
     * context, so re-read any entity loaded earlier in the same transaction.
     */
    void saveAll(UUID sourceFileId, List<PageImage> pages);

    /** In page order. */
    List<PageRecord> findBySourceFile(UUID sourceFileId);
}
