package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.ApiAuditEntry;

public interface ApiAuditService {

    void record(ApiAuditEntry entry);
}
