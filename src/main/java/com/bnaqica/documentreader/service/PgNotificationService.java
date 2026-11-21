package com.bnaqica.documentreader.service;

/** Turns a Postgres notification into the wake-up it stands for. The notification listener's only collaborator. */
public interface PgNotificationService {

    String OUTBOX_READY = "outbox_ready";
    String INBOX_READY = "inbox_ready";
    String BATCH_COMPLETED = "batch_completed";

    /** Routes one notification; a channel or payload it doesn't recognise is ignored. */
    void onNotification(String channel, String payload);

    /** The listener (re)connected: notifications sent while it was away are lost, so every loop is woken once. */
    void onConnected();
}
