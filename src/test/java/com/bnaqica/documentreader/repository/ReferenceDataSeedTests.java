package com.bnaqica.documentreader.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.bnaqica.documentreader.TestcontainersConfiguration;
import com.bnaqica.documentreader.processors.DataSchemaRules;
import com.bnaqica.documentreader.processors.TypeNameNormalizer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks what the Flyway seed migrations put in a fresh database. It reads the tables directly, so it checks the
 * stored data rather than how the application maps it.
 */
@DataJpaTest
@Import(TestcontainersConfiguration.class)
class ReferenceDataSeedTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private JdbcClient jdbc;

    @Test
    void seedsEightLoanPurposes() {
        List<Map<String, Object>> purposes = jdbc.sql(
                        "SELECT code, name, description, active FROM document_reader.loan_purpose ORDER BY code")
                .query()
                .listOfRows();

        assertThat(purposes)
                .extracting(row -> row.get("code"))
                .containsExactly(
                        "BOAT_PURCHASE",
                        "BOAT_REFINANCE",
                        "CAR_PURCHASE",
                        "CAR_REFINANCE",
                        "HOME_PURCHASE",
                        "MORTGAGE_REFINANCE",
                        "RV_PURCHASE",
                        "RV_REFINANCE");
        assertThat(purposes).allSatisfy(row -> {
            assertThat(row.get("active")).isEqualTo(true);
            assertThat((String) row.get("name")).isNotBlank();
            assertThat((String) row.get("description")).isNotBlank();
        });
    }

    @Test
    void seedsFortyOneDocumentTypes() {
        List<Map<String, Object>> types = jdbc.sql("""
                        SELECT name, normalized_key, description, data_schema::text AS data_schema, schema_version, origin
                        FROM document_reader.document_type
                        """).query().listOfRows();

        assertThat(types)
                .extracting(row -> row.get("name"))
                .containsExactlyInAnyOrder(
                        "Appraisal",
                        "Authorization for credit check",
                        "Bank statements",
                        "Bill of sale",
                        "Break-even or net tangible benefit analysis",
                        "Birth certificate",
                        "Closing disclosure",
                        "Current loan or payoff statement",
                        "Credit explanation letter",
                        "Declaration page",
                        "Divorce decree or court support order",
                        "Driver's license or government photo ID",
                        "Employment verification",
                        "Flood determination",
                        "Gift letter",
                        "Homeowners association documents",
                        "Hull number or serial number documentation",
                        "Inspection or survey report",
                        "Investment and retirement account statements",
                        "Loan application",
                        "Loan estimate",
                        "Loan agreement or promissory note",
                        "Marine survey",
                        "Mileage or odometer reading",
                        "Other income statements",
                        "Pay stubs",
                        "Profit-and-loss statement",
                        "Proof of address",
                        "Proof of down payment or trade-in",
                        "Proof of insurance",
                        "Proof of residency or citizenship",
                        "Purchase agreement or buyer's order",
                        "Registration",
                        "Settlement statement",
                        "Social Security number or taxpayer ID",
                        "Tax returns",
                        "Title or title insurance",
                        "Valuation report",
                        "VIN",
                        "W-2 forms",
                        "1099 or K-1 forms");
        assertThat(types).extracting(row -> row.get("normalized_key")).doesNotHaveDuplicates();
        assertThat(types).allSatisfy(row -> {
            String name = (String) row.get("name");
            assertThat(row.get("normalized_key"))
                    .as(name)
                    .isEqualTo(TypeNameNormalizer.key(name).orElseThrow());
            assertThat((String) row.get("description")).as(name).isNotBlank();
            assertThat(row.get("schema_version")).as(name).isEqualTo(1);
            assertThat(row.get("origin")).as(name).isEqualTo("SEEDED");
            assertThat(DataSchemaRules.violations(JSON.readTree((String) row.get("data_schema"))))
                    .as(name)
                    .isEmpty();
        });
    }

    @Test
    void payStubsSchemaCoversLenderFields() {
        String schema = jdbc.sql("SELECT data_schema::text FROM document_reader.document_type WHERE name = 'Pay stubs'")
                .query(String.class)
                .single();

        assertThat(JSON.readTree(schema).path("properties").propertyNames())
                .contains(
                        "employerName",
                        "employeeName",
                        "payPeriodStart",
                        "payPeriodEnd",
                        "payDate",
                        "grossPay",
                        "netPay",
                        "ytdGrossPay",
                        "deductions",
                        "hoursWorked");
    }
}
