package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.CallContext;
import com.bnaqica.documentreader.dto.PageImageRef;
import com.bnaqica.documentreader.dto.response.DocumentTypeResponse;
import java.util.List;

/** Turns a type name the model proposed, which the catalog doesn't have, into a type: an existing one or a new one. */
public interface DocumentTypeCatalogService {

    /**
     * Asks the model whether the name means an existing type, and saves it as that type's alias if so; otherwise has
     * the model draft a schema and adds a {@code MODEL} type. Two documents proposing the same name at once get the
     * same type. Runs with no transaction open, because it calls the model.
     *
     * @throws com.bnaqica.documentreader.exception.VisionException when a call fails, so the step is retried
     */
    DocumentTypeResponse createOrMatch(CallContext context, List<PageImageRef> pages, String proposedName);
}
