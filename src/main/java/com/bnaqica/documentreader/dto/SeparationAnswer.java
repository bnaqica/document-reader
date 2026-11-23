package com.bnaqica.documentreader.dto;

import java.util.List;

/**
 * One separation run's answer: the pages where a document starts, with a tentative label for each segment. The labels
 * only help the model think; classification decides the type. Not cleaned here: separation does that.
 */
public record SeparationAnswer(List<Integer> startPages, List<String> labels) {}
