package com.bnaqica.documentreader.dto.event;

import java.util.UUID;

/** The {@code SEPARATE_FILE} message: which stored file to convert and separate into documents. */
public record SeparateFilePayload(UUID sourceFileId) {}
