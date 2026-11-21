package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.request.LoanPurposeCreateRequest;
import com.bnaqica.documentreader.dto.request.LoanPurposeUpdateRequest;
import com.bnaqica.documentreader.dto.response.LoanPurposeResponse;
import java.util.List;
import java.util.Optional;

/** CRUD for loan purposes. Removing one deactivates it, because batches keep referring to its code. */
public interface LoanPurposeService {

    List<LoanPurposeResponse> findAllActive();

    /** The purpose if it exists and is active; batch intake accepts only these. */
    Optional<LoanPurposeResponse> findActive(String code);

    /** Active or not. Throws {@code ResourceNotFoundException} for an unknown code. */
    LoanPurposeResponse get(String code);

    /** Throws {@code LoanPurposeExistsException} if the code is taken. */
    LoanPurposeResponse create(LoanPurposeCreateRequest request);

    LoanPurposeResponse update(String code, LoanPurposeUpdateRequest request);

    void deactivate(String code);
}
