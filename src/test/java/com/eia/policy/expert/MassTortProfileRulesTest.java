package com.eia.policy.expert;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure rules for the mass tort profile vocabulary, notes cap, coverage-block
 * date order, and the COMPLETE gate.
 */
class MassTortProfileRulesTest {
    private static final LocalDate BLOCK_START = LocalDate.of(1978, 1, 1);
    private static final LocalDate BLOCK_END = LocalDate.of(1985, 12, 31);

    @Test
    void normalizesTheVocabularyAndTrimsBlankNotes() {
        MassTortProfileRules.ProfileSpec spec = MassTortProfileRules.validate(
                request(" asbestos ", " exposure ", " all_sums ", "  allocate by year  ",
                        BLOCK_START, BLOCK_END, " complete "));

        assertThat(spec.tortType()).isEqualTo("ASBESTOS");
        assertThat(spec.triggerType()).isEqualTo("EXPOSURE");
        assertThat(spec.allocationMethod()).isEqualTo("ALL_SUMS");
        assertThat(spec.allocationNotes()).isEqualTo("allocate by year");
        assertThat(spec.status()).isEqualTo("COMPLETE");
        assertThat(spec.coverageBlockStart()).isEqualTo(BLOCK_START);
        assertThat(spec.coverageBlockEnd()).isEqualTo(BLOCK_END);

        MassTortProfileRules.ProfileSpec blankNotes = MassTortProfileRules.validate(
                request("TALC", "INJURY", "EQUAL_SHARES", "   ", null, null, "DRAFT"));
        assertThat(blankNotes.allocationNotes()).isNull();
        assertThat(blankNotes.coverageBlockStart()).isNull();
        assertThat(blankNotes.coverageBlockEnd()).isNull();
    }

    @Test
    void rejectsAnUnknownTortTriggerAllocationOrStatus() {
        assertBadRequest(() -> MassTortProfileRules.validate(
                request("MOLD", "EXPOSURE", "ALL_SUMS", null, null, null, "DRAFT")),
                "INVALID_TORT_TYPE", "tortType");
        assertBadRequest(() -> MassTortProfileRules.validate(
                request("ASBESTOS", "OCCURRENCE", "ALL_SUMS", null, null, null, "DRAFT")),
                "INVALID_TRIGGER_TYPE", "triggerType");
        assertBadRequest(() -> MassTortProfileRules.validate(
                request("ASBESTOS", "EXPOSURE", "PER_OCCURRENCE", null, null, null, "DRAFT")),
                "INVALID_ALLOCATION_METHOD", "allocationMethod");
        assertBadRequest(() -> MassTortProfileRules.validate(
                request("ASBESTOS", "EXPOSURE", "ALL_SUMS", null, null, null, "ACTIVE")),
                "INVALID_MASS_TORT_PROFILE_STATUS", "status");
        assertBadRequest(() -> MassTortProfileRules.validate(null),
                "INVALID_MASS_TORT_PROFILE", "tortType");
    }

    @Test
    void enforcesTheTwoThousandCharacterNotesCap() {
        String atLimit = "n".repeat(MassTortProfileRules.MAX_ALLOCATION_NOTES_LENGTH);

        MassTortProfileRules.ProfileSpec spec = MassTortProfileRules.validate(
                request("PFAS", "MANIFESTATION", "PRO_RATA_PREMIUM", atLimit,
                        BLOCK_START, BLOCK_END, "COMPLETE"));
        assertThat(spec.allocationNotes()).hasSize(2000);

        String overLimit = atLimit + "n";
        assertBadRequest(() -> MassTortProfileRules.validate(
                request("PFAS", "MANIFESTATION", "PRO_RATA_PREMIUM", overLimit,
                        BLOCK_START, BLOCK_END, "COMPLETE")),
                "INVALID_ALLOCATION_NOTES", "allocationNotes");
    }

    @Test
    void rejectsACoverageBlockEndBeforeTheStart() {
        assertBadRequest(() -> MassTortProfileRules.validate(
                request("ASBESTOS", "EXPOSURE", "ALL_SUMS", "notes",
                        BLOCK_END, BLOCK_START, "DRAFT")),
                "INVALID_COVERAGE_BLOCK", "coverageBlockEnd");
    }

    @Test
    void allowsASameDayInclusiveCoverageBlock() {
        MassTortProfileRules.ProfileSpec spec = MassTortProfileRules.validate(
                request("SILICA", "REVISIONIST", "STACKED_LIMITS", "notes",
                        BLOCK_START, BLOCK_START, "COMPLETE"));

        assertThat(spec.coverageBlockStart()).isEqualTo(spec.coverageBlockEnd());
    }

    @Test
    void completeRequiresNotesAndBothCoverageBlockDates() {
        assertBadRequest(() -> MassTortProfileRules.validate(
                request("ASBESTOS", "EXPOSURE", "ALL_SUMS", null,
                        BLOCK_START, BLOCK_END, "COMPLETE")),
                "INCOMPLETE_MASS_TORT_PROFILE", "allocationNotes");
        assertBadRequest(() -> MassTortProfileRules.validate(
                request("ASBESTOS", "EXPOSURE", "ALL_SUMS", "notes",
                        null, BLOCK_END, "COMPLETE")),
                "INCOMPLETE_MASS_TORT_PROFILE", "coverageBlockStart");
        assertBadRequest(() -> MassTortProfileRules.validate(
                request("ASBESTOS", "EXPOSURE", "ALL_SUMS", "notes",
                        BLOCK_START, null, "COMPLETE")),
                "INCOMPLETE_MASS_TORT_PROFILE", "coverageBlockEnd");
    }

    @Test
    void draftAllowsPartialProfileData() {
        MassTortProfileRules.ProfileSpec spec = MassTortProfileRules.validate(
                request("OTHER", "INJURY", "CUSTOM_MATRIX", null, null, null, "DRAFT"));

        assertThat(spec.status()).isEqualTo("DRAFT");
        assertThat(spec.allocationNotes()).isNull();
        assertThat(spec.coverageBlockStart()).isNull();
        assertThat(spec.coverageBlockEnd()).isNull();
    }

    private static PutMassTortProfileRequest request(String tortType, String triggerType,
                                                     String allocationMethod, String notes,
                                                     LocalDate start, LocalDate end,
                                                     String status) {
        return new PutMassTortProfileRequest(tortType, triggerType, allocationMethod, notes,
                start, end, status);
    }

    private static void assertBadRequest(ThrowingCall call, String code, String field) {
        assertThatThrownBy(call::run).isInstanceOf(ApiException.class).satisfies(ex -> {
            ApiException api = (ApiException) ex;
            assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(api.code()).isEqualTo(code);
            assertThat(api.details()).extracting(ApiException.FieldIssue::field)
                    .contains(field);
        });
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}
