package com.eia.policy.expert;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure rating invariants and the reproducible calculation hash.
 *
 * <p>Only a {@code DRAFT} or {@code QUOTED} policy may be rated. The two
 * supported methods are:
 * <ul>
 *   <li>{@code RATE_TIMES_EXPOSURE}: {@code baseRate * exposureAmount * factor}</li>
 *   <li>{@code FLAT}: {@code baseRate * factor}, with no exposure stored</li>
 * </ul>
 * The factor defaults to {@code 1}, the base rate and factor must be
 * non-negative, and {@code RATE_TIMES_EXPOSURE} requires a strictly positive
 * exposure. The computed premium is rounded once, to scale 4 with HALF_UP,
 * matching the platform money contract (SPEC 10.3).</p>
 *
 * <p>{@code calc_hash} is the SHA-256 of a canonical, line-based rendering of
 * the normalized rating method, base rate (scale 6), exposure (scale 4 or
 * {@code ~}), factor (scale 6), computed premium (scale 4), and currency. The
 * normalized form makes the hash independent of the request's decimal scale,
 * so {@code 100} and {@code 100.000000} reproduce the same hash.</p>
 */
public final class RatingRules {
    public static final String RATE_TIMES_EXPOSURE = "RATE_TIMES_EXPOSURE";
    public static final String FLAT = "FLAT";
    public static final String CALCULATED = "CALCULATED";

    public static final Set<String> METHODS = Set.of(RATE_TIMES_EXPOSURE, FLAT);
    public static final Set<String> RATEABLE_STATUSES = Set.of("DRAFT", "QUOTED");

    static final int RATE_SCALE = 6;
    static final int FACTOR_SCALE = 6;
    static final int MONEY_SCALE = Money.SCALE;

    /** numeric(19,4) upper bound; a larger computed premium cannot be stored. */
    private static final BigDecimal MAX_PREMIUM = new BigDecimal("999999999999999.9999");
    private static final String NONE = "~";

    private RatingRules() {
    }

    /** Enforces the rateable lifecycle states: DRAFT or QUOTED only. */
    public static void requireRateable(Policy policy) {
        String status = policy.status();
        if (status != null && RATEABLE_STATUSES.contains(status)) {
            return;
        }
        String reportedStatus = status == null ? "UNKNOWN" : status;
        List<String> allowed = RATEABLE_STATUSES.stream().sorted().toList();
        throw PolicyErrors.conflict("POLICY_NOT_RATEABLE", "status",
                "Policy " + policy.policyNumber() + " in status " + reportedStatus
                        + " cannot be rated; only " + String.join(" or ", allowed)
                        + " policies are rateable.",
                Map.of("policyId", policy.id().toString(),
                        "sourceStatus", reportedStatus,
                        "allowedStatuses", allowed));
    }

    /**
     * Validates and normalizes one rating request against the policy currency.
     *
     * @throws ApiException {@code 400} for a missing/unknown method, a missing
     *                      or negative base rate, a missing/invalid exposure for
     *                      {@code RATE_TIMES_EXPOSURE}, a negative factor, or a
     *                      computed premium outside numeric(19,4)
     */
    public static RatingSpec validate(RatingRequest request, String currency) {
        if (request == null) {
            throw PolicyErrors.badRequest("RATING_REQUEST_REQUIRED", "request",
                    "A rating request is required.");
        }
        String method = normalizeMethod(request.ratingMethod());
        String normalizedCurrency = normalizeCurrency(currency);

        if (request.baseRate() == null) {
            throw PolicyErrors.badRequest("RATING_BASE_RATE_REQUIRED", "baseRate",
                    "baseRate is required.");
        }
        BigDecimal baseRate = normalize(request.baseRate(), RATE_SCALE);
        if (baseRate.signum() < 0) {
            throw PolicyErrors.badRequest("RATING_BASE_RATE_INVALID", "baseRate",
                    "baseRate must be non-negative.");
        }

        BigDecimal factor = normalize(
                request.factor() == null ? BigDecimal.ONE : request.factor(), FACTOR_SCALE);
        if (factor.signum() < 0) {
            throw PolicyErrors.badRequest("RATING_FACTOR_INVALID", "factor",
                    "factor must be non-negative.");
        }

        BigDecimal exposure = null;
        if (RATE_TIMES_EXPOSURE.equals(method)) {
            if (request.exposureAmount() == null) {
                throw PolicyErrors.badRequest("RATING_EXPOSURE_REQUIRED", "exposureAmount",
                        "exposureAmount is required for RATE_TIMES_EXPOSURE.");
            }
            exposure = normalize(request.exposureAmount(), MONEY_SCALE);
            if (exposure.signum() <= 0) {
                throw PolicyErrors.badRequest("RATING_EXPOSURE_INVALID", "exposureAmount",
                        "exposureAmount must be greater than zero for RATE_TIMES_EXPOSURE.");
            }
        }

        BigDecimal computedPremium = compute(method, baseRate, exposure, factor);
        if (computedPremium.compareTo(MAX_PREMIUM) > 0) {
            throw PolicyErrors.badRequest("RATING_PREMIUM_OUT_OF_RANGE", "computedPremium",
                    "The computed premium exceeds numeric(19,4).");
        }
        return new RatingSpec(method, baseRate, exposure, factor, computedPremium,
                normalizedCurrency);
    }

