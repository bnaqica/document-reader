package com.bnaqica.documentreader.api;

import static com.bnaqica.documentreader.api.RecordControllerTestSupport.admin;
import static com.bnaqica.documentreader.api.RecordControllerTestSupport.user1;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bnaqica.documentreader.config.SecurityConfig;
import com.bnaqica.documentreader.dto.Caller;
import com.bnaqica.documentreader.dto.response.DocumentRecord;
import com.bnaqica.documentreader.entity.DocumentStatus;
import com.bnaqica.documentreader.exception.ResourceNotFoundException;
import com.bnaqica.documentreader.service.RecordQueryService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DocumentRecordController.class)
@Import(SecurityConfig.class)
class DocumentRecordControllerTests {

    private static final UUID DOCUMENT = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RecordQueryService records;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void getsADocument() throws Exception {
        given(records.document(DOCUMENT, new Caller("user-1", false)))
                .willReturn(new DocumentRecord(
                        DOCUMENT,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        1,
                        1,
                        2,
                        DocumentStatus.CLASSIFIED,
                        null,
                        false,
                        null,
                        null));

        mockMvc.perform(get("/records/documents/" + DOCUMENT).with(user1()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLASSIFIED"))
                .andExpect(jsonPath("$.endPage").value(2));
    }

    @Test
    void anotherCallersDocumentIs404() throws Exception {
        given(records.document(DOCUMENT, new Caller("user-1", false)))
                .willThrow(new ResourceNotFoundException("Document", DOCUMENT));

        mockMvc.perform(get("/records/documents/" + DOCUMENT).with(user1()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void writesAreNotAllowedEvenForAnAdmin() throws Exception {
        mockMvc.perform(delete("/records/documents/" + DOCUMENT).with(admin()))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(patch("/records/documents/" + DOCUMENT)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(records);
    }

    @Test
    void readWithoutTokenIs401() throws Exception {
        mockMvc.perform(get("/records/documents/" + DOCUMENT)).andExpect(status().isUnauthorized());
        verifyNoInteractions(records);
    }
}
