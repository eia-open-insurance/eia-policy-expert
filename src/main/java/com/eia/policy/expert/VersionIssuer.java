package com.eia.policy.expert;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Issues the next immutable policy version. Callers insert the endorsement (or
 * other child rows) first, so the canonical hash captures the post-change
 * snapshot; the previous ISSUED version is then superseded and its effective
 * window closed.
 */
@Component
public class VersionIssuer {
    private final PolicyVersionRepository versionRepository;
    private final PolicySnapshotLoader snapshotLoader;

    public VersionIssuer(PolicyVersionRepository versionRepository,
                         PolicySnapshotLoader snapshotLoader) {
        this.versionRepository = versionRepository;
        this.snapshotLoader = snapshotLoader;
    }

    public PolicyVersion issue(Policy policy,
                               UUID versionId,
                               String changeType,
                               LocalDate effectiveFrom,
                               String description,
                               BigDecimal premiumDeltaAmount,
                               String premiumDeltaCurrency,
                               UUID approvedBy) {
        UUID tenantId = policy.tenantId();
        UUID policyId = policy.id();
        int versionNumber = versionRepository.nextVersionNumber(tenantId, policyId);
        PolicyVersion current = versionRepository.findLatest(tenantId, policyId)
                .filter(version -> !"SUPERSEDED".equals(version.status())
                        && !"VOIDED".equals(version.status()))
                .orElse(null);
        Instant now = Instant.now();
        PolicyVersion candidate = new PolicyVersion(
                versionId, tenantId, policyId, versionNumber,
                policy.policyNumber() + "-" + String.format("%02d", versionNumber),
                changeType, "ISSUED", effectiveFrom, null, now, null, approvedBy, null,
                premiumDeltaAmount, premiumDeltaCurrency, description,
                current == null ? null : current.id(), "", now);
        PolicySnapshot snapshot = snapshotLoader.load(tenantId, policyId);
        PolicyVersion issued = candidate.withHash(
                PolicyVersionHasher.hash(policy, candidate, snapshot));
        VersionTimeline.validateNoOverlap(effectiveFrom, null, policy.expirationDate(),
                current == null ? null : current.id(), versionRepository.findWindows(tenantId, policyId));
        if (current != null) {
            versionRepository.supersede(tenantId, policyId, current.id(), effectiveFrom);
        }
        versionRepository.insert(issued);
        return issued;
    }
}
