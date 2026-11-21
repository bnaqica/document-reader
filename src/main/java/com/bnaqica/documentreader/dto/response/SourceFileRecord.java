package com.bnaqica.documentreader.dto.response;

import com.bnaqica.documentreader.entity.SourceFileStatus;
import java.util.UUID;
import lombok.Builder;

@Builder
public record SourceFileRecord(
        UUID id,
        UUID batchId,
        int fileIndex,
        String originalFilename,
        String contentType,
        long sizeBytes,
        String sha256,
        String s3Bucket,
        String s3Key,
        int pageCount,
        SourceFileStatus status,
        String failureReason) {}
