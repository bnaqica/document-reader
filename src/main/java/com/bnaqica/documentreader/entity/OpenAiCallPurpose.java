package com.bnaqica.documentreader.entity;

/** Why an OpenAI call was made. */
public enum OpenAiCallPurpose {
    SEPARATE,
    CLASSIFY,
    TIEBREAK,
    CONFIRM_TYPE,
    DRAFT_SCHEMA,
    EXTRACT,
    CONSOLIDATE,
    FINAL
}
