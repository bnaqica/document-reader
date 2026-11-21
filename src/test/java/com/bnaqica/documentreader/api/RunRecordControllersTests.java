package com.bnaqica.documentreader.api;

import static com.bnaqica.documentreader.api.RecordControllerTestSupport.admin;
import static com.bnaqica.documentreader.api.RecordControllerTestSupport.user1;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bnaqica.documentreader.config.SecurityConfig;
import com.bnaqica.documentreader.dto.Caller;
import com.bnaqica.documentreader.dto.response.WebhookAttemptRecord;
import com.bnaqica.documentreader.entity.ExtractionStage;
import com.bnaqica.documentreader.exception.ResourceNotFoundException;
import com.bnaqica.documentreader.service.RunRecordQueryService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The seven read-only run and result controllers share one contract, so one parameterized test covers them all. */
@WebMvcTest({
    SeparationRunRecordController.class,
    ClassificationRunRecordController.class,
    ClassificationResultRecordController.class,
    ExtractionRunRecordController.class,
    ExtractionResultRecordController.class,
    WebhookAttemptRecordController.class,
    OpenAiCallRecordController.class
})
@Import(SecurityConfig.class)
class RunRecordControllersTests {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final Caller USER_1 = new Caller("user-1", false);
    private static final Caller ADMIN = new Caller("admin-1", true);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RunRecordQueryService runs;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    /** Each path, and the service call it must make for a caller. */
    static Stream<Arguments> paths() {
        return Stream.of(
                path("/records/source-files/%s/separation-runs", (s, c) -> s.separationRuns(ID, c)),
                path("/records/documents/%s/classification-runs", (s, c) -> s.classificationRuns(ID, c)),
                path("/records/documents/%s/classification-result", (s, c) -> s.classificationResult(ID, c)),
                path("/records/documents/%s/extraction-runs", (s, c) -> s.extractionRuns(ID, null, c)),
                path("/records/documents/%s/extraction-result", (s, c) -> s.extractionResult(ID, c)),
                path("/records/batches/%s/webhook-attempts", (s, c) -> s.webhookAttempts(ID, c)),
                path("/records/batches/%s/openai-calls", (s, c) -> s.openAiCalls(ID, c)));
    }

    private static Arguments path(String template, BiConsumer<RunRecordQueryService, Caller> call) {
        return Arguments.of(template.formatted(ID), call);
    }

    @ParameterizedTest
    @MethodSource("paths")
    void ownerReads(String path, BiConsumer<RunRecordQueryService, Caller> call) throws Exception {
        mockMvc.perform(get(path).with(user1())).andExpect(status().isOk());
        call.accept(verify(runs), USER_1);
    }

    @ParameterizedTest
    @MethodSource("paths")
    void adminReadsAsAdmin(String path, BiConsumer<RunRecordQueryService, Caller> call) throws Exception {
        mockMvc.perform(get(path).with(admin())).andExpect(status().isOk());
        call.accept(verify(runs), ADMIN);
    }

    @ParameterizedTest
    @MethodSource("paths")
    void writesAreNotAllowedEvenForAnAdmin(String path) throws Exception {
        mockMvc.perform(post(path)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put(path)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(patch(path)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete(path).with(admin())).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(runs);
    }

    @ParameterizedTest
    @MethodSource("paths")
    void readWithoutTokenIs401(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        verifyNoInteractions(runs);
    }

    @Test
    void listIsReturnedAsJson() throws Exception {
        given(runs.webhookAttempts(ID, USER_1))
                .willReturn(List.of(new WebhookAttemptRecord(ID, 1, null, "timeout", Instant.EPOCH)));

        mockMvc.perform(get("/records/batches/" + ID + "/webhook-attempts").with(user1()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].attempt").value(1))
                .andExpect(jsonPath("$[0].error").value("timeout"));
    }

    @Test
    void missingResultIs404() throws Exception {
        given(runs.extractionResult(ID, USER_1))
                .willThrow(new ResourceNotFoundException("Extraction result of document", ID));

        mockMvc.perform(get("/records/documents/" + ID + "/extraction-result").with(user1()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void stageFiltersExtractionRuns() throws Exception {
        mockMvc.perform(get("/records/documents/" + ID + "/extraction-runs")
                        .param("stage", "INITIAL")
                        .with(user1()))
                .andExpect(status().isOk());
        verify(runs).extractionRuns(ID, ExtractionStage.INITIAL, USER_1);
    }

    @Test
    void unknownStageIs400() throws Exception {
        mockMvc.perform(get("/records/documents/" + ID + "/extraction-runs")
                        .param("stage", "DRAFT")
                        .with(user1()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(runs);
    }
}
