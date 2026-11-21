package com.bnaqica.documentreader.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bnaqica.documentreader.TestcontainersConfiguration;
import com.bnaqica.documentreader.dto.request.LoanPurposeCreateRequest;
import com.bnaqica.documentreader.dto.request.LoanPurposeUpdateRequest;
import com.bnaqica.documentreader.dto.response.LoanPurposeResponse;
import com.bnaqica.documentreader.exception.LoanPurposeExistsException;
import com.bnaqica.documentreader.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import({TestcontainersConfiguration.class, LoanPurposeServiceImpl.class})
class LoanPurposeServiceTests {

    @Autowired
    private LoanPurposeService service;

    @Test
    void listsActivePurposesOrderedByCode() {
        assertThat(service.findAllActive())
                .extracting(LoanPurposeResponse::code)
                .containsExactly(
                        "BOAT_PURCHASE",
                        "BOAT_REFINANCE",
                        "CAR_PURCHASE",
                        "CAR_REFINANCE",
                        "HOME_PURCHASE",
                        "MORTGAGE_REFINANCE",
                        "RV_PURCHASE",
                        "RV_REFINANCE");
    }

    @Test
    void deactivatedPurposeIsNotListedButCanStillBeRead() {
        service.deactivate("BOAT_REFINANCE");

        assertThat(service.findAllActive())
                .extracting(LoanPurposeResponse::code)
                .doesNotContain("BOAT_REFINANCE");
        assertThat(service.get("BOAT_REFINANCE").active()).isFalse();
        assertThat(service.findActive("BOAT_REFINANCE")).isEmpty();
    }

    @Test
    void getsOnePurpose() {
        LoanPurposeResponse purpose = service.get("CAR_REFINANCE");

        assertThat(purpose.code()).isEqualTo("CAR_REFINANCE");
        assertThat(purpose.name()).isEqualTo("Car refinance");
        assertThat(purpose.active()).isTrue();
    }

    @Test
    void unknownCodeIsNotFound() {
        assertThatThrownBy(() -> service.get("SPACESHIP_PURCHASE")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.deactivate("SPACESHIP_PURCHASE"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createsAnActivePurpose() {
        LoanPurposeResponse created = service.create(new LoanPurposeCreateRequest(
                "MOTORCYCLE_PURCHASE", "Motorcycle purchase", "A loan to buy a motorcycle."));

        assertThat(created.active()).isTrue();
        assertThat(service.findAllActive())
                .extracting(LoanPurposeResponse::code)
                .contains("MOTORCYCLE_PURCHASE");
    }

    @Test
    void duplicateCodeIsRefusedAndTheExistingPurposeIsUnchanged() {
        assertThatThrownBy(() ->
                        service.create(new LoanPurposeCreateRequest("CAR_REFINANCE", "Overwritten", "Overwritten.")))
                .isInstanceOf(LoanPurposeExistsException.class);

        assertThat(service.get("CAR_REFINANCE").name()).isEqualTo("Car refinance");
    }

    @Test
    void updateReplacesNameDescriptionAndActive() {
        service.deactivate("RV_REFINANCE");

        LoanPurposeResponse updated = service.update(
                "RV_REFINANCE",
                new LoanPurposeUpdateRequest("Recreational vehicle refinance", "New description.", true));

        assertThat(updated.code()).isEqualTo("RV_REFINANCE");
        assertThat(updated.name()).isEqualTo("Recreational vehicle refinance");
        assertThat(updated.description()).isEqualTo("New description.");
        assertThat(updated.active()).isTrue();
    }

    @Test
    void updatingUnknownCodeIsNotFound() {
        assertThatThrownBy(() -> service.update("SPACESHIP_PURCHASE", new LoanPurposeUpdateRequest("x", "y", true)))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
