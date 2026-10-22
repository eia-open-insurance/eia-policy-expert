package com.eia.policy.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Policy rating slice (SPEC 11.2.14 rating reproducibility / RATE-02).
 *
 * <p>Every accepted command persists one immutable worksheet. A calculation
 * only ({@code apply=false}) leaves the policy untouched. When {@code apply} is
 * true, the same transaction replaces the policy premium, bumps the aggregate
 * version, and writes {@code policy.policy.rated} with the worksheet identity
 * and canonical hash. The conditional premium update keeps the aggregate
 * version as the optimistic-concurrency arbiter, so a racing change rolls the
 * worksheet and event back.</p>
 */
@Service
public class PolicyRatingService {
    /** No user identity is carried on the internal command; attribution is the system actor. */
    static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

    private final PolicyRepository policyRepository;
    private final RatingWorksheetRepository worksheetRepository;
    private final MutationGuard guard;
    private final OutboxRepository outbox;

    public PolicyRatingService(PolicyRepository policyRepository,
                               RatingWorksheetRepository worksheetRepository,
                               MutationGuard guard,
                               OutboxRepository outbox) {
        this.policyRepository = policyRepository;
        this.worksheetRepository = worksheetRepository;
        this.guard = guard;
        this.outbox = outbox;
    }

    /**
     * Calculates and persists a worksheet, applying it to the policy premium
     * when requested.
     *
     * @throws ApiException {@code 404 POLICY_NOT_FOUND}, {@code 409
     *                      POLICY_NOT_RATEABLE}, {@code 400} for invalid rating
     *                      inputs, or {@code 409 CONCURRENT_MODIFICATION}
     */
    @Transactional
    public RatingResult rate(UUID tenantId, UUID policyId, RatingRequest request, String ifMatch) {
        Policy policy = guard.requirePolicy(tenantId, policyId, ifMatch);
        RatingRules.requireRateable(policy);
        RatingRules.RatingSpec spec = RatingRules.validate(request, policy.premiumCurrency());

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        PolicyRatingWorksheet worksheet = new PolicyRatingWorksheet(
                UUID.randomUUID(), tenantId, policyId, spec.ratingMethod(),
                spec.baseRate(), spec.exposureAmount(), spec.factor(),
                spec.computedPremium(), spec.currency(), RatingRules.calcHash(spec),
                RatingRules.CALCULATED, now, SYSTEM_ACTOR);
        worksheetRepository.insert(worksheet);

        if (!Boolean.TRUE.equals(request.apply())) {
            return new RatingResult(worksheet, null);
        }

        int rows = policyRepository.updatePremium(tenantId, policyId, spec.computedPremium(),
                policy.version());
        if (rows == 0) {
            throw PolicyErrors.conflict("CONCURRENT_MODIFICATION", "If-Match",
                    "The policy changed concurrently; reload and retry.");
        }
        Policy updated = requirePolicy(tenantId, policyId);
        outbox.insert(OutboxEvent.fromEnvelope(PolicyEvents.rated(updated, worksheet, now)));
        return new RatingResult(worksheet, updated);
    }

    /**
     * Returns the newest worksheet for a policy. The policy is loaded first so
     * an unknown policy and a policy in another tenant both report
     * {@code POLICY_NOT_FOUND} on the tenant wall.
     */
    public PolicyRatingWorksheet latest(UUID tenantId, UUID policyId) {
        requirePolicy(tenantId, policyId);
        return worksheetRepository.findLatest(tenantId, policyId)
                .orElseThrow(() -> PolicyErrors.notFound("RATING_WORKSHEET_NOT_FOUND", "policyId",
                        "No rating worksheet exists for policy: " + policyId));
    }

    private Policy requirePolicy(UUID tenantId, UUID policyId) {
        return policyRepository.findById(tenantId, policyId)
                .orElseThrow(() -> PolicyErrors.notFound("POLICY_NOT_FOUND", "policyId",
                        "Policy not found: " + policyId));
    }
}
