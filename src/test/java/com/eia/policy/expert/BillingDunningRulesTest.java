package com.eia.policy.expert;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure dunning rules: level/channel vocabulary, notes trimming and the 2000
 * character cap, and the DUE/OVERDUE dunnable gate.
 */
class BillingDunningRulesTest {
    @Test
    void normalizesLevelsAndChannels() {
        assertThat(BillingDunningRules.normalizeLevel(" reminder ")).isEqualTo("REMINDER");
        assertThat(BillingDunningRules.normalizeLevel("first_notice")).isEqualTo("FIRST_NOTICE");
        assertThat(BillingDunningRules.normalizeLevel("Collections")).isEqualTo("COLLECTIONS");
        assertThat(BillingDunningRules.normalizeChannel(" email ")).isEqualTo("EMAIL");
        assertThat(BillingDunningRules.normalizeChannel("letter")).isEqualTo("LETTER");
        assertThat(BillingDunningRules.normalizeChannel("Portal")).isEqualTo("PORTAL");
    }

    @Test
    void rejectsUnknownLevelsAndChannels() {
        assertBadRequest(() -> BillingDunningRules.normalizeLevel("FINAL_DEMAND"),
                "INVALID_DUNNING_LEVEL", "level");
        assertBadRequest(() -> BillingDunningRules.normalizeLevel(null),
                "INVALID_DUNNING_LEVEL", "level");
        assertBadRequest(() -> BillingDunningRules.normalizeChannel("FAX"),
                "INVALID_DUNNING_CHANNEL", "channel");
    }

    @Test
    void trimsNotesAndTreatsBlankAsAbsent() {
        assertThat(BillingDunningRules.normalizeNotes("  call the broker  "))
                .isEqualTo("call the broker");
        assertThat(BillingDunningRules.normalizeNotes("   ")).isNull();
        assertThat(BillingDunningRules.normalizeNotes(null)).isNull();
        assertThat(BillingDunningRules.normalizeNotes("x".repeat(2000))).hasSize(2000);
    }

    @Test
    void rejectsNotesOverTwoThousandCharactersAfterTrimming() {
        assertBadRequest(() -> BillingDunningRules.normalizeNotes("  " + "x".repeat(2001) + "  "),
                "INVALID_DUNNING_NOTES", "notes");
    }

    @Test
    void onlyDueAndOverdueInstallmentsAreDunnable() {
        assertThat(BillingDunningRules.isDunnable(BillingRules.DUE)).isTrue();
        assertThat(BillingDunningRules.isDunnable(BillingRules.OVERDUE)).isTrue();
        assertThat(BillingDunningRules.isDunnable(BillingRules.PAID)).isFalse();
        assertThat(BillingDunningRules.isDunnable(BillingRules.WAIVED)).isFalse();
        assertThat(BillingDunningRules.isDunnable(null)).isFalse();
    }

    private static void assertBadRequest(ThrowingCall call, String code, String field) {
        assertThatThrownBy(call::run).isInstanceOf(ApiException.class).satisfies(ex -> {
            ApiException api = (ApiException) ex;
            assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(api.code()).isEqualTo(code);
            assertThat(api.details()).extracting(ApiException.FieldIssue::field)
                    .containsExactly(field);
        });
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}
