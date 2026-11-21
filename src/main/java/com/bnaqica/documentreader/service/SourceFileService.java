package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.NewSourceFile;
import com.bnaqica.documentreader.dto.response.SourceFileRecord;
import com.bnaqica.documentreader.entity.SourceFileStatus;
import java.util.List;
import java.util.UUID;

/** CRUD for a batch's uploaded files. */
public interface SourceFileService {

    /** Gives the files indexes 0, 1, 2… in list order, all {@code RECEIVED}. */
    List<SourceFileRecord> createAll(UUID batchId, List<NewSourceFile> files);

    /** Throws {@code ResourceNotFoundException} for an unknown id. */
    SourceFileRecord get(UUID id);

    /** In index order. */
    List<SourceFileRecord> findByBatch(UUID batchId);

    void markStored(UUID id, String s3Bucket, String s3Key);

    /** The failure reason is kept only with {@code FAILED}. */
    void updateStatus(UUID id, SourceFileStatus status, String failureReason);
}
