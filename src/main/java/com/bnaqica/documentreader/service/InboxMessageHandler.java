package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.response.InboxMessageRecord;
import com.bnaqica.documentreader.entity.MessageType;

/**
 * Implemented by each pipeline step's action service, one per message type. {@link #handle} runs with no database
 * transaction open, so it may call OpenAI; it may mark its message done inside its own final transaction. Throwing
 * schedules a retry, or exhausts the message on its last attempt.
 */
public interface InboxMessageHandler {

    MessageType type();

    void handle(InboxMessageRecord message);

    /**
     * Called once when the message has failed for good, in the transaction that marks it {@code FAILED}, so the step
     * can fail its file or document.
     */
    void onExhausted(InboxMessageRecord message, String lastError);
}
