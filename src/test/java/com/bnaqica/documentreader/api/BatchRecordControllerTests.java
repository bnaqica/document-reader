package com.bnaqica.documentreader.api;

import static com.bnaqica.documentreader.api.RecordControllerTestSupport.admin;
import static com.bnaqica.documentreader.api.RecordControllerTestSupport.user1;
import static org.mockito.BDDMockito.given;
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
import com.bnaqica.documentreader.dto.response.BatchRecord;
import com.bnaqica.documentreader.dto.response.DocumentRecord;
import com.bnaqica.documentreader.dto.response.SourceFileRecord;
import com.bnaqica.documentreader.entity.BatchStatus;
import com.bnaqica.documentreader.entity.WebhookStatus;
import com.bnaqica.documentreader.exception.ResourceNotFoundException;
import com.bnaqica.documentreader.service.RecordQueryService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(BatchRecordController.class)
@Import(SecurityConfig.class)
class BatchRecordControllerTests {

    private static final UUID BATCH = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final BatchRecord RECORD = new BatchRecord(
            BATCH,
            "CAR_REFINANCE",
            false,
            null,
            BatchStatus.PROCESSING,
            WebhookStatus.NOT_APPLICABLE,
            null,
            1,
            "user-1",
            Instant.parse("2026-11-20T16:00:00Z"),
            null);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RecordQueryService records;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void listsTheCallersBatchesWithTheDefaultLimit() throws Exception {
        given(records.batches(new Caller("user-1", false), 50)).willReturn(List.of(RECORD));

        mockMvc.perform(get("/records/batches").with(user1()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(BATCH.toString()))
                .andExpect(jsonPath("$[0].status").value("PROCESSING"));
    }

    @Test
    void adminListsWithTheGivenLimit() throws Exception {
        given(records.batches(new Caller("admin-1", true), 10)).willReturn(List.of(RECORD));

        mockMvc.perform(get("/records/batches").param("limit", "10").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].requestedBy").value("user-1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "201", "500"})
    void limitOutOfRangeIs400(String limit) throws Exception {
        mockMvc.perform(get("/records/batches").param("limit", limit).with(user1()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(records);
    }

    @Test
    void getsOneBatchWithItsFilesAndDocuments() throws Exception {
        Caller user1 = new Caller("user-1", false);
        given(records.batch(BATCH, user1)).willReturn(RECORD);
        given(records.sourceFiles(BATCH, user1)).willReturn(List.<SourceFileRecord>of());
        given(records.documents(BATCH, user1)).willReturn(List.<DocumentRecord>of());

        mockMvc.perform(get("/records/batches/" + BATCH).with(user1()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loanPurposeCode").value("CAR_REFINANCE"));
        mockMvc.perform(get("/records/batches/" + BATCH + "/source-files").with(user1()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/records/batches/" + BATCH + "/documents").with(user1()))
                .andExpect(status().isOk());
    }

    @Test
    void notFoundIs404ProblemDetail() throws Exception {
        given(records.batch(BATCH, new Caller("user-1", false)))
                .willThrow(new ResourceNotFoundException("Batch", BATCH));

        mockMvc.perform(get("/records/batches/" + BATCH).with(user1()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    static Stream<MockHttpServletRequestBuilder> writes() {
        return Stream.of(
                post("/records/batches"),
                put("/records/batches/" + BATCH),
                patch("/records/batches/" + BATCH),
                delete("/records/batches/" + BATCH),
                delete("/records/batches/" + BATCH + "/documents"));
    }

    @ParameterizedTest
    @MethodSource("writes")
    void writesAreNotAllowedEvenForAnAdmin(MockHttpServletRequestBuilder write) throws Exception {
        mockMvc.perform(write.with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(records);
    }

    @Test
    void readWithoutTokenIs401() throws Exception {
        mockMvc.perform(get("/records/batches")).andExpect(status().isUnauthorized());
        verifyNoInteractions(records);
    }
}
