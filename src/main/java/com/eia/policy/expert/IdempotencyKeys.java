package com.eia.policy.expert;

import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Idempotency support for policy creation.
 *
 * <p>The key scope is {@code tenant + key}. The request hash is computed from a
 * canonical field-ordered string, so a retry with the same business request
 * replays and a reuse with a different request is rejected. Amounts are
 * normalized numerically ({@code 1000.0} and {@code 1000.0000} hash the same),
 * which matches JSON money semantics.
 */
final class IdempotencyKeys {
    static final int MAX_LENGTH = 255;

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

    private static String normalizePolicyNumber(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeInitialStatus(String value) {
        return value == null || value.isBlank() ? "DRAFT" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String money(BigDecimal value) {
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
