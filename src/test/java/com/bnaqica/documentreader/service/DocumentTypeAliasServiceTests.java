package com.bnaqica.documentreader.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bnaqica.documentreader.TestcontainersConfiguration;
import com.bnaqica.documentreader.config.ClockConfig;
import com.bnaqica.documentreader.dto.request.DocumentTypeAliasRequest;
import com.bnaqica.documentreader.dto.request.DocumentTypeRequest;
import com.bnaqica.documentreader.dto.response.DocumentTypeAliasResponse;
import com.bnaqica.documentreader.dto.response.DocumentTypeResponse;
import com.bnaqica.documentreader.entity.DocumentTypeAlias;
import com.bnaqica.documentreader.exception.DocumentTypeKeyConflictException;
import com.bnaqica.documentreader.exception.ResourceNotFoundException;
import com.bnaqica.documentreader.exception.UnusableNameException;
import com.bnaqica.documentreader.repository.DocumentTypeAliasRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

@DataJpaTest
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({
    TestcontainersConfiguration.class,
    ClockConfig.class,
    DocumentTypeServiceImpl.class,
    DocumentTypeAliasServiceImpl.class
})
class DocumentTypeAliasServiceTests {

    @Autowired
    private DocumentTypeAliasService aliases;

    @Autowired
    private DocumentTypeService types;

    @Autowired
    private DocumentTypeAliasRepository aliasRepository;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** After the test's own transaction has rolled back, so the cleanup can't wait on its locks. */
    @AfterTransaction
    void removeCommittedTypesAndAliases() {
        CatalogCleanup.removeAllButSeeded(transactionManager, jdbc);
    }

    private UUID payStubsId() {
        return types.resolve("Pay stubs").orElseThrow().id();
    }

    @Test
    void addedAliasIsListedAndResolves() {
        DocumentTypeAliasResponse added = aliases.add(payStubsId(), new DocumentTypeAliasRequest("Earnings statement"));

        assertThat(added.normalizedKey()).isEqualTo("earningstatement");
        assertThat(aliases.findByType(payStubsId()))
                .extracting(DocumentTypeAliasResponse::alias)
                .containsExactly("Earnings statement");
        assertThat(types.resolve("earnings statements"))
                .map(DocumentTypeResponse::name)
                .contains("Pay stubs");
    }

    @Test
    void aliasMatchingItsOwnTypeIsRefused() {
        assertThatThrownBy(() -> aliases.add(payStubsId(), new DocumentTypeAliasRequest("Paystub")))
                .isInstanceOf(DocumentTypeKeyConflictException.class)
                .hasMessageContaining("Pay stubs");
    }

    @Test
    void aliasCollidingWithAnotherTypeIsRefusedNamingThatType() {
        assertThatThrownBy(() -> aliases.add(payStubsId(), new DocumentTypeAliasRequest("W-2 form")))
                .isInstanceOf(DocumentTypeKeyConflictException.class)
                .hasMessageContaining("W-2 forms");
    }

    /** The service checks first, but the database must refuse a shared key even when that check is bypassed. */
    @Test
    void databaseRefusesAnAliasSharingATypesKey() {
        DocumentTypeAlias colliding = DocumentTypeAlias.builder()
                .id(UUID.randomUUID())
                .documentTypeId(payStubsId())
                .alias("W-2 form")
                .normalizedKey("w2form")
                .build();

        assertThatThrownBy(() -> aliasRepository.saveAndFlush(colliding))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** And the other way round: a type can't take a key an alias already holds. */
    @Test
    void databaseRefusesATypeSharingAnAliasKey() {
        aliases.add(payStubsId(), new DocumentTypeAliasRequest("Earnings statement"));

        assertThatThrownBy(() -> jdbc.sql("""
                                INSERT INTO document_reader.document_type
                                    (id, name, normalized_key, description, data_schema, schema_version, origin, created_at)
                                VALUES (?, 'Earnings statement', 'earningstatement', 'x', '{}', 1, 'MANUAL', now())
                                """).param(UUID.randomUUID()).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aliasWithNoKeyIsRefused() {
        assertThatThrownBy(() -> aliases.add(payStubsId(), new DocumentTypeAliasRequest("N/A")))
                .isInstanceOf(UnusableNameException.class);
    }

    @Test
    void removedAliasNoLongerResolves() {
        DocumentTypeAliasResponse added = aliases.add(payStubsId(), new DocumentTypeAliasRequest("Earnings statement"));

        aliases.delete(payStubsId(), added.id());

        assertThat(types.resolve("Earnings statement")).isEmpty();
    }

    @Test
    void deletingATypeRemovesItsAliases() {
        DocumentTypeResponse lease = types.create(new DocumentTypeRequest(
                "Lease agreement",
                "A rental lease.",
                JsonMapper.builder()
                        .build()
                        .readTree("{\"type\":\"object\",\"properties\":{\"landlord\":{\"type\":[\"string\",\"null\"]}},"
                                + "\"required\":[\"landlord\"],\"additionalProperties\":false}")));
        aliases.add(lease.id(), new DocumentTypeAliasRequest("Rental agreement"));

        types.delete(lease.id());

        assertThat(types.resolve("Rental agreement")).isEmpty();
        assertThat(jdbc.sql(
                                "SELECT count(*) FROM document_reader.document_type_alias WHERE normalized_key = 'rentalagreement'")
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    void unknownTypeOrAliasIsNotFound() {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> aliases.findByType(unknown)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> aliases.add(unknown, new DocumentTypeAliasRequest("Earnings statement")))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> aliases.delete(payStubsId(), unknown)).isInstanceOf(ResourceNotFoundException.class);
    }
}
