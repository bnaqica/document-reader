package com.bnaqica.documentreader.api;

import com.bnaqica.documentreader.dto.Caller;
import com.bnaqica.documentreader.dto.request.BatchUploadRequest;
import com.bnaqica.documentreader.dto.response.BatchAcceptedResponse;
import com.bnaqica.documentreader.dto.response.BatchResultResponse;
import com.bnaqica.documentreader.service.BatchIntakeService;
import com.bnaqica.documentreader.service.BatchIntakeService.Accepted;
import com.bnaqica.documentreader.service.BatchIntakeService.Finished;
import com.bnaqica.documentreader.service.BatchQueryService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Batch intake and the batch result (§7). Validation, storing and waiting all happen in {@link BatchIntakeService};
 * this maps its outcome to 200 with the result or 202 with where to find it.
 */
@RestController
@RequestMapping("/batches")
@RequiredArgsConstructor
public class BatchController {

    private final BatchIntakeService intake;
    private final BatchQueryService query;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<?> submit(
            @ModelAttribute BatchUploadRequest request,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication) {
        return switch (intake.submit(request, Caller.of(authentication))) {
            case Finished finished -> ResponseEntity.ok(finished.result());
            case Accepted accepted ->
                ResponseEntity.accepted()
                        .body(new BatchAcceptedResponse(
                                accepted.batchId(), accepted.status(), statusUrl(accepted.batchId())));
        };
    }

    @GetMapping("/{id}")
    BatchResultResponse result(
            @PathVariable UUID id,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication) {
        return query.getResult(id, Caller.of(authentication));
    }

    private static String statusUrl(UUID batchId) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/batches/{id}")
                .buildAndExpand(batchId)
                .toUriString();
    }
}
