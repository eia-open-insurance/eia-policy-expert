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
 * Cancellation and reinstatement slice.
 *
 * <p>Cancellation records the notice, reason, effective date, and the pro-rata
 * return premium computed from the policy term (SPEC POL-08). Reinstatement
 * restores coverage only after an approval reference and documented premium
 * evidence, issues a REINSTATEMENT version, and closes the active cancellation
 * (SPEC POL-09). Both commands write their outbox events in the same
 * transaction as the state change.</p>
 */
@Service
public class CancellationService {
    private final PolicyRepository policyRepository;
    private final MutationGuard guard;
    private final CancellationRepository cancellationRepository;
    private final PolicyVersionService versionService;
    private final VersionIssuer versionIssuer;
    private final EndorsementFactory endorsementFactory;
    private final CommandIdempotency idempotency;
    private final OutboxRepository outbox;

    public CancellationService(PolicyRepository policyRepository,
                               MutationGuard guard,
                               CancellationRepository cancellationRepository,
                               PolicyVersionService versionService,
                               VersionIssuer versionIssuer,
                               EndorsementFactory endorsementFactory,
                               CommandIdempotency idempotency,
                               OutboxRepository outbox) {
        this.policyRepository = policyRepository;
        this.guard = guard;
        this.cancellationRepository = cancellationRepository;
        this.versionService = versionService;
        this.versionIssuer = versionIssuer;
        this.endorsementFactory = endorsementFactory;
        this.idempotency = idempotency;
        this.outbox = outbox;
    }

    public ItemPage<PolicyCancellation> listCancellations(UUID tenantId, UUID policyId,
                                                          int page, int size) {
        requirePolicy(tenantId, policyId);
        return SubPages.page(cancellationRepository.findCancellations(tenantId, policyId),
                page, size, "/internal/v1/policies/" + policyId + "/cancellations", tenantId);
    }

    public ItemPage<PolicyReinstatement> listReinstatements(UUID tenantId, UUID policyId,
                                                            int page, int size) {
        requirePolicy(tenantId, policyId);
        return SubPages.page(cancellationRepository.findReinstatements(tenantId, policyId),
                page, size, "/internal/v1/policies/" + policyId + "/reinstatements", tenantId);
    }

    @Transactional
    public CancellationResult cancel(UUID tenantId, UUID policyId, CancelPolicyRequest request,
                                     String ifMatch, String rawIdempotencyKey) {
        Policy policy = guard.requirePolicy(tenantId, policyId, ifMatch);
        String type = CancellationRules.normalizeType(request.cancellationType());
        CancellationRules.requireCancellable(policy);
        CancellationRules.validateEffectiveDate(request.effectiveDate(), policy);
        CancellationRules.validateNotice(type, request.noticeDays());
        CancellationMath.Result amounts = CancellationMath.calculate(type, policy.premiumAmount(),
                policy.effectiveDate(), policy.expirationDate(), request.effectiveDate());
        UUID cancellationId = UUID.randomUUID();
        String requestHash = IdempotencyKeys.requestHash(policyId, request);
        CommandResult<PolicyCancellation> result = idempotency.run(tenantId, policyId,
                "cancellation", rawIdempotencyKey, requestHash, cancellationId,
                id -> applyCancellation(policy, id, type, request, amounts),
                id -> cancellationRepository.findCancellation(tenantId, policyId, id).orElse(null));
        Policy refreshed = requirePolicy(tenantId, policyId);
        return new CancellationResult(result.value(), refreshed, result.replayed());
    }

    @Transactional
    public ReinstatementResult reinstate(UUID tenantId, UUID policyId,
                                         ReinstatePolicyRequest request, String ifMatch,
                                         String rawIdempotencyKey) {
        Policy policy = guard.requirePolicy(tenantId, policyId, ifMatch);
        CancellationRules.requireReinstatable(policy);
        PolicyCancellation cancellation = cancellationRepository
                .findActiveCancellation(tenantId, policyId)
                .orElseThrow(() -> PolicyErrors.conflict("POLICY_NOT_CANCELLED", "status",
                        "The policy has no ACTIVE cancellation record to reinstate."));
        CancellationRules.requireApproval(request.approvedBy(), request.approvalRef());
        CancellationRules.requirePremiumEvidence(request.premiumEvidenceRef(),
                request.premiumEvidenceAmount());
        CancellationRules.requireSameCurrency(request.premiumEvidenceCurrency(),
                policy.premiumCurrency());
        CancellationRules.validateReinstatementDate(request.effectiveDate(), policy, cancellation);
        UUID reinstatementId = UUID.randomUUID();
        String requestHash = IdempotencyKeys.requestHash(policyId, request);
        CommandResult<PolicyReinstatement> result = idempotency.run(tenantId, policyId,
                "reinstatement", rawIdempotencyKey, requestHash, reinstatementId,
                id -> applyReinstatement(policy, id, request, cancellation),
                id -> cancellationRepository.findReinstatement(tenantId, policyId, id).orElse(null));
        Policy refreshed = requirePolicy(tenantId, policyId);
        return new ReinstatementResult(result.value(), refreshed, result.replayed());
    }

