package com.bnaqica.documentreader.processors;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Names compare by key, so near-duplicate document type names resolve to one type. Keys are hand-derived. */
class TypeNameNormalizerTests {

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                // pay stub variants
                "Pay stubs | paystub",
                "Paystub | paystub",
                "pay-stub | paystub",
                "PAY STUBS | paystub",
                // W-2 variants
                "W-2 forms | w2form",
                "W2 form | w2form",
                // words that keep their final s
                "Proof of address | proofofaddress",
                "Break-even analysis | breakevenanalysis",
                "Bonus status | bonusstatus",
                // other plural endings
                "Homeowners association documents | homeownerassociationdocument",
                "Insurance policies | insurancepolicy",
                "Mailing addresses | mailingaddress",
                // punctuation of any kind becomes a word break
                "Driver's license or government photo ID | driverlicenseorgovernmentphotoid",
                "1099 or K-1 forms | 1099ork1form",
                // accented letters are letters, compared without their accents
                "Résumé | resume",
                "Déclaration de revenus | declarationderevenus"
            })
    void reducesNameToKey(String name, String expectedKey) {
        assertThat(TypeNameNormalizer.key(name)).contains(expectedKey);
    }

    @ParameterizedTest(name = "\"{0}\" has no key")
    @ValueSource(strings = {"—", "N/A", "none", "Unknown", "   ", "", "?!"})
    void unusableNameHasNoKey(String name) {
        assertThat(TypeNameNormalizer.key(name)).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    void missingNameHasNoKey(String name) {
        assertThat(TypeNameNormalizer.key(name)).isEmpty();
    }

    @Test
    void wordContainingAnUnusableWordStillHasAKey() {
        assertThat(TypeNameNormalizer.key("Nonexistent")).contains("nonexistent");
    }
}
