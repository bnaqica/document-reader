package com.bnaqica.documentreader.api;

import static com.bnaqica.documentreader.api.RecordControllerTestSupport.user1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bnaqica.documentreader.config.SecurityConfig;
import com.bnaqica.documentreader.dto.Caller;
import com.bnaqica.documentreader.dto.request.BatchUploadRequest;
import com.bnaqica.documentreader.dto.response.BatchResultResponse;
import com.bnaqica.documentreader.dto.response.DocumentResultResponse;
import com.bnaqica.documentreader.dto.response.FileResultResponse;
import com.bnaqica.documentreader.entity.BatchStatus;
import com.bnaqica.documentreader.entity.DocumentStatus;
import com.bnaqica.documentreader.entity.SourceFileStatus;
import com.bnaqica.documentreader.exception.BatchValidationException;
import com.bnaqica.documentreader.exception.BatchValidationException.FileError;
import com.bnaqica.documentreader.exception.InvalidWebhookUrlException;
import com.bnaqica.documentreader.exception.ResourceNotFoundException;
import com.bnaqica.documentreader.service.BatchIntakeService;
import com.bnaqica.documentreader.service.BatchIntakeService.Accepted;
import com.bnaqica.documentreader.service.BatchIntakeService.Finished;
import com.bnaqica.documentreader.service.BatchQueryService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.web.multipart.MultipartFile;

@WebMvcTest(BatchController.class)
@Import(SecurityConfig.class)
class BatchControllerTests {

    private static final UUID BATCH = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID FILE = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final Caller USER_1 = new Caller("user-1", false);
    private static final BatchResultResponse RESULT = BatchResultResponse.builder()
            .batchId(BATCH)
            .status(BatchStatus.COMPLETED)
            .loanPurpose("CAR_REFINANCE")
            .receivedAt(Instant.parse("2026-11-20T16:00:00Z"))
            .completedAt(Instant.parse("2026-11-20T16:01:12Z"))
            .files(List.of(new FileResultResponse(FILE, 0, "packet.pdf", 1, SourceFileStatus.SEPARATED, 1, null)))
            .documents(List.of(DocumentResultResponse.builder()
                    .id(UUID.randomUUID())
                    .sourceFileId(FILE)
                    .pages(new DocumentResultResponse.Pages(1, 1))
                    .status(DocumentStatus.COMPLETED)
                    .summary("A pay stub")
                    .build()))
            .build();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BatchIntakeService intake;

    @MockitoBean
    private BatchQueryService query;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void asyncReturns202WithStatusUrl() throws Exception {
        given(intake.submit(any(), any())).willReturn(new Accepted(BATCH, BatchStatus.PROCESSING));

        mockMvc.perform(upload().param("async", "true")
                        .param("webhookUrl", "https://example.com/hook")
                        .with(user1()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.batchId").value(BATCH.toString()))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.statusUrl").value("http://localhost/batches/" + BATCH));

        ArgumentCaptor<BatchUploadRequest> sent = ArgumentCaptor.forClass(BatchUploadRequest.class);
        then(intake).should().submit(sent.capture(), eq(USER_1));
        assertThat(sent.getValue().loanPurpose()).isEqualTo("CAR_REFINANCE");
        assertThat(sent.getValue().async()).isEqualTo("true");
        assertThat(sent.getValue().webhookUrl()).isEqualTo("https://example.com/hook");
        assertThat(sent.getValue().files())
                .extracting(MultipartFile::getOriginalFilename)
                .containsExactly("a.pdf", "b.png");
    }

    @Test
    void syncReturns200WhenCompletedInTime() throws Exception {
        given(intake.submit(any(), any())).willReturn(new Finished(RESULT));

        mockMvc.perform(upload().with(user1()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(BATCH.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.files[0].fileName").value("packet.pdf"))
                .andExpect(jsonPath("$.documents[0].pages.start").value(1))
                .andExpect(jsonPath("$.documents[0].summary").value("A pay stub"));
    }

    @Test
    void syncReturns202OnTimeout() throws Exception {
        given(intake.submit(any(), any())).willReturn(new Accepted(BATCH, BatchStatus.PROCESSING));

        mockMvc.perform(upload().with(user1()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.statusUrl").value("http://localhost/batches/" + BATCH));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 413, 415, 422})
    void validationErrorKeepsItsStatusAndErrors(int status) throws Exception {
        List<FileError> errors = status == 400
                ? List.of()
                : List.of(
                        new FileError(1, "notes.docx", status, "Not accepted"), new FileError(2, "x.pdf", 422, "Bad"));
        given(intake.submit(any(), any()))
                .willThrow(new BatchValidationException(HttpStatus.valueOf(status), "The detail", errors));

        ResultActions result = mockMvc.perform(upload().with(user1()))
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("The detail"));
        if (status == 400) {
            result.andExpect(jsonPath("$.errors").doesNotExist());
        } else {
            result.andExpect(jsonPath("$.errors[0].index").value(1))
                    .andExpect(jsonPath("$.errors[0].fileName").value("notes.docx"))
                    .andExpect(jsonPath("$.errors[0].status").value(status))
                    .andExpect(jsonPath("$.errors[0].detail").value("Not accepted"))
                    .andExpect(jsonPath("$.errors[1].index").value(2));
        }
    }

    @Test
    void asyncIsPassedOnAsSent() throws Exception {
        given(intake.submit(any(), any())).willReturn(new Accepted(BATCH, BatchStatus.PROCESSING));

        mockMvc.perform(upload().param("async", "maybe").with(user1())).andExpect(status().isAccepted());

        ArgumentCaptor<BatchUploadRequest> sent = ArgumentCaptor.forClass(BatchUploadRequest.class);
        then(intake).should().submit(sent.capture(), eq(USER_1));
        assertThat(sent.getValue().async()).isEqualTo("maybe");
    }

    @Test
    void invalidWebhookUrlIs400() throws Exception {
        given(intake.submit(any(), any())).willThrow(new InvalidWebhookUrlException());

        mockMvc.perform(upload().with(user1()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("webhookUrl must be an absolute http or https URL"));
    }

    @Test
    void getReturnsTheEnvelope() throws Exception {
        given(query.getResult(BATCH, USER_1)).willReturn(RESULT);

        mockMvc.perform(get("/batches/" + BATCH).with(user1()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loanPurpose").value("CAR_REFINANCE"))
                .andExpect(jsonPath("$.completedAt").value("2026-11-20T16:01:12Z"))
                .andExpect(jsonPath("$.files[0].documentCount").value(1));
    }

    @Test
    void getOtherCallersBatchIs404() throws Exception {
        given(query.getResult(BATCH, USER_1)).willThrow(new ResourceNotFoundException("Batch", BATCH));

        mockMvc.perform(get("/batches/" + BATCH).with(user1()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Batch " + BATCH + " was not found"));
    }

    @Test
    void requestsWithoutTokenAre401() throws Exception {
        mockMvc.perform(upload()).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/batches/" + BATCH)).andExpect(status().isUnauthorized());
        verifyNoInteractions(intake, query);
    }

    private static MockMultipartHttpServletRequestBuilder upload() {
        MockMultipartHttpServletRequestBuilder request = multipart("/batches");
        request.file(new MockMultipartFile("files", "a.pdf", "application/pdf", new byte[] {1}));
        request.file(new MockMultipartFile("files", "b.png", "image/png", new byte[] {2}));
        request.param("loanPurpose", "CAR_REFINANCE");
        return request;
    }
}
