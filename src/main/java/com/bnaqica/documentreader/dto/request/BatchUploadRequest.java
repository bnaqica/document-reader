package com.bnaqica.documentreader.dto.request;

import java.util.List;
import org.springframework.web.multipart.MultipartFile;

/**
 * The parts of {@code POST /batches}. Nothing is validated on binding: intake checks every field in a fixed order so
 * each failure gets its exact §7.1 message. {@code async} is kept as sent (null when the part is missing), so a value
 * that isn't a boolean gets intake's own message rather than a binding error.
 */
public record BatchUploadRequest(List<MultipartFile> files, String loanPurpose, String async, String webhookUrl) {}
