package com.bnaqica.documentreader.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bnaqica.documentreader.config.SecurityConfig;
import com.bnaqica.documentreader.dto.response.DocumentTypeResponse;
import com.bnaqica.documentreader.entity.DocumentType;
import com.bnaqica.documentreader.entity.DocumentTypeOrigin;
import com.bnaqica.documentreader.exception.DocumentTypeKeyConflictException;
import com.bnaqica.documentreader.exception.InvalidDataSchemaException;
import com.bnaqica.documentreader.exception.ResourceNotFoundException;
import com.bnaqica.documentreader.exception.UnusableNameException;
import com.bnaqica.documentreader.service.DocumentTypeService;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

@WebMvcTest(DocumentTypeController.class)
@Import(SecurityConfig.class)
class DocumentTypeControllerTests {

    private static final UUID LEASE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"landlord\":{\"type\":[\"string\",\"null\"]}},"
                    + "\"required\":[\"landlord\"],\"additionalProperties\":false}";
    private static final String VALID_BODY =
            "{\"name\":\"Lease agreement\",\"description\":\"A rental lease.\",\"dataSchema\":" + SCHEMA + "}";
    private static final DocumentTypeResponse LEASE = new DocumentTypeResponse(
            LEASE_ID,
            "Lease agreement",
            "leaseagreement",
            "A rental lease.",
            JsonMapper.builder().build().readTree(SCHEMA),
            1,
            DocumentTypeOrigin.MANUAL);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentTypeService service;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static JwtRequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    @Test
    void readerWithoutRolesCanList() throws Exception {
        given(service.findAll()).willReturn(List.of(LEASE));

        mockMvc.perform(get("/document-types").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Lease agreement"))
                .andExpect(jsonPath("$[0].normalizedKey").value("leaseagreement"))
                .andExpect(jsonPath("$[0].dataSchema.properties.landlord").exists())
                .andExpect(jsonPath("$[0].schemaVersion").value(1))
                .andExpect(jsonPath("$[0].origin").value("MANUAL"));
    }

    @Test
    void getsOne() throws Exception {
        given(service.get(LEASE_ID)).willReturn(LEASE);

        mockMvc.perform(get("/document-types/" + LEASE_ID).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(LEASE_ID.toString()));
    }

    @Test
    void unknownIdIs404ProblemDetail() throws Exception {
        given(service.get(LEASE_ID)).willThrow(new ResourceNotFoundException("Document type", LEASE_ID));

        mockMvc.perform(get("/document-types/" + LEASE_ID).with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void adminCreates() throws Exception {
        given(service.create(any())).willReturn(LEASE);

        mockMvc.perform(post("/document-types")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.origin").value("MANUAL"));
    }

    @Test
    void takenNameIs409NamingTheOwner() throws Exception {
        given(service.create(any())).willThrow(new DocumentTypeKeyConflictException("paystub", "Pay stubs"));

        mockMvc.perform(post("/document-types")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("Lease agreement", "Paystubs")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Pay stubs")));
    }

    @Test
    void invalidSchemaIs400NamingDataSchema() throws Exception {
        given(service.create(any()))
                .willThrow(new InvalidDataSchemaException(List.of("$: the root must be an object schema")));

        mockMvc.perform(post("/document-types")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace(SCHEMA, "{\"type\":\"string\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.dataSchema[0]").value("$: the root must be an object schema"));
    }

    @Test
    void nameWithNoKeyIs400NamingName() throws Exception {
        given(service.create(any())).willThrow(new UnusableNameException("name"));

        mockMvc.perform(post("/document-types")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("Lease agreement", "N/A")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists());
    }

    @Test
    void missingSchemaIs400NamingDataSchema() throws Exception {
        mockMvc.perform(post("/document-types")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Lease agreement\",\"description\":\"A rental lease.\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.dataSchema").exists());
        verifyNoInteractions(service);
    }

    @Test
    void adminUpdates() throws Exception {
        given(service.update(eq(LEASE_ID), any())).willReturn(LEASE);

        mockMvc.perform(put("/document-types/" + LEASE_ID)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk());
    }

    @Test
    void adminDeletes() throws Exception {
        mockMvc.perform(delete("/document-types/" + LEASE_ID).with(admin())).andExpect(status().isNoContent());
        verify(service).delete(LEASE_ID);
    }

    @Test
    void writeWithoutAdminRoleIs403() throws Exception {
        mockMvc.perform(delete("/document-types/" + LEASE_ID).with(jwt()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(service);
    }

    @Test
    void writeWithoutTokenIs401() throws Exception {
        mockMvc.perform(post("/document-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    static Stream<MockHttpServletRequestBuilder> writes() {
        return Stream.of(
                post("/document-types").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY),
                put("/document-types/" + LEASE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY),
                delete("/document-types/" + LEASE_ID));
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
    void blankDescriptionIs400NamingDescription() throws Exception {
        mockMvc.perform(post("/document-types")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("A rental lease.", " ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.description").exists());
        verifyNoInteractions(service);
    }

    /** Two admins editing the same type at once: the later save loses, and gets 409 instead of a 500. */
    @Test
    void concurrentEditIs409() throws Exception {
        given(service.update(eq(LEASE_ID), any()))
                .willThrow(new ObjectOptimisticLockingFailureException(DocumentType.class, LEASE_ID));

        mockMvc.perform(put("/document-types/" + LEASE_ID)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
