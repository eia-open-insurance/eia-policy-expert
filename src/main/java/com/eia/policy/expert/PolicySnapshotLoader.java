package com.eia.policy.expert;

import org.springframework.stereotype.Component;

import java.util.UUID;

/** Loads the full child-row snapshot used by the version hash. */
@Component
public class PolicySnapshotLoader {
    private final CoverageRepository coverageRepository;
    private final PolicyPartyRepository partyRepository;
    private final EndorsementRepository endorsementRepository;

    public PolicySnapshotLoader(CoverageRepository coverageRepository,
                                PolicyPartyRepository partyRepository,
                                EndorsementRepository endorsementRepository) {
        this.coverageRepository = coverageRepository;
        this.partyRepository = partyRepository;
        this.endorsementRepository = endorsementRepository;
    }

    public PolicySnapshot load(UUID tenantId, UUID policyId) {
        return new PolicySnapshot(
                coverageRepository.findCoverages(tenantId, policyId),
                coverageRepository.findLimits(tenantId, policyId),
                partyRepository.findInsureds(tenantId, policyId),
                partyRepository.findLocations(tenantId, policyId),
                endorsementRepository.findAll(tenantId, policyId));
    }
}
