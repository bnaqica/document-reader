package com.bnaqica.documentreader.dto;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** The passes merged into one draft, and the fields where they disagreed. */
public record ConsolidatedAnswer(JsonNode data, String summary, List<String> disagreements) {}
