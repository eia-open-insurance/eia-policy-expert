package com.eia.policy.expert;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Insured and location slice. At least one ACTIVE named insured must exist
 * before issue; adding an insured or location after issue is a material change
 * and therefore issues an endorsement and a policy version.
 */
@Service
public class PolicyPartyService {
    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(4);

    private final PolicyRepository policyRepository;
    private final MutationGuard guard;
    private final PolicyPartyRepository partyRepository;
    private final PolicyVersionService versionService;
    private final VersionIssuer versionIssuer;
    private final EndorsementFactory endorsementFactory;
    private final CommandIdempotency idempotency;

    public PolicyPartyService(PolicyRepository policyRepository,
                              MutationGuard guard,
                              PolicyPartyRepository partyRepository,
                              PolicyVersionService versionService,
                              VersionIssuer versionIssuer,
                              EndorsementFactory endorsementFactory,
                              CommandIdempotency idempotency) {
        this.policyRepository = policyRepository;
        this.guard = guard;
        this.partyRepository = partyRepository;
        this.versionService = versionService;
        this.versionIssuer = versionIssuer;
        this.endorsementFactory = endorsementFactory;
        this.idempotency = idempotency;
    }

    public ItemPage<PolicyInsured> listInsureds(UUID tenantId, UUID policyId, int page, int size) {
        requirePolicy(tenantId, policyId);
        return SubPages.page(partyRepository.findInsureds(tenantId, policyId), page, size,
                "/internal/v1/policies/" + policyId + "/insureds", tenantId);
    }

    public ItemPage<PolicyLocation> listLocations(UUID tenantId, UUID policyId, int page, int size) {
        requirePolicy(tenantId, policyId);
        return SubPages.page(partyRepository.findLocations(tenantId, policyId), page, size,
                "/internal/v1/policies/" + policyId + "/locations", tenantId);
    }

    @Transactional
    public InsuredResult createInsured(UUID tenantId, UUID policyId, CreateInsuredRequest request,
                                       String ifMatch, String rawIdempotencyKey) {
        Policy policy = guard.requirePolicy(tenantId, policyId, ifMatch);
        String role = PartyRules.normalizeInsuredRole(request.insuredRole());
        PartyRules.validateInsuredWindow(request.effectiveFrom(), request.effectiveTo(),
                policy.effectiveDate(), policy.expirationDate());
        UUID insuredId = UUID.randomUUID();
        String requestHash = IdempotencyKeys.requestHash(policyId, request);
        CommandResult<PolicyInsured> result = idempotency.run(tenantId, policyId, "insured",
                rawIdempotencyKey, requestHash, insuredId,
                id -> applyInsured(policy, id, request, role),
                id -> partyRepository.findInsured(tenantId, policyId, id).orElse(null));
        Policy refreshed = requirePolicy(tenantId, policyId);
        return new InsuredResult(result.value(), refreshed.version(), result.replayed());
    }

    @Transactional
    public LocationResult createLocation(UUID tenantId, UUID policyId, CreateLocationRequest request,
                                         String ifMatch, String rawIdempotencyKey) {
        Policy policy = guard.requirePolicy(tenantId, policyId, ifMatch);
        PartyRules.validateLocationWindow(request.effectiveFrom(), request.effectiveTo(),
                policy.effectiveDate(), policy.expirationDate());
        UUID locationId = UUID.randomUUID();
        String requestHash = IdempotencyKeys.requestHash(policyId, request);
        CommandResult<PolicyLocation> result = idempotency.run(tenantId, policyId, "location",
                rawIdempotencyKey, requestHash, locationId,
                id -> applyLocation(policy, id, request),
                id -> partyRepository.findLocation(tenantId, policyId, id).orElse(null));
        Policy refreshed = requirePolicy(tenantId, policyId);
        return new LocationResult(result.value(), refreshed.version(), result.replayed());
    }

    private PolicyInsured applyInsured(Policy policy, UUID insuredId, CreateInsuredRequest request,
                                       String role) {
        UUID tenantId = policy.tenantId();
        UUID policyId = policy.id();
        if (partyRepository.insuredExists(tenantId, policyId, request.partyId(), role)) {
            throw PolicyErrors.conflict("DUPLICATE_INSURED", "partyId",
                    "The party already holds the role " + role + " on this policy.");
        }
        PolicyInsured insured = new PolicyInsured(insuredId, tenantId, policyId, request.partyId(),
                role, request.displayName().trim(),
                request.certificateNumber(), request.scope(), request.effectiveFrom(),
                request.effectiveTo(), "ACTIVE", Instant.now());
        partyRepository.insertInsured(insured);
        issuePartyVersion(policy, "PARTY", request.effectiveFrom(),
                "Insured " + (request.displayName() == null ? role : request.displayName().trim())
                        + " added as " + role,
                new EndorsementCoverageChange(null, "ADD", role));
        touch(policy);
        return insured;
    }

    private PolicyLocation applyLocation(Policy policy, UUID locationId,
                                         CreateLocationRequest request) {
        UUID tenantId = policy.tenantId();
        UUID policyId = policy.id();
        String code = request.locationCode().trim().toUpperCase(java.util.Locale.ROOT);
        if (partyRepository.locationCodeExists(tenantId, policyId, code)) {
            throw PolicyErrors.conflict("DUPLICATE_LOCATION_CODE", "locationCode",
                    "A location with code " + code + " already exists on this policy.");
        }
        PolicyLocation location = new PolicyLocation(locationId, tenantId, policyId, code,
                request.addressLine1().trim(),
                request.addressLine2() == null ? null : request.addressLine2().trim(),
                request.city().trim(), request.stateRegion().trim().toUpperCase(java.util.Locale.ROOT),
                request.postalCode(), request.country().trim().toUpperCase(java.util.Locale.ROOT),
                request.effectiveFrom(), request.effectiveTo(), "ACTIVE", Instant.now());
        partyRepository.insertLocation(location);
        issuePartyVersion(policy, "TERRITORY", request.effectiveFrom(),
                "Location " + code + " added", null);
        touch(policy);
        return location;
    }

    private void issuePartyVersion(Policy policy, String endorsementType, LocalDate effectiveDate,
                                   String description, EndorsementCoverageChange change) {
        if (!versionService.isIssued(policy.tenantId(), policy.id())) {
            return;
        }
        UUID versionId = UUID.randomUUID();
        PolicyVersion current = versionService.currentOrNull(policy.tenantId(), policy.id());
        LocalDate versionDate = VersionTimeline.resolve(effectiveDate, policy, current,
                null, null, null);
        endorsementFactory.create(UUID.randomUUID(), policy, versionId, null, endorsementType,
                effectiveDate, description, ZERO_MONEY, policy.premiumCurrency(), "NONE",
                change == null ? List.of() : List.of(change), null, null, null, null, null, false);
        versionIssuer.issue(policy, versionId, "ENDORSEMENT", versionDate, description,
                ZERO_MONEY, policy.premiumCurrency(), null);
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
}
