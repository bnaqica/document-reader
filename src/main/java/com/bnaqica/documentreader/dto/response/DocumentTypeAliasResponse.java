package com.bnaqica.documentreader.dto.response;

import java.util.UUID;
import lombok.Builder;

@Builder
public record DocumentTypeAliasResponse(UUID id, UUID documentTypeId, String alias, String normalizedKey) {}