    /** Computes the premium for an already-normalized method and operands. */
    public static BigDecimal compute(String method, BigDecimal baseRate, BigDecimal exposure,
                                     BigDecimal factor) {
        BigDecimal product;
        if (RATE_TIMES_EXPOSURE.equals(method)) {
            if (exposure == null) {
                throw new IllegalArgumentException(
                        "RATE_TIMES_EXPOSURE requires an exposure amount");
            }
            product = baseRate.multiply(exposure).multiply(factor);
        } else if (FLAT.equals(method)) {
            product = baseRate.multiply(factor);
        } else {
            throw new IllegalArgumentException("Unsupported rating method: " + method);
        }
        return product.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** Canonical SHA-256 (lower-case hex) over the normalized spec. */
    public static String calcHash(RatingSpec spec) {
        return sha256(canonical(spec));
    }

    /** The exact canonical text that {@link #calcHash(RatingSpec)} hashes. */
    public static String canonical(RatingSpec spec) {
        StringBuilder out = new StringBuilder(256);
        append(out, "ratingMethod", spec.ratingMethod());
        append(out, "baseRate", fixed(spec.baseRate(), RATE_SCALE));
        append(out, "exposureAmount", spec.exposureAmount() == null
                ? NONE : fixed(spec.exposureAmount(), MONEY_SCALE));
        append(out, "factor", fixed(spec.factor(), FACTOR_SCALE));
        append(out, "computedPremium", fixed(spec.computedPremium(), MONEY_SCALE));
        append(out, "currency", spec.currency());
        return out.toString();
    }

    private static String normalizeMethod(String raw) {
        if (raw == null || raw.isBlank()) {
            throw PolicyErrors.badRequest("RATING_METHOD_REQUIRED", "ratingMethod",
                    "ratingMethod is required.");
        }
        String method = raw.trim().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            throw PolicyErrors.badRequest("RATING_METHOD_INVALID", "ratingMethod",
                    "ratingMethod must be RATE_TIMES_EXPOSURE or FLAT.");
        }
        return method;
    }

    private static String normalizeCurrency(String raw) {
        if (raw == null || raw.isBlank()) {
            throw PolicyErrors.badRequest("RATING_CURRENCY_REQUIRED", "currency",
                    "The policy premium currency is required to rate.");
        }
        String currency = raw.trim().toUpperCase(Locale.ROOT);
        if (currency.length() != 3) {
            throw PolicyErrors.badRequest("RATING_CURRENCY_INVALID", "currency",
                    "The policy premium currency must be an ISO 4217 code.");
        }
        return currency;
    }

    private static BigDecimal normalize(BigDecimal value, int scale) {
        return value.setScale(scale, RoundingMode.HALF_UP);
    }

    private static void append(StringBuilder out, String key, Object value) {
        out.append(key).append('=').append(value).append('\n');
    }

    private static String fixed(BigDecimal value, int scale) {
        return value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", ex);
        }
    }

    /** Normalized rating inputs and result before persistence. */
    public record RatingSpec(
            String ratingMethod,
            BigDecimal baseRate,
            BigDecimal exposureAmount,
            BigDecimal factor,
            BigDecimal computedPremium,
            String currency
    ) {
    }
}
