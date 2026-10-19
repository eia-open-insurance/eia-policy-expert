package com.eia.policy.expert;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyRulesTest {
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 1, 1);

    @Test
    void acceptsEqualEffectiveAndExpirationDates() {
        assertThatCode(() -> PolicyRules.validateCreate(null, EFFECTIVE, EFFECTIVE,
                BigDecimal.ZERO, BigDecimal.ZERO)).doesNotThrowAnyException();
    }

    @Test
    void rejectsEffectiveDateAfterExpirationDate() {
        assertThatThrownBy(() -> PolicyRules.validateCreate(null, LocalDate.of(2027, 1, 2),
                LocalDate.of(2027, 1, 1), BigDecimal.TEN, BigDecimal.TEN))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("VALIDATION_ERROR");
                    assertThat(api.details()).anyMatch(issue -> issue.field().equals("expirationDate"));
                });
    }

    @Test
    void rejectsNegativePremiumAndLimit() {
        assertThatThrownBy(() -> PolicyRules.validateCreate(null, EFFECTIVE, EFFECTIVE.plusYears(1),
                new BigDecimal("-0.01"), new BigDecimal("-1.00")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).details())
                        .extracting(ApiException.FieldIssue::field)
                        .contains("premiumAmount", "limitAmount"));
    }

    @Test
    void newPoliciesMustStartInDraft() {
        assertThat(PolicyRules.normalizeInitialStatus(null)).isEqualTo("DRAFT");
        assertThat(PolicyRules.normalizeInitialStatus("draft")).isEqualTo("DRAFT");
        assertThatThrownBy(() -> PolicyRules.normalizeInitialStatus("IN_FORCE"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("INVALID_INITIAL_STATUS"));
    }

    @Test
    void acceptsTheSpecLifecycle() {
        List<String[]> transitions = List.of(
                new String[]{"DRAFT", "QUOTED"},
                new String[]{"QUOTED", "BOUND"},
                new String[]{"BOUND", "ISSUED"},
                new String[]{"ISSUED", "IN_FORCE"},
                new String[]{"IN_FORCE", "ENDORSED"},
                new String[]{"ENDORSED", "IN_FORCE"},
                new String[]{"IN_FORCE", "CANCELLED"},
                new String[]{"CANCELLED", "REINSTATED"},
                new String[]{"REINSTATED", "IN_FORCE"},
                new String[]{"EXPIRED", "RENEWED"},
                new String[]{"IN_FORCE", "VOID"}
        );
        transitions.forEach(transition ->
                assertThat(PolicyRules.validateTransition(transition[0], transition[1]))
                        .isEqualTo(transition[1]));
    }

    @Test
    void rejectsSkippedAndTerminalTransitions() {
        assertThatThrownBy(() -> PolicyRules.validateTransition("DRAFT", "ISSUED"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("INVALID_STATUS_TRANSITION"));
        assertThatThrownBy(() -> PolicyRules.validateTransition("VOID", "IN_FORCE"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PolicyRules.validateTransition("DRAFT", "DRAFT"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsUnknownStatus() {
        assertThatThrownBy(() -> PolicyRules.normalizeStatus("PENDING"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("INVALID_STATUS"));
    }
}
