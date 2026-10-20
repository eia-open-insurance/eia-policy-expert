package com.eia.policy.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Billing dunning slice (SPEC 11.2.13).
 *
 * <p>A dunning action is recorded only while the installment is still
 * collectible: its effective status must be DUE or OVERDUE, so a stored DUE row
 * past its due date is dunnable while PAID and WAIVED rows are not. One action
 * per escalation level is allowed per installment; the unique
 * {@code (tenant_id, installment_id, level)} constraint is the concurrency
 * arbiter and a duplicate is a 409. Recording an action bumps the policy
 * aggregate version and writes {@code policy.dunning.sent} in the same
 * transaction, so a rollback leaves neither the action nor the event behind.</p>
 */
@Service
public class BillingDunningService {
    private final PolicyRepository policyRepository;
    private final BillingDunningRepository dunningRepository;
    private final OutboxRepository outbox;
    private final Clock clock;

    @Autowired
    public BillingDunningService(PolicyRepository policyRepository,
                                 BillingDunningRepository dunningRepository,
                                 OutboxRepository outbox) {
        this(policyRepository, dunningRepository, outbox, Clock.systemUTC());
    }

    BillingDunningService(PolicyRepository policyRepository,
                          BillingDunningRepository dunningRepository,
                          OutboxRepository outbox, Clock clock) {
        this.policyRepository = policyRepository;
        this.dunningRepository = dunningRepository;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Dunning history for one installment, newest first. The target lookup
     * enforces the tenant wall before the history read, and an installment
     * without actions returns an empty list.
     */
    public List<BillingDunningAction> list(UUID tenantId, UUID installmentId) {
        requireTarget(tenantId, installmentId);
        return dunningRepository.findForInstallment(tenantId, installmentId);
    }

    /**
     * Records a dunning action and writes {@code policy.dunning.sent}. The
     * validation order is tenant wall, then installment state, then duplicate
     * level: a cross-tenant installment is a 404, a settled one is a 409
     * {@code INSTALLMENT_NOT_DUNNABLE}, and a repeated level is a 409
     * {@code DUNNING_ALREADY_SENT}.
     */
    @Transactional
    public BillingDunningAction create(UUID tenantId, UUID installmentId,
                                       CreateDunningActionRequest request) {
        BillingDunningTarget target = requireTarget(tenantId, installmentId);
        String level = BillingDunningRules.normalizeLevel(request.level());
        String channel = BillingDunningRules.normalizeChannel(request.channel());
        String notes = BillingDunningRules.normalizeNotes(request.notes());

        LocalDate today = LocalDate.now(clock);
        String effectiveStatus = BillingRules.effectiveStatus(
                target.storedStatus(), target.dueDate(), today);
        if (!BillingDunningRules.isDunnable(effectiveStatus)) {
            throw notDunnable(target.installmentId(), effectiveStatus);
        }
        if (dunningRepository.findByLevel(tenantId, installmentId, level).isPresent()) {
            throw alreadySent(installmentId, level);
        }

        Policy policy = policyRepository.findById(tenantId, target.policyId())
                .orElseThrow(() -> PolicyErrors.notFound("POLICY_NOT_FOUND", "policyId",
                        "Policy not found: " + target.policyId()));
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        BillingDunningAction action = new BillingDunningAction(UUID.randomUUID(), tenantId,
                installmentId, target.planId(), target.policyId(), level, channel, notes,
                BillingDunningRules.SENT, now, now);
        try {
            dunningRepository.insert(action);
        } catch (DuplicateKeyException ex) {
            // The unique (tenant, installment, level) constraint decides a race.
            throw alreadySent(installmentId, level);
        }

        Policy touched = touch(policy);
        outbox.insert(OutboxEvent.fromEnvelope(
                PolicyEvents.dunningSent(touched, action, now)));
        return action;
    }

    private BillingDunningTarget requireTarget(UUID tenantId, UUID installmentId) {
        return dunningRepository.findTarget(tenantId, installmentId)
                .orElseThrow(() -> PolicyErrors.notFound("INSTALLMENT_NOT_FOUND",
                        "installmentId", "Installment not found: " + installmentId));
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

    private static ApiException notDunnable(UUID installmentId, String effectiveStatus) {
        return PolicyErrors.conflict("INSTALLMENT_NOT_DUNNABLE", "status",
                "Installment " + installmentId + " is " + effectiveStatus
                        + " and cannot be dunned.",
                Map.of("installmentId", installmentId.toString(),
                        "currentStatus", effectiveStatus));
    }

    private static ApiException alreadySent(UUID installmentId, String level) {
        return PolicyErrors.conflict("DUNNING_ALREADY_SENT", "level",
                "A " + level + " dunning action already exists for installment "
                        + installmentId + ".",
                Map.of("installmentId", installmentId.toString(), "level", level));
    }
}
