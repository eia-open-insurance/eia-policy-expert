package com.eia.policy.expert;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure billing rules: equal split with the rounding remainder on the last
 * installment, due-date cadence, and the allowed vocabulary.
 */
class BillingRulesTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final LocalDate FIRST_DUE = LocalDate.of(2026, 2, 1);

    @Test
    void splitsEquallyAndPutsTheRemainderOnTheLastInstallment() {
        List<BillingRules.InstallmentDraft> drafts = BillingRules.split(
                new BigDecimal("100000.00"), 3, BillingRules.MONTHLY, FIRST_DUE);

        assertThat(drafts).extracting(BillingRules.InstallmentDraft::amount)
                .containsExactly(
                        new BigDecimal("33333.3333"),
                        new BigDecimal("33333.3333"),
                        new BigDecimal("33333.3334"));
        assertThat(sum(drafts)).isEqualByComparingTo("100000.0000");
        assertThat(drafts).extracting(BillingRules.InstallmentDraft::dueDate)
                .containsExactly(LocalDate.of(2026, 2, 1),
                        LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 1));
    }

    @Test
    void preservesTheExactPremiumForSevenInstallments() {
        List<BillingRules.InstallmentDraft> drafts = BillingRules.split(
                new BigDecimal("120000.00"), 7, BillingRules.MONTHLY, FIRST_DUE);

        assertThat(drafts).hasSize(7);
        assertThat(drafts.get(0).amount()).isEqualByComparingTo("17142.8571");
        assertThat(drafts.get(6).amount()).isEqualByComparingTo("17142.8574");
        assertThat(sum(drafts)).isEqualByComparingTo("120000.0000");
    }

    @Test
    void singleInstallmentGetsTheWholePremium() {
        List<BillingRules.InstallmentDraft> drafts = BillingRules.split(
                new BigDecimal("5000.00"), 1, BillingRules.ANNUAL, FIRST_DUE);

        assertThat(drafts).singleElement()
                .satisfies(draft -> {
                    assertThat(draft.amount()).isEqualByComparingTo("5000.0000");
                    assertThat(draft.dueDate()).isEqualTo(FIRST_DUE);
                });
    }

    @Test
    void zeroPremiumSplitsIntoZeroAmounts() {
        List<BillingRules.InstallmentDraft> drafts = BillingRules.split(
                BigDecimal.ZERO, 4, BillingRules.QUARTERLY, FIRST_DUE);

        assertThat(drafts).allSatisfy(draft ->
                assertThat(draft.amount()).isEqualByComparingTo("0.0000"));
        assertThat(sum(drafts)).isEqualByComparingTo("0.0000");
    }

    @Test
    void appliesTheFrequencyToEveryDueDate() {
        assertThat(dueDates(BillingRules.MONTHLY)).containsExactly(
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 5, 1));
        assertThat(dueDates(BillingRules.QUARTERLY)).containsExactly(
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 5, 1),
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 11, 1));
        assertThat(dueDates(BillingRules.SEMI_ANNUAL)).containsExactly(
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 8, 1),
                LocalDate.of(2027, 2, 1), LocalDate.of(2027, 8, 1));
        assertThat(dueDates(BillingRules.ANNUAL)).containsExactly(
                LocalDate.of(2026, 2, 1), LocalDate.of(2027, 2, 1),
                LocalDate.of(2028, 2, 1), LocalDate.of(2029, 2, 1));
    }

    @Test
    void rejectsCountsOutsideOneToTwentyFour() {
        assertInvalidCount(0);
        assertInvalidCount(25);
        assertInvalidCount(-1);
    }

    @Test
    void rejectsUnknownFrequency() {
        assertThatThrownBy(() -> BillingRules.validateCreate(
                new CreateBillingPlanRequest(2, "WEEKLY", FIRST_DUE), policy()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("INVALID_BILLING_FREQUENCY");
                });
    }

    @Test
    void normalizesFrequencyAndStatus() {
        assertThat(BillingRules.normalizeFrequency(" monthly ")).isEqualTo(BillingRules.MONTHLY);
        assertThat(BillingRules.normalizeFrequency("semi_annual"))
                .isEqualTo(BillingRules.SEMI_ANNUAL);
        assertThat(BillingRules.normalizeTargetStatus("paid")).isEqualTo(BillingRules.PAID);
        assertThat(BillingRules.normalizeTargetStatus("Waived")).isEqualTo(BillingRules.WAIVED);
    }

    @Test
    void rejectsInstallmentStatesOtherThanPaidOrWaived() {
        for (String status : List.of("DUE", "OVERDUE", "CREDITED")) {
            assertThatThrownBy(() -> BillingRules.normalizeTargetStatus(status))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException api = (ApiException) ex;
                        assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(api.code()).isEqualTo("INVALID_INSTALLMENT_STATUS");
                    });
        }
    }

    @Test
    void outstandingAmountsRespectCreditsAndTerminalStates() {
        assertThat(BillingRules.outstandingAmount(new BigDecimal("100.0000"),
                new BigDecimal("25.0000"), BillingRules.DUE)).isEqualByComparingTo("75.0000");
        assertThat(BillingRules.outstandingAmount(new BigDecimal("100.0000"),
                new BigDecimal("25.0000"), BillingRules.OVERDUE)).isEqualByComparingTo("75.0000");
        assertThat(BillingRules.outstandingAmount(new BigDecimal("100.0000"),
                new BigDecimal("25.0000"), BillingRules.PAID)).isEqualByComparingTo("0.0000");
        assertThat(BillingRules.outstandingAmount(new BigDecimal("100.0000"),
                BigDecimal.ZERO, BillingRules.WAIVED)).isEqualByComparingTo("0.0000");
        assertThat(BillingRules.outstandingAmount(new BigDecimal("100.0000"),
                new BigDecimal("100.0000"), BillingRules.CREDITED))
                .isEqualByComparingTo("0.0000");
        assertThat(BillingRules.isSettled(BillingRules.CREDITED)).isTrue();
        assertThat(BillingRules.isSettleable(BillingRules.CREDITED)).isFalse();
    }

    @Test
    void validateCreateBuildsTheWholeScheduleFromThePolicyPremium() {
        BillingRules.PlanSpec spec = BillingRules.validateCreate(
                new CreateBillingPlanRequest(4, "quarterly", FIRST_DUE), policy());

        assertThat(spec.installmentCount()).isEqualTo(4);
        assertThat(spec.frequency()).isEqualTo(BillingRules.QUARTERLY);
        assertThat(spec.totalPremium()).isEqualByComparingTo("120000.0000");
        assertThat(spec.installments()).hasSize(4);
        assertThat(sum(spec.installments())).isEqualByComparingTo("120000.0000");
    }

    @Test
    void validateAdjustmentDerivesThePositiveRemainderAndNextInstallment() {
        BillingRules.AdjustmentSpec spec = BillingRules.validateAdjustment(
                new BillingAdjustmentRequest(LocalDate.of(2026, 5, 1), "  exposure audit  "),
                policy("150000.00"), plan(new BigDecimal("120000.00"), 3, "ACTIVE"));

        assertThat(spec.adjustmentAmount()).isEqualByComparingTo("30000.0000");
        assertThat(spec.newTotalPremium()).isEqualByComparingTo("150000.0000");
        assertThat(spec.installmentNumber()).isEqualTo(4);
        assertThat(spec.dueDate()).isEqualTo(LocalDate.of(2026, 5, 1));
        assertThat(spec.reason()).isEqualTo("exposure audit");
    }

    @Test
    void validateAdjustmentRejectsZeroAndNegativeRemainders() {
        assertAdjustmentRejected(policy("120000.00"), plan(new BigDecimal("120000.00"), 2, "ACTIVE"),
                "NO_PREMIUM_ADJUSTMENT", HttpStatus.BAD_REQUEST, "delta");
        assertAdjustmentRejected(policy("100000.00"), plan(new BigDecimal("120000.00"), 2, "ACTIVE"),
                "NO_PREMIUM_ADJUSTMENT", HttpStatus.BAD_REQUEST, "delta");
    }

    @Test
    void validateAdjustmentRejectsAnInactivePlan() {
        assertAdjustmentRejected(policy("150000.00"),
                plan(new BigDecimal("120000.00"), 2, "CANCELLED"),
                "BILLING_PLAN_NOT_ACTIVE", HttpStatus.CONFLICT, "policyId");
    }

    @Test
    void validateAdjustmentAllowsTheTwentyFourthInstallment() {
        BillingRules.AdjustmentSpec spec = BillingRules.validateAdjustment(
                new BillingAdjustmentRequest(LocalDate.of(2026, 5, 1), "growth"),
                policy("150000.00"), plan(new BigDecimal("120000.00"), 23, "ACTIVE"));

        assertThat(spec.installmentNumber()).isEqualTo(24);
        assertThat(spec.adjustmentAmount()).isEqualByComparingTo("30000.0000");
        assertThat(spec.newTotalPremium()).isEqualByComparingTo("150000.0000");
    }

    @Test
    void validateAdjustmentRejectsAFullScheduleBeforeTheMoneyCheck() {
        assertAdjustmentRejected(policy("120000.00"), plan(new BigDecimal("120000.00"), 24, "ACTIVE"),
                "MAX_INSTALLMENTS_REACHED", HttpStatus.CONFLICT, "installmentCount");
    }

    @Test
    void validateAdjustmentRequiresAValidDueDateAndReason() {
        assertAdjustmentRejected(policy("150000.00"), plan(new BigDecimal("120000.00"), 2, "ACTIVE"),
                new BillingAdjustmentRequest(LocalDate.of(2025, 12, 31), "audit"),
                "INVALID_ADJUSTMENT_DUE_DATE", HttpStatus.BAD_REQUEST, "dueDate");
        assertAdjustmentRejected(policy("150000.00"), plan(new BigDecimal("120000.00"), 2, "ACTIVE"),
                new BillingAdjustmentRequest(LocalDate.of(2026, 5, 1), "   "),
                "INVALID_ADJUSTMENT_REASON", HttpStatus.BAD_REQUEST, "reason");
        assertAdjustmentRejected(policy("150000.00"), plan(new BigDecimal("120000.00"), 2, "ACTIVE"),
                new BillingAdjustmentRequest(LocalDate.of(2026, 5, 1), "x".repeat(501)),
                "INVALID_ADJUSTMENT_REASON", HttpStatus.BAD_REQUEST, "reason");
    }

    @Test
    void validateAdjustmentAllowsThePolicyEffectiveDate() {
        BillingRules.AdjustmentSpec spec = BillingRules.validateAdjustment(
                new BillingAdjustmentRequest(LocalDate.of(2026, 1, 1), "inception correction"),
                policy("150000.00"), plan(new BigDecimal("120000.00"), 2, "ACTIVE"));

        assertThat(spec.dueDate()).isEqualTo(LocalDate.of(2026, 1, 1));
    }

    private static void assertAdjustmentRejected(Policy policy, PolicyBillingPlan plan,
                                                 String code, HttpStatus status, String field) {
        assertAdjustmentRejected(policy, plan,
                new BillingAdjustmentRequest(LocalDate.of(2026, 5, 1), "audit"),
                code, status, field);
    }

    private static void assertAdjustmentRejected(Policy policy, PolicyBillingPlan plan,
                                                 BillingAdjustmentRequest request, String code,
                                                 HttpStatus status, String field) {
        assertThatThrownBy(() -> BillingRules.validateAdjustment(request, policy, plan))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(status);
                    assertThat(api.code()).isEqualTo(code);
                    assertThat(api.details()).singleElement()
                            .satisfies(issue -> assertThat(issue.field()).isEqualTo(field));
                });
    }

    private static List<LocalDate> dueDates(String frequency) {
        return BillingRules.split(new BigDecimal("100.00"), 4, frequency, FIRST_DUE).stream()
                .map(BillingRules.InstallmentDraft::dueDate)
                .toList();
    }

    private static BigDecimal sum(List<BillingRules.InstallmentDraft> drafts) {
        return drafts.stream().map(BillingRules.InstallmentDraft::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static void assertInvalidCount(int count) {
        assertThatThrownBy(() -> BillingRules.validateCreate(
                new CreateBillingPlanRequest(count, "MONTHLY", FIRST_DUE), policy()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("INVALID_INSTALLMENT_COUNT");
                });
    }

    private static Policy policy() {
        return policy("120000.00");
    }

    private static Policy policy(String premium) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new Policy(POLICY_ID, TENANT, "POL-2026-0000001", "CGL", UUID.randomUUID(),
                "ISSUED", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1),
                new BigDecimal(premium), "USD", new BigDecimal("5000000.0000"), "USD",
                0, now, now);
    }

    private static PolicyBillingPlan plan(BigDecimal totalPremium, int installmentCount,
                                          String status) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new PolicyBillingPlan(POLICY_ID, TENANT, POLICY_ID, "BP-0001", totalPremium,
                "USD", installmentCount, BillingRules.MONTHLY, FIRST_DUE, status, now);
    }
}
