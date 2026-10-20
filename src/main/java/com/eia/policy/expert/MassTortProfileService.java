package com.eia.policy.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Mass tort profile slice (SPEC 11.2.4 / 11.2.11).
 *
 * <p>A policy has exactly one profile. PUT is an upsert: a missing row is
 * inserted ({@code 201}) and an existing row is rewritten in place
 * ({@code 200}). Every accepted upsert bumps the policy aggregate version and
 * writes {@code policy.mass-tort-profile.updated} in the same transaction, so
 * a rollback leaves neither the profile change nor the event behind. The
 * version bump also gives every outbox event a distinct
 * {@code aggregateVersion}.</p>
 */
@Service
public class MassTortProfileService {
    private final PolicyRepository policyRepository;
    private final MassTortProfileRepository profileRepository;
    private final OutboxRepository outbox;
    private final Clock clock;

    @Autowired
    public MassTortProfileService(PolicyRepository policyRepository,
                                  MassTortProfileRepository profileRepository,
                                  OutboxRepository outbox) {
        this(policyRepository, profileRepository, outbox, Clock.systemUTC());
    }

    MassTortProfileService(PolicyRepository policyRepository,
                           MassTortProfileRepository profileRepository,
                           OutboxRepository outbox, Clock clock) {
        this.policyRepository = policyRepository;
        this.profileRepository = profileRepository;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Returns the policy's profile. A policy without a profile is a 404, and
     * the policy lookup enforces the tenant wall before the profile read.
     */
    public PolicyMassTortProfile get(UUID tenantId, UUID policyId) {
        requirePolicy(tenantId, policyId);
        return profileRepository.find(tenantId, policyId)
                .orElseThrow(() -> PolicyErrors.notFound("MASS_TORT_PROFILE_NOT_FOUND",
                        "policyId", "No mass tort profile exists for policy: " + policyId));
    }

    /**
     * Creates or replaces the profile. Validation runs before any write, so a
     * rejected COMPLETE body never touches the row or the outbox.
     */
    @Transactional
    public MassTortProfileResult upsert(UUID tenantId, UUID policyId,
                                        PutMassTortProfileRequest request) {
        Policy policy = requirePolicy(tenantId, policyId);
        MassTortProfileRules.ProfileSpec spec = MassTortProfileRules.validate(request);
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        var existing = profileRepository.find(tenantId, policyId);
        boolean created = existing.isEmpty();
        PolicyMassTortProfile profile = existing
                .map(current -> new PolicyMassTortProfile(current.id(), tenantId, policyId,
                        spec.tortType(), spec.triggerType(), spec.allocationMethod(),
                        spec.allocationNotes(), spec.coverageBlockStart(),
                        spec.coverageBlockEnd(), spec.status(), now))
                .orElseGet(() -> new PolicyMassTortProfile(UUID.randomUUID(), tenantId, policyId,
                        spec.tortType(), spec.triggerType(), spec.allocationMethod(),
                        spec.allocationNotes(), spec.coverageBlockStart(),
                        spec.coverageBlockEnd(), spec.status(), now));

        if (created) {
            try {
                profileRepository.insert(profile);
            } catch (DuplicateKeyException ex) {
                // The unique policy_id is the concurrency arbiter. A racing
                // insert wins; the caller can reload and replay the PUT.
                throw PolicyErrors.conflict("MASS_TORT_PROFILE_ALREADY_EXISTS", "policyId",
                        "A mass tort profile already exists for policy: " + policyId);
            }
        } else if (profileRepository.update(profile) == 0) {
            throw PolicyErrors.conflict("CONCURRENT_MODIFICATION", "policyId",
                    "The mass tort profile changed concurrently; reload and retry.");
        }

        Policy touched = touch(policy);
        outbox.insert(OutboxEvent.fromEnvelope(
                PolicyEvents.massTortProfileUpdated(touched, profile, now)));
        return new MassTortProfileResult(profile, created);
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
}
