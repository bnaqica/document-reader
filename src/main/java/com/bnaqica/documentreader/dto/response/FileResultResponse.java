package com.bnaqica.documentreader.dto.response;

import com.bnaqica.documentreader.entity.SourceFileStatus;
import java.util.UUID;
import lombok.Builder;

/** One uploaded file in the result envelope. A file that produced no documents is still listed. */
@Builder
public record FileResultResponse(
        UUID id, int index, String fileName, int pageCount, SourceFileStatus status, int documentCount, String error) {}
