package com.eia.policy.expert;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure billing invariants: frequency/status vocabulary, the 1..24 installment
 * window, the equal split with a remainder on the last installment, and the
 * collectible remainder after applied credit notes.
 *
 * <p>The split uses the same numeric(19,4) money semantics as the rest of the
 * Policy vertical (HALF_UP at scale 4). The first n-1 amounts are equal and the
 * final amount is {@code total - base * (n - 1)}, so the sum always equals the
 * policy premium to the last minor unit.</p>
 */
final class BillingRules {
    static final int MIN_INSTALLMENTS = 1;
    static final int MAX_INSTALLMENTS = 24;

    static final int MAX_RECEIVABLE_LIMIT = 200;

    static final String MONTHLY = "MONTHLY";
    static final String QUARTERLY = "QUARTERLY";
    static final String SEMI_ANNUAL = "SEMI_ANNUAL";
    static final String ANNUAL = "ANNUAL";

    static final String DUE = "DUE";
    static final String OVERDUE = "OVERDUE";
    static final String PAID = "PAID";
    static final String WAIVED = "WAIVED";
    static final String CREDITED = "CREDITED";
    static final String ACTIVE = "ACTIVE";

    static final int MAX_ADJUSTMENT_REASON = 500;

    private static final Set<String> FREQUENCIES =
            Set.of(MONTHLY, QUARTERLY, SEMI_ANNUAL, ANNUAL);
    private static final Set<String> SETTLEABLE = Set.of(DUE, OVERDUE);
    private static final Set<String> SETTLEMENT_TARGETS = Set.of(PAID, WAIVED);
    private static final Set<String> TERMINAL = Set.of(PAID, WAIVED, CREDITED);
    private static final Set<String> RECEIVABLE = Set.of(DUE, OVERDUE);

    private BillingRules() {
    }

    static PlanSpec validateCreate(CreateBillingPlanRequest request, Policy policy) {
        if (request == null) {
            throw PolicyErrors.badRequest("INVALID_BILLING_PLAN", "installmentCount",
                    "A billing plan request is required.");
        }
        Integer requestedCount = request.installmentCount();
        if (requestedCount == null
                || requestedCount < MIN_INSTALLMENTS || requestedCount > MAX_INSTALLMENTS) {
            String message = "installmentCount must be between " + MIN_INSTALLMENTS
                    + " and " + MAX_INSTALLMENTS + ".";
            throw PolicyErrors.badRequest("INVALID_INSTALLMENT_COUNT", "installmentCount", message);
        }
        if (request.firstDueDate() == null) {
            throw PolicyErrors.badRequest("INVALID_FIRST_DUE_DATE", "firstDueDate",
                    "firstDueDate is required.");
        }
        String frequency = normalizeFrequency(request.frequency());
        int count = requestedCount;
        BigDecimal total = Money.of(policy.premiumAmount());
        List<InstallmentDraft> installments = split(total, count, frequency, request.firstDueDate());
        return new PlanSpec(count, frequency, request.firstDueDate(), total, installments);
    }

