package com.bnaqica.documentreader.dto;

import com.bnaqica.documentreader.entity.ExtractionStage;
import tools.jackson.databind.JsonNode;

/** One extraction answer to save. */
public record NewExtractionRun(ExtractionStage stage, int passNumber, JsonNode data, String summary) {}
