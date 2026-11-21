package com.bnaqica.documentreader.dto.response;

import lombok.Builder;

@Builder
public record LoanPurposeResponse(String code, String name, String description, boolean active) {}
