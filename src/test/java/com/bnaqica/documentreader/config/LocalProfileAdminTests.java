package com.bnaqica.documentreader.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bnaqica.documentreader.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** The `local` profile turns authentication off, so every request must act as an admin to manage reference data. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
@Transactional // roll back the created purpose
class LocalProfileAdminTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void writeWithoutTokenSucceedsInLocalProfile() throws Exception {
        mockMvc.perform(post("/loan-purposes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"MOTORCYCLE_PURCHASE\",\"name\":\"Motorcycle purchase\","
                                + "\"description\":\"A loan to buy a motorcycle.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("MOTORCYCLE_PURCHASE"));
    }

    /** Spring passes null for an anonymous Authentication parameter; records must still read the local admin. */
    @Test
    void recordsAreReadableWithoutTokenInLocalProfile() throws Exception {
        mockMvc.perform(get("/records/batches")).andExpect(status().isOk());
    }
}
