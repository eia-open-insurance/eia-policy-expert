package com.eia.policy.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * Policy renewal slice (SPEC 11.2.4 renewal lineage, SPEC 11.2.10 renew).
 *
 * <p>Renewal creates a brand-new DRAFT policy term. It never mutates the source
 * policy: the source stays {@code ISSUED} / {@code IN_FORCE} / {@code ENDORSED} /
 * {@code REINSTATED}, and the new row records the predecessor in
 * {@code renewed_from_policy_id}.</p>
 *
 * <p>The new term inherits the source product, insured party, coverage grants,
 * limit schedules, insureds, and locations so the terms are preserved. Child
 * windows are rebound to the new policy term, and each copied limit gets a fresh
 * zeroed erosion state. The renewal row, its children, its initial DRAFT
 * version, and {@code policy.policy.renewed} are written in one transaction; a
 * rollback leaves none of them behind.</p>
 */
@Service
public class PolicyRenewalService {
    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    private static final BigDecimal ZERO_PERCENT = BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);

    private final PolicyRepository policyRepository;
    private final CoverageRepository coverageRepository;
    private final PolicyPartyRepository partyRepository;
    private final PolicyVersionService versionService;
    private final OutboxRepository outbox;

    public PolicyRenewalService(PolicyRepository policyRepository,
                                CoverageRepository coverageRepository,
                                PolicyPartyRepository partyRepository,
                                PolicyVersionService versionService,
                                OutboxRepository outbox) {
        this.policyRepository = policyRepository;
        this.coverageRepository = coverageRepository;
        this.partyRepository = partyRepository;
        this.versionService = versionService;
        this.outbox = outbox;
    }

    @Transactional
    public Policy renew(UUID tenantId, UUID policyId, RenewPolicyRequest request) {
        Policy source = policyRepository.findById(tenantId, policyId)
                .orElseThrow(() -> PolicyErrors.notFound("POLICY_NOT_FOUND", "policyId",
                        "Policy not found: " + policyId));
        PolicyRenewalRules.RenewalTerms terms = PolicyRenewalRules.validate(source, request);

        UUID renewalId = UUID.randomUUID();
        CreatePolicyRequest createRequest = new CreatePolicyRequest(
                source.tenantId(), null, source.productCode(), source.insuredPartyId(), "DRAFT",
                terms.effectiveDate(), terms.expirationDate(), terms.premiumAmount(),
                terms.premiumCurrency(), terms.limitAmount(), terms.limitCurrency());
        Policy renewal = policyRepository.create(createRequest, renewalId, source.id());
        versionService.createInitialVersion(renewal, UUID.randomUUID());

        Instant now = Instant.now();
        copyCoverages(source, renewal, terms, now);
        copyInsureds(source, renewal, terms, now);
        copyLocations(source, renewal, terms, now);

        outbox.insert(OutboxEvent.fromEnvelope(PolicyEvents.renewed(source, renewal, now)));
        return renewal;
    }

    private void copyCoverages(Policy source, Policy renewal, PolicyRenewalRules.RenewalTerms terms,
                               Instant now) {
        UUID tenantId = source.tenantId();
        for (PolicyCoverage coverage : coverageRepository.findCoverages(tenantId, source.id())) {
            UUID coverageId = UUID.randomUUID();
            PolicyCoverage copied = new PolicyCoverage(coverageId, tenantId, renewal.id(),
                    coverage.coverageCode(), coverage.coverageName(), coverage.coveragePart(),
                    coverage.occurrenceBasis(), coverage.coinsurancePct(),
                    terms.effectiveDate(), terms.expirationDate(), coverage.status(),
                    coverage.sequence(), now);
            coverageRepository.insertCoverage(copied);

            coverageRepository.findLimitByCoverage(tenantId, source.id(), coverage.id())
                    .ifPresent(limit -> copyLimit(tenantId, renewal, copied, limit, terms, now));
        }
    }

    private void copyLimit(UUID tenantId, Policy renewal, PolicyCoverage coverage,
                           PolicyLimit limit, PolicyRenewalRules.RenewalTerms terms, Instant now) {
        UUID limitId = UUID.randomUUID();
        PolicyLimit copied = new PolicyLimit(limitId, tenantId, renewal.id(), coverage.id(),
                limit.perOccurrenceAmount(), limit.aggregateAmount(), limit.aggregateBasis(),
                limit.sublimitAmount(), limit.deductibleAmount(), limit.coinsurancePct(),
                limit.currency(), limit.erosionBasis(), terms.effectiveDate(),
                terms.expirationDate(), limit.sequence(), now);
        coverageRepository.insertLimit(copied);

        BigDecimal base = copied.erosionBase();
        PolicyLimitState state = new PolicyLimitState(UUID.randomUUID(), tenantId, renewal.id(),
                limitId, now, base, ZERO_MONEY, ZERO_MONEY, ZERO_MONEY, ZERO_MONEY, base,
                ZERO_PERCENT, ZERO_MONEY, 0L, now, "CURRENT", 0);
        coverageRepository.insertState(state);
    }

    private void copyInsureds(Policy source, Policy renewal, PolicyRenewalRules.RenewalTerms terms,
                              Instant now) {
        for (PolicyInsured insured : partyRepository.findInsureds(source.tenantId(), source.id())) {
            partyRepository.insertInsured(new PolicyInsured(UUID.randomUUID(), insured.tenantId(),
                    renewal.id(), insured.partyId(), insured.insuredRole(), insured.displayName(),
                    insured.certificateNumber(), insured.scope(), terms.effectiveDate(),
                    terms.expirationDate(), insured.status(), now));
        }
    }

    private void copyLocations(Policy source, Policy renewal, PolicyRenewalRules.RenewalTerms terms,
                               Instant now) {
        for (PolicyLocation location : partyRepository.findLocations(source.tenantId(), source.id())) {
            partyRepository.insertLocation(new PolicyLocation(UUID.randomUUID(), location.tenantId(),
                    renewal.id(), location.locationCode(), location.addressLine1(),
                    location.addressLine2(), location.city(), location.stateRegion(),
                    location.postalCode(), location.country(), terms.effectiveDate(),
                    terms.expirationDate(), location.status(), now));
        }
    }
}
