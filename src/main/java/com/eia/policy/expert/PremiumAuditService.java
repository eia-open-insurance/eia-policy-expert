package com.eia.policy.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Premium audit slice (SPEC 11.2.10 {@code policy.premium-audit}).
 *
 * <p>An audit is an endorsement-like policy command: it records a
 * {@code PREMIUM_AUDIT} endorsement and immutable policy version, replaces the
 * policy premium with the audited amount, and bumps the aggregate version. The
 * effective date is resolved by {@link VersionTimeline}, so an audit before
 * inception or a regression of the version timeline needs the same backdate
 * reason and approval as any other versioned change.</p>
 *
 * <p>The endorsement, version, policy update, and outbox events commit in one
 * transaction. The version hash therefore captures the audited premium and the
 * new endorsement.</p>
 */
@Service
public class PremiumAuditService {
    private final PolicyRepository policyRepository;
    private final MutationGuard guard;
    private final EndorsementFactory endorsementFactory;
    private final PolicyVersionService versionService;
    private final VersionIssuer versionIssuer;
    private final OutboxRepository outbox;

    public PremiumAuditService(PolicyRepository policyRepository,
                               MutationGuard guard,
                               EndorsementFactory endorsementFactory,
                               PolicyVersionService versionService,
                               VersionIssuer versionIssuer,
                               OutboxRepository outbox) {
        this.policyRepository = policyRepository;
        this.guard = guard;
        this.endorsementFactory = endorsementFactory;
        this.versionService = versionService;
        this.versionIssuer = versionIssuer;
        this.outbox = outbox;
    }

    /**
     * Applies the audit and returns the refreshed policy with the audited
     * premium and the bumped aggregate version.
     */
    @Transactional
    public Policy audit(UUID tenantId, UUID policyId, PremiumAuditRequest request, String ifMatch) {
        Policy policy = guard.requirePolicy(tenantId, policyId, ifMatch);
        PremiumAuditRules.requireAuditable(policy);
        PremiumAuditRules.validate(request);

        BigDecimal previousPremium = Money.of(policy.premiumAmount());
        BigDecimal auditedPremium = Money.of(request.auditedPremium());
        BigDecimal delta = PremiumAuditRules.delta(policy, auditedPremium);
        PremiumAuditRules.requireChange(delta);

        PolicyVersion current = versionService.currentOrNull(tenantId, policyId);
        LocalDate versionDate = VersionTimeline.resolve(request.effectiveDate(), policy, current,
                request.reason(), request.approvedBy(), request.approvalRef());
        String reason = PremiumAuditRules.normalizeReason(request.reason());
        String description = PremiumAuditRules.description(policy, auditedPremium, delta);
        boolean backdated = request.effectiveDate().isBefore(policy.effectiveDate())
                || (current != null && request.effectiveDate().isBefore(current.effectiveFrom()));
        UUID versionId = UUID.randomUUID();

        // The endorsement must exist before the version is hashed: the canonical
        // snapshot includes every endorsement, so the audit appears in the hash.
        endorsementFactory.create(UUID.randomUUID(), policy, versionId, null,
                PremiumAuditRules.ENDORSEMENT_TYPE, request.effectiveDate(), description, delta,
                policy.premiumCurrency(), "NONE", List.of(),
                backdated ? reason : null, request.approvedBy(), request.approvalRef(),
                null, null, false);

        int rows = policyRepository.updatePremium(tenantId, policyId, auditedPremium,
                policy.version());
        if (rows == 0) {
            throw PolicyErrors.conflict("CONCURRENT_MODIFICATION", "If-Match",
                    "The policy changed concurrently; reload and retry.");
        }
        Policy updated = requirePolicy(tenantId, policyId);
        PolicyVersion version = versionIssuer.issue(updated, versionId,
                PremiumAuditRules.CHANGE_TYPE, versionDate, description, delta,
                policy.premiumCurrency(), request.approvedBy());

        Instant now = Instant.now();
        outbox.insert(OutboxEvent.fromEnvelope(PolicyEvents.premiumAudited(updated, previousPremium,
                auditedPremium, delta, request.effectiveDate(), reason, now)));
        if (version != null) {
            outbox.insert(OutboxEvent.fromEnvelope(
                    PolicyEvents.versionIssued(updated, version, now)));
        }
        return updated;
    }

    private Policy requirePolicy(UUID tenantId, UUID policyId) {
        return policyRepository.findById(tenantId, policyId)
                .orElseThrow(() -> PolicyErrors.notFound("POLICY_NOT_FOUND", "policyId",
                        "Policy not found: " + policyId));
    }
}
