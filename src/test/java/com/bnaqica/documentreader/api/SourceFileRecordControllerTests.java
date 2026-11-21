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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bnaqica.documentreader.config.SecurityConfig;
import com.bnaqica.documentreader.dto.Caller;
import com.bnaqica.documentreader.dto.response.PageRecord;
import com.bnaqica.documentreader.dto.response.SourceFileRecord;
import com.bnaqica.documentreader.entity.SourceFileStatus;
import com.bnaqica.documentreader.exception.ResourceNotFoundException;
import com.bnaqica.documentreader.service.RecordQueryService;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(SourceFileRecordController.class)
@Import(SecurityConfig.class)
class SourceFileRecordControllerTests {

    private static final UUID FILE = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final Caller USER_1 = new Caller("user-1", false);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RecordQueryService records;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void getsAFileAndItsPages() throws Exception {
        given(records.sourceFile(FILE, USER_1))
                .willReturn(new SourceFileRecord(
                        FILE,
                        UUID.randomUUID(),
                        0,
                        "scan.pdf",
                        "application/pdf",
                        100,
                        "a".repeat(64),
                        "docs",
                        "files/x/original.pdf",
                        2,
                        SourceFileStatus.SEPARATED,
                        null));
        given(records.pages(FILE, USER_1)).willReturn(List.of(new PageRecord(FILE, 1, "p1.png", 10, 20)));

        mockMvc.perform(get("/records/source-files/" + FILE).with(user1()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalFilename").value("scan.pdf"));
        mockMvc.perform(get("/records/source-files/" + FILE + "/pages").with(user1()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pageNumber").value(1));
    }

    @Test
    void notFoundIs404() throws Exception {
        given(records.pages(FILE, USER_1)).willThrow(new ResourceNotFoundException("Source file", FILE));

        mockMvc.perform(get("/records/source-files/" + FILE + "/pages").with(user1()))
                .andExpect(status().isNotFound());
    }

    static Stream<MockHttpServletRequestBuilder> writes() {
        return Stream.of(
                put("/records/source-files/" + FILE),
                delete("/records/source-files/" + FILE),
                post("/records/source-files/" + FILE + "/pages"),
                patch("/records/source-files/" + FILE));
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
        mockMvc.perform(get("/records/source-files/" + FILE)).andExpect(status().isUnauthorized());
        verifyNoInteractions(records);
    }
}
