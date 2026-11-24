package com.bnaqica.documentreader.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.bnaqica.documentreader.ProcessingFixtures;
import com.bnaqica.documentreader.TestcontainersConfiguration;
import com.bnaqica.documentreader.client.VisionClient;
import com.bnaqica.documentreader.config.ClockConfig;
import com.bnaqica.documentreader.dto.CallContext;
import com.bnaqica.documentreader.dto.PageImageRef;
import com.bnaqica.documentreader.dto.TypeMatchAnswer;
import com.bnaqica.documentreader.dto.request.DocumentTypeRequest;
import com.bnaqica.documentreader.dto.response.DocumentTypeResponse;
import com.bnaqica.documentreader.entity.DocumentTypeOrigin;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DataJpaTest
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({
    TestcontainersConfiguration.class,
    ClockConfig.class,
    DocumentTypeCatalogServiceImpl.class,
    DocumentTypeServiceImpl.class,
    DocumentTypeAliasServiceImpl.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DocumentTypeCatalogServiceTests {

    private static final JsonNode SCHEMA = JsonMapper.builder()
            .build()
            .readTree("{\"type\":\"object\",\"properties\":{\"appraiser\":{\"type\":[\"string\",\"null\"]}},"
                    + "\"required\":[\"appraiser\"],\"additionalProperties\":false}");
    private static final List<PageImageRef> PAGES = List.of(new PageImageRef(1, "files/x/pages/0001.png"));

    @Autowired
    private DocumentTypeCatalogService catalog;

    @Autowired
    private DocumentTypeService documentTypes;

    @Autowired
    private DocumentTypeAliasService aliases;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private VisionClient vision;

    private final List<UUID> documents = new ArrayList<>();

    @BeforeEach
    void draftsAStrictSchema() {
        given(vision.draftSchema(any(), anyList(), any())).willReturn(SCHEMA);
    }

    @AfterEach
    void cleanUp() {
        CatalogCleanup.removeAllButSeeded(transactionManager, jdbc);
        ProcessingFixtures fixtures = new ProcessingFixtures(jdbc);
        documents.forEach(document -> fixtures.deleteBatch(fixtures.batchOf(document)));
    }

    @Test
    void newNameConfirmedAsExistingBecomesAlias() {
        given(vision.confirmNewType(any(), anyList(), eq("Earnings statement"), anyList()))
                .willReturn(new TypeMatchAnswer(true, "Pay stubs"));

        DocumentTypeResponse type = catalog.createOrMatch(context(), PAGES, "Earnings statement");

        assertThat(type.name()).isEqualTo("Pay stubs");
        assertThat(aliases.findByType(type.id()))
                .anySatisfy(alias -> assertThat(alias.alias()).isEqualTo("Earnings statement"));
        assertThat(documentTypes.resolve("earnings statements").orElseThrow().id())
                .isEqualTo(type.id());
        then(vision).should(never()).draftSchema(any(), anyList(), any());
    }

    @Test
    void newNameRejectedCreatesTypeWithDraftedSchema() {
        given(vision.confirmNewType(any(), anyList(), any(), anyList())).willReturn(new TypeMatchAnswer(false, null));
        CallContext context = context();

        DocumentTypeResponse type = catalog.createOrMatch(context, PAGES, "Moon rock appraisal");

        assertThat(type.name()).isEqualTo("Moon rock appraisal");
        assertThat(type.origin()).isEqualTo(DocumentTypeOrigin.MODEL);
        assertThat(type.dataSchema()).isEqualTo(SCHEMA);
        assertThat(jdbc.sql("SELECT created_by_document_id FROM document_reader.document_type WHERE id = ?")
                        .param(type.id())
                        .query(UUID.class)
                        .single())
                .isEqualTo(context.documentId());
    }

    @Test
    void confirmedMatchThatDoesNotResolveCreatesTheType() {
        given(vision.confirmNewType(any(), anyList(), any(), anyList()))
                .willReturn(new TypeMatchAnswer(true, "A type that isn't there"));

        DocumentTypeResponse type = catalog.createOrMatch(context(), PAGES, "Moon rock appraisal");

        assertThat(type.origin()).isEqualTo(DocumentTypeOrigin.MODEL);
    }

    @Test
    void nameAddedMeanwhileSkipsTheCalls() {
        UUID existing = documentTypes
                .create(new DocumentTypeRequest("Moon rock appraisal", "Added by an admin", SCHEMA))
                .id();

        DocumentTypeResponse type = catalog.createOrMatch(context(), PAGES, "Moon-rock appraisals");

        assertThat(type.id()).isEqualTo(existing);
        then(vision).shouldHaveNoInteractions();
    }

    @Test
    void concurrentCreateOfSameNewTypeYieldsOneType() throws Exception {
        given(vision.confirmNewType(any(), anyList(), any(), anyList())).willReturn(new TypeMatchAnswer(false, null));
        CyclicBarrier bothDrafted = new CyclicBarrier(2);
        given(vision.draftSchema(any(), anyList(), any())).willAnswer(call -> {
            bothDrafted.await(10, TimeUnit.SECONDS); // both resolved nothing; now both try to insert
            return SCHEMA;
        });
        CallContext first = context();
        CallContext second = context();

        List<UUID> ids = new ArrayList<>();
        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            Future<DocumentTypeResponse> a =
                    threads.submit(() -> catalog.createOrMatch(first, PAGES, "Moon rock appraisal"));
            Future<DocumentTypeResponse> b =
                    threads.submit(() -> catalog.createOrMatch(second, PAGES, "Moon rock appraisals"));
            ids.add(a.get(30, TimeUnit.SECONDS).id());
            ids.add(b.get(30, TimeUnit.SECONDS).id());
        }

        assertThat(ids.get(0)).isEqualTo(ids.get(1));
        assertThat(jdbc.sql("SELECT count(*) FROM document_reader.document_type WHERE normalized_key = ?")
                        .param("moonrockappraisal")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    /** A committed document of its own, which a created type records. */
    private CallContext context() {
        UUID document = new ProcessingFixtures(jdbc).document();
        documents.add(document);
        return CallContext.builder()
                .batchId(new ProcessingFixtures(jdbc).batchOf(document))
                .documentId(document)
                .loanPurposeDescription("Refinancing a car")
                .build();
    }
}
