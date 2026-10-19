package com.eia.policy.expert;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Return-premium arithmetic for cancellation (SPEC POL-08, SPEC 10.3).
 *
 * <p>Flat and void cancellations refund the whole premium. Pro-rata and
 * non-payment refund the unearned fraction of the term. Short-rate applies a
 * filed short-rate factor (the minimum earned premium is recorded as 90% of the
 * pro-rata earned premium in this slice). Amounts are rounded HALF_UP to four
 * fraction digits, the storage precision from SPEC 10.3.
 */
public final class CancellationMath {
    public static final BigDecimal SHORT_RATE_FACTOR = new BigDecimal("0.90");
    private static final int MONEY_SCALE = 4;
    private static final int PERCENT_SCALE = 6;

    private CancellationMath() {
    }

    /** Computed cancellation figures. */
    public record Result(int termDays,
                         int remainingDays,
                         BigDecimal proRataPct,
                         BigDecimal earnedPremium,
                         BigDecimal returnPremium) {
    }

    public static Result calculate(String cancellationType,
                                   BigDecimal premium,
                                   LocalDate effectiveFrom,
                                   LocalDate expirationDate,
                                   LocalDate cancellationDate) {
        if (cancellationType == null) {
            throw new IllegalArgumentException("cancellationType is required");
        }
        if (premium == null || premium.signum() < 0) {
            throw new IllegalArgumentException("premium must be zero or greater");
        }
        if (effectiveFrom == null || expirationDate == null || cancellationDate == null) {
            throw new IllegalArgumentException("dates are required");
        }
        long termDays = ChronoUnit.DAYS.between(effectiveFrom, expirationDate);
        if (termDays <= 0) {
            throw new IllegalArgumentException("the policy term must be at least one day");
        }
        if (cancellationDate.isBefore(effectiveFrom) || !cancellationDate.isBefore(expirationDate)) {
            throw new IllegalArgumentException("cancellation date must fall inside the policy term");
        }
        long remainingDays = ChronoUnit.DAYS.between(cancellationDate, expirationDate);
        BigDecimal unearned = BigDecimal.valueOf(remainingDays)
                .divide(BigDecimal.valueOf(termDays), PERCENT_SCALE, RoundingMode.HALF_UP);
        BigDecimal earned = premium.subtract(premium.multiply(unearned))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        return switch (cancellationType) {
            case "FLAT", "VOID" -> new Result((int) termDays, (int) remainingDays,
                    BigDecimal.ONE.setScale(PERCENT_SCALE, RoundingMode.HALF_UP),
                    BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                    premium.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
            case "SHORT_RATE" -> {
                BigDecimal refund = premium.multiply(unearned)
                        .multiply(SHORT_RATE_FACTOR)
                        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                yield new Result((int) termDays, (int) remainingDays,
                        unearned.multiply(SHORT_RATE_FACTOR)
                                .setScale(PERCENT_SCALE, RoundingMode.HALF_UP),
                        premium.setScale(MONEY_SCALE, RoundingMode.HALF_UP).subtract(refund),
                        refund);
            }
            default -> {
                BigDecimal refund = premium.multiply(unearned)
                        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                yield new Result((int) termDays, (int) remainingDays, unearned,
                        premium.setScale(MONEY_SCALE, RoundingMode.HALF_UP).subtract(refund), refund);
            }
        };
    }
}
