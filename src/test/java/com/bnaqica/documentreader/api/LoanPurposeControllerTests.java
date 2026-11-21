package com.bnaqica.documentreader.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
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
import com.bnaqica.documentreader.dto.request.LoanPurposeCreateRequest;
import com.bnaqica.documentreader.dto.request.LoanPurposeUpdateRequest;
import com.bnaqica.documentreader.dto.response.LoanPurposeResponse;
import com.bnaqica.documentreader.exception.LoanPurposeExistsException;
import com.bnaqica.documentreader.exception.ResourceNotFoundException;
import com.bnaqica.documentreader.service.LoanPurposeService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(LoanPurposeController.class)
@Import(SecurityConfig.class)
class LoanPurposeControllerTests {

    private static final String VALID_CREATE =
            "{\"code\":\"MOTORCYCLE_PURCHASE\",\"name\":\"Motorcycle purchase\",\"description\":\"A loan to buy a motorcycle.\"}";
    private static final LoanPurposeResponse CAR_REFINANCE =
            new LoanPurposeResponse("CAR_REFINANCE", "Car refinance", "Replaces a car loan.", true);
    private static final LoanPurposeResponse MOTORCYCLE =
            new LoanPurposeResponse("MOTORCYCLE_PURCHASE", "Motorcycle purchase", "A loan to buy a motorcycle.", true);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LoanPurposeService service;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static JwtRequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    @Test
    void readerWithoutRolesCanList() throws Exception {
        given(service.findAllActive()).willReturn(List.of(CAR_REFINANCE));

        mockMvc.perform(get("/loan-purposes").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("CAR_REFINANCE"))
                .andExpect(jsonPath("$[0].active").value(true));
    }

    @Test
    void getsOne() throws Exception {
        given(service.get("CAR_REFINANCE")).willReturn(CAR_REFINANCE);

        mockMvc.perform(get("/loan-purposes/CAR_REFINANCE").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Car refinance"));
    }

    @Test
    void unknownCodeIs404ProblemDetail() throws Exception {
        given(service.get("SPACESHIP_PURCHASE"))
                .willThrow(new ResourceNotFoundException("Loan purpose", "SPACESHIP_PURCHASE"));

        mockMvc.perform(get("/loan-purposes/SPACESHIP_PURCHASE").with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void adminCreates() throws Exception {
        given(service.create(any())).willReturn(MOTORCYCLE);

        mockMvc.perform(post("/loan-purposes")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("MOTORCYCLE_PURCHASE"));
        verify(service)
                .create(new LoanPurposeCreateRequest(
                        "MOTORCYCLE_PURCHASE", "Motorcycle purchase", "A loan to buy a motorcycle."));
    }

    @Test
    void duplicateCodeIs409ProblemDetail() throws Exception {
        given(service.create(any())).willThrow(new LoanPurposeExistsException("MOTORCYCLE_PURCHASE"));

        mockMvc.perform(post("/loan-purposes")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void invalidCodeIs400NamingTheField() throws Exception {
        mockMvc.perform(post("/loan-purposes")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE.replace("MOTORCYCLE_PURCHASE", "motorcycle-purchase")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.code").exists());
        verifyNoInteractions(service);
    }

    @Test
    void adminUpdates() throws Exception {
        given(service.update(eq("CAR_REFINANCE"), any())).willReturn(CAR_REFINANCE);

        mockMvc.perform(
                        put("/loan-purposes/CAR_REFINANCE")
                                .with(admin())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"name\":\"Car refinance\",\"description\":\"Replaces a car loan.\",\"active\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("CAR_REFINANCE"));
        verify(service)
                .update("CAR_REFINANCE", new LoanPurposeUpdateRequest("Car refinance", "Replaces a car loan.", true));
    }

    @Test
    void adminDeactivates() throws Exception {
        mockMvc.perform(delete("/loan-purposes/BOAT_REFINANCE").with(admin())).andExpect(status().isNoContent());
        verify(service).deactivate("BOAT_REFINANCE");
    }

    @Test
    void deactivatingUnknownCodeIs404() throws Exception {
        willThrow(new ResourceNotFoundException("Loan purpose", "SPACESHIP_PURCHASE"))
                .given(service)
                .deactivate("SPACESHIP_PURCHASE");

        mockMvc.perform(delete("/loan-purposes/SPACESHIP_PURCHASE").with(admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void writeWithoutAdminRoleIs403ProblemDetailAndChangesNothing() throws Exception {
        mockMvc.perform(post("/loan-purposes")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(service);
    }

    @Test
    void writeWithoutTokenIs401() throws Exception {
        mockMvc.perform(post("/loan-purposes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    /** Goes through the real token decoding path, so it checks SecurityConfig wires the realm role mapping. */
    @Test
    void realTokenWithAdminRealmRoleCanWrite() throws Exception {
        given(jwtDecoder.decode(anyString()))
                .willReturn(Jwt.withTokenValue("token")
                        .header("alg", "none")
                        .subject("admin-user")
                        .claim("realm_access", Map.of("roles", List.of("admin")))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(60))
                        .build());
        given(service.create(any())).willReturn(MOTORCYCLE);

        mockMvc.perform(post("/loan-purposes")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE))
                .andExpect(status().isCreated());
    }

    static Stream<MockHttpServletRequestBuilder> writes() {
        return Stream.of(
                post("/loan-purposes").contentType(MediaType.APPLICATION_JSON).content(VALID_CREATE),
                put("/loan-purposes/CAR_REFINANCE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"name\":\"Car refinance\",\"description\":\"Replaces a car loan.\",\"active\":true}"),
                delete("/loan-purposes/CAR_REFINANCE"));
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
    void invalidUpdateBodyIs400NamingTheField() throws Exception {
        mockMvc.perform(put("/loan-purposes/CAR_REFINANCE")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" \",\"description\":\"Replaces a car loan.\",\"active\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists());
        verifyNoInteractions(service);
    }

    @Test
    void updateWithoutActiveIs400NamingActive() throws Exception {
        mockMvc.perform(put("/loan-purposes/CAR_REFINANCE")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Car refinance\",\"description\":\"Replaces a car loan.\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.active").exists());
        verifyNoInteractions(service);
    }
}
