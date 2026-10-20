package com.eia.policy.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Endorsement slice (SPEC 11.2.8 {@code policy.endorse}). Every endorsement
 * issues an immutable policy version, rejects a backdated change without a
 * reason and an approval (SPEC POL-07 follow-on rule), and cannot be posted
 * before the policy is issued.
 *
 * <p>The endorsement, its version and the outbox events commit together.</p>
 */
@Service
public class EndorsementService {
    private final PolicyRepository policyRepository;
    private final MutationGuard guard;
    private final EndorsementRepository endorsementRepository;
    private final PolicyVersionService versionService;
    private final PolicyVersionRepository versionRepository;
    private final VersionIssuer versionIssuer;
    private final EndorsementFactory endorsementFactory;
    private final CommandIdempotency idempotency;
    private final OutboxRepository outbox;

    public EndorsementService(PolicyRepository policyRepository,
                              MutationGuard guard,
                              EndorsementRepository endorsementRepository,
                              PolicyVersionService versionService,
                              PolicyVersionRepository versionRepository,
                              VersionIssuer versionIssuer,
                              EndorsementFactory endorsementFactory,
                              CommandIdempotency idempotency,
                              OutboxRepository outbox) {
        this.policyRepository = policyRepository;
        this.guard = guard;
        this.endorsementRepository = endorsementRepository;
        this.versionService = versionService;
        this.versionRepository = versionRepository;
        this.versionIssuer = versionIssuer;
        this.endorsementFactory = endorsementFactory;
        this.idempotency = idempotency;
        this.outbox = outbox;
    }

    public ItemPage<PolicyEndorsement> list(UUID tenantId, UUID policyId, int page, int size) {
        requirePolicy(tenantId, policyId);
        return SubPages.page(endorsementRepository.findAll(tenantId, policyId), page, size,
                "/internal/v1/policies/" + policyId + "/endorsements", tenantId);
    }

    public PolicyEndorsement get(UUID tenantId, UUID policyId, UUID endorsementId) {
        requirePolicy(tenantId, policyId);
        return endorsementRepository.findById(tenantId, policyId, endorsementId)
                .orElseThrow(() -> PolicyErrors.notFound("ENDORSEMENT_NOT_FOUND", "endorsementId",
                        "Endorsement not found: " + endorsementId));
    }

    @Transactional
    public EndorsementResult create(UUID tenantId, UUID policyId, CreateEndorsementRequest request,
                                    String ifMatch, String rawIdempotencyKey) {
        Policy policy = guard.requirePolicy(tenantId, policyId, ifMatch);
        if (!versionService.isIssued(tenantId, policyId)) {
            throw PolicyErrors.conflict("POLICY_NOT_ISSUED", "status",
                    "A policy must be issued before it can be endorsed.");
        }
        String endorsementType = EndorsementRules.normalizeType(request.endorsementType());
        EndorsementRules.validateEffectiveDate(policy, request.effectiveDate());
        EndorsementRules.requireBackdateJustification(policy, request.effectiveDate(),
                request.backdateReason(), request.approvedBy(), request.approvalRef());
        EndorsementRules.validatePremiumDelta(policy, request.premiumDeltaAmount(),
                request.premiumDeltaCurrency());
        List<EndorsementCoverageChange> changes =
                EndorsementRules.normalizeChanges(request.coverageChanges());
        String coverageImpact = CoverageRules.normalizeImpact(request.coverageImpact());
        UUID endorsementId = UUID.randomUUID();
        String requestHash = IdempotencyKeys.requestHash(policyId, request);
        CommandResult<EndorsementOutcome> result = idempotency.run(tenantId, policyId,
                "endorsement", rawIdempotencyKey, requestHash, endorsementId,
                id -> applyEndorsement(policy, id, endorsementType, request, coverageImpact, changes),
                id -> loadOutcome(tenantId, policyId, id));
        Policy refreshed = requirePolicy(tenantId, policyId);
        return new EndorsementResult(result.value().endorsement(), result.value().version(),
                refreshed.version(), result.replayed());
    }

    private EndorsementOutcome applyEndorsement(Policy policy, UUID endorsementId,
                                                String endorsementType,
                                                CreateEndorsementRequest request,
                                                String coverageImpact,
                                                List<EndorsementCoverageChange> changes) {
        UUID tenantId = policy.tenantId();
        UUID policyId = policy.id();
        UUID versionId = UUID.randomUUID();
        PolicyVersion current = versionService.currentOrNull(tenantId, policyId);
        LocalDate versionDate = VersionTimeline.resolve(request.effectiveDate(), policy, current,
                request.backdateReason(), request.approvedBy(), request.approvalRef());
        PolicyEndorsement endorsement = endorsementFactory.create(endorsementId, policy, versionId,
                request.endorsementNumber(), endorsementType, request.effectiveDate(),
                request.description().trim(), request.premiumDeltaAmount(),
                request.premiumDeltaCurrency(), coverageImpact, changes,
                request.backdateReason(), request.approvedBy(), request.approvalRef(),
                request.formNumber(), request.formEdition(), false);
        PolicyVersion version = versionIssuer.issue(policy, versionId, "ENDORSEMENT", versionDate,
                request.description().trim(), Money.of(request.premiumDeltaAmount()),
                request.premiumDeltaCurrency(), request.approvedBy());
        touch(policy);
        Policy updated = requirePolicy(tenantId, policyId);
        Instant now = Instant.now();
        outbox.insert(OutboxEvent.fromEnvelope(
                PolicyEvents.endorsed(updated, endorsement, version, now)));
        if (version != null) {
            outbox.insert(OutboxEvent.fromEnvelope(
                    PolicyEvents.versionIssued(updated, version, now)));
        }
        return new EndorsementOutcome(endorsement, version);
    }

    private EndorsementOutcome loadOutcome(UUID tenantId, UUID policyId, UUID endorsementId) {
        PolicyEndorsement endorsement = endorsementRepository
                .findById(tenantId, policyId, endorsementId).orElse(null);
        if (endorsement == null) {
            return null;
        }
        PolicyVersion version = versionRepository
                .findById(tenantId, policyId, endorsement.policyVersionId()).orElse(null);
        return version == null ? null : new EndorsementOutcome(endorsement, version);
    }

    private void touch(Policy policy) {
        int rows = policyRepository.touch(policy.tenantId(), policy.id(), policy.version());
        if (rows == 0) {
            throw PolicyErrors.conflict("CONCURRENT_MODIFICATION", "If-Match",
                    "The policy changed concurrently; reload and retry.");
        }
    }

    private Policy requirePolicy(UUID tenantId, UUID policyId) {
        return policyRepository.findById(tenantId, policyId)
                .orElseThrow(() -> PolicyErrors.notFound("POLICY_NOT_FOUND", "policyId",
                        "Policy not found: " + policyId));
    }

    private record EndorsementOutcome(PolicyEndorsement endorsement, PolicyVersion version) {
    }
}
