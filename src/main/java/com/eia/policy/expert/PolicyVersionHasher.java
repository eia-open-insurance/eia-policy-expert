package com.eia.policy.expert;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Canonical SHA-256 hash of a policy version snapshot (SPEC 11.2.4
 * {@code immutable_hash}, SPEC POL-06). The canonical form is field ordered,
 * line based, and independent of row order: child collections are sorted by
 * their natural business key. Mutable metadata (status, issuedAt, approval
 * actor) is excluded so a superseded version keeps verifying its snapshot.
 */
public final class PolicyVersionHasher {
    private static final String NONE = "~";

    private PolicyVersionHasher() {
    }

    public static String hash(Policy policy, PolicyVersion version, PolicySnapshot snapshot) {
        return sha256(canonical(policy, version, snapshot));
    }

    public static String canonical(Policy policy, PolicyVersion version, PolicySnapshot snapshot) {
        StringBuilder out = new StringBuilder(2048);
        append(out, "policy.id", policy.id());
        append(out, "policy.tenantId", policy.tenantId());
        append(out, "policy.policyNumber", policy.policyNumber());
        append(out, "policy.productCode", policy.productCode());
        append(out, "policy.insuredPartyId", policy.insuredPartyId());
        append(out, "policy.effectiveDate", policy.effectiveDate());
        append(out, "policy.expirationDate", policy.expirationDate());
        append(out, "policy.premiumAmount", money(policy.premiumAmount()));
        append(out, "policy.premiumCurrency", policy.premiumCurrency());
        append(out, "policy.limitAmount", money(policy.limitAmount()));
        append(out, "policy.limitCurrency", policy.limitCurrency());

        append(out, "version.versionNumber", version.versionNumber());
        append(out, "version.policyVersionNumber", version.policyVersionNumber());
        append(out, "version.changeType", version.changeType());
        append(out, "version.effectiveFrom", version.effectiveFrom());
        append(out, "version.effectiveTo", version.effectiveTo());
        append(out, "version.premiumDeltaAmount", money(version.premiumDeltaAmount()));
        append(out, "version.premiumDeltaCurrency", version.premiumDeltaCurrency());
        append(out, "version.description", version.description());

        List<PolicyCoverage> coverages = snapshot.coverages().stream()
                .sorted(Comparator.comparing(PolicyCoverage::coverageCode)
                        .thenComparing(PolicyCoverage::effectiveFrom))
                .toList();
        for (PolicyCoverage coverage : coverages) {
            append(out, "coverage.id", coverage.id());
            append(out, "coverage.code", coverage.coverageCode());
            append(out, "coverage.name", coverage.coverageName());
            append(out, "coverage.part", coverage.coveragePart());
            append(out, "coverage.occurrenceBasis", coverage.occurrenceBasis());
            append(out, "coverage.coinsurancePct", percent(coverage.coinsurancePct()));
            append(out, "coverage.effectiveFrom", coverage.effectiveFrom());
            append(out, "coverage.effectiveTo", coverage.effectiveTo());
            append(out, "coverage.status", coverage.status());
        }

        List<PolicyLimit> limits = snapshot.limits().stream()
                .sorted(Comparator.comparing(PolicyLimit::coverageId))
                .toList();
        for (PolicyLimit limit : limits) {
            append(out, "limit.id", limit.id());
            append(out, "limit.coverageId", limit.coverageId());
            append(out, "limit.perOccurrenceAmount", money(limit.perOccurrenceAmount()));
            append(out, "limit.aggregateAmount", money(limit.aggregateAmount()));
            append(out, "limit.aggregateBasis", limit.aggregateBasis());
            append(out, "limit.sublimitAmount", money(limit.sublimitAmount()));
            append(out, "limit.deductibleAmount", money(limit.deductibleAmount()));
            append(out, "limit.coinsurancePct", percent(limit.coinsurancePct()));
            append(out, "limit.currency", limit.currency());
            append(out, "limit.erosionBasis", limit.erosionBasis());
            append(out, "limit.effectiveFrom", limit.effectiveFrom());
            append(out, "limit.effectiveTo", limit.effectiveTo());
        }

        List<PolicyInsured> insureds = snapshot.insureds().stream()
                .sorted(Comparator.comparing(PolicyInsured::partyId)
                        .thenComparing(PolicyInsured::insuredRole))
                .toList();
        for (PolicyInsured insured : insureds) {
            append(out, "insured.partyId", insured.partyId());
            append(out, "insured.role", insured.insuredRole());
            append(out, "insured.displayName", insured.displayName());
            append(out, "insured.certificateNumber", insured.certificateNumber());
            append(out, "insured.effectiveFrom", insured.effectiveFrom());
            append(out, "insured.effectiveTo", insured.effectiveTo());
            append(out, "insured.status", insured.status());
        }

        List<PolicyLocation> locations = snapshot.locations().stream()
                .sorted(Comparator.comparing(PolicyLocation::locationCode))
                .toList();
        for (PolicyLocation location : locations) {
            append(out, "location.code", location.locationCode());
            append(out, "location.line1", location.addressLine1());
            append(out, "location.line2", location.addressLine2());
            append(out, "location.city", location.city());
            append(out, "location.stateRegion", location.stateRegion());
            append(out, "location.postalCode", location.postalCode());
            append(out, "location.country", location.country());
            append(out, "location.effectiveFrom", location.effectiveFrom());
            append(out, "location.effectiveTo", location.effectiveTo());
            append(out, "location.status", location.status());
        }

        List<PolicyEndorsement> endorsements = snapshot.endorsements().stream()
                .sorted(Comparator.comparing(PolicyEndorsement::sequence))
                .toList();
        for (PolicyEndorsement endorsement : endorsements) {
            append(out, "endorsement.number", endorsement.endorsementNumber());
            append(out, "endorsement.sequence", endorsement.sequence());
            append(out, "endorsement.type", endorsement.endorsementType());
            append(out, "endorsement.effectiveDate", endorsement.effectiveDate());
            append(out, "endorsement.description", endorsement.description());
            append(out, "endorsement.premiumDeltaAmount", money(endorsement.premiumDeltaAmount()));
            append(out, "endorsement.premiumDeltaCurrency", endorsement.premiumDeltaCurrency());
            append(out, "endorsement.coverageImpact", endorsement.coverageImpact());
            append(out, "endorsement.backdateReason", endorsement.backdateReason());
            if (endorsement.coverageChanges() != null) {
                for (EndorsementCoverageChange change : endorsement.coverageChanges()) {
                    append(out, "endorsement.change.coverageCode", change.coverageCode());
                    append(out, "endorsement.change.type", change.changeType());
                    append(out, "endorsement.change.description", change.description());
                }
            }
        }
        return out.toString();
    }

    private static void append(StringBuilder out, String key, Object value) {
        out.append(key).append('=').append(scalar(value)).append('\n');
    }

    private static String scalar(Object value) {
        if (value == null) {
            return NONE;
        }
        if (value instanceof LocalDate date) {
            return date.toString();
        }
        if (value instanceof UUID uuid) {
            return uuid.toString();
        }
        String text = String.valueOf(value);
        return text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String money(BigDecimal amount) {
        return amount == null ? NONE : amount.stripTrailingZeros().toPlainString();
    }

    private static String percent(BigDecimal value) {
        return value == null ? NONE : value.stripTrailingZeros().toPlainString();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", ex);
        }
    }
}
