package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.request.LoanPurposeCreateRequest;
import com.bnaqica.documentreader.dto.request.LoanPurposeUpdateRequest;
import com.bnaqica.documentreader.dto.response.LoanPurposeResponse;
import com.bnaqica.documentreader.entity.LoanPurpose;
import com.bnaqica.documentreader.exception.LoanPurposeExistsException;
import com.bnaqica.documentreader.exception.ResourceNotFoundException;
import com.bnaqica.documentreader.repository.LoanPurposeRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LoanPurposeServiceImpl implements LoanPurposeService {

    private static final String RESOURCE = "Loan purpose";

    private final LoanPurposeRepository repository;

    @Override
    public List<LoanPurposeResponse> findAllActive() {
        return repository.findAllByActiveTrueOrderByCode().stream()
                .map(LoanPurposeServiceImpl::toResponse)
                .toList();
    }

    @Override
    public Optional<LoanPurposeResponse> findActive(String code) {
        return repository.findById(code).filter(LoanPurpose::isActive).map(LoanPurposeServiceImpl::toResponse);
    }

    @Override
    public LoanPurposeResponse get(String code) {
        return toResponse(find(code));
    }

    @Override
    @Transactional
    public LoanPurposeResponse create(LoanPurposeCreateRequest request) {
        if (repository.existsById(request.code())) {
            throw new LoanPurposeExistsException(request.code());
        }
        LoanPurpose purpose = LoanPurpose.builder()
                .code(request.code())
                .name(request.name())
                .description(request.description())
                .active(true)
                .newEntity(true)
                .build();
        try {
            repository.saveAndFlush(purpose);
        } catch (DataIntegrityViolationException e) {
            // another request created the same code between the check and the insert
            throw new LoanPurposeExistsException(request.code());
        }
        log.info("Created loan purpose {}", purpose.getCode());
        return toResponse(purpose);
    }

    @Override
    @Transactional
    public LoanPurposeResponse update(String code, LoanPurposeUpdateRequest request) {
        LoanPurpose purpose = find(code);
        purpose.update(request.name(), request.description(), request.active());
        log.info("Updated loan purpose {}", code);
        return toResponse(purpose);
    }

    @Override
    @Transactional
    public void deactivate(String code) {
        find(code).deactivate();
        log.info("Deactivated loan purpose {}", code);
    }

    private LoanPurpose find(String code) {
        return repository.findById(code).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, code));
    }

    private static LoanPurposeResponse toResponse(LoanPurpose purpose) {
        return LoanPurposeResponse.builder()
                .code(purpose.getCode())
                .name(purpose.getName())
                .description(purpose.getDescription())
                .active(purpose.isActive())
                .build();
    }
}
