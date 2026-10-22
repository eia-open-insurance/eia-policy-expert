package com.eia.policy.expert;

import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Idempotency support for policy commands.
 *
 * <p>The key scope is {@code tenant + operation + key}. The request hash is
 * computed from a canonical field-ordered string, so a retry with the same
 * business request replays and a reuse with a different request is rejected.
 * Amounts are normalized numerically ({@code 1000.0} and {@code 1000.0000} hash
 * the same), enum-like fields are trimmed and upper-cased, and free text is
 * trimmed, which matches JSON semantics. Keys are capped at 128 characters for
 * every command, including rating and submission conversion.
 */
final class IdempotencyKeys {
    static final int MAX_LENGTH = 128;

    private IdempotencyKeys() {
    }

    static String normalize(String rawKey) {
        if (rawKey == null) {
            return null;
        }
        String key = rawKey.trim();
        if (key.isEmpty()) {
            return null;
        }
        if (key.length() > MAX_LENGTH) {
            String message = "Idempotency-Key must be at most " + MAX_LENGTH + " characters.";
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", message,
                    List.of(new ApiException.FieldIssue(
                            "Idempotency-Key", "INVALID_IDEMPOTENCY_KEY", message)));
        }
        return key;
    }

    static String requestHash(CreatePolicyRequest request) {
        String canonical = "tenantId=" + request.tenantId()
                + "\npolicyNumber=" + normalizePolicyNumber(request.policyNumber())
                + "\nproductCode=" + trim(request.productCode())
                + "\ninsuredPartyId=" + request.insuredPartyId()
                + "\nstatus=" + normalizeInitialStatus(request.status())
                + "\neffectiveDate=" + request.effectiveDate()
                + "\nexpirationDate=" + request.expirationDate()
                + "\npremiumAmount=" + money(request.premiumAmount())
                + "\npremiumCurrency=" + upper(request.premiumCurrency())
                + "\nlimitAmount=" + money(request.limitAmount())
                + "\nlimitCurrency=" + upper(request.limitCurrency());
        return sha256(canonical);
    }

    /**
     * Canonical hash for one rating command. The policy id is part of the
     * canonical form so the same key cannot replay a worksheet from another
     * policy. The rating method is trimmed and upper-cased, defaults applied by
     * {@link RatingRequest} are already materialized, and the decimal fields are
     * normalized numerically, so an equivalent body replays even when its
     * scale or whitespace differs.
     */
    static String requestHash(UUID policyId, RatingRequest request) {
        StringBuilder canonical = new StringBuilder("policyId=").append(policyId);
        if (request != null) {
            canonical.append("\nratingMethod=").append(upper(request.ratingMethod()))
                    .append("\nbaseRate=").append(money(request.baseRate()))
                    .append("\nexposureAmount=").append(money(request.exposureAmount()))
                    .append("\nfactor=").append(money(request.factor()))
                    .append("\napply=").append(Boolean.TRUE.equals(request.apply()));
        }
        return hash(canonical.toString());
    }

    /**
     * Canonical hash for one submission conversion. The submission id is part
     * of the canonical form so the same key cannot replay a policy converted
     * from another submission. The premium is normalized numerically and the
     * currency is trimmed and upper-cased, so an equivalent body replays.
     */
    static String requestHash(UUID submissionId, ConvertSubmissionRequest request) {
        StringBuilder canonical = new StringBuilder("submissionId=").append(submissionId);
        if (request != null) {
            canonical.append("\npremiumAmount=").append(money(request.premiumAmount()))
                    .append("\npremiumCurrency=").append(upper(request.premiumCurrency()))
                    .append("\nexpirationDate=")
                    .append(request.expirationDate() == null
                            ? "" : request.expirationDate().toString());
        }
        return hash(canonical.toString());
    }

    /**
     * Canonical hash for a second-slice command. The policy id is part of the
     * canonical form so the same key cannot replay a resource from another
     * policy.
     */
    static String hash(String canonical) {
        return sha256(canonical);
    }

    static String requestHash(UUID policyId, CreateCoverageRequest request) {
        CreateCoverageRequest.LimitSpec limit = request.limit();
        return hash("policyId=" + policyId
                + "\ncoverageCode=" + upper(request.coverageCode())
                + "\ncoverageName=" + trim(request.coverageName())
                + "\ncoveragePart=" + upper(request.coveragePart())
                + "\noccurrenceBasis=" + upper(request.occurrenceBasis())
                + "\neffectiveFrom=" + request.effectiveFrom()
                + "\neffectiveTo=" + request.effectiveTo()
                + "\ncoinsurancePct=" + percent(request.coinsurancePct())
                + "\nperOccurrenceAmount=" + money(limit.perOccurrenceAmount())
                + "\naggregateAmount=" + money(limit.aggregateAmount())
                + "\naggregateBasis=" + upper(limit.aggregateBasis())
                + "\nsublimitAmount=" + money(limit.sublimitAmount())
                + "\ndeductibleAmount=" + money(limit.deductibleAmount())
                + "\nlimitCoinsurancePct=" + percent(limit.coinsurancePct())
                + "\ncurrency=" + upper(limit.currency())
                + "\nerosionBasis=" + upper(limit.erosionBasis()));
    }

    static String requestHash(UUID policyId, CreateEndorsementRequest request) {
        StringBuilder canonical = new StringBuilder()
                .append("policyId=").append(policyId)
                .append("\nendorsementNumber=").append(trim(request.endorsementNumber()))
                .append("\nendorsementType=").append(upper(request.endorsementType()))
                .append("\neffectiveDate=").append(request.effectiveDate())
                .append("\ndescription=").append(trim(request.description()))
                .append("\npremiumDeltaAmount=").append(money(request.premiumDeltaAmount()))
                .append("\npremiumDeltaCurrency=").append(upper(request.premiumDeltaCurrency()))
                .append("\ncoverageImpact=").append(upper(request.coverageImpact()))
                .append("\nbackdateReason=").append(trim(request.backdateReason()))
                .append("\napprovedBy=").append(request.approvedBy())
                .append("\napprovalRef=").append(request.approvalRef());
        if (request.coverageChanges() != null) {
            for (CreateEndorsementRequest.CoverageChange change : request.coverageChanges()) {
                canonical.append("\nchange=").append(upper(change.coverageCode()))
                        .append(':').append(upper(change.changeType()))
                        .append(':').append(trim(change.description()));
            }
        }
        return hash(canonical.toString());
    }

    static String requestHash(UUID policyId, CreateInsuredRequest request) {
        return hash("policyId=" + policyId
                + "\npartyId=" + request.partyId()
                + "\ninsuredRole=" + upper(request.insuredRole())
                + "\ndisplayName=" + trim(request.displayName())
                + "\ncertificateNumber=" + trim(request.certificateNumber())
                + "\nscope=" + trim(request.scope())
                + "\neffectiveFrom=" + request.effectiveFrom()
                + "\neffectiveTo=" + request.effectiveTo());
    }

    static String requestHash(UUID policyId, CreateLocationRequest request) {
        return hash("policyId=" + policyId
                + "\nlocationCode=" + upper(request.locationCode())
                + "\naddressLine1=" + trim(request.addressLine1())
                + "\naddressLine2=" + trim(request.addressLine2())
                + "\ncity=" + trim(request.city())
                + "\nstateRegion=" + upper(request.stateRegion())
                + "\npostalCode=" + trim(request.postalCode())
                + "\ncountry=" + upper(request.country())
                + "\neffectiveFrom=" + request.effectiveFrom()
                + "\neffectiveTo=" + request.effectiveTo());
    }

    static String requestHash(UUID policyId, CancelPolicyRequest request) {
        return hash("policyId=" + policyId
                + "\ncancellationType=" + upper(request.cancellationType())
                + "\nreason=" + trim(request.reason())
                + "\neffectiveDate=" + request.effectiveDate()
                + "\nnoticeDays=" + (request.noticeDays() == null ? 0 : request.noticeDays())
                + "\napprovedBy=" + request.approvedBy()
                + "\napprovalRef=" + request.approvalRef());
    }

    static String requestHash(UUID policyId, ReinstatePolicyRequest request) {
        return hash("policyId=" + policyId
                + "\neffectiveDate=" + request.effectiveDate()
                + "\nreason=" + trim(request.reason())
                + "\napprovedBy=" + request.approvedBy()
                + "\napprovalRef=" + request.approvalRef()
                + "\npremiumEvidenceRef=" + trim(request.premiumEvidenceRef())
                + "\npremiumEvidenceAmount=" + money(request.premiumEvidenceAmount())
                + "\npremiumEvidenceCurrency=" + upper(request.premiumEvidenceCurrency()));
    }

    static String requestHash(UUID policyId, ErosionRequest request) {
        return hash("policyId=" + policyId
                + "\nlimitId=" + request.limitId()
                + "\nclaimId=" + request.claimId()
                + "\nallocationId=" + request.allocationId()
                + "\nsourceVersion=" + request.sourceVersion()
                + "\npaidAmount=" + money(request.paidAmount())
                + "\nreservedAmount=" + money(request.reservedAmount())
                + "\nrecoveredAmount=" + money(request.recoveredAmount())
                + "\nasOf=" + request.asOf());
    }

    private static String normalizePolicyNumber(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeInitialStatus(String value) {
        return value == null || value.isBlank() ? "DRAFT" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String money(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static String percent(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
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
