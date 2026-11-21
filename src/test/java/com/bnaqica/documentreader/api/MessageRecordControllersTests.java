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
import com.bnaqica.documentreader.dto.response.InboxMessageRecord;
import com.bnaqica.documentreader.entity.InboxStatus;
import com.bnaqica.documentreader.entity.MessageType;
import com.bnaqica.documentreader.exception.ResourceNotFoundException;
import com.bnaqica.documentreader.service.InboxMessageService;
import com.bnaqica.documentreader.service.OutboxMessageService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({OutboxMessageRecordController.class, InboxMessageRecordController.class})
@Import(SecurityConfig.class)
class MessageRecordControllersTests {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final String OUTBOX = "/records/outbox-messages";
    private static final String INBOX = "/records/inbox-messages";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OutboxMessageService outbox;

    @MockitoBean
    private InboxMessageService inbox;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void adminListsTheOutbox() throws Exception {
        mockMvc.perform(get(OUTBOX).with(admin())).andExpect(status().isOk());
        verify(outbox).findRecent(50);
    }

    @Test
    void adminListsTheInboxWithFilters() throws Exception {
        given(inbox.find(MessageType.CLASSIFY_DOCUMENT, InboxStatus.FAILED, 10))
                .willReturn(List.of(InboxMessageRecord.builder()
                        .id(ID)
                        .messageType(MessageType.CLASSIFY_DOCUMENT)
                        .status(InboxStatus.FAILED)
                        .attempts(5)
                        .build()));

        mockMvc.perform(get(INBOX)
                        .param("type", "CLASSIFY_DOCUMENT")
                        .param("status", "FAILED")
                        .param("limit", "10")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("FAILED"))
                .andExpect(jsonPath("$[0].attempts").value(5));
    }

    @Test
    void inboxListWithoutFiltersMatchesAny() throws Exception {
        mockMvc.perform(get(INBOX).with(admin())).andExpect(status().isOk());
        verify(inbox).find(null, null, 50);
    }

    @Test
    void adminReadsOneInboxMessage() throws Exception {
        given(inbox.get(ID))
                .willReturn(InboxMessageRecord.builder()
                        .id(ID)
                        .status(InboxStatus.DONE)
                        .build());

        mockMvc.perform(get(INBOX + "/" + ID).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
    }

    @Test
    void unknownMessageIs404() throws Exception {
        given(inbox.get(ID)).willThrow(new ResourceNotFoundException("Inbox message", ID));

        mockMvc.perform(get(INBOX + "/" + ID).with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void unknownStatusIs400() throws Exception {
        mockMvc.perform(get(INBOX).param("status", "PAUSED").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(inbox);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "500"})
    void limitOutOfRangeIs400(String limit) throws Exception {
        mockMvc.perform(get(OUTBOX).param("limit", limit).with(admin())).andExpect(status().isBadRequest());
        mockMvc.perform(get(INBOX).param("limit", limit).with(admin())).andExpect(status().isBadRequest());
        verifyNoInteractions(outbox, inbox);
    }

    @ParameterizedTest
    @ValueSource(strings = {OUTBOX, INBOX, INBOX + "/00000000-0000-0000-0000-0000000000b1"})
    void nonAdminIs403(String path) throws Exception {
        mockMvc.perform(get(path).with(user1()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(outbox, inbox);
    }

    @ParameterizedTest
    @ValueSource(strings = {OUTBOX, INBOX, INBOX + "/00000000-0000-0000-0000-0000000000b1"})
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
        verifyNoInteractions(outbox, inbox);
    }

    @ParameterizedTest
    @ValueSource(strings = {OUTBOX, INBOX})
    void readWithoutTokenIs401(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        verifyNoInteractions(outbox, inbox);
    }
}
