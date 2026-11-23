package com.bnaqica.documentreader.exception;

import com.bnaqica.documentreader.entity.OpenAiCallPurpose;
import lombok.Getter;

/**
 * A vision call failed, or its answer can't be used. The message names the purpose, the reason and any HTTP status,
 * never the prompt or the answer. {@code httpStatus} is null when no HTTP response came.
 */
@Getter
public class VisionException extends RuntimeException {

    private final OpenAiCallPurpose purpose;

    /** Short and free of content, so the call log can hold it. */
    private final String reason;

    private final Integer httpStatus;

    public VisionException(OpenAiCallPurpose purpose, String reason, Integer httpStatus, Throwable cause) {
        super(purpose + " call failed: " + reason + (httpStatus == null ? "" : " (HTTP " + httpStatus + ")"), cause);
        this.purpose = purpose;
        this.reason = reason;
        this.httpStatus = httpStatus;
    }

    public VisionException(OpenAiCallPurpose purpose, String reason) {
        this(purpose, reason, null, null);
    }
}
