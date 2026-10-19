package com.eia.policy.expert;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Coverage and limit invariants: SPEC POL-03 (per-occurrence at or below the
 * aggregate), POL-04 (deductible below the each-occurrence limit), POL-05
 * (unique coverage codes), and the coverage window must sit inside the policy
 * term. Amounts are compared only in the policy currency (SPEC 10.3).
 */
public final class CoverageRules {
    public static final Set<String> OCCURRENCE_BASES = Set.of(
            "OCCURRENCE", "CLAIMS_MADE", "CLAIMS_MADE_WITH_RETRO", "DISCOVERY"
    );
    public static final Set<String> AGGREGATE_BASES = Set.of("PER_POLICY", "PER_COVERAGE");
    public static final Set<String> EROSION_BASES = Set.of("PAID", "INCURRED", "PAID_PLUS_RESERVED");

    private CoverageRules() {
    }

    /** Normalized limit schedule. */
    public record LimitSpec(BigDecimal perOccurrenceAmount,
                            BigDecimal aggregateAmount,
                            String aggregateBasis,
                            BigDecimal sublimitAmount,
                            BigDecimal deductibleAmount,
                            BigDecimal coinsurancePct,
                            String currency,
                            String erosionBasis) {
    }

    /** Normalized coverage grant. */
    public record CoverageSpec(String coverageCode,
                               String coverageName,
                               String coveragePart,
                               String occurrenceBasis,
                               BigDecimal coinsurancePct,
                               LocalDate effectiveFrom,
                               LocalDate effectiveTo,
                               LimitSpec limit) {
    }

    public static String normalizeOccurrenceBasis(String raw) {
        if (raw == null || raw.isBlank()) {
            throw PolicyErrors.badRequest("INVALID_OCCURRENCE_BASIS", "occurrenceBasis",
                    "occurrenceBasis is required.");
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!OCCURRENCE_BASES.contains(value)) {
            throw PolicyErrors.badRequest("INVALID_OCCURRENCE_BASIS", "occurrenceBasis",
                    "Unknown occurrence basis: " + raw);
        }
        return value;
    }

    public static String normalizeImpact(String raw) {
        if (raw == null || raw.isBlank()) {
            return "NONE";
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("NONE", "BROADEN", "RESTRICT", "CLARIFY").contains(value)) {
            throw PolicyErrors.badRequest("INVALID_COVERAGE_IMPACT", "coverageImpact",
                    "Unknown coverage impact: " + raw);
        }
        return value;
    }

    /** Enforces that the coverage window sits inside the policy term. */
    public static void validateWindow(LocalDate effectiveFrom,
                                      LocalDate effectiveTo,
                                      LocalDate policyFrom,
                                      LocalDate policyTo,
                                      String code) {
        if (!effectiveTo.isAfter(effectiveFrom)) {
            throw PolicyErrors.businessRule("INVALID_COVERAGE_RANGE", "effectiveTo",
                    "effectiveTo must be after effectiveFrom.");
        }
        if (effectiveFrom.isBefore(policyFrom) || effectiveTo.isAfter(policyTo)) {
            throw PolicyErrors.businessRule(code, "effectiveFrom",
                    "Coverage dates must fall inside the policy term "
                            + policyFrom + " to " + policyTo + ".");
        }
    }

