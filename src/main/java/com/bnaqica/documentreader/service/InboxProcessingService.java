package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.entity.MessageType;
import java.util.Set;

/** Claims due inbox messages of one type and runs them through that type's handler. */
public interface InboxProcessingService {

    /** Claims up to {@code inbox.concurrency} due messages, handles them in parallel, and returns how many it claimed. */
    int processDue(MessageType type);

    /** The types that have a handler; workers run only for these. */
    Set<MessageType> handledTypes();

    /** {@code webhook.max-attempts} for webhooks, {@code inbox.max-attempts} for everything else. */
    int maxAttempts(MessageType type);
}
