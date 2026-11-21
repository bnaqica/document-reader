package com.bnaqica.documentreader.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bnaqica.documentreader.ProcessingFixtures;
import com.bnaqica.documentreader.StepClock;
import com.bnaqica.documentreader.StepClockConfig;
import com.bnaqica.documentreader.TestcontainersConfiguration;
import com.bnaqica.documentreader.dto.NewExtractionRun;
import com.bnaqica.documentreader.entity.ExtractionStage;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DataJpaTest
@Import({TestcontainersConfiguration.class, StepClockConfig.class, ExtractionRunServiceImpl.class})
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
class ExtractionRunServiceTests {

    @Autowired
    private ExtractionRunService service;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private StepClock clock;

    private UUID document;

    @Autowired
    private EntityManager entityManager;

    /** Writes pending changes and forgets every entity, so the next read comes from Postgres, not Hibernate's cache. */
    private void reread() {
        entityManager.flush();
        entityManager.clear();
    }

    @BeforeEach
    void setUp() {
        clock.reset();
        document = new ProcessingFixtures(jdbc).document();
    }

    private JsonNode json(String text) {
        return jsonMapper.readTree(text);
    }

    private NewExtractionRun pass(ExtractionStage stage, int number) {
        return new NewExtractionRun(stage, number, json("{\"pass\":" + number + "}"), "summary " + number);
    }

    @Test
    void stageFilterReturnsThatStageInPassOrder() {
        service.save(document, pass(ExtractionStage.INITIAL, 2));
        service.save(document, pass(ExtractionStage.CONSOLIDATED, 1));
        service.save(document, pass(ExtractionStage.INITIAL, 3));
        service.save(document, pass(ExtractionStage.INITIAL, 1));

        assertThat(service.findByDocumentAndStage(document, ExtractionStage.INITIAL))
                .extracting(r -> r.stage() + ":" + r.passNumber())
                .containsExactly("INITIAL:1", "INITIAL:2", "INITIAL:3");
        assertThat(service.findByDocument(document))
                .extracting(r -> r.stage() + ":" + r.passNumber())
                .containsExactly("INITIAL:1", "INITIAL:2", "INITIAL:3", "CONSOLIDATED:1");
    }

    @Test
    void runWithoutDataIsRefused() {
        assertThatThrownBy(() -> service.save(document, new NewExtractionRun(ExtractionStage.INITIAL, 1, null, "s")))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void runReadsBackWithKeysInSavedOrder() {
        service.save(
                document,
                new NewExtractionRun(
                        ExtractionStage.FINAL,
                        1,
                        json("{\"employerName\":\"Acme\",\"payDate\":\"2026-10-31\",\"grossPay\":4200.5}"),
                        "October pay stub"));

        reread();

        assertThat(service.findByDocumentAndStage(document, ExtractionStage.FINAL))
                .singleElement()
                .satisfies(run -> {
                    assertThat(run.documentId()).isEqualTo(document);
                    assertThat(run.data().propertyNames()).containsExactly("employerName", "payDate", "grossPay");
                    assertThat(run.data().get("grossPay").decimalValue()).isEqualByComparingTo("4200.5");
                    assertThat(run.summary()).isEqualTo("October pay stub");
                    assertThat(run.createdAt()).isEqualTo(StepClock.START);
                });
    }
}