    public static LimitSpec validateLimit(BigDecimal perOccurrence,
                                          BigDecimal aggregate,
                                          String aggregateBasis,
                                          BigDecimal sublimit,
                                          BigDecimal deductible,
                                          BigDecimal coinsurancePct,
                                          String currency,
                                          String erosionBasis,
                                          String policyCurrency) {
        if (perOccurrence == null || perOccurrence.signum() <= 0) {
            throw PolicyErrors.businessRule("INVALID_PER_OCCURRENCE_LIMIT", "limit.perOccurrenceAmount",
                    "The each-occurrence limit must be greater than zero.");
        }
        String normalizedCurrency = currency == null ? null : currency.trim().toUpperCase(Locale.ROOT);
        if (normalizedCurrency == null || !normalizedCurrency.equals(policyCurrency)) {
            throw PolicyErrors.businessRule("CURRENCY_MISMATCH", "limit.currency",
                    "The limit currency " + currency + " does not match the policy currency "
                            + policyCurrency + ".");
        }
        if (aggregate != null && aggregate.signum() <= 0) {
            throw PolicyErrors.businessRule("INVALID_AGGREGATE_LIMIT", "limit.aggregateAmount",
                    "The aggregate limit must be greater than zero, or omitted for NONE.");
        }
        if (aggregate != null && perOccurrence.compareTo(aggregate) > 0) {
            throw PolicyErrors.businessRule("LIMIT_EXCEEDS_AGGREGATE", "limit.perOccurrenceAmount",
                    "The each-occurrence limit must not exceed the aggregate limit (SPEC POL-03).");
        }
        if (deductible != null) {
            if (deductible.signum() < 0) {
                throw PolicyErrors.businessRule("INVALID_DEDUCTIBLE", "limit.deductibleAmount",
                        "The deductible must be zero or greater.");
            }
            if (deductible.compareTo(perOccurrence) >= 0) {
                throw PolicyErrors.businessRule("DEDUCTIBLE_EXCEEDS_LIMIT", "limit.deductibleAmount",
                        "The deductible must be less than the each-occurrence limit (SPEC POL-04).");
            }
        }
        if (sublimit != null) {
            if (sublimit.signum() < 0) {
                throw PolicyErrors.businessRule("INVALID_SUBLIMIT", "limit.sublimitAmount",
                        "The sublimit must be zero or greater.");
            }
            if (sublimit.compareTo(perOccurrence) > 0
                    || (aggregate != null && sublimit.compareTo(aggregate) > 0)) {
                throw PolicyErrors.businessRule("SUBLIMIT_EXCEEDS_LIMIT", "limit.sublimitAmount",
                        "The sublimit must not exceed the each-occurrence or aggregate limit.");
            }
        }
        if (coinsurancePct != null
                && (coinsurancePct.signum() < 0 || coinsurancePct.compareTo(BigDecimal.ONE) > 0)) {
            throw PolicyErrors.businessRule("INVALID_COINSURANCE", "limit.coinsurancePct",
                    "The coinsurance percentage must be a fraction between 0 and 1.");
        }
        String normalizedAggregateBasis = aggregate == null
                ? null
                : normalizeBasis(aggregateBasis, AGGREGATE_BASES, "PER_COVERAGE", "aggregateBasis",
                "INVALID_AGGREGATE_BASIS");
        String normalizedErosion = normalizeBasis(erosionBasis, EROSION_BASES, "PAID_PLUS_RESERVED",
                "limit.erosionBasis", "INVALID_EROSION_BASIS");
        return new LimitSpec(Money.of(perOccurrence), Money.of(aggregate), normalizedAggregateBasis,
                Money.of(sublimit), Money.of(deductible), Money.percent(coinsurancePct),
                normalizedCurrency, normalizedErosion);
    }

    public static CoverageSpec validate(CreateCoverageRequest request,
                                        String policyCurrency,
                                        LocalDate policyFrom,
                                        LocalDate policyTo) {
        String occurrenceBasis = normalizeOccurrenceBasis(request.occurrenceBasis());
        validateWindow(request.effectiveFrom(), request.effectiveTo(), policyFrom, policyTo,
                "COVERAGE_OUTSIDE_POLICY_TERM");
        String coveragePart = request.coveragePart() == null
                ? "A" : request.coveragePart().trim().toUpperCase(Locale.ROOT);
        CreateCoverageRequest.LimitSpec raw = request.limit();
        LimitSpec limit = validateLimit(raw.perOccurrenceAmount(), raw.aggregateAmount(),
                raw.aggregateBasis(), raw.sublimitAmount(), raw.deductibleAmount(),
                raw.coinsurancePct() != null ? raw.coinsurancePct() : request.coinsurancePct(),
                raw.currency(), raw.erosionBasis(), policyCurrency);
        return new CoverageSpec(request.coverageCode().trim().toUpperCase(Locale.ROOT),
                request.coverageName().trim(), coveragePart, occurrenceBasis,
                Money.percent(request.coinsurancePct()), request.effectiveFrom(),
                request.effectiveTo(), limit);
    }

    private static String normalizeBasis(String raw, Set<String> allowed, String fallback,
                                         String field, String code) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(value)) {
            throw PolicyErrors.businessRule(code, field, "Unknown value: " + raw);
        }
        return value;
    }

    /** Convenience for tests: the effective coverage codes of a coverage list. */
    public static List<String> codes(List<PolicyCoverage> coverages) {
        return coverages.stream().map(PolicyCoverage::coverageCode).toList();
    }
}