    private PolicyCancellation applyCancellation(Policy policy, UUID cancellationId, String type,
                                                 CancelPolicyRequest request,
                                                 CancellationMath.Result amounts) {
        UUID tenantId = policy.tenantId();
        UUID policyId = policy.id();
        UUID versionId = UUID.randomUUID();
        PolicyVersion current = versionService.currentOrNull(tenantId, policyId);
        LocalDate versionDate = VersionTimeline.resolve(request.effectiveDate(), policy, current,
                request.reason(), request.approvedBy(), request.approvalRef());
        BigDecimal premiumDelta = amounts.returnPremium().negate();
        String description = "Policy cancelled (" + type + "): " + request.reason().trim();
        endorsementFactory.create(UUID.randomUUID(), policy, versionId, null, "CANCELLATION",
                request.effectiveDate(), description, premiumDelta, policy.premiumCurrency(),
                "NONE", List.of(), request.reason(), request.approvedBy(), request.approvalRef(),
                null, null, false);
        PolicyVersion version = versionIssuer.issue(policy, versionId, "CANCELLATION", versionDate,
                description, premiumDelta, policy.premiumCurrency(), request.approvedBy());
        Instant now = Instant.now();
        PolicyCancellation cancellation = new PolicyCancellation(cancellationId, tenantId, policyId,
                versionId, type, request.reason().trim(), request.effectiveDate(),
                request.noticeDays() == null ? 0 : request.noticeDays(), now,
                amounts.termDays(), amounts.remainingDays(), amounts.proRataPct(),
                amounts.earnedPremium(), amounts.returnPremium(), policy.premiumCurrency(),
                request.approvalRef(), request.approvedBy(), "ACTIVE", now,
                cancellationRepository.nextCancellationNumber());
        cancellationRepository.insertCancellation(cancellation);
        Policy updated = policyRepository.updateStatus(tenantId, policyId, "CANCELLED",
                request.reason().trim(), policy.version());
        outbox.insert(OutboxEvent.fromEnvelope(
                PolicyEvents.cancelled(updated, cancellation, version, now)));
        if (version != null) {
            outbox.insert(OutboxEvent.fromEnvelope(
                    PolicyEvents.versionIssued(updated, version, now)));
        }
        return cancellation;
    }

    private PolicyReinstatement applyReinstatement(Policy policy, UUID reinstatementId,
                                                   ReinstatePolicyRequest request,
                                                   PolicyCancellation cancellation) {
        UUID tenantId = policy.tenantId();
        UUID policyId = policy.id();
        UUID versionId = UUID.randomUUID();
        PolicyVersion current = versionService.currentOrNull(tenantId, policyId);
        LocalDate versionDate = VersionTimeline.resolve(request.effectiveDate(), policy, current,
                request.reason(), request.approvedBy(), request.approvalRef());
        String description = "Policy reinstated: " + request.reason().trim();
        endorsementFactory.create(UUID.randomUUID(), policy, versionId, null, "REINSTATEMENT",
                request.effectiveDate(), description, Money.of(request.premiumEvidenceAmount()),
                policy.premiumCurrency(), "NONE", List.of(), request.reason(),
                request.approvedBy(), request.approvalRef(), null, null, false);
        PolicyVersion version = versionIssuer.issue(policy, versionId, "REINSTATEMENT", versionDate,
                description, Money.of(request.premiumEvidenceAmount()), policy.premiumCurrency(),
                request.approvedBy());
        Instant now = Instant.now();
        PolicyReinstatement reinstatement = new PolicyReinstatement(reinstatementId, tenantId,
                policyId, cancellation.id(), versionId, request.effectiveDate(),
                request.reason().trim(), request.approvalRef(), request.approvedBy(),
                request.premiumEvidenceRef().trim(), Money.of(request.premiumEvidenceAmount()),
                request.premiumEvidenceCurrency(), "APPROVED", now);
        cancellationRepository.insertReinstatement(reinstatement);
        int rows = cancellationRepository.markReinstated(tenantId, policyId, cancellation.id());
        if (rows == 0) {
            throw PolicyErrors.conflict("CONCURRENT_MODIFICATION", "cancellationId",
                    "The cancellation changed concurrently; reload and retry.");
        }
        Policy updated = policyRepository.updateStatus(tenantId, policyId, "REINSTATED",
                request.reason().trim(), policy.version());
        outbox.insert(OutboxEvent.fromEnvelope(
                PolicyEvents.reinstated(updated, reinstatement, version, now)));
        if (version != null) {
            outbox.insert(OutboxEvent.fromEnvelope(
                    PolicyEvents.versionIssued(updated, version, now)));
        }
        return reinstatement;
    }

    private Policy requirePolicy(UUID tenantId, UUID policyId) {
        return policyRepository.findById(tenantId, policyId)
                .orElseThrow(() -> PolicyErrors.notFound("POLICY_NOT_FOUND", "policyId",
                        "Policy not found: " + policyId));
    }
}
