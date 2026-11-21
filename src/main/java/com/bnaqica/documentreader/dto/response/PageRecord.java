package com.bnaqica.documentreader.dto.response;

import java.util.UUID;
import lombok.Builder;

@Builder
public record PageRecord(UUID sourceFileId, int pageNumber, String s3Key, int width, int height) {}
