package com.bnaqica.documentreader.entity;

/** How a classification result was decided: the initial vote, the tie-break vote, or average confidence. */
public enum DecidedBy {
    VOTE,
    TIEBREAK,
    CONFIDENCE
}
