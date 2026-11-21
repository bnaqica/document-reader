package com.bnaqica.documentreader.entity;

/** The work one pipeline step hands to the next. */
public enum MessageType {
    SEPARATE_FILE,
    CLASSIFY_DOCUMENT,
    EXTRACT_DOCUMENT,
    DELIVER_WEBHOOK
}
