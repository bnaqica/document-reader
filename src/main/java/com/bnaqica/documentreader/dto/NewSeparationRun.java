package com.bnaqica.documentreader.dto;

import java.util.List;

/** One separation answer to save. {@code boundaries} is null when the run failed. */
public record NewSeparationRun(
        int runNumber, int windowStart, int windowEnd, boolean succeeded, List<SeparationBoundary> boundaries) {}
