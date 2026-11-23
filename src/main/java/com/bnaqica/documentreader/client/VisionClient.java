package com.bnaqica.documentreader.client;

import com.bnaqica.documentreader.dto.CallContext;
import com.bnaqica.documentreader.dto.CatalogEntry;
import com.bnaqica.documentreader.dto.ClassificationAnswer;
import com.bnaqica.documentreader.dto.ConsolidatedAnswer;
import com.bnaqica.documentreader.dto.ExtractionAnswer;
import com.bnaqica.documentreader.dto.PageImageRef;
import com.bnaqica.documentreader.dto.SeparationAnswer;
import com.bnaqica.documentreader.dto.TypeMatchAnswer;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Every OpenAI vision call the pipeline makes. Each call sends the pages (except {@link #consolidate}) and the loan
 * purpose, returns a typed answer, and is logged to the OpenAI call log without its prompt or answer.
 *
 * <p>Every method throws {@link com.bnaqica.documentreader.exception.VisionException} when the call fails or its answer
 * can't be used, and {@link IllegalStateException} when called inside a database transaction.
 */
public interface VisionClient {

    SeparationAnswer separate(CallContext context, List<PageImageRef> pages, List<String> catalogNames);

    ClassificationAnswer classify(CallContext context, List<PageImageRef> pages, List<CatalogEntry> catalog);

    /** Like {@link #classify}, but the model may only choose among the tied candidates. */
    ClassificationAnswer tiebreak(CallContext context, List<PageImageRef> pages, List<CatalogEntry> candidates);

    TypeMatchAnswer confirmNewType(
            CallContext context, List<PageImageRef> pages, String proposedName, List<CatalogEntry> catalog);

    /** A data schema for a new type, which follows the catalog's schema rules. */
    JsonNode draftSchema(CallContext context, List<PageImageRef> pages, String typeName);

    ExtractionAnswer extract(CallContext context, List<PageImageRef> pages, JsonNode schema);

    /** Text only: merges the passes and lists where they disagreed. */
    ConsolidatedAnswer consolidate(CallContext context, JsonNode schema, List<ExtractionAnswer> passes);

    /** Looks at the pages again to settle the draft; {@code validationErrors} come from an earlier final attempt. */
    ExtractionAnswer finalizeExtraction(
            CallContext context,
            List<PageImageRef> pages,
            JsonNode schema,
            ConsolidatedAnswer draft,
            List<String> validationErrors);
}
