package com.bnaqica.documentreader.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bnaqica.documentreader.config.SecurityConfig;
import com.bnaqica.documentreader.dto.request.DocumentTypeAliasRequest;
import com.bnaqica.documentreader.dto.response.DocumentTypeAliasResponse;
import com.bnaqica.documentreader.exception.DocumentTypeKeyConflictException;
import com.bnaqica.documentreader.exception.ResourceNotFoundException;
import com.bnaqica.documentreader.exception.UnusableNameException;
import com.bnaqica.documentreader.service.DocumentTypeAliasService;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(DocumentTypeAliasController.class)
@Import(SecurityConfig.class)
class DocumentTypeAliasControllerTests {

    private static final UUID PAY_STUBS = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID ALIAS = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final DocumentTypeAliasResponse EARNINGS =
            new DocumentTypeAliasResponse(ALIAS, PAY_STUBS, "Earnings statement", "earningstatement");
    private static final String PATH = "/document-types/" + PAY_STUBS + "/aliases";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentTypeAliasService service;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static JwtRequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    @Test
    void readerWithoutRolesCanList() throws Exception {
        given(service.findByType(PAY_STUBS)).willReturn(List.of(EARNINGS));

        mockMvc.perform(get(PATH).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].alias").value("Earnings statement"))
                .andExpect(jsonPath("$[0].normalizedKey").value("earningstatement"));
    }

    @Test
    void adminAdds() throws Exception {
        given(service.add(eq(PAY_STUBS), any())).willReturn(EARNINGS);

        mockMvc.perform(post(PATH)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alias\":\"Earnings statement\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ALIAS.toString()));
        verify(service).add(PAY_STUBS, new DocumentTypeAliasRequest("Earnings statement"));
    }

    @Test
    void takenKeyIs409NamingTheOwner() throws Exception {
        given(service.add(eq(PAY_STUBS), any())).willThrow(new DocumentTypeKeyConflictException("w2form", "W-2 forms"));

        mockMvc.perform(post(PATH)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alias\":\"W-2 form\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("W-2 forms")));
    }

    @Test
    void aliasWithNoKeyIs400NamingAlias() throws Exception {
        given(service.add(eq(PAY_STUBS), any())).willThrow(new UnusableNameException("alias"));

        mockMvc.perform(post(PATH)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alias\":\"N/A\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.alias").exists());
    }

    @Test
    void blankAliasIs400() throws Exception {
        mockMvc.perform(post(PATH)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alias\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.alias").exists());
        verifyNoInteractions(service);
    }

    @Test
    void adminRemoves() throws Exception {
        mockMvc.perform(delete(PATH + "/" + ALIAS).with(admin())).andExpect(status().isNoContent());
        verify(service).delete(PAY_STUBS, ALIAS);
    }

    @Test
    void unknownAliasIs404() throws Exception {
        willThrow(new ResourceNotFoundException("Document type alias", ALIAS))
                .given(service)
                .delete(PAY_STUBS, ALIAS);

        mockMvc.perform(delete(PATH + "/" + ALIAS).with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void writeWithoutAdminRoleIs403() throws Exception {
        mockMvc.perform(post(PATH)
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alias\":\"Earnings statement\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    static Stream<MockHttpServletRequestBuilder> writes() {
        return Stream.of(
                post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"alias\":\"Earnings statement\"}"),
                delete(PATH + "/" + ALIAS));
    }

    @ParameterizedTest
    @MethodSource("writes")
    void everyWriteNeedsTheAdminRole(MockHttpServletRequestBuilder write) throws Exception {
        mockMvc.perform(write.with(jwt()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(service);
    }

    @Test
    void aliasesOfUnknownTypeIs404() throws Exception {
        given(service.findByType(PAY_STUBS)).willThrow(new ResourceNotFoundException("Document type", PAY_STUBS));

        mockMvc.perform(get(PATH).with(jwt())).andExpect(status().isNotFound());
    }
}
