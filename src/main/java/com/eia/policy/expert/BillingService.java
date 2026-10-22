package com.eia.policy.expert;

import com.eia.platform.libs.events.EventEnvelope;
import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Billing plan and installment slice (SPEC 11.2.13).
 *
 * <p>Creating a plan splits the policy premium across 1..24 installments and
 * writes the plan, the schedule, and {@code policy.billing-plan.created} in one
 * transaction. Settling an installment writes the row change and
 * {@code policy.installment.paid} / {@code policy.installment.waived} in one
 * transaction. Every mutation also bumps the policy aggregate version, both to
 * guard concurrent billing commands and to give every outbox event a distinct
 * {@code aggregateVersion} (the outbox unique key).</p>
 */
@Service
public class BillingService {
    private final PolicyRepository policyRepository;
    private final BillingRepository billingRepository;
    private final OutboxRepository outbox;
    private final Clock clock;

    @Autowired
    public BillingService(PolicyRepository policyRepository, BillingRepository billingRepository,
                          OutboxRepository outbox) {
        this(policyRepository, billingRepository, outbox, Clock.systemUTC());
    }

    BillingService(PolicyRepository policyRepository, BillingRepository billingRepository,
                   OutboxRepository outbox, Clock clock) {
        this.policyRepository = policyRepository;
        this.billingRepository = billingRepository;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Returns the ACTIVE plan, its schedule, and the derived money summary, or
     * the most recently closed plan when no plan is active. A policy without any
     * billing plan is a 404. Each installment carries its applied
     * {@code creditedAmount} and the resulting {@code outstandingAmount}; the
     * summary totals paid, waived, credited, outstanding, and overdue money.
     */
    public BillingPlanView get(UUID tenantId, UUID policyId) {
        requirePolicy(tenantId, policyId);
        PolicyBillingPlan plan = billingRepository.findCurrentPlan(tenantId, policyId)
                .orElseThrow(() -> PolicyErrors.notFound("BILLING_PLAN_NOT_FOUND", "policyId",
                        "No billing plan exists for policy: " + policyId));
        return new BillingPlanView(plan,
                billingRepository.findInstallments(tenantId, plan.id()));
    }

    /**
     * Tenant-wide premium receivables worklist. Only collectible installments
     * are returned (PAID, WAIVED, and fully CREDITED rows are excluded);
     * {@code status} optionally narrows the effective status (DUE or OVERDUE)
     * and {@code dueBefore} is an inclusive due-date cutoff. Overdue is derived
     * from the due date at read time, so a DUE row past its due date is reported
     * as OVERDUE with a positive {@code daysPastDue} without the read mutating
     * the stored row. The row amount stays gross; its {@code outstandingAmount}
     * is the uncredited remainder and both summary totals sum that remainder.
     */
    public ReceivablesView listReceivables(UUID tenantId, String rawStatus,
                                           LocalDate dueBefore, int limit) {
        String status = BillingRules.normalizeReceivableStatus(rawStatus);
        int effectiveLimit = BillingRules.normalizeReceivableLimit(limit);
        LocalDate today = LocalDate.now(clock);
        List<InstallmentReceivable> items = billingRepository
                .findReceivables(tenantId, status, today, dueBefore, effectiveLimit).stream()
                .map(row -> toReceivable(row, today))
                .toList();
        // Totals are collectible remainders: the gross amount less the credits
        // already applied to each open installment.
        BigDecimal totalAmount = items.stream()
                .map(InstallmentReceivable::outstandingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal overdueAmount = items.stream()
                .filter(item -> BillingRules.OVERDUE.equals(item.status()))
                .map(InstallmentReceivable::outstandingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ReceivablesView(items, Money.of(totalAmount), Money.of(overdueAmount),
                items.size());
    }

    private static InstallmentReceivable toReceivable(ReceivableRow row, LocalDate today) {
        String status = BillingRules.effectiveStatus(row.storedStatus(), row.dueDate(), today);
        BigDecimal outstanding = BillingRules.outstandingAmount(
                row.amount(), row.creditedAmount(), status);
        return new InstallmentReceivable(row.installmentId(), row.planId(), row.planNumber(),
                row.policyId(), row.policyNumber(), row.insuredPartyId(), row.installmentNumber(),
                row.dueDate(), row.amount(), row.creditedAmount(), outstanding, row.currency(),
                status, BillingRules.daysPastDue(status, row.dueDate(), today), row.paidAt(),
                row.paymentId(), row.lastDunningLevel(), row.lastDunningAt());
    }

    @Transactional
    public BillingPlanView create(UUID tenantId, UUID policyId, CreateBillingPlanRequest request) {
        Policy policy = requirePolicy(tenantId, policyId);
        BillingRules.PlanSpec spec = BillingRules.validateCreate(request, policy);
        if (billingRepository.findActivePlan(tenantId, policyId).isPresent()) {
            throw activePlanConflict(policyId);
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID planId = UUID.randomUUID();
        PolicyBillingPlan plan = new PolicyBillingPlan(planId, tenantId, policyId,
                billingRepository.nextPlanNumber(tenantId, policyId), spec.totalPremium(),
                policy.premiumCurrency(), spec.installmentCount(), spec.frequency(),
                spec.firstDueDate(), "ACTIVE", now);
        List<PolicyInstallment> installments = new ArrayList<>(spec.installments().size());
        for (BillingRules.InstallmentDraft draft : spec.installments()) {
            installments.add(new PolicyInstallment(UUID.randomUUID(), tenantId, planId,
                    draft.installmentNumber(), draft.dueDate(), draft.amount(), BillingRules.DUE,
                    null, null, now));
        }

        try {
            billingRepository.insertPlan(plan);
            for (PolicyInstallment installment : installments) {
                billingRepository.insertInstallment(installment);
            }
        } catch (DuplicateKeyException ex) {
            // The PostgreSQL partial unique index is the concurrency arbiter for
            // "one ACTIVE plan per policy"; H2 relies on the check above.
            throw activePlanConflict(policyId);
        }

        Policy touched = touch(policy);
        outbox.insert(OutboxEvent.fromEnvelope(
                PolicyEvents.billingPlanCreated(touched, plan, installments, now)));
        return new BillingPlanView(plan, List.copyOf(installments));
    }

    /**
     * Appends one DUE installment for the positive premium remainder between the
     * policy written premium and the ACTIVE plan's net premium, then moves the
     * plan total premium up by the same amount and grows the installment count by
     * one. The net premium is the plan total less the credits already applied to
     * its installments, floored at zero, so credits that reduce the collectible
     * schedule let a following adjustment bill only the remaining gap. A plan
     * that is absent is a 404 and a plan that is not ACTIVE is a 409; a zero or
     * negative remainder is a 400 {@code NO_PREMIUM_ADJUSTMENT} because a
     * decrease is a credit-note concern, not an installment. The 1..24 schedule
     * window is enforced before the append.
     *
     * <p>The plan update is conditional on the total, count, and credited total
     * the command read, so a concurrent adjustment, credit, or plan closure
     * makes it return zero and the whole transaction (including the appended
     * installment) rolls back. The policy version bump and
     * {@code policy.billing-plan.adjusted} commit with the plan and installment
     * rows.</p>
     */
    @Transactional
    public BillingAdjustmentView adjust(UUID tenantId, UUID policyId,
                                        BillingAdjustmentRequest request) {
        Policy policy = requirePolicy(tenantId, policyId);
        PolicyBillingPlan plan = billingRepository.findCurrentPlan(tenantId, policyId)
                .orElseThrow(() -> PolicyErrors.notFound("BILLING_PLAN_NOT_FOUND", "policyId",
                        "No billing plan exists for policy: " + policyId));
        BigDecimal creditedAmount = billingRepository.sumCreditedAmount(tenantId, plan.id());
        BillingRules.AdjustmentSpec spec = BillingRules.validateAdjustment(request, policy, plan,
                creditedAmount);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        PolicyInstallment installment = new PolicyInstallment(UUID.randomUUID(), tenantId,
                plan.id(), spec.installmentNumber(), spec.dueDate(), spec.adjustmentAmount(),
                BillingRules.DUE, null, null, now);

        int rows = billingRepository.updatePlanAfterAdjustment(tenantId, policyId, plan.id(),
                spec.newTotalPremium(), spec.installmentNumber(), plan.totalPremium(),
                plan.installmentCount(), creditedAmount);
        if (rows == 0) {
            throw PolicyErrors.conflict("CONCURRENT_MODIFICATION", "policyId",
                    "The billing plan changed concurrently; reload and retry.");
        }
        try {
            billingRepository.insertInstallment(installment);
        } catch (DuplicateKeyException ex) {
            // (plan_id, installment_number) is the concurrency arbiter; a racing
            // adjustment already took this installment number.
            throw PolicyErrors.conflict("CONCURRENT_MODIFICATION", "policyId",
                    "The billing plan changed concurrently; reload and retry.");
        }

        Policy touched = touch(policy);
        PolicyBillingPlan updatedPlan = new PolicyBillingPlan(plan.id(), plan.tenantId(),
                plan.policyId(), plan.planNumber(), spec.newTotalPremium(), plan.currency(),
                spec.installmentNumber(), plan.frequency(), plan.firstDueDate(), plan.status(),
                plan.createdAt());
        outbox.insert(OutboxEvent.fromEnvelope(PolicyEvents.billingPlanAdjusted(touched,
                updatedPlan, installment, spec.adjustmentAmount(), spec.reason(), now)));
        return new BillingAdjustmentView(updatedPlan, installment, spec.adjustmentAmount(),
                spec.newTotalPremium());
    }

    /**
     * Settles one installment as PAID or WAIVED. Repeating the same terminal
     * status is an idempotent no-op (no new event, no version bump); switching a
     * settled installment to the other terminal status, or settling a CREDITED
     * row, is a 409. A DUE or OVERDUE row can be settled exactly once because
     * the UPDATE is conditional on its current status.
     */
    @Transactional
    public PolicyInstallment updateInstallment(UUID tenantId, UUID policyId,
                                               UUID installmentId,
                                               UpdateInstallmentRequest request) {
        Policy policy = requirePolicy(tenantId, policyId);
        PolicyInstallment current = billingRepository
                .findInstallment(tenantId, policyId, installmentId)
                .orElseThrow(() -> installmentNotFound(installmentId));
        String target = BillingRules.normalizeTargetStatus(request.status());

        if (BillingRules.isSettled(current.status())) {
            if (current.status().equals(target)) {
                return current;
            }
            throw settledConflict(current);
        }
        if (!BillingRules.isSettleable(current.status())) {
            throw PolicyErrors.conflict("INSTALLMENT_NOT_SETTLEABLE", "status",
                    "Installment " + installmentId + " is in status " + current.status()
                            + " and cannot be settled.");
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant paidAt = BillingRules.PAID.equals(target) ? now : null;
        UUID paymentId = BillingRules.PAID.equals(target) ? request.paymentId() : null;
        int rows = billingRepository.updateStatus(tenantId, current.planId(), installmentId,
                target, paidAt, paymentId, current.status());
        if (rows == 0) {
            PolicyInstallment raced = billingRepository
                    .findInstallment(tenantId, policyId, installmentId)
                    .orElseThrow(() -> installmentNotFound(installmentId));
            if (raced.status().equals(target)) {
                return raced;
            }
            throw settledConflict(raced);
        }

        PolicyBillingPlan plan = billingRepository.findPlan(tenantId, policyId, current.planId())
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "INTERNAL_ERROR", "The billing plan for installment " + installmentId
                        + " could not be reloaded."));
        Policy touched = touch(policy);
        PolicyInstallment updated = billingRepository
                .findInstallment(tenantId, policyId, installmentId)
                .orElseThrow(() -> installmentNotFound(installmentId));
        EventEnvelope event = BillingRules.PAID.equals(target)
                ? PolicyEvents.installmentPaid(touched, plan, updated, now)
                : PolicyEvents.installmentWaived(touched, plan, updated, now);
        outbox.insert(OutboxEvent.fromEnvelope(event));
        return updated;
    }

    private Policy requirePolicy(UUID tenantId, UUID policyId) {
        return policyRepository.findById(tenantId, policyId)
                .orElseThrow(() -> PolicyErrors.notFound("POLICY_NOT_FOUND", "policyId",
                        "Policy not found: " + policyId));
    }

    private Policy touch(Policy policy) {
        int rows = policyRepository.touch(policy.tenantId(), policy.id(), policy.version());
        if (rows == 0) {
            throw PolicyErrors.conflict("CONCURRENT_MODIFICATION", "policyId",
                    "The policy changed concurrently; reload and retry.");
        }
        return policyRepository.findById(policy.tenantId(), policy.id())
                .orElseThrow(() -> PolicyErrors.notFound("POLICY_NOT_FOUND", "policyId",
                        "Policy not found: " + policy.id()));
    }

    private static ApiException activePlanConflict(UUID policyId) {
        return PolicyErrors.conflict("BILLING_PLAN_ALREADY_EXISTS", "policyId",
                "An ACTIVE billing plan already exists for policy: " + policyId);
    }

    private static ApiException installmentNotFound(UUID installmentId) {
        return PolicyErrors.notFound("INSTALLMENT_NOT_FOUND", "installmentId",
                "Installment not found: " + installmentId);
    }

    private static ApiException settledConflict(PolicyInstallment installment) {
        return PolicyErrors.conflict("INSTALLMENT_ALREADY_SETTLED", "status",
                "Installment " + installment.id() + " is already " + installment.status() + ".",
                Map.of("installmentId", installment.id().toString(),
                        "currentStatus", installment.status()));
    }
}
