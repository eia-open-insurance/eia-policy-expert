package com.eia.policy.expert;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalEntityRulesTest {
    @Test
    void normalizesCodeJurisdictionAndEntityType() {
        LegalEntityRules.LegalEntitySpec spec = LegalEntityRules.validateCreate(
                new CreateLegalEntityRequest("  le-001  ", "  Acme Insurance  ",
                        " insurer ", " us ", "  NAIC  ", "  LIC-42  "));

        assertThat(spec.code()).isEqualTo("LE-001");
        assertThat(spec.name()).isEqualTo("Acme Insurance");
        assertThat(spec.entityType()).isEqualTo("INSURER");
        assertThat(spec.jurisdiction()).isEqualTo("US");
        assertThat(spec.regulator()).isEqualTo("NAIC");
        assertThat(spec.licenseNumber()).isEqualTo("LIC-42");
    }

    @Test
    void acceptsEveryEntityType() {
        for (String type : new String[]{"INSURER", "MGA", "CAPTIVE", "POOL", "FRONTER"}) {
            assertThat(LegalEntityRules.validateCreate(new CreateLegalEntityRequest(
                    "LE-" + type, "Entity " + type, type, "US", null, null)).entityType())
                    .isEqualTo(type);
        }
    }

    @Test
    void blankOptionalFieldsBecomeNull() {
        LegalEntityRules.LegalEntitySpec spec = LegalEntityRules.validateCreate(
                new CreateLegalEntityRequest("LE-1", "Entity", "MGA", "GB", "   ", ""));

        assertThat(spec.regulator()).isNull();
        assertThat(spec.licenseNumber()).isNull();
    }

    @Test
    void rejectsABlankCode() {
        assertThatThrownBy(() -> LegalEntityRules.validateCreate(new CreateLegalEntityRequest(
                "  ", "Entity", "INSURER", "US", null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.code()).isEqualTo("INVALID_LEGAL_ENTITY_CODE");
                    assertThat(api.details()).extracting(ApiException.FieldIssue::field)
                            .containsExactly("code");
                });
    }

    @Test
    void rejectsAnOverlongCode() {
        assertThatThrownBy(() -> LegalEntityRules.validateCreate(new CreateLegalEntityRequest(
                "X".repeat(33), "Entity", "INSURER", "US", null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_LEGAL_ENTITY_CODE"));
    }

    @Test
    void rejectsABlankName() {
        assertThatThrownBy(() -> LegalEntityRules.validateCreate(new CreateLegalEntityRequest(
                "LE-1", "   ", "INSURER", "US", null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_LEGAL_ENTITY_NAME"));
    }

    @Test
    void rejectsUnknownEntityType() {
        assertThatThrownBy(() -> LegalEntityRules.validateCreate(new CreateLegalEntityRequest(
                "LE-1", "Entity", "REINSURER", "US", null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_LEGAL_ENTITY_TYPE"));
    }

    @Test
    void rejectsANonAlpha2Jurisdiction() {
        for (String jurisdiction : new String[]{"U", "USA", "1A", "", null}) {
            assertThatThrownBy(() -> LegalEntityRules.validateCreate(
                    new CreateLegalEntityRequest("LE-1", "Entity", "INSURER", jurisdiction,
                            null, null)))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).code())
                            .isEqualTo("INVALID_JURISDICTION"));
        }
    }

    @Test
    void rejectsOverlongOptionalFields() {
        assertThatThrownBy(() -> LegalEntityRules.validateCreate(new CreateLegalEntityRequest(
                "LE-1", "Entity", "INSURER", "US", "R".repeat(121), null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_REGULATOR"));

        assertThatThrownBy(() -> LegalEntityRules.validateCreate(new CreateLegalEntityRequest(
                "LE-1", "Entity", "INSURER", "US", null, "L".repeat(81))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_LICENSE_NUMBER"));
    }

    @Test
    void rejectsANullRequest() {
        assertThatThrownBy(() -> LegalEntityRules.validateCreate(null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_LEGAL_ENTITY"));
    }
}
