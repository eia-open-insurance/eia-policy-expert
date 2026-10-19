package com.eia.policy.expert;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Builds and persists an endorsement row. Every endorsement is numbered per
 * policy and carries the version that it issued, so the version snapshot and
 * the business change reference each other.
 */
@Component
public class EndorsementFactory {
    private final EndorsementRepository repository;

    public EndorsementFactory(EndorsementRepository repository) {
        this.repository = repository;
    }

    public PolicyEndorsement create(UUID endorsementId,
                                    Policy policy,
                                    UUID policyVersionId,
                                    String endorsementNumber,
                                    String endorsementType,
                                    LocalDate effectiveDate,
                                    String description,
                                    BigDecimal premiumDeltaAmount,
                                    String premiumDeltaCurrency,
                                    String coverageImpact,
                                    List<EndorsementCoverageChange> coverageChanges,
                                    String backdateReason,
                                    UUID approvedBy,
                                    UUID approvalRef,
                                    String formNumber,
                                    String formEdition,
                                    boolean manuscriptFlag) {
        int sequence = repository.nextSequence(policy.tenantId(), policy.id());
        String number = endorsementNumber == null || endorsementNumber.isBlank()
                ? "END-" + String.format("%04d", sequence)
                : endorsementNumber.trim();
        if (endorsementNumber != null && !endorsementNumber.isBlank()
                && repository.numberExists(policy.tenantId(), policy.id(), number)) {
            throw PolicyErrors.conflict("DUPLICATE_ENDORSEMENT_NUMBER", "endorsementNumber",
                    "Endorsement number already exists for this policy: " + number);
        }
        Instant now = Instant.now();
        PolicyEndorsement endorsement = new PolicyEndorsement(
                endorsementId, policy.tenantId(), policy.id(), policyVersionId, number, sequence,
                formNumber, formEdition, endorsementType, effectiveDate, description,
                Money.of(premiumDeltaAmount), premiumDeltaCurrency, coverageImpact, coverageChanges,
                manuscriptFlag, backdateReason, approvalRef, approvedBy, "ISSUED", now, now);
        repository.insert(endorsement);
        return endorsement;
    }
}