    /**
     * Validates a positive premium adjustment against the ACTIVE plan and the
     * credits already applied to its schedule. The appended amount is the policy
     * written premium less the plan's net premium (its total less the applied
     * credits, floored at zero), so the plan's net premium always catches up to
     * the policy premium. A non-positive remainder is
     * {@code NO_PREMIUM_ADJUSTMENT}; a premium decrease must use a credit note.
     *
     * <p>The 1..24 installment window is checked before the money remainder so a
     * full schedule reports {@code MAX_INSTALLMENTS_REACHED} even when the
     * premium has not moved. The due date is required and may not precede the
     * policy effective date (the effective date itself is allowed); the reason is
     * required, trimmed, and capped at 500 characters.</p>
     */
    static AdjustmentSpec validateAdjustment(BillingAdjustmentRequest request, Policy policy,
                                             PolicyBillingPlan plan,
                                             BigDecimal creditedAmount) {
        if (request == null) {
            throw PolicyErrors.badRequest("INVALID_BILLING_ADJUSTMENT", "request",
                    "A billing adjustment request is required.");
        }
        if (plan == null) {
            throw PolicyErrors.notFound("BILLING_PLAN_NOT_FOUND", "policyId",
                    "No billing plan exists for policy: " + policy.id());
        }
        if (!ACTIVE.equals(plan.status())) {
            throw PolicyErrors.conflict("BILLING_PLAN_NOT_ACTIVE", "policyId",
                    "Billing plan " + plan.id() + " is " + plan.status()
                            + "; only an ACTIVE plan can be adjusted.",
                    Map.of("planId", plan.id().toString(), "currentStatus", plan.status()));
        }
        if (plan.installmentCount() >= MAX_INSTALLMENTS) {
            throw PolicyErrors.conflict("MAX_INSTALLMENTS_REACHED", "installmentCount",
                    "A billing plan cannot exceed " + MAX_INSTALLMENTS + " installments.",
                    Map.of("maxInstallments", MAX_INSTALLMENTS,
                            "installmentCount", plan.installmentCount()));
        }
        LocalDate dueDate = request.dueDate();
        if (dueDate == null) {
            throw PolicyErrors.badRequest("INVALID_ADJUSTMENT_DUE_DATE", "dueDate",
                    "dueDate is required.");
        }
        if (policy.effectiveDate() != null && dueDate.isBefore(policy.effectiveDate())) {
            throw PolicyErrors.badRequest("INVALID_ADJUSTMENT_DUE_DATE", "dueDate",
                    "dueDate must not be before the policy effective date "
                            + policy.effectiveDate() + ".");
        }
        String reason = normalizeAdjustmentReason(request.reason());
        BigDecimal credited = creditedAmount == null
                ? Money.of(BigDecimal.ZERO) : Money.of(creditedAmount);
        BigDecimal adjustmentAmount = adjustmentDelta(policy, plan, credited);
        if (adjustmentAmount.signum() <= 0) {
            BigDecimal netPlanPremium = netPlanPremium(plan, credited);
            throw PolicyErrors.badRequest("NO_PREMIUM_ADJUSTMENT", "delta",
                    "The policy premium does not exceed the billing plan's net premium by a"
                            + " positive amount; a premium decrease must be handled with a credit"
                            + " note.",
                    Map.of("delta", adjustmentAmount.toPlainString(),
                            "policyPremiumAmount", Money.of(policy.premiumAmount()).toPlainString(),
                            "planTotalPremium",
                            Money.of(plan.totalPremium()).toPlainString(),
                            "creditedAmount", credited.toPlainString(),
                            "netPlanPremium", netPlanPremium.toPlainString()));
        }
        BigDecimal newTotalPremium = Money.of(plan.totalPremium().add(adjustmentAmount));
        return new AdjustmentSpec(adjustmentAmount, newTotalPremium,
                plan.installmentCount() + 1, dueDate, reason);
    }

    /** The positive premium remainder the adjustment bills, net of applied credits. */
    static BigDecimal adjustmentDelta(Policy policy, PolicyBillingPlan plan,
                                      BigDecimal creditedAmount) {
        return Money.of(policy.premiumAmount()).subtract(netPlanPremium(plan, creditedAmount));
    }

    /**
     * The plan premium net of the credits already applied to its schedule,
     * floored at zero: credits can cover the scheduled total but can never make
     * the collectible plan premium negative.
     */
    static BigDecimal netPlanPremium(PolicyBillingPlan plan, BigDecimal creditedAmount) {
        BigDecimal credited = creditedAmount == null
                ? Money.of(BigDecimal.ZERO) : Money.of(creditedAmount);
        BigDecimal net = Money.of(plan.totalPremium()).subtract(credited);
        return net.signum() < 0 ? Money.of(BigDecimal.ZERO) : net;
    }

    static String normalizeAdjustmentReason(String raw) {
        if (raw == null || raw.isBlank()) {
            throw PolicyErrors.badRequest("INVALID_ADJUSTMENT_REASON", "reason",
                    "reason is required.");
        }
        String value = raw.trim();
        if (value.length() > MAX_ADJUSTMENT_REASON) {
            throw PolicyErrors.badRequest("INVALID_ADJUSTMENT_REASON", "reason",
                    "reason must be at most " + MAX_ADJUSTMENT_REASON + " characters.");
        }
        return value;
    }

    static String normalizeFrequency(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!FREQUENCIES.contains(value)) {
            String message = "frequency must be one of " + String.join(", ",
                    List.of(MONTHLY, QUARTERLY, SEMI_ANNUAL, ANNUAL)) + ".";
            throw PolicyErrors.badRequest("INVALID_BILLING_FREQUENCY", "frequency", message);
        }
        return value;
    }

    static String normalizeTargetStatus(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!SETTLEMENT_TARGETS.contains(value)) {
            throw PolicyErrors.badRequest("INVALID_INSTALLMENT_STATUS", "status",
                    "status must be PAID or WAIVED.");
        }
        return value;
    }

    static boolean isSettled(String status) {
        return TERMINAL.contains(status);
    }

    static boolean isSettleable(String status) {
        return SETTLEABLE.contains(status);
    }

    /**
     * The collectible remainder of one installment. WAIVED, PAID, and CREDITED
     * rows owe nothing; an open DUE/OVERDUE row owes its gross amount less the
     * credits already applied to it. The result is never negative.
     */
    static BigDecimal outstandingAmount(BigDecimal amount, BigDecimal creditedAmount,
                                        String status) {
        if (PAID.equals(status) || WAIVED.equals(status) || CREDITED.equals(status)) {
            return Money.of(BigDecimal.ZERO);
        }
        BigDecimal gross = Money.of(amount == null ? BigDecimal.ZERO : amount);
        BigDecimal credited = Money.of(
                creditedAmount == null ? BigDecimal.ZERO : creditedAmount);
        BigDecimal outstanding = gross.subtract(credited);
        return outstanding.signum() < 0 ? Money.of(BigDecimal.ZERO) : outstanding;
    }

    /**
     * Normalizes the optional receivables status filter. A blank value keeps
     * the default (all unsettled installments); anything outside the two
     * receivable statuses is a 400.
     */
    static String normalizeReceivableStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!RECEIVABLE.contains(value)) {
            throw PolicyErrors.badRequest("INVALID_INSTALLMENT_STATUS", "status",
                    "status must be DUE or OVERDUE.");
        }
        return value;
    }

    /**
     * Clamps the worklist size into 1..200. The default of 100 is applied by
     * the controller, so a service caller always supplies an explicit limit.
     */
    static int normalizeReceivableLimit(int limit) {
        return Math.min(Math.max(limit, 1), MAX_RECEIVABLE_LIMIT);
    }

    /**
     * Effective status at read time. A stored DUE row past its due date is
     * reported as OVERDUE; the stored row is never rewritten by the query.
     */
    static String effectiveStatus(String storedStatus, LocalDate dueDate, LocalDate today) {
        if (OVERDUE.equals(storedStatus)) {
            return OVERDUE;
        }
        if (DUE.equals(storedStatus) && dueDate != null && dueDate.isBefore(today)) {
            return OVERDUE;
        }
        return storedStatus;
    }

    static long daysPastDue(String effectiveStatus, LocalDate dueDate, LocalDate today) {
        if (!OVERDUE.equals(effectiveStatus) || dueDate == null) {
            return 0L;
        }
        return Math.max(0L, ChronoUnit.DAYS.between(dueDate, today));
    }

    static List<InstallmentDraft> split(BigDecimal totalPremium, int installmentCount,
                                        String frequency, LocalDate firstDueDate) {
        BigDecimal total = Money.of(totalPremium);
        BigDecimal base = total.divide(BigDecimal.valueOf(installmentCount), Money.SCALE,
                RoundingMode.HALF_UP);
        List<InstallmentDraft> drafts = new ArrayList<>(installmentCount);
        for (int number = 1; number <= installmentCount; number++) {
            BigDecimal amount = number == installmentCount
                    ? total.subtract(base.multiply(BigDecimal.valueOf(installmentCount - 1L)))
                    : base;
            amount = amount.setScale(Money.SCALE, RoundingMode.HALF_UP);
            drafts.add(new InstallmentDraft(number,
                    dueDate(firstDueDate, frequency, number - 1), amount));
        }
        return List.copyOf(drafts);
    }

    static LocalDate dueDate(LocalDate firstDueDate, String frequency, int offset) {
        return switch (normalizeFrequency(frequency)) {
            case MONTHLY -> firstDueDate.plusMonths(offset);
            case QUARTERLY -> firstDueDate.plusMonths(3L * offset);
            case SEMI_ANNUAL -> firstDueDate.plusMonths(6L * offset);
            case ANNUAL -> firstDueDate.plusYears(offset);
            default -> throw new IllegalStateException("Unhandled billing frequency");
        };
    }

    record PlanSpec(
            int installmentCount,
            String frequency,
            LocalDate firstDueDate,
            BigDecimal totalPremium,
            List<InstallmentDraft> installments
    ) {
    }

    record InstallmentDraft(int installmentNumber, LocalDate dueDate, BigDecimal amount) {
    }

    record AdjustmentSpec(
            BigDecimal adjustmentAmount,
            BigDecimal newTotalPremium,
            int installmentNumber,
            LocalDate dueDate,
            String reason
    ) {
    }
}
